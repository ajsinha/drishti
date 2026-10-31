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
# Data architecture: live updates, history, Iceberg, reactive connectors, content and the database

How Drishti's data layer is built, for developers: **live updates** (section 1: streams, frames, coalescing, reconnects, live
BI), **history queries** (section 2: an entity over time, built in 2.6.5), **drishti-iceberg** (section 3: Drishti's own Apache Iceberg
engine, built in 2.6.0), **reactive connectors** (section 4: R1 to R4 built in 2.8.0; R5 and R6 planned), **the content store**
(section 5: user content every node reaches, built in 2.7.0) and **the one Drishti database** (section 6: design, not built; the
write-back tables are built).

| Section | Status |
|---|---|
| 1. Live updates | built |
| 2. History queries | built and released in 2.6.5 |
| 3. drishti-iceberg | built in 2.6.0 (`native` is the default engine) |
| 4. Reactive connectors | R1 to R4 built and released in 2.8.0; R5 and R6 planned |
| 5. The content store | built, released in 2.7.0 |
| 6. The one Drishti database | design, not built (the write-back tables are built) |

The people who load data read [DATA_GUIDE.md](../guides/DATA_GUIDE.md); the connectors are [CONNECTORS.md](../connectors/CONNECTORS.md)
and the per-store documents; VaR's intake is [PARALLEL_LOADER.md](PARALLEL_LOADER.md). Each section keeps its own numbering.

## Contents

1. [Live updates](#1-live-updates)
    - [What you see in the browser](#what-you-see-in-the-browser)
    - [Try it yourself](#try-it-yourself)
    - [Event reference](#event-reference)
    - [How a tick reaches the screen](#how-a-tick-reaches-the-screen)
    - [Guarantees and limits](#guarantees-and-limits)
    - [Monitors and alerts](#monitors-and-alerts)
    - [Live BI: a report's live tables](#live-bi-a-reports-live-tables)
    - [Latency](#latency)
    - [Settings](#settings)
    - [Streaming sources](#streaming-sources)
    - [The demo source](#the-demo-source)
    - [Implementation note](#implementation-note)
2. [History queries: an entity over time](#2-history-queries-an-entity-over-time)
    - [1. The ask](#1-the-ask)
    - [2. What exists already](#2-what-exists-already)
    - [3. Principles](#3-principles)
    - [4. The command](#4-the-command)
    - [5. The history view](#5-the-history-view)
    - [6. The `history:` block in pack.yaml](#6-the-history-block-in-packyaml)
    - [7. The server endpoint](#7-the-server-endpoint)
    - [8. The JSON contract](#8-the-json-contract)
    - [9. The connector SPI](#9-the-connector-spi)
    - [10. Fast paths, store by store](#10-fast-paths-store-by-store)
    - [11. Limits, cache and settings](#11-limits-cache-and-settings)
    - [12. Drishti\*net and super scale](#12-drishtinet-and-super-scale)
    - [13. Security](#13-security)
    - [14. Performance targets](#14-performance-targets)
    - [15. Extras: DIFF, export, CLI, BI, plain words](#15-extras-diff-export-cli-bi-plain-words)
    - [16. Errors](#16-errors)
    - [17. The data flow](#17-the-data-flow)
    - [18. Build steps H1..H14](#18-build-steps-h1h14)
    - [19. Tests](#19-tests)
    - [20. Docs to update when it is built](#20-docs-to-update-when-it-is-built)
    - [21. Where the code forced a change from the agreed design](#21-where-the-code-forced-a-change-from-the-agreed-design)
    - [22. Open questions](#22-open-questions)
3. [drishti-iceberg: Drishti's own Apache Iceberg engine](#3-drishti-iceberg-drishtis-own-apache-iceberg-engine)
    - [1. The ask](#1-the-ask-1)
    - [2. Principles](#2-principles)
    - [3. Why not keep the library and only drop Hadoop](#3-why-not-keep-the-library-and-only-drop-hadoop)
    - [4. The picture](#4-the-picture)
    - [5. What of the protocol is covered](#5-what-of-the-protocol-is-covered)
    - [6. Catalogs](#6-catalogs)
    - [7. Reading](#7-reading)
    - [8. Writing and commits](#8-writing-and-commits)
    - [9. Files: local disk and S3](#9-files-local-disk-and-s3)
    - [10. Engines and configuration](#10-engines-and-configuration)
    - [11. Windows](#11-windows)
    - [12. Proving it keeps to the protocol](#12-proving-it-keeps-to-the-protocol)
    - [13. Build plan](#13-build-plan)
    - [14. Risks and open questions](#14-risks-and-open-questions)
    - [15. Two connectors: Drishti tables and external tables](#15-two-connectors-drishti-tables-and-external-tables)
    - [16. Vector columns and orphan files (2.9.5)](#16-vector-columns-and-orphan-files-295)
4. [Reactive connectors](#4-reactive-connectors)
    - [Contents](#contents-1)
    - [1. The ask, in one paragraph](#1-the-ask-in-one-paragraph)
    - [2. Decisions this design rests on](#2-decisions-this-design-rests-on)
    - [3. Four kinds of connector](#3-four-kinds-of-connector)
    - [4. What a reactive connector is](#4-what-a-reactive-connector-is)
    - [5. The connector file](#5-the-connector-file)
    - [6. The request and its lifecycle](#6-the-request-and-its-lifecycle)
    - [7. Triggers: computing on events](#7-triggers-computing-on-events)
    - [8. Point-in-time stamping, ordering and idempotence](#8-point-in-time-stamping-ordering-and-idempotence)
    - [9. Isolation: two users never see each other's answers](#9-isolation-two-users-never-see-each-others-answers)
    - [10. Coalescing, incremental recompute and back-pressure](#10-coalescing-incremental-recompute-and-back-pressure)
    - [11. Caching](#11-caching)
    - [12. One screen, many connectors](#12-one-screen-many-connectors)
    - [13. Failure](#13-failure)
    - [14. Several servers (Drishti*net)](#14-several-servers-drishtinet)
    - [15. Observability and audit](#15-observability-and-audit)
    - [16. The SPI and the APIs](#16-the-spi-and-the-apis)
    - [17. Configuration reference](#17-configuration-reference)
    - [18. Build plan](#18-build-plan)
    - [19. Tests](#19-tests-1)
    - [20. Open questions for the product owner](#20-open-questions-for-the-product-owner)
    - [21. As built: R1 to R4](#21-as-built-r1-to-r4)
    - [22. How the VaR engine plugs in](#22-how-the-var-engine-plugs-in)
5. [The content store: user content every node reaches](#5-the-content-store-user-content-every-node-reaches)
    - [1. The problem](#1-the-problem)
    - [2. The interface](#2-the-interface)
    - [3. Backends](#3-backends)
    - [4. Configuration](#4-configuration)
    - [5. Designs: the first user](#5-designs-the-first-user)
    - [6. Several servers](#6-several-servers)
    - [7. Tests](#7-tests)
    - [8. Next steps: what is still kept on one node](#8-next-steps-what-is-still-kept-on-one-node)
    - [9. BI content and BI runs](#9-bi-content-and-bi-runs)
    - [10. Collaboration, notes and the admin data](#10-collaboration-notes-and-the-admin-data)
    - [11. UI-written configuration: shared overlays (as built for connectors and load overrides)](#11-ui-written-configuration-shared-overlays-as-built-for-connectors-and-load-overrides)
6. [The one Drishti database (design, not built)](#6-the-one-drishti-database-design-not-built)
    - [1. The decision](#1-the-decision)
    - [2. What lives in it, and what does not](#2-what-lives-in-it-and-what-does-not)
    - [3. SQLite or PostgreSQL](#3-sqlite-or-postgresql)
    - [4. No migrations: two complete schema files](#4-no-migrations-two-complete-schema-files)
    - [5. The schema version stamp](#5-the-schema-version-stamp)
    - [6. Switching and upgrading: `db copy`](#6-switching-and-upgrading-db-copy)
    - [7. Configuration: config/db/database.yaml (planned)](#7-configuration-configdbdatabaseyaml-planned)
    - [8. Several servers](#8-several-servers)
    - [9. Today and the change](#9-today-and-the-change)
    - [10. Build plan (2.8.0 item 0)](#10-build-plan-280-item-0)
    - [11. Tests](#11-tests)
    - [12. Decisions (product owner, 2026-10-20)](#12-decisions-product-owner-2026-10-20)

## 1. Live updates

Some entities change while you look at them: a trade's mark-to-market, a curve, an FX spot. When the source
that holds such an entity can push changes, Drishti keeps the open view up to date. Only the cells that
changed are repainted, and each one flashes briefly. This document explains how that works, how to watch it
happen with `curl`, what the events look like, and which settings control it.

If you only want to *use* live views, read the first section and stop. Operators should read
[Try it yourself](#try-it-yourself) and [Settings](#settings). The full path is described in
[How a tick reaches the screen](#how-a-tick-reaches-the-screen).

### What you see in the browser

1. Open a live entity, for example type `TRD END-1000008 <GO>` (a commodity forward in the `trading` pack's samples).
2. The top bar shows **`Live, p99 N ms`**. N is the 99th percentile, over the last 30 seconds and across the
   whole server, of the time from a source tick to a finished frame. Single-digit milliseconds is normal.
3. The `MTM (USD)` figure in the header strip changes every few hundred milliseconds and flashes when it does.
   The *How this view was built* panel shows the source generation going up (`endur-comm, gen 29`, `gen 30`, …).
4. Other states of the same indicator:

   | Top bar | Meaning |
   |---|---|
   | `Live` | The stream is open; no frame has arrived yet. |
   | `Live, p99 2 ms` | Frames are arriving. |
   | `Reconnecting…` | The connection dropped, or could not be opened within 10 s; the browser keeps trying by itself. The page itself works meanwhile. |
   | `Paused while hidden` | The tab has been hidden for 10 seconds and gave its subscriptions back. It takes them again and repaints when you return. |
   | `Static` | This view does not tick: the source is not live, or you picked a past business date. |
   | `Deleted` (red dot) | The source deleted the entity. A banner over the view says when; see [Deleted entities](#deleted-entities). |

A view is live only when **both** are true: the source holding the entity can push changes, and you are
looking at today's business date. Pick a past date in the top bar and every view becomes a static snapshot.

### Try it yourself

The server streams over Server-Sent Events (SSE): a plain HTTP response that never ends, made of text
events. `curl -N` (no buffering) shows them as they arrive. The examples run against a local server with
security off (`drishti.security.enabled: false`); with security on, add an `Authorization: Bearer …`
header (see [API_GUIDE.md](../guides/API_GUIDE.md)).

#### 1. Stream one view from the server

```bash
curl -N http://localhost:18480/api/v1/views/trade/END-1000008/stream
```

You should see one `view` event, then a `frame` event about every 400 ms (the demo source's tick rate).
Abbreviated:

```text
event:view
id:0
data:{"ref":{"kind":"trade","id":"END-1000008"},"mnemonic":"TRD","title":{"pill":"Commodity · Commodity forward","id":"END-1000008","with":{"text":"Harbor Point Bank",…}},"strip":[{"label":"Notional","text":"USD 231,000,000","path":"$.currency"},…,{"label":"MTM (USD)","text":"+2,148,146","tone":"pos","emphasis":true,"path":"$.mtm"}],"panels":[…],"provenance":{"layout":"Sutra cmd-forward v1 + inference","fingerprint":"205d…ad0d","source":"endur-comm","generation":29,"fetchedAt":"2026-10-01T01:01:40.877Z","live":true,"businessDate":null},"timings":{"fetch":0.09,"layout":0.13,"links":0.21,"bind":0.15,"total":0.57}}

event:frame
id:1
data:{"seq":1,"generation":30,"patches":[{"op":"strip","index":4,"cell":{"label":"MTM (USD)","text":"+2,101,143","tone":"pos","emphasis":true,"path":"$.mtm"}},{"op":"panel","panel":{"id":"built","kind":"provenance",…}},{"op":"provenance","provenance":{…,"generation":30,"live":true}}],"latencyMs":1.197765,"p99Ms":1.198}

event:frame
id:2
data:{"seq":2,"generation":31,"patches":[{"op":"strip","index":4,"cell":{"label":"MTM (USD)","text":"+2,095,229",…}},…],…}
```

Press Ctrl+C to stop. The server notices the closed connection, releases the stream's slot and, if you were
the last viewer of END-1000008, closes the source subscription.

When nothing changes for 15 seconds you see a heartbeat line instead. It is an SSE comment, which clients ignore:

```text
:hb
```

#### 2. Ask for a past business date

```bash
curl -N "http://localhost:18480/api/v1/views/trade/END-1000008/stream?asOf=2026-09-28"
```

You should see exactly one `view` event whose provenance says `"live":false,"businessDate":"2026-09-28"`,
after which the server ends the response and `curl` returns. A past date never ticks and holds no stream slot.
The header `X-Drishti-As-Of: 2026-09-28` does the same as the `asOf` parameter.

#### 3. Check the live counters

```bash
curl -s http://localhost:18480/api/v1/health/live
```

You should see something like this (while one stream from step 1 is open):

```json
{"streams":1,"topics":1,"frames":11652,"p50Ms":0.886,"p99Ms":1.917}
```

| Field | Meaning |
|---|---|
| `streams` | Open live streams on this server (view streams and monitor streams together). |
| `topics` | Entities with an open source subscription. Several viewers of one entity share one topic. |
| `frames` | Frames built since the server started. |
| `p50Ms`, `p99Ms` | Tick-to-frame latency percentiles over the rolling window (`drishti.live.window`, 30 s). Both are `0.0` when no frame was built in the window. |

The same figures appear under `live` in `GET /api/v1/admin/health` (Admin → Health), which also reports
`droppedFrames`. Frames for a slow client are merged rather than dropped, so it stays `0`.

#### 4. Watch what the browser receives

The web console does not let each panel, or each tab, open its own stream. Each **browser** opens **one** channel
to the console, shared by all its tabs and workspace panes, which relays the server's streams and turns panel
patches into ready HTML:

```bash
curl -N "http://localhost:17480/api/channel?s=view:trade/END-1000008&s=alerts"
```

(With console sign-in on, add your session cookie: `-b "drishti_session=…"`.) You should see:

```text
event: channel
data: {"ch": "", "d": {"id": "NavddrgbWaTX_LlT", "subs": ["view:trade/END-1000008", "alerts"], "who": "5f0c9e2a7d1b4c3e8a6f2d10"}}

event: hello
data: {"ch": "alerts", "d": {"user": "ash"}}

event: view
data: {"ch": "view:trade/END-1000008", "d": {"generation": 18}}

event: frame
data: {"ch": "view:trade/END-1000008", "d": {"seq": 1, "generation": 19, "patches": [{"op": "strip", "index": 4, "cell": {"label": "MTM (USD)", "text": "+2,110,140", …}}, {"op": "panel", "panel": {"id": "built", "kind": "provenance"}, "html": "<section class=…"}, …]}}
```

Every event says which subscription it belongs to (`ch`). The first `channel` event carries the channel id and
`who`, a fingerprint of the server, user and sign-in session the channel runs as (pages carry the same value in
`<meta name="drishti-live">`). The relayed `view` event carries only the generation, because the page was already
painted by the console.

### Event reference

#### Server: one view (`GET /api/v1/views/{kind}/{id}/stream`)

| Event | `id:` | Data |
|---|---|---|
| `view` | `0` | The full ViewModel, the same JSON as `GET /api/v1/views/{kind}/{id}`. Always the first event, on every connect and reconnect. |
| `frame` | the frame's `seq` | `{"seq", "generation", "patches": [...], "latencyMs", "p99Ms"}`. |
| comment `:hb` | none | Heartbeat every `drishti.live.heartbeat` (15 s) when no frame was sent. |

The ViewModel and the frame are defined field by field in [VIEWMODEL.md](VIEWMODEL.md#5-live-the-stream-and-its-patches), with a JSON Schema
(`$defs.Frame`) at `GET /api/v1/viewmodel/schema`. A frame's `patches` are a list of these:

| `op` | Fields | Apply it by |
|---|---|---|
| `strip` | `index`, `cell` | Replacing header strip cell number `index` (0-based) with `cell`. |
| `panel` | `panel` | Replacing the panel with the same `panel.id`. |
| `provenance` | `provenance` | Replacing the view's provenance (generation, source, fetched-at). |
| `deleted` | `at` (ISO-8601 instant) | Saying the entity was deleted at `at`; keeping what is shown; applying no later patches. |
| `restored` | none | Repainting the whole view: a deleted entity came back (the patches after it bring it up to date). |

#### Server: a monitor (`GET /api/v1/me/monitors/{name}/stream`)

A monitor is a saved list of 1 to 50 entities shown as rows. Its stream is **one** connection for all rows:

| Event | Data |
|---|---|
| `hello` | `{"rows": 3}`, the number of rows. |
| `row` | `{"kind", "id", "patches", "p99Ms"}`. Only `strip` patches are sent, because a row shows only the strip. |
| comment `:hb` | Every 15 s when quiet. |

Rows whose entity is missing, not live, or not visible to you simply never tick.

#### Server: alerts (`GET /api/v1/me/alerts/stream`)

```bash
curl -N http://localhost:18480/api/v1/me/alerts/stream
```

```text
event:hello
data:{"user":"anonymous"}
```

With security off and no `X-Drishti-User` header the user is `anonymous`; the console sends
`X-Drishti-User: ash` (or the signed-in user). When one of your rules fires you receive:

```text
event:alert
id:12
data:{"seq":12,"at":"2026-09-30T14:02:11.120Z","user":"ash","rule":"mtm-limit","kind":"trade","id":"END-1000008","severity":"warn","message":"MTM above 2.1m","generation":31}
```

The alerts stream does not count against `drishti.live.max-streams`.

#### Console: the browser's channel (`GET /api/channel?s=...`)

| Subscription `s=` | Relays |
|---|---|
| `view:<kind>/<id>` | The view stream above. `frame` patches for panels other than line and area charts gain an `html` field with the rendered panel. |
| `alerts` | The alerts stream. |
| `monitor:<name>` | A monitor's stream. |
| `compute:<request id>` | (2.8.0) A computed panel's request ([REACTIVE_CONNECTOR.md](../connectors/REACTIVE_CONNECTOR.md)): `hello`, `progress` (`{done, of, estimate}`), `state` (stale, with words), `done`, `failed`, and `updated` while the panel stays open and a trigger recomputes what it shows. Its owner's only. The page's `compute.js` subscribes for every panel with `data-compute-req` and fetches the panel again on `done`, `failed` or `updated`. |
| `bilive:<report id>` | A BI report's live tables ([below](#live-bi-a-reports-live-tables)): `hello`, `tick` and `status` events with no data. |

Up to `live.max_subscriptions` (32) subscriptions per channel, that is per browser: a view open in several tabs
counts once. One more is answered at once with `gone`, `DRS-5003` "this browser already follows 32 live
subscriptions (live.max_subscriptions): close some tabs or panes", and the view shows `Static`. Events: `channel`
(first; carries the id, the subscriptions it opened with and `who`), the relayed events wrapped as
`{"ch": "<subscription>", "d": <data>}`, `gone` (`{"code", "detail"}` when one subscription fails; the others carry
on), `end` (every subscription has finished; the browser then closes instead of reconnecting) and a `: hb` comment
about every 14 s when nothing else is sent.

`GET /api/channel/who` answers `{"who": "…"}`: the fingerprint of the session the browser's cookies carry now. The
browser's hub asks it when a page of another session joins (see [One connection per browser](#one-connection-per-browser)).

`gone` does not pass the server's own error code through: a view the server refuses (an entity that does not
exist, `DRS-1001` on the server; a kind you may not open, `DRS-5002`) arrives as `DRS-5003 stream refused`, and a
console that cannot reach the server as `DRS-5003 backend unreachable: …`. For example:

```bash
curl -sN --max-time 3 "http://localhost:17480/api/channel?s=view:trade/END-1000008&s=view:trade/NOPE-1"
```

You should see, among the frames of `END-1000008`:

```text
event: gone
data: {"ch": "view:trade/NOPE-1", "d": {"code": "DRS-5003", "detail": "stream refused"}}
```

To see the real reason, ask the server directly: `curl -s http://localhost:18480/api/v1/views/trade/NOPE-1/stream`
answers `404` with `"code":"DRS-1001"` and `no source holds trade/NOPE-1`.

`POST /api/channel/{id}` with `{"add": ["view:trade/MX-20000001"], "remove": ["view:trade/END-1000008"]}` changes the
subscriptions of an open channel and answers `{"ok": true}`. A workspace uses it as its panes load one by one.
It answers `404` with `DRS-5001` "no such channel: open a new one" when the channel has closed or belongs to
another user. Removing a subscription closes its upstream stream on the server; adding one that is already
there does nothing, and one beyond the limit is answered with `gone` as above.

The console also keeps `GET /api/stream/{kind}/{id}` (one view, same relaying) for API clients. Pages do not use it.

### How a tick reaches the screen

```
SourcePlugin.subscribe ─► TopicHub topic (one per entity, one source subscription, latest-wins slot)
                              │ leading-edge frame throttle (drishti.live.frame, 50 ms)
                              ▼
                          ViewStream (one per client view; also listens to its charts' source entities)
                              │ rebuild with the cached layout → PatchDiffer (old vs new ViewModel)
                              ▼
                          FrameMailbox (latest-wins; merges frames for a slow client)
                              │ one virtual-thread writer per client
                              ▼
            SSE  /api/v1/views/{kind}/{id}/stream   event: view (full ViewModel), then event: frame (patches)
                              │
            console /api/channel: ONE stream per BROWSER carrying the views of all its tabs (every workspace
                              │ pane), the alerts bell and monitors; panels re-rendered to HTML with the same
                              │ Jinja macros; charts sent as data
                              ▼
            live-hub.js (a SharedWorker, or the leader tab): routes each event to the tabs that subscribed
                              ▼
            channel.js → live.js: strip cells updated in place, panels swapped, charts moved; changes flash
```

Step by step, for one tick of END-1000008:

1. The demo source moves the trade's price one random-walk step and raises its generation (29 → 30).
2. The entity's **topic** in `TopicHub` receives the new document. If no frame went out in the last 50 ms it
   is delivered at once; otherwise it waits in a one-slot "latest wins" holder until the frame is due.
3. Each **ViewStream** watching END-1000008 rebuilds the view from the new document and diffs it against the
   previous ViewModel. The rebuild re-evaluates the Sutra's `match` (cheap) and takes the effective layout from
   the layout cache, so inference does not run again unless the document's shape changed or a Sutra was
   reloaded. Here only the MTM cell, the provenance panel and the provenance changed, so the frame has three
   patches. If a rebuild fails (the source is down, the entity is gone), the stream retries after 0.5 s,
   doubling to 30 s, and any new tick retries at once.
4. The frame goes into that client's **FrameMailbox**. The client's writer (a virtual thread) takes it and
   writes `event:frame`.
5. The console's channel relays it to the browser, rendering the `built` panel to HTML.
6. In the browser, the hub (`live-hub.js`) passes it to every tab that subscribed to `ch`, and each tab's
   `channel.js` hands it to `live.js`, which swaps the strip cell and the panel and flashes the change.

#### One connection per browser

Browsers open at most six connections to one site over HTTP/1.1, shared by all the site's tabs. A stream per view
and one for the alerts bell used them up with three tabs open; a channel per tab used them up with six tabs on
live views (several screens on a desk), and after that **no** page of the console loaded at all, with no message
(QA finding UX-01). So the number of live connections no longer grows with tabs or panes: each browser holds
**one** channel (`/api/channel?s=view:trade/MX-20000001&s=alerts`), shared by all its tabs, and a workspace's panes
share their page's subscriptions. Subscriptions that arrive later are added to the open channel
(`POST /api/channel/{id}`) instead of reconnecting. Five of the six connections are always left for pages,
searches and suggestions, however many tabs are open.

The channel is held by a **hub**, `drishti-console/web/static/js/live-hub.js`:

1. In a **SharedWorker** that can hold an `EventSource` (Chrome, Edge and Firefox on the desktop): one worker per
   console origin and browser profile, started by the first tab and ended by the browser when the last tab closes.
2. Where there is no SharedWorker, or it cannot hold an `EventSource` (Chrome on Android, for example): the tabs
   elect a **leader** with the Web Lock `drishti-live|<version>`. The leader holds the channel and relays to the
   other tabs over a `BroadcastChannel` of the same name. When the leader closes, the browser hands the lock to
   another tab, which opens a new channel; the other tabs register with it and their views repaint.
3. In a browser with neither (very old ones): a hub inside each tab, so one channel per tab, as before.

A view open in several tabs is subscribed **once**; a tab that subscribes later is first given what a stream of its
own would have opened with (the view's generation, the alerts `hello`, a deleted entity's `deleted` frame, a
`gone`), then the frames as they come. A tab hidden for 10 s gives its subscriptions back (and the hub closes
the channel when no tab holds any); shown again, it takes them back and its views repaint from fresh data.

**Sessions.** The hub serves only the tabs of one browser (one origin, one browser profile), which share the
cookies the channel was opened with. Every frame is still built by the console for the signed-in user of that
channel, with that user's permissions and field masks. Pages carry the fingerprint of their session in
`<meta name="drishti-live" content="…">` (server, user and session, hashed; never the session id), and the
channel's first event says which session it runs as (`who`). The hub sends a channel's events only to tabs
with the same fingerprint. When a page of another session joins (someone signed in again, or picked another
server, in another tab), the hub asks `GET /api/channel/who` which session the browser has now: if it is the new
page's, the channel is reopened as it; pages of any other session are told they are stale and get nothing more
(their Live pill says `Reconnecting…` until reloaded).

**A page never waits for live updates.** Pages load without a live connection; the hub is started after the page
and connects in the background. When the channel cannot be opened within 10 s, or a tab cannot reach the hub
within 5 s, each view says `Reconnecting…` rather than waiting silently, and the hub keeps trying (after a
refusal, again after 2 s, doubling up to 30 s).

Upstream, the console reads the server's streams through its pooled HTTP client and closes each one within
seconds of the browser going away, busy or quiet: it closes the HTTP response itself from a task outside the
request's cancel scope, because Starlette's scope cancels every clean-up await of a finished request. A test
guards that no page opens its own `EventSource` (only `live-hub.js` does), and
`drishti-console/tests/test_live_tabs_browser.py` opens eight live tabs and a four-pane workspace in Chromium and checks
that the next page loads at once and every tab and pane keeps ticking.

##### Hub protocol

Tabs talk to the hub with small messages (over the worker's port, or the `BroadcastChannel`):

| From a tab | Meaning |
|---|---|
| `{t: "hello", tab, who}` | register (again), with the page's session fingerprint; followed by a `sub` per key |
| `{t: "sub", tab, key}` / `{t: "unsub", tab, key}` | take or give back one subscription |
| `{t: "ping", tab}` | every 10 s; a tab silent for 45 s (closed without a word) is dropped |
| `{t: "bye", tab}` | the page is going away |

| From the hub | Meaning |
|---|---|
| `{t: "hi"}` | registered |
| `{t: "ev", key, type, d}` | one event of one subscription (`view`, `frame`, `gone`, `alert`, `row`, `hello`) |
| `{t: "error"}` | the channel dropped or cannot be opened; it keeps trying |
| `{t: "stale"}` | the page belongs to another session than the browser's current one |
| `{t: "who"}` | the hub does not know this tab (a new leader, or it was dropped): say hello again |

##### In the browser: `channel.js`

`drishti-console/web/static/js/channel.js` gives every script on the page one object, `window.DrishtiChannel`, with a
single call, `subscribe(key, handlers)`, which returns a function that unsubscribes:

```js
var off = window.DrishtiChannel.subscribe('view:trade/END-1000008', {
  view:   function (d) { /* first event: d.generation */ },
  frame:  function (f) { /* f.patches, f.p99Ms */ },
  gone:   function (d) { /* d.code, d.detail: this subscription ended */ },
  error:  function () { /* the connection dropped, or cannot be had; it keeps trying by itself */ },
  paused: function () { /* the tab was hidden for 10 s and gave its subscriptions back */ }
});
```

Handlers for `alert`, `row` and `hello` work the same way. `live.js` (entity views), `alerts.js` (the bell) and
`monitor.js` (monitor pages) are its only users. `DrishtiChannel.transport()` says how the tab reaches the hub:
`shared-worker`, `leader`, `follower`, `tab` (a hub of its own) or `none`. How it behaves:

| Situation | What happens |
|---|---|
| the first subscriptions of the browser | the hub waits 60 ms so that subscriptions made together open **one** `EventSource` on `/api/channel?s=…&s=…` (keys sorted) |
| a subscription added later (another tab, a workspace pane loading) | `POST /api/channel/{id}` with `add`, on the open connection; no reconnect |
| a key another tab already holds | nothing on the server; the tab gets the key's opening events from the hub at once |
| the last tab holding a key gives it back | `POST … {"remove": [key]}` after 60 ms; when no keys are left, the connection is closed |
| two handlers for one key (two panes of one entity) | one subscription; both handlers receive every event |
| a `POST` answered `404` (the console restarted, or another process holds the channel) | closes and opens a new channel with every current key |
| `end` | closes, so the browser does not reconnect to a channel with nothing left; a new subscription opens a new one |
| a dropped connection | every tab's handlers get `error`; the browser reconnects; the new `channel` event carries a new id, and the hub re-sends anything subscribed meanwhile; the new stream's `view` makes each view reload |
| a refused connection (the browser gave up) | `error`, then the hub opens it again after 2 s, doubling up to 30 s |
| no `channel` event within 10 s | `error` (the Live pill says `Reconnecting…`); still trying |
| tab hidden for 10 s | the tab gives its keys back and calls `paused`; when shown again it takes them back, and each view reloads from fresh data |
| inside a workspace pane (an iframe of the same site) | uses the parent window's `DrishtiChannel`, so a workspace of four panes is one tab to the hub |
| a page of another session | `GET /api/channel/who`; the channel follows the browser's current session, and other pages get `stale` |

##### In the console: lifetime of a channel

Each `GET /api/channel` gets a random id and a task per subscription that reads the server's stream through the
console's pooled HTTP client. The channel ends, and every upstream stream is closed within about a second, when
the browser disconnects, when nothing has read the channel for 5 s (a browser that vanished without closing it),
or after `end`. A channel opened with no subscriptions stays open, waiting for `POST … add`.

Open channels live in the memory of the console process that served them. If you run several console
processes behind a load balancer, make sessions sticky, so that the browser's `POST /api/channel/{id}` reaches the
process holding its channel. Otherwise it gets `404` and the browser's hub reopens its channel.

### Guarantees and limits

- **One source subscription per entity**, however many people watch it. It closes when the last
  viewer leaves.
- **Frames.**
  - A tick after a quiet period goes out immediately.
  - Ticks within 50 ms of the last frame are coalesced: the latest wins, so a fast source costs one
    delivery per frame.
  - Rebuilds for one view never overlap; a change that arrives during a rebuild causes exactly one more.
- **Minimal patches.** Only changed strip cells, changed panels, and the provenance when the
  generation moves. A Sutra edit changes the layout label, and the affected panels are re-sent.
- **Slow clients** never slow down others. Their pending frames merge (newest value per cell or panel)
  in a one-slot mailbox, so memory is bounded.
- **Reconnects.** The browser's EventSource reconnects by itself. The server opens every stream with
  a fresh `view` event, so a client that missed frames repaints from it; nothing is replayed. In the console,
  a `view` event that arrives after the first one (a reconnect) makes the page reload itself, so it shows the
  current state rather than patching a stale one.
- **Sutra edits.** A Sutra saved while views are open (hot reload) clears the layout cache; open live views pick
  up the new layout on their next tick, as a `panel` patch for every panel that changed.
- **Heartbeats.** An SSE comment every 15 s keeps proxies from closing idle streams.
- **Capacity.** `drishti.live.max-streams` (20,000) caps the streams per server, view and monitor streams together. A slot is taken atomically before any work, so concurrent requests never overshoot it, and released exactly once however the stream ends.
  A request over the cap is refused with `400`:

  ```json
  {"type":"about:blank","title":"bad request","status":400,"detail":"DRS-5001 too many live streams on this server","code":"DRS-5001"}
  ```

- **Entitlements.** Opening a stream checks that your roles may open the entity's kind, exactly like a
  plain view request (`403`, `DRS-5002`, otherwise). A missing entity is `404`, `DRS-1001`, before any
  stream starts.

#### Deleted entities

A source that learns an entity was deleted (a Kafka tombstone; an ActiveMQ or RabbitMQ delete message) tells the
views that show it, through the same subscription that carries its updates:

1. The connector drops the entity from its state, its caches and type-ahead (a cheap removal from the `HitIndex`),
   and hands every subscriber `EntityDocument.deleted(ref, provenance)`: a document whose `deleted()` is true, whose
   data is missing, and whose provenance's `fetchedAt` is when the source learnt of the delete (a tombstone's
   timestamp). Updates and deletes travel through one listener, so a delete followed by a re-creation stays in order
   in the topic's latest-wins slot.
2. Each `ViewStream` of the entity sends one frame with a single `deleted` patch, `{"op": "deleted", "at":
   "2026-10-01T09:30:05Z"}`, and rebuilds nothing while the entity stays deleted (ticks of its charts' sources
   included). The server also forgets the entity in every user's "recent" list, so the command line stops offering
   it. Alerts on the entity do not evaluate a deletion.
3. The console relays the patch unchanged. `live.js` keeps the view's last state, greys its panels and strip, puts a
   banner over it, *"MX-29000001 was deleted at 1 Oct 2026, 09:30:05 by its source. What you see is its last state;
   it no longer updates."*, and sets the top bar to `Deleted`. It is not an error page: links still work, and the
   last values can still be read and copied.
4. If the entity comes back (a new message for it), the next frame starts with a `restored` patch and the page
   repaints from the server. A page reloaded while the entity is deleted gets the usual "not held" answer
   (`DRS-1001`), since no source holds it.

A source plugin that deletes (see [§5.2 of the developer guide](../guides/PACK_DEVELOPER_GUIDE.md#52-add-a-source-plugin))
pushes `EntityDocument.deleted(ref, provenance)` to its listeners; one that never deletes needs no change. A listener
that does not care about deletes skips documents whose `deleted()` is true. Eviction from a full state store is not a
deletion and is not pushed.

### Monitors and alerts

- A **monitor** stream holds one `ViewStream` per row and multiplexes their strip patches as `row` events
  over one connection, with a latest-wins mailbox per row.
- The **alert engine** subscribes to the topics of every entity that some enabled rule watches, whether
  or not a browser is open. It evaluates each rule's Rachana-EL condition on every new document of that entity,
  fires on the false → true edge, and pushes `alert` events to that user's open streams. A rule's subscription
  keeps the source subscription open too, so an entity watched by a rule ticks even with no viewer.

#### Worked example: a monitor

A monitor is a named list of entities, saved per user. The examples use `curl` against the server with security
off; `X-Drishti-User` says whose monitor it is (with security on, your token does).

1. Save a monitor of three live entities:

   ```bash
   curl -s -X PUT http://localhost:18480/api/v1/me/monitors/rates-desk \
        -H 'Content-Type: application/json' -H 'X-Drishti-User: ash' \
        -d '{"entities": [{"kind": "trade", "id": "MX-20000001"}, {"kind": "trade", "id": "END-1000008"},
                          {"kind": "netting-set", "id": "NS-MERIDIAN-RE-NY"}]}'
   ```

   You should see the saved list echoed back: `{"entities":[{"kind":"trade","id":"MX-20000001"},…]}`. Between 1 and 50
   entities are allowed (`DRS-5001 a monitor has 1 to 50 entities` otherwise), and each must be a kind you may open.
2. Read its rows once: `curl -s -H 'X-Drishti-User: ash' http://localhost:18480/api/v1/me/monitors/rates-desk`.
   You should see one object per entity with `ref`, `mnemonic`, `title`, `strip` and `live`; an entity that cannot
   be shown has `error` instead (`DRS-1001 no source holds …`).
3. Stream it:

   ```bash
   curl -N -H 'X-Drishti-User: ash' http://localhost:18480/api/v1/me/monitors/rates-desk/stream
   ```

   You should see `event:hello` with `{"rows":3}`, then `event:row` events such as
   `{"kind":"trade","id":"END-1000008","patches":[{"op":"strip","index":4,"cell":{"label":"MTM (USD)","text":"+2,104,880",…}}],"p99Ms":1.2}`.
   Only strip patches are sent. The stream counts as one live stream against `drishti.live.max-streams`, however
   many rows it has.
4. In the console, the monitor page subscribes with `monitor:rates-desk` on the tab's channel.

#### Worked example: an alert that fires

1. Look at the value you want to watch. `END-1000008`'s MTM moves around 2.1 million:

   ```bash
   curl -s http://localhost:18480/api/v1/views/trade/END-1000008 | python3 -c "
   import json, sys; print([c['text'] for c in json.load(sys.stdin)['strip'] if 'MTM' in c['label']])"
   ```

   You should see something like `['+2,102,537']`.
2. Keep a stream open in a second terminal, so you see the alert arrive:

   ```bash
   curl -N -H 'X-Drishti-User: ash' http://localhost:18480/api/v1/me/alerts/stream
   ```

   You should see `event:hello` with `{"user":"ash"}`.
3. Save a rule (`PUT /api/v1/me/alerts/rules/{name}`). `when` is a Rachana-EL condition over the entity's
   document; `message` is a template:

   ```bash
   curl -s -X PUT http://localhost:18480/api/v1/me/alerts/rules/mtm-above-2-1m \
        -H 'Content-Type: application/json' -H 'X-Drishti-User: ash' \
        -d '{"kind": "trade", "id": "END-1000008", "when": "$.mtm > 2100000", "severity": "warn",
             "message": "${$.tradeId}: MTM ${fmt($.mtm, '"'"'signed0'"'"')}"}'
   ```

   You should see the rule echoed back. Saving checks the condition and the message (`DRS-2101` for a typo,
   `DRS-5001 severity must be one of […]` for anything but `info`, `warn` or `critical`), then evaluates the rule **at once** against the
   current document.
4. If MTM is already above 2.1 m, the alert fires immediately; otherwise it fires on the first tick that crosses
   the line. In the second terminal you should see:

   ```text
   event:alert
   id:1
   data:{"seq":1,"at":"2026-10-01T01:50:12.301Z","user":"ash","rule":"mtm-above-2-1m","kind":"trade","id":"END-1000008","severity":"warn","message":"END-1000008: MTM +2,104,880","generation":41}
   ```

   It does not fire again while MTM stays above 2.1 m. When MTM falls below and rises above again, it fires again.
5. Recent alerts (the newest `drishti.alerts.keep`, 1,000, per user, kept in the identity database across restarts) are listed by
   `curl -s -H 'X-Drishti-User: ash' 'http://localhost:18480/api/v1/me/alerts?limit=10'`; the bell in the console
   shows the same. Delete the rule with `curl -s -X DELETE -H 'X-Drishti-User: ash' http://localhost:18480/api/v1/me/alerts/rules/mtm-above-2-1m`.

Packs can suggest rules for a kind (`alerts:` in `pack.yaml`), which the console offers when you create one:
`GET /api/v1/me/alerts/suggestions/netting-set` returns, with the retired example pack `finance` enabled,
`{"kind": "netting-set", "name": "PFE near limit", "when": "$.utilisation > 0.8", "severity": "warn", …}`. With the
banking packs enabled, no pack suggests rules for `trade`, so `GET /api/v1/me/alerts/suggestions/trade` returns `[]`.

A rule is saved with `PUT /api/v1/me/alerts/rules/{name}`; it needs `kind`, `id` and `when`, and takes an
optional `severity` (`info`, `warn` (default) or `critical`), `message` and `enabled`. For example, to be
told when END-1000008's MTM rises above 2.1 million:

```json
{"kind": "trade", "id": "END-1000008", "when": "$.mtm > 2100000", "severity": "warn",
 "message": "MTM ${fmt($.mtm, 'signed0')}"}
```

Because it fires on the false → true edge, you get one alert when MTM crosses 2.1 m, not one per tick while it
stays above. Users normally create rules from the console's alerts page; see [USER_GUIDE.md](../guides/USER_GUIDE.md).

### Live BI: a report's live tables

Business intelligence uses this machinery too (Rūpaka phase 5, [RUPAKA.md](RUPAKA.md) section 12; the pages are [BI_GUIDE.md](../guides/BI_GUIDE.md) section 18). A dataset table with `mode: live` ([DATASETS.md](../guides/DATASETS.md#71-live-tables-phase-5)) is **fed by the same
topics**: for each entity of its kind the engine subscribes through the source layer (the connector's `subscribe`, the same call a live view makes), applies each change to an in-memory table by key (batched every 100 ms, so ticks coalesce as they do for a view), and keeps a counter that grows with
every change. It is **not** a view stream: it carries no panel patches and costs the entities' topics one more listener (`TopicHub`), not one more source subscription; a dataset that names a connector (`source: { connector: ... }`) subscribes through that connector directly instead, for a stream that keeps no state but ticks what a store serves.

What reaches a browser is small. A report over such a table has one stream, `GET /api/v1/bi/reports/{id}/live` ([API_GUIDE.md](../guides/API_GUIDE.md#reports-apiv1bireports-phase-3)), relayed by the console's one channel as the subscription `bilive:<report id>`:

| Event | Meaning |
|---|---|
| `hello` | the state of the report's live tables when the stream opens (the generation, the last change, `live` or `stale` or `failed`, counters per table) |
| `tick` | the tables changed: the **latest** state, at most one per `drishti.bi.live.frame` (250 ms); a slow browser skips ticks, it never queues them (the same latest-wins rule as a view's frame mailbox) |
| `status` | how the tables are doing when nothing changed, every `drishti.bi.live.status` (5 s): the heartbeat of this stream |

A tick carries **no data and no mask**. The page asks its visuals again, as the person looking, through the BI query path: **row rules and masks are applied to every answer, never to the stream**, so a stream shared by a hundred readers is the same bytes and each reader's picture is their own.
The hub (`live-hub.js`) forwards the two event names `tick` and `status`; a tab hidden for ten seconds gives the subscription back like any other and the report redraws everything when it takes it again.

| Limit | Setting | |
|---|---|---|
| streams open at once | `drishti.bi.live.max-streams` (100), `max-streams-per-user` (10) | `429 DRS-9051`; these are BI streams and do not count against `drishti.live.max-streams` |
| one stream per distinct report | | a board of ten tiles over two reports holds two |
| the browser's subscriptions | `live.max_subscriptions` (32) | a `bilive:` subscription counts like a view |

Pause, the "last update" time and the stale and disconnected states of the indicator are the page's (`bi-live.js`): *stale* is "nothing heard for `bi.live.stale_seconds`" (the 5-second status is what keeps a quiet healthy stream from looking stale) or the table's own `stale` state
(a feed that pushes and then goes silent for `drishti.bi.live.stale-after`).

#### Results computed by a streaming producer

A live table keeps the current rows of a kind and recomputes measures over them; it does not maintain heavy aggregates over a large stream
incrementally (only the plain rollups a dataset declares, [RUPAKA.md](RUPAKA.md) section 20.11). That belongs in a **producer**: Pravaha, Flink,
ksqlDB or a custom BI server computes keyed result rows (a total per desk, per desk and currency) and owns its arithmetic; Drishti **serves**
them: a screen per result, live delivery to many browsers, field masks, alerts, sharing and embedding, as for any kind.

This needs no Drishti code. The producer writes each result row to a **compacted Kafka topic** as an envelope keyed `<kind>/<id>`
([KAFKA_CONNECTOR.md](../connectors/KAFKA_CONNECTOR.md)), and a pack declares the result kind, its command and a Kafka connector:

```yaml
kinds:
- desk-exposure                             # a result kind, declared like any other
mnemonics:
  DEXP:
    kind: desk-exposure
    label: Desk exposure (live)
connectors:
  risk-results:
    plugin: kafka
    kinds:
    - desk-exposure
    settings:
      bootstrap-servers: ${DRISHTI_KAFKA_BOOTSTRAP:localhost:9092}
      topics: ${DRISHTI_RISK_RESULTS_TOPIC:risk.results}
      stale-after: 2m                       # amber in Health if the producer goes quiet
      disk-cache.enabled: true
```

`DEXP RATES <GO>` then opens a live view of the desk's exposure that ticks as the producer upserts, and a dataset table with `mode: live` over
`desk-exposure` puts the rows in a report. The rows follow a few rules:

- **Stable ids from the group-by key**: `RATES` for a desk, `RATES|USD` for desk and currency (`|` between key parts, distinct from other kinds'
  ids); a grand total is a row like any other with the id `ALL`.
- **State, not deltas**: each message is the whole current row (an upsert); a row that leaves the result is deleted with a tombstone, not sent
  as zeros. A producer that thinks in weighted deltas (+1 / −1) turns them into state before it writes.
- **Per key, in order**: one row's messages go to one partition; a new Drishti server reads the compacted topic from the start and has the
  current state, so a producer never replays for Drishti.
- **Small rows**: under 64 KB, a list inside a row under 500 items; detail belongs in the rows of the source kind.

Not built (proposed 2026-10-06): a `bi` source plugin that takes an HTTP stream and turns deltas into state, metadata read from a `_bi` field
(generation, as-of, input watermark: today it shows as an ordinary field), drill-down from a total to its rows by predicate, and protection
against aggregates that leak (a result field computed from a masked input declared `masked-from: [field]`, and a minimum group size below which a
row's figures show as `•••` for readers without `raw`).

### Latency

The top bar shows `Live, p99 N ms`: the rolling (30 s) p99 of source-tick → frame-built across the
server, recorded lock-free in an HdrHistogram. `GET /api/v1/health/live` reports streams, topics,
frames, p50 and p99. Prometheus exposes the same as `drishti_live_latency_p99_milliseconds`,
`drishti_live_streams`, `drishti_live_topics` and `drishti_live_frames_total` at `/actuator/prometheus`.

```bash
curl -s http://localhost:18480/actuator/prometheus | grep ^drishti_live
```

```text
drishti_live_frames_total 11680.0
drishti_live_latency_p99_milliseconds 1.917
drishti_live_streams 0.0
drishti_live_topics 0.0
```

If p99 stays above 40 ms, follow [runbooks/live-latency-high.md](../admin/runbooks/live-latency-high.md).
Measured numbers are in [PERFORMANCE.md](../admin/PERFORMANCE.md).

### Settings

All under `drishti.live` in the server's configuration ([CONFIGURATION.md](../admin/CONFIGURATION.md)):

| Key | Default | Effect |
|---|---|---|
| `drishti.live.frame` | `50ms` | Ticks for one entity within this interval coalesce; the latest wins. Larger means fewer frames and less CPU, but staler values. |
| `drishti.live.heartbeat` | `15s` | Interval of the `:hb` comment on a quiet stream. Keep it below your proxy's idle timeout. |
| `drishti.live.max-streams` | `20000` | Live view and monitor streams per server. Further requests get `DRS-5001`. |
| `drishti.live.window` | `30s` | Rolling window for the p50 and p99 figures. |

Example: coalesce more aggressively and protect a smaller server.

```yaml
# application.local.yaml next to the jar
drishti:
  live:
    frame: 100ms
    max-streams: 5000
```

Or for one run: `java -jar drishti-server-2.9.6-exec.jar --drishti.live.frame=100ms`.

The console has one live setting, in `drishti-console/config/application.yaml`:

| Key | Default | Effect |
|---|---|---|
| `live.max_subscriptions` (`DRISHTI_LIVE_MAX_SUBSCRIPTIONS`) | `32` | Subscriptions one browser's channel carries at most: views across all its tabs and workspace panes, the alerts bell, monitors (a view open in several tabs counts once). Each holds one stream to the server from the console's pool (`backend.pool_size`, 64), so keep it well below that. One more is answered with `gone`, `DRS-5003`, and the view shows `Static`. |

Behind a reverse proxy, turn off response buffering for SSE (`proxy_buffering off;` in nginx; the console
already sends `X-Accel-Buffering: no`) and set the read timeout above the heartbeat interval. See
[OPERATIONS.md](../admin/OPERATIONS.md).

### Streaming sources

A plugin that declares `live` pushes each new generation of an entity through `subscribe`. The `kafka` plugin does
this from a topic: it rebuilds the latest document per entity from the start of the topic, then forwards every new
message to the views that are open on that entity, through the same TopicHub, frames and patches as any live source.
For Live, the router asks live sources first, and a real stream before the demo samples; a picked business date
never streams.

`GET /api/v1/sources` lists every source with `"live": true` or `false`. In the sample configuration only
`demo` is live; the `trading` pack's Kafka connector `trading-stream` becomes live when you set
`DRISHTI_STREAM_TRADING=true` and point `DRISHTI_KAFKA_BOOTSTRAP` at your brokers.

#### Worked example: making trades live from Kafka

The trading pack declares the connector in `config/packs/trading/pack.yaml`:

```yaml
connectors:
  trading-stream:
    plugin: kafka
    enabled: ${DRISHTI_STREAM_TRADING:false}
    kinds: [trade]
    settings:
      bootstrap-servers: ${DRISHTI_KAFKA_BOOTSTRAP:localhost:9092}
      topics: ${DRISHTI_TRADING_TOPIC:drishti.trading.trades}
      kind: trade                 # "mapped" messages: the whole value is the trade document …
      id-field: tradeId           # … and its tradeId is the entity id
      disk-cache.enabled: ${DRISHTI_STREAM_DISK_CACHE:true}
```

1. Create the topic (compacted, so the latest message per trade is kept):

   ```bash
   kafka-topics.sh --bootstrap-server localhost:9092 --create --topic drishti.trading.trades \
     --partitions 3 --config cleanup.policy=compact
   ```
2. Start the server with the connector on:

   ```bash
   DRISHTI_STREAM_TRADING=true DRISHTI_KAFKA_BOOTSTRAP=localhost:9092 java -jar drishti-server/target/drishti-server-*-exec.jar
   ```

   `curl -s http://localhost:18480/api/v1/sources` should now list `trading-stream` with `"live":true` and
   `"health":"UP"` (`"UP (catching up)"` while it reads the topic from the beginning).
3. Publish a trade. In mapped mode the message **value** is the document; the key should be the trade id (it is
   used for deletes):

   ```bash
   echo 'MX-29000001|{"tradeId":"MX-29000001","productType":"IRS_FIXFLOAT","productName":"Interest rate swap (fixed/float)","assetClass":"Rates","status":"Live","currency":"USD","notional":50000000,"mtm":12500,"counterparty":{"id":"CP-MERIDIAN-RE","name":"Meridian Reinsurance Ltd"}}' \
     | kafka-console-producer.sh --bootstrap-server localhost:9092 --topic drishti.trading.trades \
         --property parse.key=true --property key.separator='|'
   ```
4. Open `TRD MX-29000001 <GO>` (or `curl -N http://localhost:18480/api/v1/views/trade/MX-29000001/stream`). The view uses
   the `irs-fixfloat` Sutra (its `where` matches), `How this view was built` says `trading-stream, gen <offset>`
   (the generation of a Kafka document is its offset), and the top bar says `Live`. This small document lacks
   the legs, schedule and risk the Sutra reads, so those panels say *No data available*; a real feed carries
   them. All live sources that serve `trade` are asked, live ones first, so a trade held both in Kafka and in the
   Delta Lake store is read (and ticks) from Kafka.
5. Publish the same trade again with `"mtm":13750`. Within a frame (50 ms) the stream sends
   `event:frame` with a `strip` patch for `MTM (USD)` (`+13,750`) and the provenance's new generation, and the
   cell flashes in the browser.
6. Publish a *tombstone*: a **null** value, not an empty one (an empty value is not a delete). With
   `kafka-console-producer` use `--property null.marker=NULL` and send `MX-29000001|NULL`. The trade is deleted from
   the connector, and a new view of it is `DRS-1001`. The delete is pushed: a view already open gets a `deleted`
   patch and says *"MX-29000001 was deleted at …"* over its last state, greyed ([Deleted entities](#deleted-entities)),
   and the id leaves type-ahead at once.

Other shapes: without `kind`/`id-field` the connector expects **envelopes**,
`{"kind": "trade", "id": "MX-20000001", "doc": {…}}`, and a tombstone's key is `kind/id`. `mode: ticks` keeps nothing in
memory (although it still replays the topic from the beginning at start, `UP (catching up)`) and only drives the
ticks of open views while a store (Delta Lake, a database) serves the documents: such a
view is live although its document came from the store, because a live connector declares that it pushes the kind
(`SourcePlugin.pushes`), and it subscribes there.
See [CONNECTOR_DEVELOPER_GUIDE.md](../connectors/CONNECTOR_DEVELOPER_GUIDE.md) for every Kafka setting.

### The demo source

The `demo` plugin ticks entities whose fixture says `live` (for example the `trading` pack's sample trades,
curves, spot, netting sets) every `tick-ms` (400 ms), but only while someone watches. It takes a random-walk
step and keeps dependent values consistent: for the future, MTM follows the last price, and the intraday
settlement row and variation margin follow too. Its settings:

```yaml
drishti:
  sources:
    plugins:
      demo:
        settings:
          tick-ms: 1000     # one step a second instead of 400 ms
          ticking: false    # or freeze every demo entity
```

`DRISHTI_DEMO_ENABLED=false` turns the demo source off altogether, so no sample entity ticks.

### Implementation note

The architecture first proposed re-binding only the panels whose expressions read the changed
paths. Every expression can report its paths (`Expr.paths`), and every cell and row carries its
`path`. Measurement showed that rebuilding the whole view with the cached layout and diffing takes a
few hundred microseconds, well inside the budget. The simpler rebuild-and-diff design was therefore
kept. The path index remains available if a very large view ever needs it.

## 2. History queries: an entity over time

Status: **built and released in 2.6.5** (2026-10-20), between 2.6.0 (wave D) and 2.6.6; the *As built* notes say where
the build differs from the design. Written 2026-10-20 from the
design agreed with the product owner, checked against the code on `develop` and against the two connector branches that are not merged
yet (drishti-iceberg and iceberg-external; Cassandra). Related: [ARCHITECTURE.md section 5](ARCHITECTURE.md#5-walk-through-one-request) (a command becomes a screen),
[CLUSTER_DEPLOYMENT.md](../admin/CLUSTER_DEPLOYMENT.md#1-drishtinet-many-servers-one-drishti) (forwarding), [CLUSTER_DEPLOYMENT.md](../admin/CLUSTER_DEPLOYMENT.md#5-super-scale-500-gb-of-json-a-day-for-seven-years-design-not-built) section 10 (slices and owners),
[CLUSTER_DEPLOYMENT.md](../admin/CLUSTER_DEPLOYMENT.md#4-scale-strategy-millions-of-trades-fast-in-a-bounded-heap) (the history row of its table), [section 3](#3-drishti-iceberg-drishtis-own-apache-iceberg-engine),
[CASSANDRA_CONNECTOR.md](../connectors/CASSANDRA_CONNECTOR.md).

### 1. The ask

The product owner (2026-10-20): *"if someone says `trd dffgg 30` then we should show top level attributes for that trade for last 30
days; if someone says `eq` and `30` then we should show latest 30 days of data for that equity and so on."* It is called a killer
feature. Traders and risk managers compare Drishti with Bloomberg, so the bar is a terminal: typed, keyboard-first, dense, fast and
beautiful in every theme.

One sentence: **add a range after the id, and the screen becomes that entity over time.**

```text
TRD 1000015 30 <GO>          the trade, one row per business day, the last 30 days it has data
EQ EQ-CSCA 3M <GO>           the equity: a price chart, the high, low and change, and the same table
FX FX-AUDUSD YTD <GO>        the FX spot since 1 January
CRV CRV-USD-SOFR FROM 2026-09-01 TO 2026-09-30 <GO>
TRD 1000015 30 DIFF <GO>     only the fields that changed, day by day
```

### 2. What exists already

History queries are built from parts that are in the code now. Nothing below is new.

| Part | Where | What it gives the design |
|---|---|---|
| One field over time | `HistoryController.series`, `GET /api/v1/history/{kind}/{id}/series?path=&days=` (drishti-server `server/api/HistoryController.java`) | One Rachana-EL path over up to `MAX_DAYS` = 260 business days, one `router.fetch(ref, AsOf.of(d))` per day, all at once, redacted per day with `Entitlements.redact`. Used by the field dialog `web/static/js/history.js` (click a number). |
| What changed | `HistoryController.diff`, `GET /api/v1/history/{kind}/{id}/diff?from=&to=&fromKnownAt=&toKnownAt=`; `engine/history/DocumentDiff` (limit 2000 changes) | The diff between two days, or two "known at" instants. Console page `/compare/{kind}/{id}`, export `/export/compare/{kind}/{id}.csv`. |
| Dated reads | `SourcePlugin.fetch(EntityRef, AsOf)`, `AsOf(businessDate, knownAt, live)`, `Provenance(source, generation, fetchedAt, live, businessDate)` (drishti-api) | Every dated connector answers one entity on one date; the provenance says which date the data is for and its generation. |
| Time travel | `SourcePlugin.timeTravel()`; `DRS-1007` `NO_TIME_TRAVEL` | Delta and Iceberg answer `knownAt`; others refuse it rather than show today's data. |
| A range of days as columns | `SourcePlugin.scan(ScanRequest)` with `ScanRequest(kind, paths, filters, from, to, batchRows)` and `ColumnScans.over` (at most `MAX_DAYS` = 800 calendar days) | Promoted columns over a date range with an `EQ` filter, no document parsed. Delta implements it; the others fall back. |
| Business days | `engine/time/BusinessDates` (`calendar().previous(d)`, `earliest()`, `current()`, `parse(date, knownAt)`) | The calendar the series already walks. |
| Routing | `engine/source/SourceRouter` (`fetch`, `fetchAll`, `readOrder`, `Fallback` for Drishti\*net peers), `SourceRegistry` | Several connectors per kind in read order (Redis for recent days, Delta behind it); peers when no local source holds the entity. |
| Forwarding | `server/net/ViewForwardFilter` (already forwards `/api/v1/history/{kind}/{id}...`), `PeerSource`, `NetDataController` (`/api/v1/net/fetch/{kind}/{id}`) | A history request reaches the node that serves the kind with no new routing code. |
| The command line | `engine/command/CommandParser`, `server/api/CommandController` (`POST /api/v1/command`, `/suggest`, `/history`, `/aliases`), `CommandMemory.expand` (aliases), `engine/search/SearchQuery.pick/looksLikePick`, `engine/search/PhraseParser`; console `routes/terminal_routes.py` `/go`, `web/static/js/command.js` | Where the range is parsed and the page chosen (section 4). |
| Pinned links | console `core/asof.py` (`?asOf=&knownAt=`), `share_url()` and `view()` in `routes/terminal_routes.py` (`?gen=`, `pin = asit / changed / retry`) | A history row opens "the normal view as of that day" with the machinery that exists. |
| Export | console `routes/export_routes.py`, `core/export.py` (`to_csv` with BOM and CRLF, `filename()`, `to_xlsx`, the formula guard) | The conventions history export follows. |

CLUSTER_DEPLOYMENT.md measured the series at **0.02–0.12 s for 30 days** and diffs at 6–14 ms (2026-10-18, Delta). History queries
generalise the series from one field to a whole row and give it its own page, command and API.

### 3. Principles

1. **One command, any kind.** The range follows the id; the kind decides the shape (a document table, or a market chart and table).
2. **Days with data, not calendar days.** `30` is the last 30 business days on which that entity has data. The header names the dates.
3. **Per-kind behaviour is YAML.** A pack's `history:` block picks columns, chart fields and limits. No code per kind.
4. **The SPI stays optional.** A new default method loops over today's dated reads; a connector adds a fast path only when its layout
   allows one. Every connector works on day one.
5. **Same masks, same rights.** Every day's row is redacted exactly as the live view is. Rights are checked per kind.
6. **Bounded.** A maximum range, a row cap, a timeout, bounded parallelism and a cache keyed by the data's version.
7. **Terminal-grade.** Keyboard-first, dense, every theme, every contrast rule, under a second on a laptop.

### 4. The command

#### 4.1 Grammar

```text
<MNEMONIC> <ID> <RANGE> [DIFF] <GO>

RANGE  :=  N                     N business days on which the entity has data (1 ≤ N ≤ max-days)
        |  N D | N W | N M | N Y calendar days, weeks, months, years back from the date box (30D, 2W, 3M, 1Y)
        |  YTD | MTD | QTD       since the first day of the year, month or quarter
        |  FROM yyyy-mm-dd [TO yyyy-mm-dd]
```

Case never matters (`30d`, `ytd`, `from`). `<GO>` anywhere is ignored, as today. The end of every range is the date box (the console's
`drishti_asof`, live means the current business date) unless `TO` says otherwise. `N D` and the other calendar forms take every business
day in the span that has data, so `30D` gives about 21 rows and `30` gives 30.

#### 4.2 Where it is parsed, and every clash checked

Today `CommandParser.parse` splits the text in two (`split("\\s+", 2)`): the mnemonic and **everything else as the id**. So `TRD 1000015 30`
is read now as the id `1000015 30`; `CommandController.resolve` then finds no such trade and, because `SearchQuery.looksLikePick` is true for
any id containing a space, shows a pick list. The range must therefore be taken off **before** that, in a new class
`engine/command/HistoryRange` (parse and render, about 150 lines), called from `CommandController.resolve` after `CommandMemory.expand`.

The rule: **a range is the last one to four words, never the first word after the mnemonic, and only after an id.** Then:

| Typed | Today | With history | Why it cannot clash |
|---|---|---|---|
| `TRD 1000015` | opens the trade | the same | no range word |
| `TRD 1000015 30` | pick list (id `1000015 30` not found) | history, 30 days | the range is the third word |
| `TRD 30` | opens trade `30` | the same | the range is never the first word after the mnemonic, so a numeric id is always an id |
| `TRD MX-20000001 30` | pick list | history | as above |
| `TRD MX-2000 30` (a prefix) | pick list | the id is resolved as today first: one match opens its history; several give the pick list `/s?q=TRD MX-2000&days=30`, whose rows open history | the range is kept on the pick list |
| `BOND UST 2030` where `UST 2030` **is** an id | opens it | opens it | the whole rest is tried as an id first (the existing `exists()` read); only when it is not an entity is a trailing range taken off |
| `TRD MX-2* 30`, `TRD desk=rates 30` | pick list or search | the same, unchanged | a `*`, an operator (`SearchQuery.OPERATOR`) or `where / order by / limit` (`looksLikeSearch`) means a search; ranges apply to one entity only in 2.6.5 |
| `TRD limit 30` | search (`SUFFIX`) | the same | `looksLikeSearch` is checked first |
| `MYBOOK 30` (alias for `BOOK BOOK-RATES-1`) | `BOOK BOOK-RATES-1 30`, a pick list | history of the book | `CommandMemory.expand` appends trailing words (`MYBOOK F3` becomes `BOOK BOOK-RATES-1 F3`), so the range survives the alias |
| `MYPICK 30` (alias for `TRD desk=rates`) | search | the same | an alias that expands to a search stays a search |
| `MX-20000001 30` (bare id) | not read (two words), plain words | history | allowed only when `ReferenceCatalog.kindOf(id)` names the kind (the pack's `graph.id-patterns`), as for a bare id today |
| `1000015 30` (bare, no id pattern) | plain words | plain words, which now understand it (section 15.5): an id that names one entity of one kind, then a range or a time phrase | no id pattern for `1000015` |
| `MKT`, `RUPAKA …`, `FORM …`, `FUNC …`, `WHAT …`, `DATAMAP …` | pack overview, BI, form, functions, Knowledge, data map | the same | single words and reserved first words are handled before the command in `/go` |
| `TRD 1000015 FROM 2026-09-01` | pick list | history from that date to the date box | `FROM` and `TO` are keywords only in the last four words and only followed by a date |
| `TRD YTD` | opens trade `YTD` | the same | first word after the mnemonic |

`HistoryRange.trailing(String rest)` returns `Optional<Split(String id, HistoryRange range, boolean diff)>`; it never throws. A range that is
read but not allowed (too large, `TO` before `FROM`, a future `FROM`) fails with its own code (section 16), not with a pick list.
`HistoryRange.malformed` names what reads as a range but is none (`0`, `-5`, `99999`, `1.5M`, `3X`, `FROM yesterday`,
`FROM 2026-02-30`, `DIFF DIFF`, a bare `DIFF`, `30 30`, and since QA1-203 `30.`, `1e3`, a dangling `FROM` or `TO`
(`FROM 2026-10-01 TO`: *TO needs a date*), `TO` without `FROM` (*TO follows FROM*), `DIFF 30` (*DIFF follows a range: TRD 1000001 30
DIFF*)); `CommandController` answers it with `DRS-4022` (`DRS-4021` for a number of days
past `max-days`) and what to type when what is before it is one entity the caller may open and the whole text is not itself an entity.
`TO` before `FROM` and a date that does not exist are `DRS-4022` too, as at the endpoint. The console's `/go` shows the message on the
terminal page. Digits of another script are read as digits first (`TRD 1000001 ３0` is `TRD 1000001 30`, `０` is *0 days is empty*), as
the endpoint reads `days=０`. A bare id with such a range (`1000015 -5`, `1000015 since yesterday`) goes to plain words, which answer the
same `DRS-4022` (section 15.5); the console shows it on the terminal page too.

#### 4.3 The answer and the redirect

`ApiDtos.CommandResponse` gains one field, `history` (null when there is no range):

```json
{ "ref": {"kind": "trade", "id": "1000015"}, "mnemonic": "TRD", "list": null, "matched": null, "pack": null,
  "history": {"range": "30", "days": 30, "from": null, "to": null, "diff": false} }
```

The console's `/go` (`routes/terminal_routes.py`) redirects to **`/v/{kind}/{id}/history?days=30`** (or `?from=&to=`, plus `&diff=1`).
`/v/{kind}/{id}?days=30` is accepted too and redirects there (303), so the short link the product owner wrote works. The route is
declared before `/v/{kind}/{id_:path}`, as `/about` and `/panels` are. A pick list carries `days` through `/s?q=…&days=30`.

#### 4.4 Type-ahead, history and aliases

- `SuggestionService.suggest` (engine `command/SuggestionService`): when the text is `<MNEMONIC> <ID> ` and the id names one entity, the
  first suggestions are ranges: `30 — last 30 days`, `3M — last 3 months`, `YTD — since 1 January`, `30 DIFF — what changed`. `Tab`
  completes, as today. The kind's `history.default-days` is the first one offered. Across Drishti*net the peers' suggestions are
  merged with one row per range (each completes differently) and the ranges first, then the entity whose id was typed, from whichever
  node holds it (on a node that does not hold `1000002`, `TRD 1000002` lists the peer's `1000002` before this node's `10000020`…;
  QA1-204), then the rest; for a caller with field masks the range rows keep
  their labels (only entity rows are described again from the document as the caller sees it). A partial range (`TRD 1000015 3`)
  offers the ranges that start with it to every caller.
- `CommandMemory.remember` keeps the typed text, so `↑` recalls `TRD 1000015 30` as typed.
- Aliases store any command text, so `MYTRD = TRD 1000015 30` works without change.
- Share links carry the range and pin the end: `/v/trade/1000015/history?days=30&to=2026-10-19` (and `&knownAt=` when pinned), so the
  recipient sees the same rows a week later. A link without `to` is live: it moves with the business date.

### 5. The history view

#### 5.1 Shapes

| Shape | When | Screen |
|---|---|---|
| `document` | default (trades, counterparties, agreements) | header, the table, the drawer of the day opened |
| `market` | `history.<kind>.chart` is set (equity, curves, FX), or `shape: market` | header, headline numbers, a chart, the same table |
| none | no source of the kind keeps daily history and no embedded series (section 6) | today's view with a banner: *"FX-AUDUSD keeps no daily history here: its connector holds today's data only."* (`DRS-1012`) |

#### 5.2 Trade history (document shape)

```text
┌─ TRD 1000015 ▸ HISTORY ─────────────────────────────────────────────────────────────────────── 30 business days ─┐
│ ComSwap ENGY-SWAP · S 72,000 · CP-HARBORPT · BOOK-COMM-2          2026-09-08 → 2026-10-19 · trade-endur (delta)  │
│ 30 rows · 6 days changed · 1 day restated · known: latest                       [C] changes only  [D] diff  [E] ⤓ │
├────────────┬───────────┬─────┬──────────┬────────────┬──────────────┬─────────────┬───────────┬──────┬──────────┤
│ Date       │ Status    │ Ver │ Quantity │ Price      │ Maturity     │ Portfolio   │ Cpty      │ Legs │ Events   │
├────────────┼───────────┼─────┼──────────┼────────────┼──────────────┼─────────────┼───────────┼──────┼──────────┤
│▶2026-10-19 │ VALIDATED │  3  │   72,000 │ 2,341.0500▲│ 2028-07-31   │ BOOK-COMM-2 │ CP-HARBOR…│  2 ▸ │  5 ▸     │
│ 2026-10-16 │ VALIDATED │  3  │   72,000 │ 2,336.1200▼│ 2028-07-31   │ BOOK-COMM-2 │ CP-HARBOR…│  2 ▸ │  5 ▸     │
│ 2026-10-15 │ VALIDATED │ ░3░ │ ░75,000░ │ 2,338.4000 │ 2028-07-31   │ BOOK-COMM-2 │ CP-HARBOR…│  2 ▸ │ ░5░▸ ⟲2  │
│ 2026-10-14 │ VALIDATED │  2  │   72,000 │ 2,332.9131 │ 2028-07-31   │ BOOK-COMM-2 │ CP-HARBOR…│  2 ▸ │  4 ▸     │
│ 2026-10-13 │ ·         │  ·  │        · │          · │ ·            │ ·           │ ·         │  ·   │  ·       │
│ …          │           │     │          │            │              │             │           │      │          │
├────────────┴───────────┴─────┴──────────┴────────────┴──────────────┴─────────────┴───────────┴──────┴──────────┤
│ ░ changed from the day before   ⟲2 two versions that day (the latest shown)   · same as the day before (dimmed) │
│ ↑↓ row  ←→ column  Enter open the day  C changes only  D diff  F fields  E export  R range  ? keys  Esc view   │
└────────────────────────────────────────────────────────────────────────────────────────────────────────────────┘
```

- **One row per business day**, newest first on screen (the JSON is oldest first, section 8). A day with no data for this entity is left
  out; it is listed in the header's tooltip ("no data on 2026-09-14").
- **Columns** are the kind's top-level scalars: the pack's `history.<kind>.columns` when given, otherwise the kind's search key columns
  (`drishti.search.columns`, from `columns:` in pack.yaml) first and then every other top-level scalar, labelled as the view labels them.
  A column empty on every row is hidden (`F` shows it). Values use the pack's formats (`fmt($.PRICE, 'price2')`).
- **Nested parts** (top-level lists and objects: `LEGS`, `EVENTS`) show as a count with `▸`. Clicking, or `Enter` on the cell, opens
  that day's part in the drawer. A count that changed is highlighted like any changed cell.
- **Changed cells** (different from the previous day, the day before the range included, section 7.3) use the `--d-warn` tint and a
  `▲`/`▼` for numbers; unchanged cells after the first change are dimmed with `--d-muted`, so the eye finds the moves.
- **Changes only** (`C`) hides days on which nothing moved; the header says "6 of 30 days".
- **Several versions** in one day (a restatement, a second load) show `⟲2` beside the date; its tooltip names the versions or
  generations. The row is the latest version of that day.
- **A row opens the normal view as of that day**: `Enter` (or a double click) goes to
  `/v/{kind}/{id}?asOf=2026-10-15&gen=<generation>&via=history` (and `&knownAt=` when the history is "known at"), so the banner
  says "as it was, from its history" or "changed since the history read that day" (never "shared"), with *Back to the history*.

#### 5.3 Equity history (market shape)

```text
┌─ EQ EQ-CSCA ▸ HISTORY ──────────────────────────────────────────────────────────────────────────── 3 months ──┐
│ Cascadia Airlines Inc. · TSX · CAD · Airlines                    2026-07-20 → 2026-10-19 · 64 days · market    │
│ LAST 21.84   CHG +2.89 +15.25%   HIGH 22.10 (2026-10-08)   LOW 18.71 (2026-08-04)   RANGE 3.39   AVG 20.11   │
├─────────────────────────────────────────────────────────────────────────────────────────────────────────────────┤
│ 22 ┤                                                                        ╭─╮ ╭╮                              │
│ 21 ┤                                                         ╭──╮  ╭───╮ ╭──╯ ╰─╯╰─╮╭─●                         │
│ 20 ┤                                     ╭─╮ ╭──────╮ ╭──────╯  ╰──╯   ╰─╯         ╰╯                           │
│ 19 ┤      ╭──╮      ╭─────╮  ╭────╮ ╭────╯ ╰─╯      ╰─╯                                                         │
│ 18 ┤──────╯  ╰──────╯     ╰──╯    ╰─╯                                                                           │
│    └┬──────────────┬──────────────┬──────────────┬──────────────┬──────────────┬──────────────┬────────────     │
│    07-20          08-04          08-18          09-01          09-15          09-29          10-13              │
│    [1M] [3M] [6M] [YTD] [1Y]   PRICE ▾   ○ line ○ area   ⌖ crosshair: 2026-10-08 22.10                          │
├────────────┬──────────┬──────────┬────────────────┬────────┬─────────────────────────────────────────────────────┤
│ Date       │ Price    │ Chg 1D   │ Market cap     │ Beta   │                                                     │
├────────────┼──────────┼──────────┼────────────────┼────────┤                                                     │
│▶2026-10-19 │  21.84 ▲ │ +3.85%   │ 34,324,492,441 │ 1.46   │                                                     │
│ 2026-10-16 │  21.03 ▼ │ −0.47%   │ 33,051,000,120 │ 1.46   │                                                     │
│ …          │          │          │                │        │                                                     │
└────────────┴──────────┴──────────┴────────────────┴────────┴─────────────────────────────────────────────────────┘
 ↑↓ row (the crosshair follows)  1 3 6 Y T range  P next chart field  Enter open the day  E export  ? keys  Esc view
```

- The **headline numbers** are computed by the server on the headline field (`history.<kind>.headline`, default the first chart field):
  last, change and change % over the range (first to last row), high and low with their dates, range (high − low) and average.
- The **chart** is ECharts, as the field dialog in `history.js` already uses, coloured from the theme's tokens (`--d-ink`, `--d-muted`,
  `--d-link`, `--d-border`) read at draw time, so every theme works. Several chart fields (a curve's `TEN_Y` and `SLOPE_2S_10S`) are one
  axis each, `P` steps between them. The table's selected row and the chart's crosshair move together.
- Range buttons re-ask the server; the page URL follows (`?range=3M`), so `Back` works and a link reproduces it.
- Curves and surfaces: the table shows the scalars (`TEN_Y`, `SLOPE_2S_10S`, `AS_OF`); `POINTS` is a part (`12 ▸`), and the drawer draws
  that day's curve with the previous day's as a faint line.

#### 5.4 Keys and accessibility

| Key | Does | Key | Does |
|---|---|---|---|
| `↑` `↓`, `j` `k` | previous, next row | `Home` `End`, `PgUp` `PgDn` | first, last, page |
| `←` `→` | previous, next column (the header cell is announced) | `Enter` | open the selected day's view |
| `Space` | open the selected cell's part in the drawer | `C` | changes only, on and off |
| `D` | diff mode (section 15.1) | `F` | choose fields (a checklist; the choice is kept per kind in the user's settings) |
| `R` | type a new range in place | `1` `3` `6` `Y` `T` | 1M, 3M, 6M, 1Y, YTD (market shape) |
| `P` | next chart field | `E` | export menu (CSV, JSON, Excel) |
| `?` | the keys | `Esc` | close the drawer, else back to today's view |

The table is a real `<table>` (`.tbl`, `data-plain`) with a roving `tabindex` on rows, `aria-selected` on the selected row and
`aria-sort` on the date column. A changed cell carries `data-changed` and visible text in its title ("changed from 75,000") so colour is
never the only signal. The chart has an `aria-label` with the headline numbers and a "table" fallback (the table below it). Keys are
ignored while focus is in an input, as the command line's `/` is. Reduced motion turns off the chart's animation.

**Themes:** all ten (`terminal`, `light`, `wallstreet`, `blue`, `green`, `crimson`, `crimson-dark`, `terminal-green`, `terminal-crimson`,
`terminal-blue`). The new `web/static/css/history.css` uses only tokens: text in `TEXT_TOKENS` (`--d-ink`, `--d-muted`, `--d-link`,
`--d-warn`, `--d-pos`, `--d-neg`…), fills with `--d-on-fill` text, so `drishti-console/tests/test_contrast.py` (every `color: var(--d-X)`
in `web/static/css/*.css` must be a text token or `on-accent` / `on-fill`; 4.5:1 on every ground) covers it with no new rule.

### 6. The `history:` block in pack.yaml

Kinds are declared in `pack.yaml` as a list (`kinds: [trade, …]`), and per-kind settings live in top-level maps keyed by kind:
`ingest: {trade: …}`, `form: {ir-curve: […]}`, `mnemonics:`. `history:` follows the `form:` pattern exactly: read through
`Pack.manifest()` by a new server component `server/design/HistoryDefinitions` (as `FormDefinitions` reads `form:`), parsed by a new
`engine/history/HistorySpec.parse(kind, raw, problems)` (as `engine/search/form/FormSpec.parse`).

```yaml
history:
  trade:
    columns: [TRADE_STATUS, TRADE_VERSION, QUANTITY, PRICE, MATURITY_DATE, PORTFOLIO, COUNTERPARTY]   # default: top-level scalars
    exclude: [SOURCE_UPDATE_TIME, _meta]          # never shown
    ignore-changes: [SOURCE_UPDATE_TIME]          # shown, but a change here is not "a change" (changes only, DIFF)
    parts: [LEGS, EVENTS]                         # default: every top-level list or object
    default-days: 30
    max-days: 250                                  # at most drishti.history.max-days
  equity:
    shape: market                                  # implied by chart:
    chart: [PRICE]
    headline: PRICE                                # high, low, change; default: the first chart field
    columns: [PRICE, CHANGE_1D, MARKET_CAP, BETA]
    series: {path: HISTORY, date: DATE, value: CLOSE}   # used when no source keeps daily documents
  ir-curve:
    chart: [TEN_Y, SLOPE_2S_10S]
    columns: [TEN_Y, SLOPE_2S_10S, AS_OF]
  news-topic:
    enabled: false                                 # no history for this kind (the command says so, DRS-1012)
```

#### 6.1 Schema

| Key | Type | Default | Rule |
|---|---|---|---|
| `enabled` | boolean | `true` | `false` turns history off for the kind |
| `shape` | `document` \| `market` | `market` when `chart` is set, else `document` | |
| `columns` | list of a path, or `{path \| expr, label?, format?}` | key columns first, then top-level scalars | a path is a top-level field; `expr` is Rachana-EL (as the series' `path` is today, e.g. `first($.LEGS).NOTIONAL`); at most `drishti.history.max-columns` |
| `exclude` | list of paths | `[]` | |
| `ignore-changes` | list of paths | `[]` | |
| `parts` | list of paths | every top-level list or object | each must be a list or object in the sample documents (a warning otherwise) |
| `chart` | list of paths or `{path \| expr, label?, format?}` | `[]` | numeric; at most 4 |
| `headline` | path | first of `chart` | must be in `chart` |
| `series` | `{path, date, value \| values}` | none | a list inside the document with a date per item (the market samples carry `HISTORY: [{DATE, CLOSE}]`) |
| `default-days` | 1..`max-days` | `drishti.history.default-days` (30) | |
| `max-days` | 1..`drishti.history.max-days` | the server's | |

Unknown keys are warnings, bad values errors, with a new code **`DRS-2061` `HISTORY_INVALID`** (422) beside `DRS-2060` `FORM_INVALID`. The
checks run where pack manifests are checked: the deploy stage (`manifestCheck` in `server/deploy/PackDeployService.java`), `sutra lint <pack-dir>`
(`server/cli/SutraCli`) and pack reload (a warning in the log and on Admin → Packs, as `FormDefinitions` logs a bad form). The Build
workbench gets a History tab later (open question 3). `docs/guides/PACK_DEVELOPER_GUIDE.md` gets a step for it.

#### 6.2 Embedded series

The market samples (`config/packs/market-data/samples/equity.jsonl`, `fx-spot.jsonl`) carry their own history inside today's document
(`HISTORY: [{DATE, CLOSE}]`, `[{DATE, MID}]`). When `series:` is declared and the kind's sources hold no daily documents in the range,
the rows come from that list in the newest document (`"basis": "embedded"` in the answer): a chart and a one-column table, no versions.
When daily documents exist they win, because they carry every field. *As built:* when the stored days start inside the window
(a store that keeps a week, asked for 3 months), the days before the oldest stored one are filled from the newest document's series
(`basis: "documents+series"`, those rows `source: "series"`), so the chart and the headline numbers cover the whole range; beside a
series row only the series' own fields count as changes.

#### 6.3 Shipped packs

H13 adds `history:` to `trading` (trade), `market-data` (equity, equity-index, fx-spot, ir-curve, credit-curve, commodity, bond,
inflation-index), `counterparty-risk` and `market-risk` (their headline measures), with `enabled: false` on derived kinds whose pages
are rebuilt per day at a cost (`counterparty-trades`, `portfolio-trades`: open question 2).

### 7. The server endpoint

#### 7.1 Path

```text
GET /api/v1/history/{kind}/{id}?days=30 | range=3M | from=yyyy-mm-dd&to=yyyy-mm-dd
    [&fields=PRICE,TRADE_STATUS] [&diff=true] [&knownAt=<ISO-8601>] [&order=asc|desc]
GET /api/v1/history/{kind}/~?id=<id>&…          an id a path cannot carry ("/", "\", ".."), as entity_path() does today
```

It sits in the existing `HistoryController` (`@RequestMapping("/api/v1/history")`) beside `/series` and `/diff`, not under
`/api/v1/entities` (section 21). The date box's `AsOf` (resolved by `AsOfResolver` from `X-Drishti-As-Of`) is the default end;
`to` overrides it. The access log gets `"/api/v1/history/{kind}/{id}" → "history"` in `AccessRecorder.ACTIONS`.
A mnemonic or a plural in place of the kind is `DRS-4004` saying which kind to use (*TRD is a mnemonic: use the kind trade*, *unknown
kind trades: did you mean trade?*). Kinds are matched exactly, as everywhere in the API: one in another case is `DRS-4004` too
(*unknown kind Trade: kinds are written in lower case, did you mean trade?*), and one that no pack, source or mnemonic knows names the
kinds there are (*unknown kind nokind: the kinds here are …*), unless a source that serves any kind holds that entity. A field in `fields` that is no column but a top-level field of the documents is shown as a column;
one that no day's document has is `DRS-4004` (*no trade has a field NOPE on these days*), never dropped silently.

#### 7.2 What it does

```text
HistoryController.rows(kind, id, params, AsOf, Principal)
  1. entitlements.requireOpen(principal, kind)                       DRS-6xxx as today
  2. spec   = historyDefinitions.of(kind)                             enabled? shape, columns, limits
  3. window = HistoryWindow.resolve(range, AsOf, BusinessDates, spec) dates; DRS-4021 / DRS-4022 on a bad range
  4. key    = (kind, id, window, fields, knownAt, router.dataVersion(kind, LATEST))
     cache hit → step 7
  5. days   = router.history(ref, window + one day before, fields, knownAt, budget)   section 9
  6. rows   = HistoryRows.build(days, spec): latest version per day, baseline compare, changed[], parts counts, summary
  7. redact every row's document for the principal, then project the columns   section 13
  8. answer (section 8); partial when some days failed or timed out (DRS-1014 in problems)
```

The service class is `engine/history/HistoryService` (engine, so the CLI and BI reuse it), with `HistoryWindow`, `HistoryRows` and
`HistoryCache` beside `DocumentDiff`. The controller stays thin. `series` is re-pointed at the same service (one field is a one-column
history), so the field dialog gets the fast paths and the 260-day constant becomes the setting.

#### 7.3 Days, versions and "known at"

- **Days on which the entity has data.** For `days=N` the window ends at the date box and walks back over business days
  (`BusinessDates.calendar().previous`) until N days with data are found or `max-days` business days are passed. A connector that can list
  the entity's days says them directly (section 9: `days(…)`), so no empty day is read. A **snapshot** connector that answers a date with
  an earlier date's document (its `lookback-days`) does not count that date: a row is kept only when `provenance.businessDate()` equals
  the date asked, as `ColumnScans` already skips such days.
- **Effective kinds** (a row only when the entity changes, `mode.<kind>: effective`) have data on every day by definition. Each business
  day is a row; days carried forward from an earlier change are marked `carried: true` and dimmed, so `C` (changes only) shows exactly
  the change days.
- **The baseline.** One more day before the window is read, so the first row's changes are real. It is reported as `baseline`, not as a row.
- **Several versions in a day.** A row is the latest version of its day. `versions` is how many versions of that day the store keeps
  (Delta: commits in the retained log that added files for the date; Iceberg: snapshots that added files to the partition; others:
  `null`, unknown) and `restated` is true when there is more than one. Only connectors that can say it cheaply fill it (section 10).
- **Known at.** Without `knownAt` each day is its latest version (what we know now). With `knownAt=K` the whole history is read as the store
  stood at instant K: each day is the version of that day that was known at K, and days loaded after K are absent. It is Delta's and
  Iceberg's time travel (`timeTravel()`); a connector without it that may hold one of the dates refuses with `DRS-1007` as reads do
  today. One that holds none of them (`holdsNone`, or `NOT_HELD` for every date: the shipped `file` connector before any file of the kind)
  is not asked, so it never refuses for the Delta or Iceberg sources behind it. A `knownAt` that is not an ISO-8601 instant fails with
  `DRS-4003` naming it (*knownAt '2026-10-20' is not an instant*). A window with no data as known at K is `DRS-1013` saying so, and the
  days it has now are named as known now, since they may have been loaded after K (*… has no data in the 5 business days to 2026-10-20 as
  known at 2026-10-20T13:00:00Z: none of its data in that window was known then; as known now its days with data run from 2026-07-27 to
  2026-10-20, which may have been loaded after 2026-10-20T13:00:00Z: ask without knownAt, or with a later one*). "Each day as it was known at
  the end of that day" (as first reported) is a different question, kept as open question 1.

### 8. The JSON contract

This is the contract between the two builders (section 18). Builder B builds the console against a fixture of exactly this shape
(`drishti-testkit/src/main/resources/history/trade-1000015-30.json`, added in H1) and the server's tests assert it.

```json
{
  "ref": {"kind": "trade", "id": "1000015"},
  "mnemonic": "TRD",
  "title": "ComSwap ENGY-SWAP · CP-HARBORPT",
  "shape": "document",
  "basis": "documents",
  "window": {
    "asked": "30",
    "from": "2026-09-08",
    "to": "2026-10-19",
    "days": 30,
    "mode": "data-days",
    "baseline": "2026-09-07",
    "missing": ["2026-09-14"],
    "knownAt": null,
    "loadedTo": null,
    "loadedFrom": null
  },
  "columns": [
    {"path": "TRADE_STATUS", "label": "Trade status", "type": "text", "format": null, "masked": false, "key": true},
    {"path": "QUANTITY", "label": "Quantity", "type": "number", "format": "compact", "masked": false, "key": true},
    {"path": "PRICE", "label": "Price", "type": "number", "format": null, "masked": false, "key": false},
    {"path": "TRADER_ID", "label": "Trader", "type": "text", "format": null, "masked": true, "key": false}
  ],
  "parts": [
    {"path": "LEGS", "label": "Legs"},
    {"path": "EVENTS", "label": "Events"}
  ],
  "chart": [],
  "rows": [
    {
      "date": "2026-09-08",
      "dataDate": "2026-09-08",
      "carried": false,
      "values": {"TRADE_STATUS": "VALIDATED", "QUANTITY": 72000.0, "PRICE": 2332.9131, "TRADER_ID": "•••"},
      "display": {"TRADE_STATUS": "VALIDATED", "QUANTITY": "72K", "PRICE": "2,332.9131", "TRADER_ID": "•••"},
      "parts": {"LEGS": 2, "EVENTS": 4},
      "changed": [],
      "changes": null,
      "versions": 1,
      "restated": false,
      "generation": 41,
      "source": "trade-endur",
      "deleted": false
    },
    {
      "date": "2026-10-15",
      "dataDate": "2026-10-15",
      "carried": false,
      "values": {"TRADE_STATUS": "VALIDATED", "QUANTITY": 75000.0, "PRICE": 2338.4, "TRADER_ID": "•••"},
      "display": {"TRADE_STATUS": "VALIDATED", "QUANTITY": "75K", "PRICE": "2,338.4000", "TRADER_ID": "•••"},
      "parts": {"LEGS": 2, "EVENTS": 5},
      "changed": ["QUANTITY", "PRICE", "parts.EVENTS"],
      "changes": null,
      "versions": 2,
      "restated": true,
      "generation": 58,
      "source": "trade-endur",
      "deleted": false
    }
  ],
  "summary": {
    "field": null,
    "last": null, "first": null, "change": null, "changePct": null,
    "high": null, "highDate": null, "low": null, "lowDate": null, "range": null, "average": null
  },
  "changedDays": 6,
  "partial": false,
  "problems": [],
  "truncated": false,
  "dataVersion": "7@1760700000000",
  "cache": "miss",
  "tookMs": 184
}
```

| Field | Meaning |
|---|---|
| `shape` | `document` or `market` (section 5.1) |
| `basis` | `documents` (daily documents), `embedded` (the kind's `series:` list), `documents+series` (daily documents, and the days before the oldest stored one from the newest document's `series:` list, those rows with `source: "series"`), or `none` (never sent: `DRS-1012` instead) |
| `window.mode` | `data-days` (a bare number), `calendar` (`30D`, `3M`, `YTD`…), `between` (`FROM … TO`) |
| `window.to` | the last day shown: for `data-days` the entity's newest day with data (not the date box), else the end of the span |
| `window.missing` | business days in the window with no data for this entity (every mode; capped at 400), up to the newest date the kind's sources hold |
| `window.loadedFrom` | set only when a `calendar` or `between` span starts before the entity's first day with data: that day (the days before it are not missing: not loaded here, or the entity did not exist yet) |
| `window.loadedTo` | set only when a `calendar` or `between` span ends after the newest date the kind's sources hold: that date (the days after it are not loaded yet, not missing) |
| `columns[].masked` | true when the caller's masks hide the field: its values are the mask on every row |
| `columns[].key` | one of the kind's key columns (shown first, frozen when scrolling) |
| `rows` | oldest first; at most `drishti.history.max-rows`; `order=desc` reverses |
| `rows[].values` | raw values after masks (numbers stay numbers); `display` is the same values formatted for the screen |
| `rows[].dataDate` | the date the data is for; equal to `date` except for `carried` rows of effective kinds |
| `rows[].changed` | paths that differ from the previous row (or the baseline); `parts.<path>` for a part whose count or content changed; fields in `ignore-changes` never appear |
| `rows[].changes` | with `diff=true`: the `HistoryController.Change` list (`path`, `label`, `kind`, `before`, `after`, `delta`), as `/diff` returns |
| `rows[].versions`, `restated` | section 7.3; `null` when the connector cannot say |
| `rows[].deleted` | the entity was deleted that day (a tombstone); its values are empty |
| `summary` | market shape: the headline field's numbers; all null for the document shape |
| `partial`, `problems` | some days could not be read: `[{"code": "DRS-1014", "source": "trade-endur", "days": ["2026-09-21"], "message": "…"}]` |
| `truncated` | the row cap cut the answer (the oldest rows are dropped and the header says so) |
| `dataVersion` | the kind's `dataVersion` at the time of the read (the cache key's last part) |

A market answer differs only in `shape`, `chart` (`[{"path": "PRICE", "label": "Price", "format": "price2"}]`) and a filled `summary`.
Errors use the existing problem shape of `ApiExceptionHandler` (`code`, `message`, `fix`).

### 9. The connector SPI

#### 9.1 New types in drishti-api

```java
/** One entity over business dates. fields empty means the whole document. */
public record HistoryRequest(EntityRef ref, LocalDate from, LocalDate to, Set<String> fields, Instant knownAt,
        int lastDays, Duration budget) {}

/** One business date of one entity: the document (or only the asked fields as a document) and where it came from. */
public record HistoryDay(LocalDate date, EntityDocument document, Integer versions) {}

/** What a source answered: the days it holds in the request, the dates it is authoritative for, the days it failed. */
public record HistoryRead(List<HistoryDay> days, Set<LocalDate> covered, Map<LocalDate, String> failed) {}
```

`lastDays > 0` means "the last N days with data on or before `to`" (then `from` is the earliest date the walk may reach).

#### 9.2 New methods on `SourcePlugin`

```java
/**
 * The entity on each business date of the request that this source holds, newest last. The default reads day by day
 * through fetch(ref, asOf), at most drishti.history.parallel-days at once (HistoryReads.perDay).
 */
default HistoryRead history(HistoryRequest request) throws Exception {
    return HistoryReads.perDay(this, request);
}

/** The business dates on which this source holds the entity, from..to; empty when it cannot list them cheaply. */
default Optional<NavigableSet<LocalDate>> days(EntityRef ref, LocalDate from, LocalDate to) {
    return Optional.empty();
}
```

`HistoryReads` (drishti-api, beside `ColumnScans`) is the default:

1. The dates: `days(ref, from, to)` when present, else the business days of the request (the caller passes them; a plugin has no calendar).
2. Read each date with `fetch(ref, new AsOf(d, knownAt))` on virtual threads under a `Semaphore` of `parallel-days` (8), newest first, so
   `lastDays` stops early once N days with `provenance.businessDate() == d` are found.
3. A date whose read throws goes to `failed`; a date answered by an earlier date (snapshot lookback) is skipped; `covered` is the dates
   for which `coverage(kind, AsOf.of(d))` is `HELD`.
4. `fields` are applied after the read (the default cannot read less than a document).

`ConnectorInstance` (engine, the wrapper of every configured connector) delegates both methods and counts them in `SourceStats`.
`DerivedSourcePlugin` keeps the default. `PeerSource` implements `history` with one call to the peer (section 12).

#### 9.3 The router

`SourceRouter.history(EntityRef, HistoryRequest)` merges the kind's connectors in their read order (`readOrder`, as `fetch` uses): the
first connector answers the dates it holds; the dates it does not cover (`covered`) go to the next, so Redis (today and the last days)
and Delta (years) behind it give one history. Each connector gets the remaining budget. Dates no local connector holds go to
`Fallback.peers` (Drishti\*net). The router never asks a connector for a date an earlier authoritative one covered, as `fetch` does not.

### 10. Fast paths, store by store

What each layout allows, from the code (and the two unmerged branches, read with `git show`).

| Connector | Layout (real) | History fast path | Versions |
|---|---|---|---|
| **delta** (`DeltaSourcePlugin`, `DeltaTable`) | one table per kind, partitioned `business_date=`; `(id, doc)` plus promoted columns (`layout.<kind>.columns`); `Layout.files()` is a `NavigableMap<LocalDate, List<Row>>` of the snapshot; a date's **id map** (`MappedIdMap`, memory-mapped) gives the file; one row group read by id statistics; heap and disk document caches | `days()` = `layout.files().subMap(from, true, to, true).keySet()`: the dates from the snapshot already in memory, no read. Per date: `idMap(kind, l, d).file(id)` (absent = no data, nothing read), then the one row group. When every asked field is promoted, read **only those columns** of that row group (a new `DeltaTable.row(l, d, file, id, columns)`; no `doc` decode, no JSON parse); otherwise the document through the existing caches. Reads go under `docReads` (`max-concurrent-reads`, 16). `knownAt` uses `layout(kind, knownAt)` once for the whole range. Effective tables: the entity's change dates are the dates whose id map holds it. | commits that added files for the date in the retained log (read once per table version, cached); `filesStamp(l, d)` already tells a restated date |
| **drishti-iceberg** (branch `worktree-agent-aea970bf1efcd2886`: `IcebergSourcePlugin`, `KindTable`, `NativeKindTable`, `drishti-iceberg` `scan/Planner`, `scan/Pruning`) | one table per kind; `id`, `doc`, `business_date`, promoted columns (`LEGS.CURRENCY` as `LEGS__CURRENCY`); partitioned `identity(business_date)`, **sorted by id**, files of about 250k rows with disjoint id ranges, 1 MB row groups; manifests keep id bounds | **One planned scan**: `id = X AND business_date BETWEEN from AND to`, pruned by partition summaries and the manifests' id bounds, gives about one file per day with no per-day id map (an id map reads the whole day's id column, about 75 MB per 1M ids). Then each file's row group by its id statistics, projecting `(business_date, id, <promoted fields>)` or `doc`. Files read in parallel (8). `days()` from the planned layout's dates. `knownAt` plans the snapshot `snapshotAt(K)`. | snapshots that added files to the partition, from the snapshot log (`meta/Snapshot`) |
| **iceberg-external** (same branch: `IcebergExternalPlugin`, `ExternalTable`, `KindConfig`) | any layout: id from identifier fields or named columns, date from a column, `day(ts)` or the partition spec; a table with no date is current-only | the same planned scan when the date is a partition or has column bounds; otherwise the per-day **id index** it already builds (`id-index-mb`); a table with no date has no history (`DRS-1012`) | as drishti-iceberg |
| **iceberg** (develop: `IcebergTable`, `IcebergLake`; kept as `engine: library` on the branch) | as drishti-iceberg | the default (it re-reads the Parquet footer on every read) | none |
| **cassandra** (branch `worktree-agent-ad01b2d6c9c414a21`: `CassandraSourcePlugin`, `CassandraLayout`, `DayReads`) | data table `PRIMARY KEY ((business_date, generation, bucket), id)`; **`entity_days`** `PRIMARY KEY ((kind, id), business_date)` DESC, written for every row; `kind_days` per date with its `generation` and `buckets` | `days()` = one single-partition range read: `SELECT business_date FROM entity_days WHERE kind=? AND id=? AND business_date >= ? AND business_date <= ?`, filtered by `kind_days` (an `entity_days` row may outlive its data). Then one token-aware point read per date (generation and bucket computed locally), in parallel. The documents cannot come from one query: `business_date` is in the data table's partition key. No `knownAt` (one generation kept, `DRS-1007`). | 1 (one generation kept) |
| **jdbc** table mode (`PostgresLayout`, `TableCatalog`; `(kind, id, business_date, doc jsonb)`, partitioned by month) | one row per entity per date | **one query**: `SELECT business_date, doc FROM <table> WHERE kind = ? AND id = ? AND business_date BETWEEN ? AND ? ORDER BY business_date` (only the asked fields with `doc -> 'F'` when `fields` is set), partition pruning by month; `days()` the same without `doc` | `null` |
| **jdbc** query mode (`query.<kind>` with `:id` and `:asOf`) | a site's own SQL | an optional `history.<kind>` query with `:id`, `:from`, `:to` (documented in `JDBC_QUERIES.md`); without it the default per-day loop of the existing query | from a `generation` column when the query returns one |
| **duckdb** (`DuckDbLayout`, `<domain>.entities`, promoted columns) | a row per entity per date | one query with `BETWEEN` as table-mode JDBC, promoted columns read directly | `null` |
| **mongodb** (`MongoLayout`, a document per entity per date, `day_ids` index) | `_id` per entity and date | one indexed range read over the `_id` range of the entity between the two dates (the `effective` read already finds "the last `_id` of the entity's range"), with a projection when `fields` is set | `null` |
| **aerospike** (`AerospikeLayout`) | a record per entity per date, **an index record per entity listing its dates** | `days()` = the entity's index record (one get), then one batch get of the dates' keys | `null` |
| **redis** (`RedisLayout`) | a compressed document per entity per date, **each entity's days**, a day's current generation | `days()` from the entity's days, then one pipelined `MGET` for the dates; it holds the recent days only, so the router asks Delta for the rest (section 9.3) | 1 |
| **file** (`FileSourcePlugin`, `JsonlDay`: `<root>/<yyyy-MM-dd>/<kind>.jsonl` indexed with byte offsets) | a dated folder per day | `days()` = dated folders in range whose day index holds the id (indexes are cached by `index-cache-mb`); a positioned read per day | `null` (generation = file time) |
| **s3** (`S3SourcePlugin`), **rest**, **feeds**, **news**, **kafka**, **rabbitmq**, **activemq**, **yfinance**, **demo** | current data or a stream | the default; most are undated (`capabilities().dated()` false), so the kind has no daily history unless the pack declares an embedded `series:` (yfinance's documents carry recent prices) | none |

A connector that cannot do better simply does not override `history`. The contract test (section 19.2) holds every one of them to the
same answers.

### 11. Limits, cache and settings

New `@ConfigurationProperties("drishti.history")` class `engine/history/HistoryProperties`, a `LiveSettings` (changes apply without a
restart, as `SearchProperties`), with its defaults in drishti-server's `application.yaml`:

| Setting | Default | Meaning |
|---|---|---|
| `drishti.history.enabled` | `true` | off: the range is not read and the endpoint answers `DRS-1012` |
| `drishti.history.default-days` | `30` | the range when a link or the CLI names none |
| `drishti.history.max-days` | `366` | the most business days one request may span (a pack's `max-days` can only lower it) |
| `drishti.history.max-rows` | `1000` | the most rows returned (embedded series can be long) |
| `drishti.history.max-columns` | `200` | the most columns |
| `drishti.history.timeout` | `10s` | the whole request; days not read by then are `problems` and the answer is `partial` |
| `drishti.history.parallel-days` | `8` | days read at once per request by the default loop |
| `drishti.history.max-concurrent` | `16` | history requests running at once on this server; the next waits, then `DRS-1014` |
| `drishti.history.cache-mb` | `256` | heap for cached answers, one budget for every key and caller signature; the least recently used go first |

**Cache.** `HistoryCache` keeps unmasked, unformatted rows keyed by `(kind, id, from, to, fields, knownAt, dataVersion)`, where
`dataVersion` is `SourceRouter.dataVersion(kind, AsOf.LATEST)` (Delta: the table's version and commit time; a connector that holds
nothing of the kind counts as `none`) followed, in a cluster, by the data versions of the peers the read would ask, as their digests
carry them (`SourceRouter.peerVersion`, `net:<node>{<connector>=<version>,…}`). A new load, here or on a peer, changes the version, so
the cache never serves stale data and needs no expiry. A connector or peer that gives no version (`Optional.empty()`) is not cached, nor
is an answer that failed, is partial or has no day.
Masks and formats are applied on every answer, so an entry of days this node's own sources read serves every user.

**Days a peer answered are the caller's alone** (after the 2.6.6 soak, SOAK2-01). A peer masks the days it sends for the user it acts
for (section 13), so they are not unmasked rows. When a peer answered any day of a read (`SourceHistory.Routed.fromPeer`), the entry is
kept under the key above followed by `|viewer=<signature>`, the caller's security signature (`Principal.securitySignature()`: the
sorted roles and the embedding app, which decide every node's masks and the nodes the caller reaches; the live relay's topic partition
is the same signature). A caller with another signature misses it and reads the peer again for themselves, so an administrator never
gets a viewer's `•••` and a viewer never gets an administrator's raw values. A read whose days were all this node's own is kept under
the shared key, and a lookup tries the shared key first, then the caller's. A caller without a signature (`Viewer.of(redact, masked)`)
is never cached when a peer answered. Before, the first caller's masked days were served to everyone for the life of the entry: in soak 2,
77 administrator histories of peer-held trades showed `PRICE` as `•••` in a column marked `masked: false`, with the price's changes
missing. `purgeCaches` (Admin → Cache) empties it.

**One budget, in real heap** (after the 2.6.6 soak 3, SOAK3-02). The cache is one `HistoryCache` per server: every key, shared or signed,
counts against the same `cache-mb`, and the least recently used entries are dropped to stay under it (`HistoryCacheBoundTest`: forty
signatures of a hundred peer-held trades each stay under the budget). An answer over a quarter of the budget is never kept. The size of an
entry is the heap its days take on a 64-bit JVM (`HistoryCache.estimate`: 56 bytes a field of an object, its value's box and a string's
characters, the day's records; within a few per cent of the measured heap). Before, it counted 48 bytes a node, 2.3 to 3.2 times under
the heap, so `cache-mb: 64` held 150 to 210 MB. What a steady server looks like: the cache grows while new entities and ranges are asked,
then stays at `cache-mb` (Admin → Cache: `bytes` near the budget, `entries` flat); a new load on any node changes the data version and
the old entries leave as new ones come. The other caches a cluster keeps per caller signature are bounded by count: a view's shape
(`drishti.engine.fingerprint-cache-size`, 100,000 entries of about 300 bytes, about 30 MB at most), the effective layouts
(`layout-cache-size`, 10,000, keyed by shape and not by caller), and the map panels' listings and documents (32 and 8 entries, kept
`drishti.panels.map-cache-seconds`, 60 s).

**Docs and test.** Every setting goes into `docs/admin/CONFIGURATION.md`; `drishti-console/tests/test_docs_settings.py` already fails when
a key of `application.yaml` or a `@ConfigurationProperties` binding is not in that document.

### 12. Drishti\*net and super scale

**Today's cluster (2.5.0).** No new routing is needed for the page:

- `ViewForwardFilter.target()` already matches `/api/v1/history/{kind}/{id}` (area `history`, three segments or more) and forwards the
  whole request to a node that renders the kind (`NetRouting.view(kind, principal, dated, entityId)`), retrying the next node once
  (`DRS-5052`, `DRS-5053`).
- When the pack is here but the data is elsewhere, `SourceRouter.history` sends the dates no local connector holds to `Fallback.peers`
  (`PeerSources`, ordered by `NetRouting.dataPeers`, replicas left out). `PeerSource.history` makes **one** call per peer, not one per day:
  a new `GET /api/v1/net/history/{kind}/~?id=&from=&to=&fields=&knownAt=` on `NetDataController`, beside `/net/fetch`. It carries the
  same headers (`X-Drishti-Net-Hop: 1`, `X-Drishti-Net-On-Behalf-Of`, `X-Drishti-As-Of`, `X-Drishti-Known-At`), checks
  `entitlements.requireOpen`, applies the caller's masks at the peer, and asks no further peer (one hop).
- The answers are merged by date; a date two peers both answer (shards that overlap) takes the higher generation.
- A node that holds the kind but is down, suspect or drained cannot be asked. Dates no node that answered holds are then failures of
  that node (`Fallback.downHolders`): with no day read the answer is `502 DRS-5052` naming it, as the view says; with some days read it
  is partial, a `problems` entry with code `DRS-5052` naming it. Never `DRS-1013`, and never cached.
- **A down node counts only for the dates it may hold** (after the 2.6.5 soak, SOAK-01). Each source of a node's digest carries `earliestDate` and
  `latestDate` for the kind (the oldest and newest business dates its table holds), and the last digest heard before the node went down
  is kept. A date outside every one of its sources' ranges cannot be there, so it is not named (`Fallback.downHolders(kind, date)`,
  `NetRouting.downHolders(kind, date)`). A `range=3M` history of a CALYPSO trade asked while the MUREX holder is down, whose lake starts
  on 2026-10-02, is complete: the 54 days before 10-02 are before any lake, as the window's `loadedFrom` says. A source that keeps no
  dates, or a digest without the two dates (a node of 2.6.5 or older), may hold any date, as before.
- **A peer that refuses the connection** (it just died and gossip has not said so yet, or it is restarting and its port is not open:
  `ConnectException`, a reset, no route) is down as far as the read goes (SOAK-02, SOAK-03): the next holder is asked for the same
  dates, and the dates no one answered are `DRS-5052` naming it, `… cannot be reached now: drishti-c (not answering: the connection
  failed)`, for the dates its digest says it may hold. Never `DRS-1003`.
- A peer that is asked but does not answer in time (frozen, overloaded) is a time-out of those days: `504 DRS-1014` *the node qa1-b did
  not answer the history of trade/10000901 within 10 s* when no day was read, else a `problems` entry with `DRS-1014`. Any other failure
  of the peer (it answered with its own error) is about that node too: `DRS-5052` *the node qa1-b that holds trade failed reading the
  history of trade/10000901: …*, as a view says, never `DRS-1003`, which names a source of the server that answered. A date that one
  peer failed and another answered is not failed.

**Super scale (CLUSTER_DEPLOYMENT.md › Super scale: 500 GB of JSON a day for seven years (design, not built), section 10, not built).** A slice is `(table, business day, group)`; each slice has owners on the ring
(`hash(table, day, group)`, 64 virtual nodes, 2 owners for the `hot-days`). A history of one entity spans one slice per day, so the days
spread over owners. `HistorySlices` (built with that design, not in 2.6.5) groups the window's days by `owners[0]` of each day's slice
(the group comes from the day's range map, section 9.1 there), sends one `/net/history` per owner with its days, merges, and reads any
owner that does not answer within `forward-timeout` from S3 itself (section 10.3 there: anyone can read anything). 30 days on a ring of
10 servers is at most 10 calls in parallel.

### 13. Security

- **Rights per kind.** `entitlements.requireOpen(principal, kind)` first, as `series` and `diff` do. A part opened in the drawer is the
  same entity; a linked entity opened from it goes through its own view and its own check.
- **Masks on every row.** Each day's document goes through `Entitlements.redact(principal, data)` **before** any column is projected,
  any change is computed or any summary is taken, so a masked field reads as the mask on every row, never shows as "changed", and cannot
  be probed by the headline numbers. `columns[].masked` comes from `Entitlements.masked(principal, field)`. Hidden fields (no role may see
  them) are dropped from the columns entirely.
- **The cache holds unmasked rows** of this node's own days and never leaves the server; masks are applied per answer. Days a peer
  answered arrive masked for the caller and are kept under the caller's security signature only (section 11, SOAK2-01).
- **Peers** apply masks themselves (`NetDataController`) and the asking node applies them again, as type-ahead does ("masks twice").
  A field that reads as the mask on any day, whoever masked it, is `masked: true` in `columns[]` and never in `changed` or the diff's
  changes, even where this node's own rules do not mask it (a peer's `redact` stricter than this node's). A field not masked for the
  caller shows every real change.
- **Export** (section 15.2) goes through the same endpoint, so it is masked the same way; the console never masks.
- **Audit.** Each request is one access-log row (`history`), with the range in the detail, as `series` is today.

### 14. Performance targets

| Case | Target | Reasoning |
|---|---|---|
| 30 days of one trade, Delta, 10K trades a day, laptop, cold (first read since start) | **< 1 s** at the browser; < 400 ms at the server | `days()` reads nothing; 30 id maps of 10K ids are small; 30 row groups at about 20 ms cold, 8 at a time ≈ 80 ms; formatting and masks < 20 ms |
| the same, warm (documents in the doc cache) | < 150 ms at the server | Delta's warm single read is 4.3 ms median (DELTA_CONNECTOR.md); 30 / 8 × 4.3 ≈ 20 ms |
| the same, cached answer | < 30 ms at the server | the cache key needs only `dataVersion`, which is cheap by contract |
| 250 days, Delta, promoted fields only | < 1.5 s cold | column reads of one row group per day, no `doc` |
| 30 days, drishti-iceberg | < 400 ms cold | see below |
| 30 days, Cassandra | < 100 ms (estimate) | one `entity_days` read and 30 point reads of about 5 ms in parallel |

**Iceberg.** The Iceberg builder measured one trade at **54 ms** median (native engine, doc cache emptied; 94 ms for a past date not yet
read) against Delta's **4.3 ms** warm (ICEBERG_CONNECTOR.md section 17 on the branch). Thirty single reads would cost 1.6 s one after
another, or about 300–600 ms eight at a time, and each new day also reads its whole id column for the id map. History must not go that
way on Iceberg: the planned scan (section 10) prunes to one file per day by the manifests' id bounds, reads no id map, and reads only the
needed columns, so the per-day cost is a footer (cached by the native engine) and one row group.

The benchmark (section 19.4) measures these at 10K trades × 30 days and records them in CLUSTER_DEPLOYMENT.md's history row.

**Measured (2026-10-20, H14).** `HistoryBench` (`drishti-benchmarks`), 10,000 synthetic trades × 30 business days (2026-08-19 ..
2026-09-30, about 6% amended a day) written with Drishti's writer under `target/`, the native Delta engine, 200 random trades, the 30-day
history at the service (`HistoryService.read`, columns, changes and all), laptop, one process, `-Xmx4g`:

| Case | Median | p95 | Target |
|---|---|---|---|
| cold (each trade's documents read for the first time, the table's id maps loaded) | **13.2 ms** | 19.1 ms | < 400 ms |
| warm (documents in the caches) | **0.83 ms** | 1.6 ms | < 150 ms |
| cached answer | **0.29 ms** | 0.49 ms | < 30 ms |
| default per-day loop (the connector's `days()` hidden), cold | 11.1 ms | 18.5 ms | |
| the Delta fast path again, cold, after the default loop | 8.5 ms | | |

The write took 7.6 s. **The fast path is 1.3× the default loop here, not the 3× of section 19.4**: on Delta the default loop already
costs nothing for a date without the entity (its id map, cached, answers "not here" without a read), and in this table every date holds
every trade, so both read the same 30 row groups. The fast path's gain is in the reads it never starts: dates the entity was not loaded,
and stores where a per-date call is a network round trip. The first fast-path run, before the default loop, measured 13.2 ms against the
default's 11.1 ms (an order effect: the file pages were cold for the first run), so the table reports both. Not measured yet: 250 days
of promoted fields only (that read is not built, section 21), and the other stores (H6 and the rest of H5).

### 15. Extras: DIFF, export, CLI, BI, plain words

#### 15.1 `TRD X 30 DIFF`

`diff=true` adds `rows[].changes`, each row against the one before, with `DocumentDiff` (the "What changed" engine, nested paths
included, `LIMIT` 2000 per row). The screen (`D`) becomes a change log: one block per changed day, each change on its own line
(`LEGS[FIXED].NOTIONAL 10,000,000 → 12,500,000 +2,500,000`), days with no change left out. A day's block links to
`/compare/{kind}/{id}?from=<previous>&to=<date>` for the full compare page.

#### 15.2 Export

| Route (console, `routes/export_routes.py`) | Gives |
|---|---|
| `GET /export/history/{kind}/{id}.csv?days=…&fields=…` | the table as shown: date, then the columns (display values), `changed` as a column; `to_csv` (BOM, CRLF, the formula guard) |
| `GET /export/history/{kind}/{id}.json?…` | the server's answer as it is (section 8) |
| `GET /export/history/{kind}/{id}.xlsx?…` | through `to_xlsx` (one sheet, bold frozen header, numbers stay numbers) |

Names follow `export.filename()`: `1000015-history-2026-09-08-2026-10-19.csv`. The specific routes are declared before
`/export/{kind}/{id}/{panel}.csv`. Row caps are the server's `max-rows`.

#### 15.3 CLI

In `tools/drishti.py`, a new group `history` (argparse, `cmd_history_get(a, extra)`, the shared `server_parent()` options):

```text
drishti.py history trade 1000015 --days 30
drishti.py history equity EQ-CSCA --range 3M --fields PRICE,CHANGE_1D
drishti.py history trade 1000015 --from 2026-09-01 --to 2026-09-30 --diff
drishti.py history trade 1000015 --days 30 --changes-only --json
drishti.py history trade 1000015 --days 30 --csv > trade-1000015.csv
```

Text output is a table, newest first, changed cells marked `*`; `--json` prints the answer (`say_json`); `--csv` writes the CSV of
15.2. Exit codes: 0, 1 (problems), 2 (usage), as the other groups. `tools/test_drishti_cli.py` gets its cases. The Python client
(`clients/python/drishti_client.py`) gets `history(kind, id, days=…)`.

#### 15.4 A BI data source "history"

A dataset could chart a field over time: `source: {history: {kind: trade, ids: [1000015], fields: [PRICE], range: 3M}}`, giving the
table `(business_date, id, PRICE, …)`. It needs a new `DatasetDef.Source` type `HISTORY`, `DatasetDefParser` (its own `DRS-91xx` code),
`bi/mq/DatasetSources.answer()` and `fingerprint()` (the fingerprint is the history cache key), and `core/model_layer.py` in the console.
The BI time machinery (`bi/dataset/Histories.java`, live-table history with a `ts` column) is the precedent. **It is specified here and
built as step H12**, which was left out of 2.6.5 and is not built yet (release not decided); nothing else depends on it.

#### 15.5 Plain words

`PhraseParser` (deterministic, no model) learns one more form before searches: *kind word, id, time phrase*. Time phrases: `last N days`
→ `N`, `last N weeks|months|years` → `NW|NM|NY`, `last month` → `FROM <first of the previous month> TO <its last day>`, `this month` →
`MTD`, `this year`, `ytd` → `YTD`, `since <date>` → `FROM <date>`, `between <date> and <date>`, `from|since <date> to|until <date>`
(two dates are put in order), `past 30 days`.
The id is the word that names one entity of the kind (`Vocabulary.entityId`: its id in any case, or the one id ending `-<word>`, so `audusd`
is `FX-AUDUSD`), even when it is also a field's value (`EQ-CSCA`, the ticker); else a word with a digit or a dash that is no field's value.
Without a kind word, an id that names one entity of one kind (`Vocabulary.kindOfEntity`) followed by a range or a time phrase is that
entity's history (`1000015 30`, `BOOK-RATES-3 last week`); followed by what reads as a range but is none it is `DRS-4022` with what to type
(`1000015 -5`, `1000015 since yesterday`). A kind word and one id with no time phrase open that entity (`fx audusd` is `FX FX-AUDUSD`).
A search reads one business date, so a time phrase in one (`trades in BOOK-RATES-3 last week`) is never dropped silently: it is listed as
not understood, with the step *left out: a search reads one business date (the date box); for one entity over time name it with the time
phrase alone (BOOK-RATES-3 last week)*, and the console does not run the search on its own. `since <date>` in a search stays a condition
on the kind's date (`trades in BOOK-RATES-3 since 2026-01-01`), as before (QA1-207).

```text
trade 1000015 last month                 TRD 1000015 FROM 2026-09-01 TO 2026-09-30
equity EQ-CSCA last 3 months             EQ EQ-CSCA 3M
fx audusd since 2026-10-01               FX FX-AUDUSD FROM 2026-10-01
trade 1000015 since 2026-10-01 until 2026-10-10   TRD 1000015 FROM 2026-10-01 TO 2026-10-10
fx audusd                                FX FX-AUDUSD
1000015 30                               TRD 1000015 30
what changed on trade 1000015 this week  TRD 1000015 FROM 2026-10-19 DIFF
```

The console's `/go` already sends a failed command of two or more words to `/s?words=`; the search page shows *"Read as
`TRD 1000015 FROM 2026-09-01 TO 2026-09-30` — Enter to open"*, with the steps, as phrases are explained today, and opens it directly
when the phrase was read fully (no ignored words).

### 16. Errors

New codes in `drishti-common` `ErrorCode` (none of them used before):

| Code | Name | HTTP | When | Message (example) |
|---|---|---|---|---|
| `DRS-1012` | `HISTORY_NOT_KEPT` | 404 | the kind has no daily history: history off for it (`enabled: false`), or every connector of the kind is undated and no `series:` is declared | "equity keeps no daily history here: market-store holds today's data only" |
| `DRS-1013` | `HISTORY_NO_DATA` | 404 | the entity exists but has no data in the window | "TRD 1000015 has no data from 2026-01-02 to 2026-01-30; its days with data run from 2026-06-20 to 2026-10-19" |
| `DRS-1014` | `HISTORY_TIMEOUT` | 504 (or 200 with `partial`) | a connector, or a Drishti*net peer (an HTTP time-out of its one call), did not answer within the budget: the whole request when no day was read, otherwise a `problems` entry | "trade-endur did not answer 4 of 30 days within 10 s" |
| `DRS-2061` | `HISTORY_INVALID` | 422 | the pack's `history:` block is wrong | "history.equity.headline: PRICE2 is not one of chart" |
| `DRS-4021` | `HISTORY_RANGE_TOO_LARGE` | 400 | more than `max-days` (the kind's or the server's) | "366 business days at most (asked 500): narrow it with FROM … TO" |
| `DRS-4022` | `HISTORY_BAD_RANGE` | 400 | `TO` before `FROM`, a future `FROM`, an unreadable date | "FROM 2026-10-15 is after TO 2026-10-01"; "FROM 2026-10-30 is in the future (the business date is 2026-10-20)" |

A `knownAt` on a store without time travel stays `DRS-1007`; an unknown entity stays `DRS-1001`; a Drishti*net node that holds the kind
and cannot be reached, refuses the connection or fails its read is `DRS-5052` naming it, only for the dates it may hold (section 12); a
peer's time-out is `DRS-1014`. `DRS-1003` names only a source of the server that answered, so the console never sets a healthy server aside
for another node's failure (CLUSTER_DEPLOYMENT.md 11.1). The server logs `DRS-5052`, `DRS-5053` and `DRS-1014` as one short WARN line per change
of what they say about a node, with a count once a minute, never with a stack trace (after the 2.6.5 soak, SOAK-06). Each new code goes into API_GUIDE's
"Error code table" and TROUBLESHOOTING's "Error codes" table in the same push; `drishti-console/tests/test_docs_error_codes.py` fails
otherwise (and checks `DRS-2061` in TROUBLESHOOTING). The console shows each on the history page as the view's `missing.html` does,
with the fix: the largest allowed range as a link, the first day with data as a link.

### 17. The data flow

```text
 Browser                 Console (FastAPI)                 Server (Spring)                                    Connectors
 ┌────────────────┐ GET  ┌──────────────────────┐ POST    ┌───────────────────────────────┐
 │ TRD 1000015 30 │────▶ │ /go                  │───────▶ │ CommandController.resolve     │
 │ <GO>           │      │ (terminal_routes.py) │ command │  CommandMemory.expand (alias) │
 └────────────────┘      │                      │◀─────── │  HistoryRange.trailing        │
                         │ 303 → /v/trade/      │ history │  exists() / pick as today     │
                         │  1000015/history     │         └───────────────────────────────┘
 ┌────────────────┐ GET  │  ?days=30            │
 │ history page   │────▶ │ history route        │  GET    ┌───────────────────────────────┐
 │ (history.html, │      │  backend.history()   │───────▶ │ ViewForwardFilter (net: owner)│──▶ peer node
 │  history-page  │      │  asof headers        │ /api/v1 │ HistoryController.rows        │
 │  .js)          │      └──────────────────────┘ /history│  requireOpen · HistorySpec    │
 │                │◀──── HTML + JSON (rows) ──────────────│  HistoryWindow (BusinessDates)│
 └────────────────┘                                       │  HistoryCache (dataVersion)   │
                                                          │        │ miss                 │
                                                          │  SourceRouter.history ────────┼──▶ ConnectorInstance.history
                                                          │   read order, coverage,       │      ├─ delta: days() + row groups
                                                          │   budget, Fallback.peers ─────┼──▶   ├─ cassandra: entity_days + reads
                                                          │        │                      │      ├─ jdbc: one BETWEEN query
                                                          │  HistoryRows (changed,        │      └─ default: HistoryReads.perDay
                                                          │   versions, summary, diff)    │           (fetch(ref, AsOf) × N, 8 at once)
                                                          │  Entitlements.redact per row  │◀── HistoryRead(days, covered, failed)
                                                          └───────────────────────────────┘
```

The same service answers `drishti.py history` (REST), the export routes, the field dialog (`/series`) and, later, the BI source.

### 18. Build steps H1..H14

Two builders in parallel. **Builder A**: server, SPI, connectors (Java). **Builder B**: command, console, CLI (Java in
`engine/command` and `engine/search/PhraseParser` only, Python, JavaScript, CSS). The contract between them is section 8, fixed in H1
as a fixture file; neither touches the other's files. Each step is one push with its tests and docs; the drill runs once, at the end.

| Step | Builder | What | Done when (its own tests) | Docs in the same push |
|---|---|---|---|---|
| **H1** | A | The contract: `HistoryRequest`, `HistoryDay`, `HistoryRead`, `SourcePlugin.history` and `days` defaults, `HistoryReads.perDay`; `ConnectorInstance` delegation; the fixture `drishti-testkit/src/main/resources/history/trade-1000015-30.json` (and an equity one); `DatedSourceContract` history cases | every plugin that extends `DatedSourceContract` passes the new cases with the default | `docs/connectors/CONNECTOR_DEVELOPER_GUIDE.md` (the SPI) |
| **H2** | A | `engine/history`: `HistoryProperties`, `HistoryWindow` (ranges, business days, baseline), `HistoryRows` (latest per day, `changed`, parts, summary, diff), `HistoryCache`, `HistorySpec` (+ `DRS-2061`), `HistoryService`; `SourceRouter.history` (read order, coverage, budget, partial) | unit tests (section 19.1) | CONFIGURATION (settings), API_GUIDE and TROUBLESHOOTING (codes) |
| **H3** | A | `HistoryController.rows` (`/{kind}/{id}` and `/~`), the new `ErrorCode`s, masks, `AccessRecorder.ACTIONS`; `series` re-pointed at the service (260 becomes `max-days`); `HistoryDefinitions` reading `history:`; deploy and `sutra lint` checks | controller tests with a fake router: masks per row, rights, every error, the JSON equals the fixture's shape | API_GUIDE endpoint, PACK_DEVELOPER_GUIDE `history:` |
| **H4** | A | Drishti\*net: `PeerSource.history`, `NetDataController` `/net/history`, merge by date | in-process cluster test (two nodes, a kind's dates split across them) | OPERATIONS.md routing table |
| **H5** | A | Fast paths: delta (with promoted-column row read and versions), jdbc table and query mode, duckdb, mongodb, aerospike, redis, file | the contract test per plugin with the fast path, plus "reads no document when the fields are promoted" for delta | each connector's document in `docs/connectors/` |
| **H6** | A | Fast paths on the merged branches: drishti-iceberg / iceberg-external (planned scan), cassandra (`entity_days`) | their contract tests; starts only after those branches are on develop | ICEBERG_CONNECTOR, CASSANDRA_CONNECTOR |
| **H7** | B | The command: `HistoryRange`, `CommandController.resolve` (range after alias expansion, before pick), `CommandResponse.history`, `SuggestionService` range suggestions | `HistoryRangeTest` (every row of section 4.2's table), `CommandControllerTest` cases | USER_GUIDE command line |
| **H8** | B | Console: `/v/{kind}/{id}/history` route, `/v/…?days=` redirect, `/go` redirect, `backend.history()`, `terminal/history.html`, `web/static/js/history-page.js`, `web/static/css/history.css`; document shape, keys, changes only, drawer, open the day | `FakeBackend.history` from the fixture; route and template tests; `test_contrast.py` passes | USER_GUIDE history section |
| **H9** | B | Market shape: chart, headline numbers, range buttons, crosshair, embedded series | template tests with the equity fixture | USER_GUIDE |
| **H10** | B | DIFF mode, export routes (csv, json, xlsx), share links and pick lists carrying the range | export tests (names, BOM, formula guard, masked values as served) | USER_GUIDE, API_GUIDE "console's own JSON routes" |
| **H11** | B | `drishti.py history`, the Python client's `history()`, `PhraseParser` time phrases | `test_drishti_cli.py` cases, `PhraseParserTest` cases | CLI_GUIDE, USER_GUIDE plain words |
| **H12** | A | BI source `history` (not built; was left out of 2.6.5, section 15.4) | dataset parser and `DatasetSources` tests | DATASETS.md |
| **H13** | A + B | The shipped packs' `history:` blocks (section 6.3); help card; tutorial 21; CHANGELOG | `sutra lint` of every shipped pack clean | PACKS, tutorial 21, help |
| **H14** | B, then both | Browser test and the benchmark (section 19.3, 19.4); then the release drill, once | both green under the 10K-trade cap | SCALE_STRATEGY history row, RELEASE_NOTES |

Order: H1 first (a day; it fixes the contract). Then A runs H2 → H3 → H4 → H5 (→ H6 when the branches land) and B runs H7 → H8 → H9 →
H10 → H11 at the same time against the fixture. They meet at H13 and H14, when B's console points at A's real endpoint. At most two
builders, one heavy test process each, as the house rule says.

### 19. Tests

#### 19.1 Unit

| Test | Checks |
|---|---|
| `HistoryRangeTest` (engine) | every row of section 4.2: bare number, `30D`, `2W`, `3M`, `1Y`, `YTD`, `MTD`, `QTD`, `FROM … [TO …]`, `DIFF`, case, `<GO>`; never the first word; ids with spaces; operators and `*` stay searches |
| `HistoryWindowTest` | data days vs calendar days over the business calendar (weekends, holidays), the baseline day, `max-days` from pack and server, `DRS-4021`, `DRS-4022` |
| `HistoryRowsTest` | latest version per day, snapshot lookback days skipped, effective days carried and marked, `changed` against the baseline, `ignore-changes`, parts counts, summary (high/low with dates, change %), diff rows equal `DocumentDiff`'s |
| `HistorySpecTest` | the schema of section 6.1, defaults, every `DRS-2061` problem, unknown keys as warnings |
| `HistoryCacheTest` | a new `dataVersion` misses; no version, no caching; size bound |
| `SourceRouterHistoryTest` | two connectors in read order split a range by coverage; peers asked only for the rest; a slow connector gives `partial` with `DRS-1014`; the budget is shared |
| `HistoryControllerTest` (server) | masks on every row (a masked field is never `changed` and not in the summary), `requireOpen`, every error code, the answer's shape equals the fixture's (field by field), `~` ids |
| console `test_history_routes.py` | `/go` redirects, `/v/…?days=` redirect, pick list with `days`, the template renders both shapes from the fixtures, missing data and `DRS-1012` pages |
| `test_history_export.py` | names, BOM and CRLF, the formula guard, the JSON as served |
| `tools/test_drishti_cli.py` | `history` text, `--json`, `--csv`, `--changes-only`, exit codes |

#### 19.2 Contract across plugins

`drishti-testkit` `DatedSourceContract` (extended today by the delta, jdbc (two), redis, aerospike, file, duckdb, iceberg (two),
mongodb tests and the `DayFolderContractTest` example) gets history cases over its fixture `ROWS` (D1, D2, D3; `T-1` restated on D3):

- `historyOfASnapshotKindHasOneRowPerDateItHolds`: `T-1` from D1 to D3 gives 3 days, mtm 100, 110, 125; `T-3` gives D1 only.
- `historySkipsDatesAnsweredByAnEarlierDate`: a snapshot date with no partition is not a row.
- `historyOfAnEffectiveKindListsTheChangeDates`: `CP-X` gives D1 and D3 (`A`, `A-`).
- `historyAsksOnlyTheFields`: `fields={mtm}` gives documents with `mtm` (and nothing a mask would need is lost: the server masks before projecting).
- `historyDaysAgreeWithHistory`: when `days()` is present it lists exactly the dates `history` returns.
- `historyMatchesFetchDayByDay`: for every date, the day's document equals `fetch(ref, AsOf.of(d))`. This one keeps every fast path honest.
- `historyWithKnownAtFollowsTimeTravel`: when `timeTravel()` is true, a `knownAt` before D3's load gives `T-1` without `restated`.

Cassandra's and the new Iceberg plugins' tests extend the class when their branches land.

#### 19.3 Browser

`drishti-console/tests/test_history_browser.py` (Playwright, as the other `*_browser.py`), on the demo stack with the sample packs:
type `TRD 1000015 30` in the command line, Enter; the page shows the dates in the header and 30 rows (or the days the sample holds);
`↓` `↓` moves the selection (`aria-selected`); `C` hides unchanged days and the count in the header changes; `Enter` opens the view of
that day with the pinned banner; `Esc` returns. `EQ EQ-CSCA 3M` draws the chart and the headline numbers; `1` switches to 1M and the URL
follows. Repeated in `light` and `terminal` themes. It waits for the page's own "loaded" marker (`data-history-ready`), never fixed sleeps,
as the 2.5.0 flaky-test work requires.

#### 19.4 Performance

`drishti-benchmarks` `HistoryBench`: **10K trades × 30 business days** in a Delta table under the build's temporary folder (never
`data/`, never more than 10K trades locally), cleaned at the end. It measures, for 200 random trades: cold and warm 30-day history at the
service, the cached answer, 250 days with promoted fields only, and the default loop against the fast path (the fast path must be at
least 3× faster cold). It fails when the warm median passes 150 ms or the cold p95 passes 400 ms. One heavy job at a time.

*As built:* a plain `main` (not JMH), run as
`java -Xmx4g -cp <the module's class path> com.ash.drishti.benchmarks.HistoryBench [trades=10000] [days=30] [sample=200]`; it refuses more
than 10,000 trades, deletes its folder at the end and exits 1 on a missed target. The numbers are in section 14.

### 20. Docs to update when it is built

| Document | What |
|---|---|
| `docs/guides/USER_GUIDE.md` › The command line | the range after the id (section 4), the history page, keys, changes only, DIFF, plain words |
| `docs/guides/API_GUIDE.md` | `GET /api/v1/history/{kind}/{id}` with the JSON of section 8; `/net/history`; the codes in "Error code table"; the console's export routes |
| `docs/guides/CLI_GUIDE.md` | `drishti.py history` |
| `docs/guides/PACK_DEVELOPER_GUIDE.md`, `docs/guides/PACK_DEVELOPER_GUIDE.md` | the `history:` block (section 6), with the shipped packs' blocks as examples |
| `docs/admin/CONFIGURATION.md` | `drishti.history.*` (test_docs_settings.py) |
| `docs/guides/TROUBLESHOOTING.md` | the six codes, each with its fix (test_docs_error_codes.py) |
| `docs/admin/OPERATIONS.md` | `/net/history` in the routing section |
| `docs/connectors/CONNECTOR_DEVELOPER_GUIDE.md` and each connector's document | the SPI methods and each connector's fast path |
| help cards | `drishti-console/config/help.yaml` (a "History" guide under the command line category) and the trading and market-data packs' `config/help.yaml` |
| `CHANGELOG.md`, `RELEASE_NOTES.md` | each step's line; the release note for 2.6.5 |
| `docs/admin/CLUSTER_DEPLOYMENT.md` | the history row with the new measurements |
| this document | "as built" notes, as WRITE_BACK.md and CLUSTER_DEPLOYMENT.md keep them |
| **tutorial 21** `docs/tutorials/21-an-entity-over-time.md` | hands-on and complete: (1) `TRD 1000015 30` in the UI, the same with `drishti.py history trade 1000015 --days 30`, and the `history:` YAML that shapes it; (2) changes only and DIFF (UI, `--changes-only`, `--diff`); (3) open a day (UI; `drishti.py view get trade/1000015 --date …`); (4) `EQ EQ-CSCA 3M`, the chart and the numbers (UI, CLI, YAML `chart:`, `headline:`); (5) add `history:` to your own pack and `sutra lint` it (YAML, CLI, Admin → Packs); (6) export (UI, `--csv`); (7) plain words; (8) limits and errors (`max-days`, `DRS-4021`). Every step shows the UI, the CLI where there is one, and the YAML, with screenshots in `img/` |

### 21. Where the code forced a change from the agreed design

| Agreed | In this design | Why |
|---|---|---|
| `GET /api/v1/entities/{kind}/{id}/history` | `GET /api/v1/history/{kind}/{id}` | `HistoryController` already owns `/api/v1/history/{kind}/{id}/series` and `/diff`; `ViewForwardFilter`, `AccessRecorder` and `NetRouting` already treat the `history` area; `/entities/{kind}/{id}/raw` is the raw document. One area for history keeps forwarding, audit and rights in one place. |
| link `/v/trade/1000015?days=30` | canonical `/v/trade/1000015/history?days=30`; the short form redirects to it | the view route `/v/{kind}/{id_:path}` renders panels; history is its own page (as `/about` and `/panels` are sub-routes); the short link still works |
| the parser takes a third word | the range is taken off **after** the alias is expanded and **after** the whole rest is tried as an id | `CommandParser` reads everything after the mnemonic as the id, and ids may hold spaces; `CommandMemory.expand` appends trailing words |
| `history(kind, id, from, to, fields)` | `history(HistoryRequest)` returning `HistoryRead`, plus `days(ref, from, to)` | the read also needs `knownAt`, "the last N days with data", a budget, and must say which dates it covers so the router can ask the next connector (Redis in front of Delta); `days()` lets Delta, Cassandra, Aerospike, Redis and files list dates without reading |
| a row cap and a timeout | also `parallel-days` and `max-concurrent` | the existing series fires every day's read at once with no bound |
| market kinds from daily documents | also an embedded `series:` | the shipped market samples keep their history inside today's document (`HISTORY: [{DATE, CLOSE}]`) |
| Cassandra: `entity_days` gives the days | it gives the days; the documents still need one point read per day | `business_date` is part of the data table's partition key |
| default columns: top-level scalars | key columns first, empty columns hidden | a trade has about 45 top-level scalars (`config/packs/trading/samples/trade.jsonl`) |
| *As built (H1):* `HistoryRequest(ref, from, to, fields, knownAt, lastDays, budget)` | `HistoryRequest(ref, from, to, dates, fields, knownAt, lastDays, budget, parallelDays)` | a plugin has no calendar, so the business dates travel in the request (`dates`, empty means every calendar day); the default loop's parallelism is the server's `parallel-days`, so it travels too |
| *As built (H5):* Delta: `days()`, a promoted-column row read, and `versions` from the log | `days()` only | the demo came first; with `days()` a history reads only the dates that hold the entity. The column-only read (no `doc` decode) and the per-day version count are still to build, so Delta's `versions` is `null` |
| *As built (H3):* effective kinds: every business day a row, carried days marked | the change days only | a source's history leaves out a date answered by an earlier date's document, so the rows of an effective kind are its change dates; `carried` is always false. Filling the days between is a later step |
| *As built (H14):* the fast path at least 3× the default loop | 1.3× on Delta | the default loop is already cheap on Delta (section 14) |
| *As built (H3):* `series:` only when no source keeps daily documents | also to fill the days before the oldest stored day; `window.loadedFrom` | the demo cluster's market lake keeps 7 days: `EQ EQ-CSCA 3M` drew 7 points and said "no data on 55 days" |
| *As built (H3):* `window.to` is the date box | `data-days` ends at the newest day with data; `loadedTo` added | found in the first integration run: a lake loaded up to Friday, read on Tuesday, said "no data on 2 days" for Monday and Tuesday, which are only not loaded yet |
| *As built (H1):* `window.missing` only for `calendar` and `between` | filled in every mode | the document screen's header lists the days with no data for a bare number too (section 5.2: "no data on 2026-09-14"); the fixture's 30 rows span 31 business days |

### 22. Open questions

All five were decided by the product owner on 2026-10-20, before the build started.

1. **"As first reported."** Should a mode show each day as it was known at the end of that day (bitemporal, for audit), beside "latest"
   and "known at K"? It needs one time-travel layout per day on Delta and Iceberg: affordable for 30 days, not for 366.
   **Decided: later, not in 2.6.5.** 2.6.5 has "latest" and "known at K" only.
2. **Derived kinds** (`counterparty-trades`, `portfolio-trades`): their pages are rebuilt from every member trade per day. History is off
   for them in the shipped packs; turn it on later with a per-day page cache?
   **Decided: off in the shipped packs** (`enabled: false`); a per-day page cache is not planned yet.
3. **The Build workbench:** a History tab to edit `history:` with a preview, now or with 2.7.0's write-back forms?
   **Decided: with write-back's forms (2.7.0 when decided, now 2.8.0).** In 2.6.5 the block is edited as YAML and checked by `sutra lint` and the deploy check.
4. **Ranges over a pick list** (`TRD desk=rates 30`: many trades over time) are BI's job; keep them out of the command for good?
   **Decided: left to BI** (the `history` data source, section 15.4). The command takes a range for one entity only.
5. **Tutorial numbering:** the ask names tutorial 21; 20 is not used on develop today. Keep 21 (20 left for 2.6.0's tutorial)?
   **Decided: 21** (`docs/tutorials/21-an-entity-over-time.md`).

## 3. drishti-iceberg: Drishti's own Apache Iceberg engine

Status: **built in 2.6.0** (steps I1 to I6; `native` is the default engine, the library kept as `engine: library`; the Windows laptop run of step I5 is still to do). Modules `drishti-lakeio`, `drishti-iceberg`, `plugins/drishti-plugin-iceberg` (engines `native|library|auto`) and `plugins/drishti-plugin-iceberg-external`; measured in [ICEBERG_CONNECTOR.md section 17](../connectors/ICEBERG_CONNECTOR.md#17-engines-native-and-library). Related: [ICEBERG_CONNECTOR.md](../connectors/ICEBERG_CONNECTOR.md) (the connector today),
[DELTA_CONNECTOR.md section 16](../connectors/DELTA_CONNECTOR.md#16-engines-native-and-hadoop) (the same move made for
Delta Lake), [GETTING_STARTED.md: Windows](../guides/GETTING_STARTED.md#drishti-on-windows).

### 1. The ask

The product owner (2026-10-20): can Drishti have **its own Iceberg implementation that keeps to the protocol but does
not need Hadoop**? Today the Iceberg connector (`plugins/drishti-plugin-iceberg`) reads and writes through Apache
Iceberg's Java library (`iceberg-core`, `iceberg-parquet`, `iceberg-data`, `iceberg-aws`). Path-based tables go through
Hadoop's file systems, which on Windows need `winutils.exe` and `HADOOP_HOME`. So Iceberg is the one lake format that
does not run on a Windows laptop out of the box, and the loader (`tools/load-iceberg.sh`) is bash only.

Delta Lake had the same problem and solved it with `drishti-deltalake`, the native engine (`java.nio`, the AWS SDK,
parquet-java over its own input files, no Hadoop `FileSystem`, `Shell` or `Configuration`). This design does the same for
Iceberg: a module **`drishti-iceberg`** that implements the parts of the Iceberg table spec and the REST catalog
protocol Drishti uses, with no Hadoop.

### 2. Principles

- **The spec is the contract, not the library.** Tables written by `drishti-iceberg` are ordinary Iceberg tables that
  Spark, Trino, Snowflake, DuckDB and pyiceberg read; tables they write are read by `drishti-iceberg`. What it does not
  support it **refuses by name** (the table, the file, the feature), never reads wrongly.
- **No Hadoop at run time.** No `org.apache.hadoop` class is loaded; a test proves it with a class loader that refuses
  them, as `DeltaNativeNoHadoopTest` does for Delta. (Hadoop's jars may stay on the class path while parquet-java names
  its types in signatures, exactly as for the native Delta engine.)
- **Same connector, same settings.** Packs, connector files, the layout (`IcebergLayout`), the caches and every read path
  in [ICEBERG_CONNECTOR.md section 7](../connectors/ICEBERG_CONNECTOR.md#7-how-the-connector-reads) are unchanged. Only
  what sits under them changes. A user sees a new `engine` setting and nothing else.
- **Share with Delta.** File access (local disk, S3, byte ranges), the Parquet page codecs and the row-group reader
  already exist in `drishti-deltalake`; they move into a shared module and both engines use them.
- **Small and owned.** Only what Drishti needs: Parquet data files, format versions 1 and 2, path-based and REST
  catalogs. Format version 3 is a follow-up ([section 5](#5-what-of-the-protocol-is-covered)).

### 3. Why not keep the library and only drop Hadoop

The smallest change would keep `iceberg-core` and replace only the Hadoop parts: our own `FileIO` over `java.nio`, our
own path-based `TableOperations`. That was considered and is **not** the recommendation:

| | Keep the library, swap its file access | Own engine (`drishti-iceberg`) |
|---|---|---|
| Work | small | about the size of `drishti-deltalake` (about 3,200 lines today), plus a writer |
| Hadoop | `iceberg-parquet` is built on `parquet-hadoop` (Hadoop `Configuration`, codec loading); the writer path and `GenericDeleteFilter` stay tied to it, so a Hadoop-free run is not guaranteed | none loaded, proven by test |
| Dependencies | iceberg-core, iceberg-parquet, iceberg-data, iceberg-aws and what they bring | Avro (manifests), parquet-java (already used), the AWS SDK (already used), Jackson (already used) |
| Parquet versions | Iceberg's choice of Parquet must match Delta Kernel's (today 1.10.1 was picked for that reason) | one Parquet, Drishti's choice |
| Format version 3 | arrives when a library upgrade fits the rest of the server | added when Drishti needs it |
| Footer caching | Iceberg's reader re-reads the footer on every single-entity read (ICEBERG_CONNECTOR.md section 13) | footers cached per file (data files never change) |
| Risk | low | conformance: covered by the interop suite in [section 12](#12-proving-it-keeps-to-the-protocol) |

The library stays available as the `library` engine for what the native engine does not read (Azure, Google Cloud
Storage, HDFS), as `hadoop` does for Delta.

### 4. The picture

```
 IcebergSourcePlugin (unchanged read paths: id maps, single reads, column sets, type-ahead, reverse lookups)
          │
          ▼  IcebergTables  (interface: refresh, plan a snapshot, read a file, commit)
   ┌──────┴───────────────────────────────┐
   │ engine: native                        │ engine: library
   ▼                                       ▼
 drishti-iceberg                         Apache Iceberg Java (today's code, Hadoop file systems)
   ├── catalog/   path-based, static, REST (OAuth2, SigV4, vended credentials)
   ├── meta/      table metadata JSON, schemas, specs, sort orders, snapshots, refs   (Jackson)
   ├── manifest/  manifest lists and manifests                                       (Avro, read and write)
   ├── scan/      planning, partition and metric pruning, delete matching
   ├── read/      Parquet by field id, schema evolution, position and equality deletes
   └── write/     Parquet with field ids and metrics, manifests, commits, maintenance
          │
          ▼
 drishti-lakeio (shared with drishti-deltalake): Storage (local, S3), RangeReader, page codecs, footer cache
```

### 5. What of the protocol is covered

Against the Apache Iceberg table spec (format versions 1 and 2) and the REST catalog OpenAPI spec:

| Area | Covered | Refused or later |
|---|---|---|
| Table metadata | every v1/v2 field; fields it does not know are **kept as they are** when it writes new metadata | |
| Types | boolean, int, long, float, double, decimal, date, time, timestamp, timestamptz, string, uuid, fixed, binary, struct, list, map | v3 types (timestamp_ns, variant, geometry, unknown): refused, later |
| Schema evolution | by field id: add, drop, rename, reorder, int→long, float→double, decimal widening; `schema.name-mapping.default` for files without ids | |
| Partitioning | identity, bucket (the spec's Murmur3 hashing per type), truncate, year, month, day, hour, void; several specs (partition evolution) | |
| Manifests | manifest lists and manifests (Avro), data and delete manifests, status, inherited snapshot ids and sequence numbers, partition summaries, column metrics with the spec's single-value binary bounds | |
| Deletes | position deletes; equality deletes; the sequence-number rules for which delete applies to which data file | v3 deletion vectors (Puffin): later, reusing the Delta engine's roaring bitmap reader |
| Snapshots | current snapshot, snapshot log (time travel by *known at*), the `main` ref | reading a named branch or tag: later |
| Data files | Parquet | ORC, Avro data files: refused, naming the file |
| Encryption | | refused (a `key_metadata` present) |
| Catalogs | path-based (`version-hint.text`), a metadata file named directly, REST | Hive metastore, JDBC catalog (both reachable through a REST catalog in front of them) |
| Views | | not needed |

### 6. Catalogs

**Path-based** (`catalog: hadoop` today; the name stays, it no longer means Hadoop). A table is a folder:
`metadata/version-hint.text` names the current version, `metadata/vN.metadata.json` is it. With no hint the highest
`vN` is found by listing. This is the convention of Iceberg's `HadoopTables`, so tables move between the two engines
unchanged.

**A metadata file named directly** (`metadata-location: …/v12.metadata.json`): read-only, for a table someone else
owns.

**REST** (`catalog: rest`), the protocol of Apache Polaris, Snowflake Open Catalog, AWS Glue's Iceberg endpoint, Unity
Catalog, Nessie, Lakekeeper and Gravitino, over the JDK's HTTP client:

| Call | Use |
|---|---|
| `GET /v1/config` | server defaults and overrides, the `prefix` |
| OAuth2 client credentials (`/v1/oauth/tokens` or `oauth2-server-uri`), or a fixed `token` | the bearer token, refreshed before it expires |
| `GET …/namespaces/{ns}/tables/{t}` | the metadata location, the metadata, and the table's `config` (vended S3 credentials, refreshed when they expire) |
| `POST …/namespaces/{ns}/tables/{t}` | a commit: requirements (`assert-table-uuid`, `assert-ref-snapshot-id`, `assert-current-schema-id`, …) and updates (`add-snapshot`, `set-snapshot-ref`, `add-schema`, `set-properties`, `remove-snapshots`) |
| SigV4 request signing | AWS Glue, with the AWS SDK's signer (no Hadoop) |

Every setting the connector takes today (`uri`, `warehouse`, `credential`, `token`, `scope`, `oauth2-server-uri`,
`prefix`, `catalog.<key>`) keeps its meaning.

### 7. Reading

1. **Refresh** (every `refresh-seconds`): path-based, read `version-hint.text` and, when it changed, the new metadata
   JSON; REST, `loadTable` (with the metadata location's ETag, so an unchanged table costs one small answer).
2. **Plan a snapshot**: read its manifest list; drop manifests whose partition summaries cannot hold the days asked;
   read the rest on virtual threads; drop entries by partition value and column bounds; attach to each data file the
   delete files that apply (position deletes of the same partition with a sequence number at or after the file's;
   equality deletes with a sequence number after it). The result is the same per-day **layout** the connector keeps
   today, with the same per-day fingerprints.
3. **Read a file**: the footer from the footer cache (new: read once per file, since data files never change; this
   removes the larger part of a first single-entity read, ICEBERG_CONNECTOR.md section 13); columns matched **by
   field id**, not by name, so renamed columns read correctly; columns added after the file was written read as null;
   promoted types widened; row groups skipped by their statistics for `id = X`; pages decompressed by the shared codecs
   (Snappy, ZSTD, GZIP, LZ4, LZ4_RAW).
4. **Apply deletes**: position deletes as a sorted set of row positions per file; equality deletes as a set of key
   tuples per file (kept by memory, as today); `cacheStats` keeps counting `daysWithDeletes`.

Time travel by *known at* uses the snapshot log, its schema and its files, exactly as today.

### 8. Writing and commits

Writing serves the loader (`IcebergLoader`) and maintenance (`IcebergMaintenance`); the connector itself only reads.

**Data files.** parquet-java's writer over Drishti's own output files, configured with `PlainParquetConfiguration` (no
Hadoop `Configuration`) and Drishti's own compression codecs (parquet-java's `CompressionCodecFactory` is an interface;
the native engine supplies one). The Parquet schema carries Iceberg **field ids**. Column metrics (value counts, null
counts, lower and upper bounds, truncated as `write.metadata.metrics.*` says) are taken from the finished footer. Every
table property in ICEBERG_CONNECTOR.md section 3 is honoured.

**Operations**, each one commit:

| Operation | Used by |
|---|---|
| append (new data files, a new manifest) | loading a new day |
| replace a partition (old files marked deleted, new ones added) | reloading a day |
| delete whole partitions (metadata only) | `--keep-days` |
| rewrite files, validated against the snapshot read (no file of that day added or deleted since) | `--relayout` |
| rewrite manifests (one per month of business dates) | `--rewrite-manifests` |
| expire snapshots, then delete the files only they referenced | `--expire-hours` |
| add columns, set properties | `--columns`, layout properties |

**How a commit is made safe:**

| Catalog | Commit | Two writers at once |
|---|---|---|
| path-based, local disk (Windows included) | write `vN+1.metadata.json` to a temporary name, then **create** it under its final name with a hard link, which fails if the name exists; then replace `version-hint.text` | the loser sees the name taken, re-reads, re-applies its change on the new version, retries (`commit.retry.num-retries`) |
| path-based, S3 | a **conditional PUT** (`If-None-Match: *`) of `vN+1.metadata.json` | as above; a store that does not honour conditional writes is detected at start and the table is marked **single-writer** (today's rule, ICEBERG_CONNECTOR.md section 4) |
| REST | the catalog's commit with requirements | the catalog answers 409; re-read, re-apply, retry |

A re-applied commit re-validates: an append always applies again; a rewrite of a day fails (and is reported) if that
day changed in between. Metadata files older than `write.metadata.previous-versions-max` are removed after a commit,
as today. Files written by a commit that finally failed are deleted at once (no orphans from Drishti's own writers).

### 9. Files: local disk and S3

From the shared module (today's `drishti-deltalake` classes, moved):

- **Local disk**: `java.nio`, with Windows drive letters, backslashes and UNC shares (`LocalPaths`).
- **S3 and S3-compatible stores** (MinIO, Ceph): the AWS SDK v2, one pooled client per connector, `ListObjectsV2`,
  ranged GETs with the `s3.read-block-kb` read-ahead, TLS and proxy settings as today (`TlsS3FileIO`'s settings carry
  over unchanged).
- **Not read**: `abfs://`, `gs://`, `hdfs://`. A native-engine connector with such a root refuses to start, saying to
  use `engine: library`.

### 10. Engines and configuration

| `engine` | What reads and writes | Use it for |
|---|---|---|
| `native` (the default since 2.6.0) | `drishti-iceberg` | everything on local disk and S3; Windows. Left to the default, a root it cannot read (`abfs://`, `gs://`, `hdfs://`) is read by the library; asked for by name, such a root refuses to start |
| `library` | Apache Iceberg Java over Hadoop (today's code) | Azure, Google Cloud Storage, HDFS; a fallback |
| `auto` | `native` on Windows, `library` elsewhere | kept from 2.6.0's first release, while `native` earned trust |

`DRISHTI_ICEBERG_ENGINE` sets it for every connector, as `DRISHTI_DELTA_ENGINE` does for Delta. The loader and
maintenance take `--engine`. All other settings (ICEBERG_CONNECTOR.md section 15) are unchanged. This table is the `iceberg`
connector's; `iceberg-external` takes `native` or `auto` only and refuses `library` ([ICEBERG_EXTERNAL_CONNECTOR.md section 10](../connectors/ICEBERG_EXTERNAL_CONNECTOR.md#10-settings)).
As built: [ICEBERG_CONNECTOR.md section 17](../connectors/ICEBERG_CONNECTOR.md#17-engines-native-and-library).

### 11. Windows

- The connector runs with no `winutils.exe` and no `HADOOP_HOME`, and the GETTING_STARTED Windows table loses its
  Iceberg row.
- `tools/windows/load-iceberg.ps1` beside `load-delta.ps1`: sample data and `--trades N --days D`, same options as
  `load-iceberg.sh`; the spill folder defaults under the data folder, never the system temp folder.
- `tools/windows/start-server.ps1` takes `-Iceberg` to start with the Iceberg connector files.
- Tutorial: the Iceberg steps of GETTING_STARTED gain PowerShell next to bash.

### 12. Proving it keeps to the protocol

| Test | What it proves |
|---|---|
| **Interop, read**: fixture tables written by **pyiceberg** (pure Python, independent of the Java library) and by the `library` engine: v1 and v2, each transform, partition evolution, each schema change, position and equality deletes, inherited metadata, files without field ids plus a name mapping | `drishti-iceberg` reads what others write; answers equal to pyiceberg's, row for row |
| **Interop, write**: tables written by `drishti-iceberg` read back by pyiceberg, by DuckDB's `iceberg` extension and by the `library` engine | others read what Drishti writes |
| **Metadata round trip**: every fixture's metadata read and written back; unknown fields survive | nothing is lost on rewrite |
| **Engine equality**: every existing plugin test (`IcebergSourcePluginTest`, the dated-source contract, TLS, proxy, REST catalog) runs on both engines; one test compares their answers on the same lakes | same connector behaviour |
| **No Hadoop**: the connector, the loader and maintenance run in a class loader that refuses `org.apache.hadoop` | the goal itself |
| **Concurrent commits**: two writers on local disk, on MinIO, and through a REST catalog (Testcontainers, its own containers only) | no lost commit; a conflicting rewrite reported |
| **Refusals**: ORC file, encrypted table, v3 table, unknown type | refused by name, never read wrongly |
| **Windows paths**: drive letters, backslashes, UNC, on every OS; one real run on a Windows laptop before release | Windows works |
| **Speed**: the Delta engines' benchmark table (section 16 of DELTA_CONNECTOR.md) for Iceberg, both engines | native at least as fast; first single reads faster (footer cache) |

Fixture tables are small, synthetic and committed under the plugin's test resources (no real data).

### 13. Build plan

Five steps, each merged with its tests and docs. (The plan kept `library` the default until step 5; as built, 2.6.0 made `native`
the default.)

| Step | Content | Done when |
|---|---|---|
| I1 | **Shared lake I/O**: move `Storage`, `LocalStorage`, `S3Storage`, `LocalPaths`, `RangeReader`, the page codecs and the row-group reader from `drishti-deltalake` into `drishti-lakeio`; add the footer cache | every Delta test passes unchanged |
| I2 | **Read**: metadata, path-based and direct catalogs, manifests, planning with pruning, field-id Parquet reads, schema evolution, deletes, time travel; the `engine` setting | interop-read suite, engine equality, no-Hadoop test |
| I3 | **REST catalog**: config, OAuth2, loadTable with ETag, vended credentials, SigV4 | the REST catalog test on both engines |
| I4 | **Write**: Parquet with field ids and metrics, manifests, the three commit kinds, the operations of section 8; loader and maintenance on the native engine; `load-iceberg.ps1` | interop-write suite, concurrent-commit tests, maintenance tests on both engines |
| I6 | **The external-table connector** (`iceberg-external`, [section 15](#15-two-connectors-drishti-tables-and-external-tables)): table mapping, rows as documents, discovery from identifier fields and partition specs, every column searchable, id lookup for unsorted tables, the connector form's table browser | a Spark-shaped and a Snowflake-shaped fixture table (pyiceberg-written, merge-on-read deletes, nested types) served as kinds: one entity, search, type-ahead, reverse links, time travel |
| I5 | **Switch and document**: `native` the default, speed table measured, ICEBERG_CONNECTOR.md, GETTING_STARTED (Windows), TOOLS, CONFIGURATION, CHANGELOG, help cards | the Windows laptop run; the release drill |

Size: about the native Delta engine for reading (I2–I3) plus about as much again for writing (I4). One builder.

### 14. Risks and open questions

- **Equality deletes** are the most intricate part of the spec (several key columns, nulls, type promotion of the
  keys). Mitigation: the interop suite writes them with pyiceberg in every shape, and the library engine stays as a
  fallback.
- **S3 stores without conditional writes** would silently allow two path-based writers. Mitigation: a probe at start
  (two creates of a probe key; the second must be refused) and single-writer mode otherwise.
- **Parquet writer without Hadoop**: parquet-java's writer has a Hadoop-free configuration and a pluggable codec
  factory; step I4 starts with a spike that confirms no Hadoop class loads when writing zstd files.
- **Keeping up with the spec**: format version 3 (deletion vectors, row lineage, new types) and new REST endpoints.
  Each is added when a real table needs it; until then they are refused by name.
- **Open for the product owner**: the release (not decided); whether `auto` or `native` is the default in the first
  release that ships it; whether format version 3 reading (deletion vectors) is part of the first release.

### 15. Two connectors: Drishti tables and external tables

The product owner (2026-10-20): Drishti needs **a connector for Iceberg tables Drishti writes and one for Iceberg tables
it does not write**. Both run on `drishti-iceberg` (the `iceberg` connector also on the `library` engine; `iceberg-external` refuses it); they differ in what they expect of a table.

| | `iceberg` (Drishti tables) | `iceberg-external` (anyone's tables) |
|---|---|---|
| Tables | written in Drishti's layout by `IcebergLoader` (ICEBERG_CONNECTOR.md section 3): `id`, `doc`, `business_date`, promoted columns, sorted by id | written by Spark, Flink, Trino, Snowflake, Glue, dbt, pyiceberg, in any layout |
| The document | the `doc` column, exactly as loaded | **the row**, as JSON (every column; structs as objects, lists as arrays, maps as objects), or a JSON column named by `document: column:<name>` |
| The id | the `id` column | one column or several (`id: [SOURCE_SYSTEM, TRADE_ID]`, joined by `id-separator`, `:`); by default the table's **identifier fields** (Iceberg v2's `identifier-field-ids`) |
| The business date | `business_date` | a date column, or a timestamp column with `date-from: day` (in `date-zone`); by default the partition spec's date (`identity` of a date, or `day(ts)`); none: the table is **current only** (every read is the current snapshot) |
| Searchable fields | the promoted columns the pack declares | **every primitive column** (nested ones flattened to `depth`, 2): the table is already columnar, nothing to promote |
| One entity | id map or range map: one row group | pushed-down filter on the id columns with partition, metric and row-group pruning; for a table **not sorted by id**, an **id index** built by Drishti on first use per snapshot and day (id → file and row group, bounded by `id-index-mb`), so later reads are one row group |
| Writes | the loader and maintenance | **never**: the connector only reads; the owners' engines maintain the tables |
| Deletes, time travel | as section 7 | the same: merge-on-read position and equality deletes from Spark and Flink are applied; *known at* uses the snapshot log |

The external connector's reference, which this section once held in full (configuration, rows as documents, reading at
scale, settings, tests), is one document: [ICEBERG_EXTERNAL_CONNECTOR.md](../connectors/ICEBERG_EXTERNAL_CONNECTOR.md). What
was 15.1 *Configuration* is its [section 2](../connectors/ICEBERG_EXTERNAL_CONNECTOR.md#2-a-connector-file) and
[section 8](../connectors/ICEBERG_EXTERNAL_CONNECTOR.md#8-discovery-tables-auto) (`tables: auto`); 15.2 *Rows as documents* is its
[section 3](../connectors/ICEBERG_EXTERNAL_CONNECTOR.md#3-rows-as-documents) and *Make a pack* its
[section 9](../connectors/ICEBERG_EXTERNAL_CONNECTOR.md#9-make-a-pack); 15.3 *Reading at scale* is its
[sections 5 and 6](../connectors/ICEBERG_EXTERNAL_CONNECTOR.md#6-one-entity-at-scale); 15.4 *Settings* is its
[section 10](../connectors/ICEBERG_EXTERNAL_CONNECTOR.md#10-settings) (generated settings: the plugin's
[README](../../plugins/drishti-plugin-iceberg-external/README.md)); 15.5 *Tests* is its
[section 12](../connectors/ICEBERG_EXTERNAL_CONNECTOR.md#12-how-it-is-tested).

### 16. Vector columns and orphan files (2.9.5)

**`list<double>` columns.** The layout's vector column (`layout.<kind>.vectors`, a P&L vector of 509 values) is an optional
`list<double>` whose elements are optional: field ids are assigned after the promoted columns, the column's then its element's. The
writer (`ParquetDataWriter`) writes a three-level list; a null element is written as an empty element group, so it reads back as null
(never 0). The reader's list converter (`Converters.ListConverter`) gives a `List` with the nulls in place; the connector turns it into a
`double[]` with NaN for null ([ICEBERG_CONNECTOR.md section 18](../connectors/ICEBERG_CONNECTOR.md#18-parity-with-delta-vectors-documents-and-disk-caches-295)).
Adding a vector column to an existing table (`Operations.addColumns`) gives the column the next id and its element the one after, and
records the higher as `last-column-id`. Delta stays the default lake format; this is the Iceberg side of the same column.

**Orphan files.** A load that dies after writing data files but before its commit leaves files no snapshot references, and Iceberg's
expiry only removes the files of the snapshots it expires. `OrphanFiles` (in the plugin, run by `IcebergMaintenance --remove-orphans`)
lists every file under the table's location first, then reads the references: the current metadata file and every one its
`metadata-log` lists, `version-hint.text`, each snapshot's manifest list, every manifest and every data and delete file they name (any
entry status, so a file a kept snapshot may read is never taken) and the statistics files. A file not referenced and older than
`--older-than-hours` (72 at least: longer than the longest load) is deleted; `--dry-run` lists them. Reading the references after the
listing means a commit landing in between only adds references; the age keeps a load in flight safe. The sweep reads the table with
drishti-iceberg whichever engine wrote it, on local disk and in S3 alike (`IcebergOrphanFilesTest`).

## 4. Reactive connectors

Status: **R1 to R4 built and released in 2.8.0 (2026-10-26); R5 and R6 planned, in no release yet.** Designed with the product owner (2026-10-24). What is built, and
where it differs from the design below, is in [section 21](#21-as-built-r1-to-r4); the reference for people who write and run reactive
connectors is [REACTIVE_CONNECTOR.md](../connectors/REACTIVE_CONNECTOR.md) and [tutorial 23](../tutorials/23-computed-panels.md).
Everything named below that section 21 does not list as built is **planned**. Related designs:
[PYTHON_COMPUTE.md](PYTHON_COMPUTE.md) (the workers that run the computations, `requires:` and approval),
[VAR_GUIDE.md Part III](../guides/VAR_GUIDE.md#24-where-the-engine-lives) (the first reactive connector: a generic VaR engine), [WRITE_BACK.md](../guides/WRITE_BACK.md#16-the-design-and-its-reasons) (a saved change
can trigger a computation), [SUTRA_NEXT.md](SUTRA_NEXT.md) (screens that read many sources), [section 1](#1-live-updates) (how results reach
the screen), [CONNECTOR_DEVELOPER_GUIDE.md](../connectors/CONNECTOR_DEVELOPER_GUIDE.md) (the connector SPI as built).

### Contents

1. [The ask, in one paragraph](#1-the-ask-in-one-paragraph)
2. [Decisions this design rests on](#2-decisions-this-design-rests-on)
3. [Four kinds of connector](#3-four-kinds-of-connector)
4. [What a reactive connector is](#4-what-a-reactive-connector-is)
5. [The connector file](#5-the-connector-file)
6. [The request and its lifecycle](#6-the-request-and-its-lifecycle)
7. [Triggers: computing on events](#7-triggers-computing-on-events)
8. [Point-in-time stamping, ordering and idempotence](#8-point-in-time-stamping-ordering-and-idempotence)
9. [Isolation: two users never see each other's answers](#9-isolation-two-users-never-see-each-others-answers)
10. [Coalescing, incremental recompute and back-pressure](#10-coalescing-incremental-recompute-and-back-pressure)
11. [Caching](#11-caching)
12. [One screen, many connectors](#12-one-screen-many-connectors)
13. [Failure](#13-failure)
14. [Several servers (Drishti*net)](#14-several-servers-drishtinet)
15. [Observability and audit](#15-observability-and-audit)
16. [The SPI and the APIs](#16-the-spi-and-the-apis)
17. [Configuration reference](#17-configuration-reference)
18. [Build plan](#18-build-plan)
19. [Tests](#19-tests-1)
20. [Open questions for the product owner](#20-open-questions-for-the-product-owner)
21. [As built: R1 to R4](#21-as-built-r1-to-r4)
22. [How the VaR engine plugs in](#22-how-the-var-engine-plugs-in)

---

### 1. The ask, in one paragraph

Today a connector brings data that already exists somewhere: a lake, a database, a queue, a web service. The product owner wants
connectors that **compute** their answer: a VaR for any group of trades, a what-if price for a trade that is not booked, the effect of
an amendment on a desk's risk. The computation runs **when someone asks**, and **again when something changes** (a saved edit, a
message on Kafka, a schedule). Packs stay exactly as they are (kinds, routes, screens); the connector carries the execution and the
libraries it needs. A screen can combine as many connectors as it likes: stored data, live data and computed data side by side, all
for the same business date, and each panel says on its own if its source failed.

### 2. Decisions this design rests on

| # | Decision (product owner, 2026-10-24) | What it means here |
|---|---|---|
| 1 | **Packs stay as they are.** "Connector can be reactive, on demand, static, whatever it is." | No computation and no library is declared in a pack. A pack names kinds, routes each kind to a connector and draws screens. |
| 2 | **A connector may be static, live, on-demand or reactive.** | One `mode:` per connector file ([section 3](#3-four-kinds-of-connector)). |
| 3 | **A pack combines results from as many connectors as it wishes.** | Per-panel `source:` already exists ([SUTRA_NEXT.md section 6](SUTRA_NEXT.md#6-sources-over-several-packs)); this design adds parameters, one pinned as-of per screen, and per-panel failure ([section 12](#12-one-screen-many-connectors)). |
| 4 | **Reactive connectors compute on request and on events** (a write-back, a Kafka change, a schedule). | `triggers:` ([section 7](#7-triggers-computing-on-events)) with coalescing, incremental recompute, point-in-time stamps, ordering, idempotence and back-pressure. |
| 5 | **Two users' write-backs or requests never mix their responses.** | A correlation id on every request and event, per-user sandboxes, cache keys that include the viewer's rights, masks applied per recipient ([section 9](#9-isolation-two-users-never-see-each-others-answers)). |
| 6 | **The connector declares `requires:`** (Python, Java, plugins); an administrator approves before use. | The block, the locked environments and the approval are in [PYTHON_COMPUTE.md](PYTHON_COMPUTE.md). |
| 7 | **Python runs out of process, Java stays on 21, no FFI, no embedded CPython.** | A reactive connector's code runs in a worker process, never inside the server ([PYTHON_COMPUTE.md](PYTHON_COMPUTE.md)). |

### 3. Four kinds of connector

| Mode | What `fetch` means | Data lives | History (a picked business date) | Pushes changes | Examples today |
|---|---|---|---|---|---|
| **static** | read stored records | in the store, written by a loader | yes, from the store's dated partitions | only when a new load is published (the data version moves) | `delta`, `iceberg`, `jdbc`, `duckdb`, `cassandra`, `file` |
| **live** | read the latest value and subscribe to its changes | in a stream or a cache fed by one | through a lake behind it, if any | yes, every message | `kafka`, `rabbitmq`, `activemq`, `redis` in subscribe mode |
| **on-demand** | call a service; the id is the question | nowhere: the answer is fetched or worked out per call, then cached | only if the service can answer for a date | no | `news`, `yfinance`, `rest` |
| **reactive** (new) | **run a computation** parameterised by the request, over data read from other connectors | the result is computed, stamped and cached; nothing is stored unless the connector says so | yes: the computation is run on the inputs **as of** the picked date | **yes: the result is recomputed and pushed when a declared trigger fires** | planned (2.8.0): `var-engine` ([VAR_GUIDE.md Part III](../guides/VAR_GUIDE.md#24-where-the-engine-lives)) |

- The first three exist today and do not change. Writing `mode:` in their connector files is optional (the plugin's manifest already
  says what it can do: `live`, `search`, `dated`); a planned (2.8.0) `mode` field in the manifest makes it visible in Admin →
  Connectors and in the connector type's README front matter (`capabilities:` gains `compute`).
- An **on-demand** connector is a reactive connector without triggers, without parameters beyond its id, and without workers: it stays
  the light pattern of [the developer guide](../connectors/CONNECTOR_DEVELOPER_GUIDE.md#connectors-that-compute-data-on-demand).
  A reactive connector is for computations that are heavy, parameterised, need their own libraries, or must react to events.

### 4. What a reactive connector is

A reactive connector is **a connector whose answer is a computation**. It has five parts:

| Part | Says | Section |
|---|---|---|
| **kinds** | the kinds it answers (`var-result`, `var-contribution`, `what-if` …), each with its parameters and the shape of its result | [5](#5-the-connector-file) |
| **inputs** (`reads:`) | which kinds of which connectors it reads, always **as the requesting user** | [6](#6-the-request-and-its-lifecycle) |
| **engine** | where the code runs: a Python worker, a Java worker, or (trusted, light code only) in the server's own process | [PYTHON_COMPUTE.md](PYTHON_COMPUTE.md) |
| **requires** | the libraries the engine needs, pinned, approved by an administrator | [PYTHON_COMPUTE.md](PYTHON_COMPUTE.md) |
| **triggers** | the events on which results are recomputed and pushed | [7](#7-triggers-computing-on-events) |

Everything that already consumes connectors works on it without change: a Sutra panel's `source:`, BI datasets, history queries,
entitlements and masks, Drishti\*net routing, Admin → Connectors and Health, the README standard. That is why the execution unit is a
connector and not a new concept.

```
 Sutra panel / BI / API ──▶ engine router (kind → connector, as today)
                                     │
                     ┌───────────────┼──────────────────────────────┐
                     ▼               ▼                              ▼
              static connector   live connector            reactive connector  (var-engine)
              (risk-store,       (trade-kafka)              │ 1. key + cache + single flight
               Delta lake)                                  │ 2. admission (interactive | event | batch)
                     ▲                                      │ 3. reads inputs AS THE USER through the router ──┐
                     └──────────────────────────────────────┼──────────────────────────────────────────────────┘
                                                            │ 4. Arrow batches ──▶ compute worker pool (Python / Java)
                                                            │ 5. partial results ──▶ live channel (progress)
                                                            │ 6. stamped result ──▶ cache ──▶ the requester only
                     triggers: write-back applied, ─────────┘ 7. on an event: affected scopes recomputed, pushed to
                     Kafka message, schedule, new load           each subscriber, re-masked for each
```

### 5. The connector file

A reactive connector is a connector file in `config/connectors/` like any other (the file name is its name). The plugin is the
generic planned (2.8.0) `reactive` plugin; what is specific to the computation is configuration plus the code the `engine:` names.

```yaml
# config/connectors/var-engine.yaml                           (planned, 2.8.0)
plugin: reactive
mode: reactive
enabled: true
description: Generic VaR, ES and what-if over precomputed 509-day P&L vectors
kinds:
  var-result:                          # one answer: VaR, ES and the headline numbers for one scope
    params:
      scope:      { type: scope, required: true }          # a hierarchy node, a saved basket, or a scope expression (VAR_GUIDE.md section 5)
      confidence: { type: decimal, default: 0.99, allowed: [0.95, 0.975, 0.99] }
      horizon:    { type: int, default: 1, allowed: [1, 10] }
      method:     { type: enum, default: historical, options: [historical, age-weighted, filtered] }
      whatif:     { type: sandbox-ref, required: false }   # a private what-if sandbox of the requester (section 9)
    result: { schema: schemas/var-result.v1.json }
  var-contribution: { params: { scope: { type: scope, required: true }, by: { type: enum, options: [child, position, risk-class] } } }
  var-path:         { params: { scope: { type: scope, required: true } } }     # the 509 scenario P&Ls
  var-history:      { params: { scope: { type: scope, required: true }, days: { type: int, default: 250, max: 750 } } }
  what-if:          { params: { sandbox: { type: sandbox-ref, required: true } } }
reads:                                  # the inputs, always read as the requesting user, through the normal routing
  - { kind: pnl-vector }                # served by the static connector risk-store (Delta)
  - { kind: scenario-set }
  - { kind: trade }                     # only for what-if: the trade to amend
  - { kind: pnl-hypothetical }          # backtesting
engine:
  type: python                          # python | java | in-process (trusted Java only)
  entry: drishti_var.connector:VarEngine
  pool: risk                            # a worker pool of config/compute/compute.yaml (PYTHON_COMPUTE.md)
requires:                               # PYTHON_COMPUTE.md section 4; nothing runs until an administrator approves it
  python:
    version: "3.12"
    lock: requires/var-engine.lock      # pinned wheels with sha256
  java: []
triggers:                               # section 7
  - on: write-back
    kinds: [trade]
    affects: "scopes_of(trade.TT_BOOK)"           # the book and every node above it
    recompute: [var-result, var-contribution]
  - on: data-version
    kinds: [pnl-vector]
    affects: all
    recompute: [var-result, var-contribution, var-history]
  - on: schedule
    cron: "0 */15 7-19 * * MON-FRI"
    zone: America/New_York
    affects: "subscribed"                         # only scopes someone is watching
    recompute: [var-result]
coalesce: { quiet: 250ms, max-wait: 2s }          # section 10
cache:   { results: 20000, bytes: 512MB, ttl: 8h, store: disk }  # section 11
limits:  { timeout: 30s, timeout-batch: 10m, max-inflight-per-user: 4, max-scope-positions: 5000000 }
history: { dated: true }                          # a picked business date computes on that date's inputs
```

- **The pack does not change** except for routes: `var-result: var-engine` beside `pnl-vector: risk-store`, as for any kind.
- **`reads:` is checked at load:** each kind must be routed to a connector; a reactive connector may read another reactive connector,
  but a cycle is refused at load with the cycle named.
- **`params:` is checked twice:** at load (types, defaults, allowed values) and per request (a value outside `allowed` is refused in
  words: *confidence 0.9 is not allowed for var-result; allowed: 0.95, 0.975, 0.99*).
- **`result:` names a JSON schema** in the connector's folder of supporting files; every result is validated against it before it is
  cached or sent. A result that does not match is an error of the connector, never shown as data.
- **Off until approved.** A reactive connector whose `requires:` is not approved loads as *waiting for approval*: Admin → Connectors
  says so, Health says `DOWN: requirements not approved`, and panels reading its kinds say *This computation is not approved yet*.

### 6. The request and its lifecycle

#### 6.1 The request

The connector SPI's "the id is the question" is kept, but a reactive kind takes **typed parameters** too. A Sutra panel names them
with a planned (2.8.0) `params:` key on `source:`:

```yaml
panels:
  - use: kpi-tiles@1
    with: { id: headline, rows: $src }
    source:
      kind: var-result
      id: "'DESK:' + $.DESK_ID"                       # the scope, written as an id: readable, linkable, cacheable
      params: { confidence: ctl.confidence, horizon: 1, method: ctl.method }
      as-of: ctl.asof
```

Inside the server every read of a reactive kind becomes one **compute request**:

```json
{
  "requestId": "req-01JB4C2Q…",               // ULID, the correlation id: the answer goes back to this and nothing else
  "connector": "var-engine", "kind": "var-result",
  "id": "DESK:RATES-NY",
  "params": { "confidence": 0.99, "horizon": 1, "method": "historical" },
  "asOf": { "businessDate": "2026-10-23", "knownAt": null, "pin": "pin-7f3c…" },     // section 8
  "viewer": { "user": "asha", "roles": ["risk"], "viewSignature": "vs-91a2…" },      // section 9
  "origin": { "type": "panel", "screen": "desk/var-explorer", "panel": "headline" }, // or trigger, api, bi
  "class": "interactive"                     // interactive | event | batch (section 10)
}
```

#### 6.2 The lifecycle

| Step | What happens | Where |
|---|---|---|
| 1. **Receive** | The router sends the read to the connector the kind is routed to, exactly as today. | server |
| 2. **Authorise** | The viewer's roles may open the kind; the viewer holds the planned (2.8.0) `compute` power for computed kinds; the scope is within the viewer's entitlements ([VAR_GUIDE.md section 9](../guides/VAR_GUIDE.md#9-who-sees-what-entitlements-and-audit)). Refused in words, before any work. | server |
| 3. **Pin the as-of** | The request's as-of is resolved to concrete input versions (the Delta table version of `pnl-vector` for the date, the edit-log watermark), once, and recorded as a **pin** ([section 8](#8-point-in-time-stamping-ordering-and-idempotence)). | server |
| 4. **Key** | The canonical key: connector, kind, id, normalised params, pin, the viewer's **view signature** ([section 9](#9-isolation-two-users-never-see-each-others-answers)), engine version, environment lock hash. | server |
| 5. **Cache and single flight** | A cached result for the key is answered at once. A request for a key already being computed **joins** that computation (one computation, many waiters, each answered under its own request id). | server, then the key's owner node ([section 14](#14-several-servers-drishtinet)) |
| 6. **Admit** | The request takes a slot in its class's queue; a full queue answers *busy, try again* (interactive) or waits (event, batch) ([section 10](#10-coalescing-incremental-recompute-and-back-pressure)). | server |
| 7. **Read the inputs as the user** | Each `reads:` kind is read through the normal router **as the requesting viewer**: kinds, row entitlements and field masks applied before a byte reaches the computation. Bulk inputs travel as Arrow (the existing `scan` SPI). | server |
| 8. **Compute** | The request, the inputs and a scratch folder go to a worker of the connector's pool. The worker never fetches data itself and holds no credentials. | worker ([PYTHON_COMPUTE.md](PYTHON_COMPUTE.md)) |
| 9. **Progress** | A long computation sends partial results (*desks 7 of 12 done*, a first estimate) on the requester's live channel; the panel paints them, marked *computing 58%*. | worker → server → console |
| 10. **Stamp, validate, cache** | The result is validated against the kind's schema, stamped (pin, computedAt, engine and environment), and cached under its key. | server |
| 11. **Answer** | The result goes to the request id that asked, and to every request id that joined it, each masked for its own viewer. | server |
| 12. **Audit** | Who asked what, with which pin, how long it took, which result hash ([section 15](#15-observability-and-audit)). | server |

**Timeout.** An interactive request has a budget (`limits.timeout`, 30 s by default). If the panel's own wait (the view's link budget,
`drishti.graph.link-budget`) passes first, the panel is drawn *computing* and fills itself from the live channel when the result
lands; the computation is not abandoned because a page was drawn.

**Cancel.** A request is cancelled when every waiter has gone (the panel closed, the page left, a new selection replaced it). A
computation already running in a worker is stopped only if it is interactive and nobody else joined it.

### 7. Triggers: computing on events

A reactive connector recomputes **without being asked** when a declared trigger fires, and pushes the new result to the screens that
watch it.

| `on:` | Fires when | The event carries | Typical use |
|---|---|---|---|
| `request` (always on) | a panel, a BI query or an API call reads the kind | the request | every computed kind |
| `write-back` | a change is **applied** in the edit log ([WRITE_BACK.md › The design and its reasons, section 18](../guides/WRITE_BACK.md#18-280-saving-through-connectors-triggers-and-viewers)) | change id, kind, entity, lane, seq, by, the fields changed | an amended trade recomputes the VaR of its book and every node above it |
| `live` | a live connector delivers a message for one of the listed kinds (Kafka, MQ, Redis) | kind, id, the source's offset or sequence | a trade booked in the source system recomputes its book's VaR intraday |
| `data-version` | a static connector's data version moves for a kind and date (a new lake load is published) | kind, business date, old and new version | the overnight vectors land: every cached result of that date is stale, the watched ones recompute |
| `schedule` | a cron expression, in a named zone | the tick time | refresh the watched scopes every 15 minutes in trading hours |

#### 7.1 From an event to the scopes it affects

Each trigger says which results it affects with `affects:`:

- `all`: every cached result of the connector for the event's business date is marked stale; those somebody watches recompute.
- `subscribed`: only results some open screen is watching.
- an **expression** over the event (Rachana-EL, as panels use): `"scopes_of(trade.TT_BOOK)"` returns the keys the event touches. The
  function `scopes_of` is supplied by the connector (for VaR: the book and its ancestors in the hierarchy, plus every saved ad-hoc
  scope that contains the trade; [VAR_GUIDE.md section 8.4](../guides/VAR_GUIDE.md#84-what-if-pricing-and-intraday-recomputes-what-if-planned-intraday-updates-as-built-in-294)).

Only affected results that **somebody is watching** are recomputed at once; the others are only marked stale and recompute on their
next read. A trigger never computes for a screen nobody has open.

#### 7.2 What a trigger may and may not do

- It recomputes **shared** results (a book's VaR as seen by the risk role) and pushes them; it never recomputes a user's private
  what-if sandbox, which changes only when its owner acts.
- It runs under the **connector's service identity** for reading inputs that every subscriber shares, and the result is then
  **re-masked per subscriber** before it is pushed ([section 9.3](#93-pushing-one-recomputed-result-to-many-viewers)). A subscriber
  whose rights produce a different view signature gets a result computed for that signature (one per distinct signature, not one per
  person).
- It is **idempotent**: an event already processed (same change id, same Kafka offset, same schedule tick) is ignored
  ([section 8](#8-point-in-time-stamping-ordering-and-idempotence)).

### 8. Point-in-time stamping, ordering and idempotence

#### 8.1 The stamp on every result

```json
"stamp": {
  "businessDate": "2026-10-23",
  "knownAt": null,                                      // or an instant, for "as known at" reads (section 2)
  "pin": "pin-7f3c…",
  "inputs": {                                           // exactly what the computation read
    "risk-store/pnl-vector":   { "version": "delta:v41", "date": "2026-10-23" },
    "risk-store/scenario-set": { "version": "delta:v12" },
    "edit-log":                { "watermark": { "L17": 3412, "L42": 988 } },   // the last applied change per lane included
    "trade-kafka/trade":       { "offsets": { "p3": 182277 } }
  },
  "trigger": { "type": "write-back", "eventId": "chg-01JB3Z…" },               // absent for a plain request
  "computedAt": "2026-10-23T14:02:13.418Z",
  "engine": { "connector": "var-engine", "entry": "drishti_var.connector:VarEngine", "package": "drishti-var 0.1.0" },
  "environment": "env-sha256:7f3c9a…",                    // the locked environment (PYTHON_COMPUTE.md section 5)
  "resultHash": "sha256:…"
}
```

The stamp is what *How this view was built* shows for a computed panel, and what makes a number **reproducible**: the same pin, the
same environment and the same engine give the same result hash.

#### 8.2 The pin

When a request (or a screen, [section 12.2](#122-one-as-of-for-the-whole-screen)) is resolved, the server records a **pin**: the
concrete version of every input for the business date at that moment. A pin is small (a few hundred bytes), kept for the request's
life and, for results that are cached or audited, with the result. Two panels of one screen that share a pin computed on the same
data, whatever loads arrive while they run.

#### 8.3 Ordering

- Results for one key are **versioned by their watermark**: the input versions, edit-log seqs and offsets they include. A result is
  newer than another if every watermark is at least as high and one is higher.
- **A late result never overwrites a newer one.** If an event-driven recompute that started at watermark `L17:3410` finishes after one
  that started at `L17:3412`, the older is dropped (counted, never shown).
- Events of one **lane** of the edit log are processed in seq order (the lane already orders them,
  [WRITE_BACK.md › The design and its reasons, section 7](../guides/WRITE_BACK.md#7-edit-queues-lanes-the-edit-log-and-the-transaction-handler)); events of one **Kafka
  partition** in offset order. Across lanes and partitions there is no order to keep: a result simply includes a set of
  watermarks.

#### 8.4 Idempotence

Every event has an id (the change id, `connector/partition/offset`, `schedule/<tick>`, `data-version/<kind>/<date>/<version>`). The
connector keeps the ids of processed events per key for `triggers.dedupe-window` (default 24 h). An event seen again (a redelivered
Kafka message, a resent change after a fail-over) is ignored. Recomputing is itself idempotent: the same pin gives the same result.

### 9. Isolation: two users never see each other's answers

The product owner's question (2026-10-24): *if two users write back or ask at the same time, can their responses mix?* No. Five rules
make sure of it.

#### 9.1 The rules

1. **Every request and every event has a correlation id** (`requestId`, the change id). A result is delivered only to the request ids
   that asked for it or joined it, over the requester's own live channel. There is no broadcast of a computed result to "everyone on
   the page".
2. **The cache key includes the viewer's view signature.** A view signature is a hash of what decides what the viewer may see: the
   roles' kinds, row entitlements (for VaR: the hierarchy nodes the viewer may see position by position or only in aggregate) and
   field masks. Two people with the same rights share a cached result (that is the point of the cache); two people with different
   rights never do.
3. **What-ifs are private sandboxes.** A what-if is a sandbox owned by one user (`sandbox:asha:01JB…`); only its owner can read, change
   or compute it; a request naming another user's sandbox is refused as *not found* (it does not even confirm the sandbox exists). A
   sandbox can be shared read-only as a frozen copy, never as a live link to the owner's changes.
4. **Masks are applied per recipient.** A result computed once for a signature is still passed through the recipient's field masks
   when it is sent (belt and braces: a signature mismatch can only hide, never reveal).
5. **No state survives between requests in a worker.** A worker process handles one request at a time and is reset (or forked fresh)
   between requests ([PYTHON_COMPUTE.md section 7](PYTHON_COMPUTE.md#7-the-worker-pool)); a Python global, a cached object or a temp
   file of one request is never seen by the next.

#### 9.2 Two traders, two amendments, the same second

Asha (RATES-NY) amends MX-50000001 at 14:02:11.200; Ben (also RATES-NY) books CLY-30000002 at 14:02:11.350. Both changes apply in the
edit log (different lanes, `L17:3412` and `L42:988`).

| | Asha sees | Ben sees | Everybody watching RATES-NY sees |
|---|---|---|---|
| Their own change's state | *applied* on her row (her change id) | *applied* on his row | the updated records, with a "changed by" mark |
| **Impact of their own change** | *your change: incremental VaR +1.8m on RATES-NY*, computed against the state just before her event | *your change: incremental VaR −0.4m*, against the state just before his | nothing private |
| **Shared VaR** | one number including **both** changes, in event order, stamped `L17:3412, L42:988` | the same number, the same stamp | the same number, the same stamp |

- The **incremental impact** is attributed per event: the connector computes the vector of the scope before and after each event, in
  watermark order, and sends each submitter the difference for **their** event, under their change id. Ben never receives Asha's
  impact, and neither receives the other's private what-ifs.
- The **shared** number is one result for the scope and signature, coalesced over both events ([section 10](#10-coalescing-incremental-recompute-and-back-pressure)),
  stamped with both watermarks, so a reader can tell exactly which changes it includes.

#### 9.3 Pushing one recomputed result to many viewers

```
 trigger (event) ─▶ affected keys ─▶ for each key: group the subscribers by view signature
                                      ├─ signature A (risk, all positions): compute once ─▶ mask per subscriber ─▶ push to A1, A2, A3
                                      └─ signature B (desk head, aggregate only): compute once ─▶ mask ─▶ push to B1
```

Subscribers are the open live channels that read the key (section 1's one subscription per entity, kept per key and signature).

### 10. Coalescing, incremental recompute and back-pressure

#### 10.1 Coalescing bursts

A burst of events for one key (twenty amendments to one book in a second) must not mean twenty computations.

- **Quiet window** (`coalesce.quiet`, default 250 ms): after an event, wait until no new event for the key arrives for this long.
- **Maximum wait** (`coalesce.max-wait`, default 2 s): never wait longer than this after the first event of a burst; compute with what
  has arrived. A steady stream therefore still yields a result every two seconds.
- **At most one computation per key at a time**; events that arrive during it cause **exactly one more** afterwards (the same rule
  section 1 uses for view rebuilds, [section 1, guarantees](#guarantees-and-limits)).
- Per-event impacts ([section 9.2](#92-two-traders-two-amendments-the-same-second)) are still computed per event, because they are
  cheap (a difference of vectors), even when the shared result is coalesced.

#### 10.2 Incremental recompute

A connector may declare that a kind is **incremental**: given the previous result and the events since, it can produce the new result
without starting again. For VaR this is natural: the P&L vector of a scope is a sum, so an amended trade changes the scope's vector by
(new vector − old vector), and only the scopes above the trade change ([VAR_GUIDE.md section 8.4](../guides/VAR_GUIDE.md#84-what-if-pricing-and-intraday-recomputes-what-if-planned-intraday-updates-as-built-in-294)).
The connector's entry point receives `previous` (result plus stamp) and `events`; it may answer *full recompute needed* when the
events are too many or of a kind it cannot apply incrementally. Every Nth incremental result (`incremental.verify-every`, default 50)
is checked against a full recompute in the background; a difference beyond tolerance is a health warning and the full result wins.

#### 10.3 Back-pressure and fairness

| Class | Who | Queue | When full |
|---|---|---|---|
| `interactive` | a person waiting on a panel or an API call | short, highest priority | *busy, try again in a moment*; the panel shows the last good result, marked stale |
| `event` | triggers | bounded per connector (`limits.event-queue`, default 10,000 keys); keys, not events, are queued, so a burst for one key is one entry | the oldest *unwatched* keys are dropped to *stale, recompute on read*; watched keys are never dropped |
| `batch` | schedules, BI runs, precomputation | lowest priority, a fixed share of workers (`pool.batch-share`, default 25%) | waits |

- **Per-user fairness:** at most `limits.max-inflight-per-user` requests of one user run at once; the rest queue behind other users'.
- **Admission by cost:** a request declares (or the connector estimates) its size (for VaR: the number of positions in the scope); one
  over `limits.max-scope-positions` is refused before any read, with the size in the message, or sent as a BI run job
  ([BI_RUNS.md](BI_RUNS.md)) if the caller allows.

### 11. Caching

| Tier | Holds | Key | Invalidated by |
|---|---|---|---|
| **in memory** (per node) | recent results | the key of [section 6.2](#62-the-lifecycle) step 4 | a trigger that affects the key; size and age limits |
| **on disk** (`store: disk`) | expensive results, under `<data dir>/compute/results/<connector>/` | the same | the same; a restart keeps it |
| **inputs** | Arrow batches of inputs read for a pin (the P&L vectors of a desk) | connector, kind, scope, pin, signature | a new data version of the input |

- **The pin is in the key**, so a cached result is never served for another state of the inputs; a new load or an applied edit yields
  a new pin and therefore a new key. Old keys age out.
- **The engine version and the environment hash are in the key**: upgrading the VaR package or a pinned wheel never serves a result
  computed by the previous code.
- **Single flight** (one computation per key, others join) holds within a node and, through the key's owner
  ([section 14](#14-several-servers-drishtinet)), across a cluster.
- **Never cached:** a result that failed validation, a partial result, a private sandbox's result beyond the owner's session
  (sandbox results live with the sandbox, not in the shared cache).
- `purgeCaches()` (Admin → Caches, as for every connector) drops all three tiers.

### 12. One screen, many connectors

A pack already combines connectors: each kind is routed to one connector, and each panel can name its own `source:`. This design
makes combining **computed** results first-class.

#### 12.1 An example: the desk VaR screen

```yaml
# config/packs/market-risk/sutras/pages/desk-var.v1.sutra.yaml       (planned, 2.8.0)
match: { kind: desk }
title: "VaR · ${$.DESK_ID}"
consistency: snapshot                       # one pin for the whole screen (12.2)
controls:
  - { id: confidence, type: enum, options: [0.99, 0.975], default: 0.99 }
panels:
  - id: headline                            # reactive: var-engine
    use: kpi-tiles@1
    source: { kind: var-result, id: "'DESK:' + $.DESK_ID", params: { confidence: ctl.confidence } }
  - id: books                               # reactive: var-engine, contributions by child
    use: ranked-bars@1
    source: { kind: var-contribution, id: "'DESK:' + $.DESK_ID", params: { by: child } }
    emits: [ { name: select, on: bar-click, payload: { scope: "@.SCOPE_ID" } } ]
  - id: path                                # reactive: the 509 scenario P&Ls of the picked book
    use: series-bars@1
    source: { kind: var-path, id: "@books.select.scope" }
  - id: limits                              # static: core pack, a Delta table
    use: field-list@1
    source: { kind: desk, id: "$.DESK_ID" }
  - id: blotter                             # live: trades ticking from Kafka
    use: row-picker@1
    source: { kind: portfolio-trades, id: "$.DESK_ID" }
    refresh: 10
```

Five panels, three kinds of connector, one screen. Nothing in the pack names a library or a worker.

#### 12.2 One as-of for the whole screen

- **`consistency: snapshot`** (planned, 2.8.0; the default for screens with a computed panel): when the screen is opened the server
  resolves **one pin** for the business date and every panel's source is read and computed against it. The strip says *as of
  2026-10-23 · pinned 14:02:13*. When newer data arrives (a trigger fires for a key the screen shows), panels are **not** silently
  mixed: the screen shows *Newer data: 3 changes since 14:02 · Update all*, and **Update all** re-pins and refreshes every panel
  together. A screen that is live ([SUTRA_NEXT.md section 9](SUTRA_NEXT.md#9-live-panels-refresh-control-and-freshness)) may set
  `consistency: snapshot, follow: true`: the screen re-pins by itself, at most once per `coalesce.max-wait`, and all panels move together.
- **`consistency: freshest`**: each panel shows its newest result as it lands; each panel's header says its own as-of. For monitoring
  screens where one late panel must not hold the others back.
- The view model carries each panel's pin; a panel computed on a different pin from the screen's (it could not be computed on the
  screen's pin in time) is marked *computed on newer data* in amber, never presented as consistent.

#### 12.3 Cross-connector joins

Two ways, and the design keeps both:

1. **On the screen**, through events: a click on a book in a reactive panel opens static and live panels for the same book (as the
   book desk does today). No join engine is needed; each panel reads its own source.
2. **In the reactive connector**, through `reads:`: when the answer itself needs data from several connectors (VaR over vectors from
   the lake and intraday vectors computed from Kafka trades), the connector reads them all as the user, on one pin, and returns one
   result. The join is the connector's code and is tested with it.

#### 12.4 Partial failure, per panel

Each panel has its own state, drawn in place, never as an error page:

| State | What the panel shows |
|---|---|
| `computing` | the last good result, dimmed, with *computing 58%* and the partial result if the connector sends one |
| `ready` | the result; the header's freshness from the result's stamp |
| `stale` | the last good result in amber: *stale · var-engine is recomputing after 3 changes* |
| `failed` | the connector named in words: *var-engine could not compute this: the worker ran out of memory (request req-01JB…)*; with the last good result if there is one |
| `refused` | why, in words: *not approved yet*, *your role cannot see positions of RATES-LDN*, *the scope has 6.2 million positions; the limit is 5 million* |
| `hidden` | *Hidden for you* (masks), as today |

The other panels are unaffected. *How this view was built* lists each panel's source, pin, state and request id.

### 13. Failure

| What fails | What happens | What the user sees |
|---|---|---|
| a worker crashes or is killed (memory, time) | the request fails; the worker is replaced; the same request is retried **once** on a fresh worker if it is interactive and the crash was not a limit | `failed` with the reason and request id; the last good result |
| the same request crashes workers twice | the key is **quarantined** for `limits.quarantine` (default 10 min): further requests are refused at once instead of crashing more workers | *this computation keeps failing; an administrator has been told* |
| an input connector is down | the request fails with that connector named; triggers from it stop | `failed`: *risk-store isn't answering* |
| the worker pool is exhausted | interactive requests queue up to their timeout; event keys are coalesced; batch waits | `computing`, then `failed: busy` |
| requirements are revoked by an administrator | in-flight requests finish; new ones are refused | `refused: not approved` |
| a result fails its schema | an error of the connector, logged with the request; never cached or shown | `failed: the computation returned an invalid result` |

A connector whose error rate passes `limits.circuit` (default 50% over one minute) opens its **circuit**: requests are answered from
the cache (marked stale) or refused, for 30 s, then one probe is let through. Health shows `DEGRADED: circuit open`.

### 14. Several servers (Drishti*net)

- **Who computes a key:** the key's **owner node**, chosen on the Drishti\*net ring by the key's hash among the nodes that serve the
  connector ([CLUSTER_DEPLOYMENT.md](../admin/CLUSTER_DEPLOYMENT.md#1-drishtinet-many-servers-one-drishti)). Any node receiving a request forwards it to the owner (signed, on behalf of the
  user, as views are forwarded today), so single flight and the cache hold across the cluster.
- **Triggers** are received by every node (an applied edit is published on the cluster; a Kafka consumer group gives each partition to
  one node) and routed to the owner of each affected key.
- **Pushes** go from the owner to the nodes holding the subscribers' live channels (the existing live relay).
- **A node leaving:** its keys move to the next owner; in-flight computations there are lost and recomputed on the next read or
  trigger (results are reproducible from their pins, so nothing is wrong, only late). Private sandboxes are kept in the content store
  ([section 5](#5-the-content-store-user-content-every-node-reaches)), so they survive a node.

### 15. Observability and audit

- **Admin → Compute** (planned, 2.8.0): per reactive connector, its pools and workers (busy, idle, memory, requests served, recycled),
  queues per class (depth, oldest wait), latency (p50, p95, p99 per kind), cache hit rate, coalescing ratio (events in, computations
  out), quarantined keys, circuit state, and its approved requirements.
- **Metrics** under `drishti.compute.*` on the server's metrics endpoint, labelled by connector and kind.
- **Tracing:** the request id is carried into the worker and into every log line and metric exemplar; Admin → Compute finds a
  request by id and shows its timeline (received, pinned, admitted, inputs read, computed, answered).
- **Audit:** every computed answer delivered to a person is an audit entry: who, which kind and id, params, pin, signature, result
  hash, duration, and the trigger if any. What-if sandboxes record every change and computation. Entries are signed like all audit
  entries and kept as the site's audit retention says.
- ***How this view was built*** on a computed panel shows the stamp of [section 8.1](#81-the-stamp-on-every-result) in words: *VaR
  computed by var-engine (drishti-var 0.1.0) at 14:02:13 over 18,412 P&L vectors of 2026-10-23 (lake version 41) and 3 changes
  applied since (L17:3412, L42:988)*.

### 16. The SPI and the APIs

#### 16.1 Java SPI (planned, 2.8.0)

The generic `reactive` plugin implements `SourcePlugin` once; what differs per connector is behind a small interface, implemented by
the worker bridge (Python, Java worker) or by trusted in-process Java code:

```java
// drishti-api, com.ash.drishti.api.compute (planned)
public interface ComputeEngine extends AutoCloseable {
    ComputeManifest manifest();                                   // kinds, params, result schemas, incremental kinds
    ComputeResult compute(ComputeRequest request, ComputeInputs inputs, ProgressSink progress) throws ComputeException;
    default Optional<ComputeResult> incremental(ComputeRequest request, ComputeResult previous,
                                                List<ComputeEvent> events, ComputeInputs inputs) { return Optional.empty(); }
    default Set<String> affected(ComputeEvent event, String expression) { return Set.of(); }   // scopes_of(...)
}
```

`ComputeInputs` hands the engine Arrow batches already read as the user; the engine never sees a connector, a credential or the
router.

#### 16.2 HTTP API (planned, 2.8.0)

Panels and BI read reactive kinds through the existing view and source paths; nothing new is needed for them. For programs:

| Method and path | Does |
|---|---|
| `POST /api/v1/compute/{connector}/{kind}` | submit a request (`id`, `params`, `asOf`); answers the result inline when ready within the budget, otherwise `202` with the request id |
| `GET /api/v1/compute/requests/{requestId}` | the state and, when done, the result (only to the user who asked) |
| `GET /api/v1/compute/requests/{requestId}/stream` | progress and the result as server-sent events |
| `DELETE /api/v1/compute/requests/{requestId}` | cancel |
| `GET /api/v1/admin/compute` | Admin → Compute's data (administrator) |

A token needs the planned (2.8.0) `compute` scope. The CLI gains `drishti.py compute run|status|cancel` (planned, 2.8.0).

### 17. Configuration reference

| Key (connector file) | Default | Meaning |
|---|---|---|
| `mode` | from the plugin | `static`, `live`, `on-demand`, `reactive` |
| `kinds.<kind>.params` | — | typed parameters: `type` (`scope`, `decimal`, `int`, `enum`, `date`, `sandbox-ref`, `text`), `default`, `allowed`, `required`, `max` |
| `kinds.<kind>.result.schema` | — | JSON schema each result must match |
| `kinds.<kind>.incremental` | `false` | the engine can update a previous result from events |
| `reads` | — | input kinds, read as the requester through the router |
| `engine.type`, `engine.entry`, `engine.pool` | — | where the code runs ([PYTHON_COMPUTE.md](PYTHON_COMPUTE.md)) |
| `requires` | — | libraries ([PYTHON_COMPUTE.md section 4](PYTHON_COMPUTE.md#4-requires-what-a-connector-needs-to-run)) |
| `triggers[]` | — | `on`, `kinds`, `affects`, `recompute`, `cron`, `zone` |
| `triggers.dedupe-window` | `24h` | how long processed event ids are remembered |
| `coalesce.quiet`, `coalesce.max-wait` | `250ms`, `2s` | burst handling |
| `incremental.verify-every` | `50` | a full recompute checks every Nth incremental result |
| `cache.results`, `cache.bytes`, `cache.ttl`, `cache.store` | `20000`, `512MB`, `8h`, `heap` | result cache |
| `limits.timeout`, `limits.timeout-batch` | `30s`, `10m` | budgets |
| `limits.max-inflight-per-user` | `4` | fairness |
| `limits.event-queue` | `10000` | event keys queued |
| `limits.quarantine`, `limits.circuit` | `10m`, `0.5` | failure handling |
| `history.dated` | `true` | a picked business date computes on that date's inputs |

The worker pools themselves are configured once per site, in the planned (2.8.0) `config/compute/compute.yaml`
([PYTHON_COMPUTE.md section 13](PYTHON_COMPUTE.md#13-configuration)).

### 18. Build plan

| Step | Delivers | Accepted when |
|---|---|---|
| R1 (**built**) | the `reactive` plugin and `ComputeEngine` SPI; typed `params:` on `source:` (lint, view model); the compute request, key, pin and stamp; cache and single flight on one node; an **in-process Java** test engine | a panel reads a computed kind with params; two panels asking the same key cause one computation; a new data version gives a new key |
| R2 (**built**) | the worker bridge to the Python pool ([PYTHON_COMPUTE.md](PYTHON_COMPUTE.md) steps C1–C3); progress on the live channel; per-panel states | a Python engine computes a result for a panel, with *computing N%* shown, and a crash shows `failed` in that panel only |
| R3 (**built**; `write-back` from the edit log since W6/W7, 2026-10-25, and through the events API) | triggers: `write-back`, `live`, `data-version`, `schedule`; `affects:`; coalescing; ordering by watermark; dedupe | 200 events for one key in a second cause at most 2 computations; an older result never replaces a newer; a redelivered event is ignored |
| R4 (**built**, except per-event impacts: W6/V5) | isolation: view signatures in keys, per-recipient masks on push, per-event impacts, private sandboxes | the two-traders test of [section 19](#19-tests-1) passes |
| R5 (planned) | `consistency: snapshot` and `freshest`; *Newer data · Update all* | a screen of five panels on three connectors shows one pin; an applied edit offers *Update all* and re-pins all panels together |
| R6 (planned) | Drishti\*net: key owners, forwarding, cluster single flight, trigger routing; Admin → Compute; audit; circuit and quarantine | three nodes, one key requested on each at once: one computation; a node killed mid-computation: the next read recomputes on the new owner |

### 19. Tests

- **Isolation (the product owner's question):** two users with different entitlements request the same scope at the same instant; each
  receives only the result for their own signature, under their own request id; a third user with the first user's rights shares the
  first's cached result. Two users submit write-backs to one book within 100 ms; each receives only their own incremental impact;
  both see one shared result stamped with both watermarks. A request naming another user's sandbox is *not found*.
- **Ordering:** random event streams over many keys and lanes; results for a key are never replaced by one with a lower watermark.
- **Coalescing:** a burst of N events yields at most ⌈burst length / max-wait⌉ + 1 computations per key.
- **Idempotence:** a redelivered Kafka message and a resent change id do not recompute.
- **Incremental equals full:** for VaR, a random sequence of amendments applied incrementally equals a full recompute to 1e-9.
- **Failure:** a crashing engine is retried once and quarantined after the second crash; the circuit opens and closes.
- **Masks:** a field masked for a subscriber never appears in a pushed result.
- **Cluster:** the R6 acceptance tests, plus a network split (results stay correct; late, never mixed).
- **Windows:** everything except the native QuantLib-Java worker runs on the Windows QA machine
  ([PYTHON_COMPUTE.md section 12](PYTHON_COMPUTE.md#12-windows)).

### 20. Open questions for the product owner

1. **Default consistency.** Should every screen with a computed panel default to `consistency: snapshot` (one pin, *Update all*), or
   should `freshest` be the default and snapshot opt-in?
2. **Triggers under the service identity.** Is it acceptable that shared recomputes read inputs as the connector's service identity
   and then compute once per distinct view signature, or must every recompute run as a named user?
3. **Kafka-triggered intraday VaR in 2.8.0?** The `write-back` and `data-version` triggers are needed for 2.8.0; is the `live`
   (Kafka) trigger in 2.8.0 too, or 2.8.x?
4. **Where private sandboxes live.** The content store (survives nodes, per user) is proposed; is a time limit wanted (for example
   sandboxes expire after 30 days unless saved)?
5. **Limits.** Are 5 million positions per interactive request and 4 in-flight requests per user the right starting limits?
6. **In-process Java engines.** Allow trusted, light Java engines inside the server process (with their own classloader), or require
   every engine to run in a worker?

### 21. As built: R1 to R4

Built for 2.8.0 (unreleased), on the compute workers C1 to C3 ([PYTHON_COMPUTE.md](PYTHON_COMPUTE.md)). The reference is
[REACTIVE_CONNECTOR.md](../connectors/REACTIVE_CONNECTOR.md); the sample is the `compute-demo` pack (not loaded by default).

| Part | As built | Where |
|---|---|---|
| SPI | `ComputeEngine`, `EngineManifest`, `EngineRequest`, `EngineInputs` (documents by input name), `EngineResult` (rows, tables, meta), `EngineProgress`, `EngineException`, `ComputeEvent`, `Viewer`, `ComputeRef` (the id with its parameters). The names differ from section 16.1 (`Engine…` rather than `Compute…`) so they do not clash with drishti-compute's worker types | `drishti-api`, `com.ash.drishti.api.compute` |
| Plugin | `reactive` (built-in, `ReactiveSourcePlugin`), `ReactiveConnector` (the lifecycle), `ReactiveSpec`, `ParamSpec`, `ResultCache`, `Coalescer`, `SeenEvents`, `Watermark`, `Triggers`, `Sandboxes`, `RequestEvents`, `ReactiveHub` (the host the server installs) | `drishti-compute`, `com.ash.drishti.compute.reactive` |
| Connector file | the design's keys at the top level (`mode`, `kinds` with `params`, `reads`, `engine`, `triggers`, `coalesce`, `cache`, `limits`, `history`, `incremental`); refused on any other plugin. Added: `kinds.<k>.compute` (the engine's name for the kind), `engine.split`/`chunk-rows`/`reduce` (fan-out), `engine.version`, `limits.wait`, `limits.max-input-rows`, `limits.dedupe-window` (the design's `triggers.dedupe-window`), `reads[].ids` and `{inputs.<as>.<FIELD>}` (a read takes its ids from an earlier one) | `ConnectorFiles` |
| Templates | single braces: `{id}`, `{params.x}`, `{inputs.book.MEMBERS}`, `{payload.BOOK_ID}`, `{entity}`; `${NAME}` stays an environment variable | `Templates` |
| Result rules | `result: { required, types, max-rows }` instead of a JSON schema file (`result.schema`: planned) | `Results` |
| Parameters on a panel | `source: { kind, id, params: { name: expression } }`, only with `id`; lint `DRS-2012` (shape) and `DRS-2057` (not with `where`/`search`); evaluated as the viewer (a masked value: *not available to your role*) into the id `ID~name=value` | `WiringReader`, `ScreenWire` |
| Panel states | `ready`, `computing` (progress), `stale`, `failed`, `refused` in the view model (`PanelView.compute`) and drawn in place; the console follows `compute:<request>` on the live channel and fetches the panel again when it is done or updated | `ViewModel`, `panels.html`, `compute.js` |
| Lifecycle | authorise (the viewer's kinds), parameters, pin (the read kinds' data versions and the sandbox version), key (with the view signature: a hash of the roles and the embedding app, `Principal.securitySignature`), cache, single flight, admission (per-user in-flight; the pool's fair queue), inputs read and masked as the viewer, engine, progress, validate, stamp (`STAMP`: pin, inputs, watermark, trigger, engine, where it ran, signature, result hash), cache, audit (`compute.answer`, `compute.failed`) | `ReactiveConnector` |
| Engines | Python in a compute pool (Arrow in and out; fan-out over every worker of the pool and its peers with `split` and `reduce`); Java in the server (Drishti's own always; others with `compute.in-process-java: true`) | `PythonRunner`, `JavaRunner` |
| Triggers | `write-back` and any event through `POST /api/v1/compute/events` (administrators) and `ReactiveHub.publish` (the write-back module's hook: built 2026-10-25, every applied change after its commit, with the watermark `L<lane> -> seq`, replayed per lane from the edit log's cursor; [WRITE_BACK.md › The design and its reasons, section 19](../guides/WRITE_BACK.md#19-as-built-280-2026-10-25)); `live`: the entities of its kinds a watched computation read are followed, a new generation recomputes the results that read them; `data-version`: the load signal and a check every 30 s; `schedule`: `cron` (Spring's six fields) or `every`. `affects`: `all`, `subscribed`, id templates, or an expression a Java engine answers. Coalescing, watermark ordering, dedupe as designed | `Triggers`, `Coalescer` |
| Isolation | request ids per read, events to the owner only, view signature in keys, inputs masked per viewer, a recomputed result read again by each watcher through their own view, private sandboxes (`sbx-…`, frozen copies), live views rebuilt on behalf of their viewer | `RequestEvents`, `ViewerExecutor` |
| HTTP and CLI | `POST /api/v1/compute/{connector}/{kind}`, `GET …/requests/{id}/stream`, `DELETE …/requests/{id}`, `POST …/events`, sandboxes, `GET /api/v1/admin/compute/reactive`; `drishti.py compute` `run`, `cancel`, `event`, `sandboxes`, `reactive` | `ReactiveController`, `tools/computecli.py` |

**Not built yet:** per-event impacts for each submitter (section 9.2, with W6 and V5); the result cache on disk (`cache.store: disk`);
`consistency:` and *Update all* (R5); key owners, forwarding, cluster single flight, quarantine and the circuit (R6); a JSON schema file
per kind; a `compute` power (any role that may open the kind may compute it); the designer's awareness of parameters (a source with
`params:` is kept as written).

**Measured** (a workstation, 2026-10-25; `ReactiveOverheadTest`, `PythonBridgeTest` and the compute-demo screen): a cache hit inside the
connector 4.7 µs at p50 (p99 9.6 µs); a miss with an engine that does nothing 47 µs (p99 183 µs); a miss through a Python worker (fork,
trivial engine) 2.3 ms; through the server, one computed panel from the cache 2.7 ms, the same as a plain panel (2.6 ms); the
compute-demo book screen, two computed sources and two 250-point charts, 5.0 ms. A panel never waits longer than `limits.wait`.

### 22. How the VaR engine plugs in

The VaR engine ([VAR_GUIDE.md Part III](../guides/VAR_GUIDE.md#24-where-the-engine-lives), steps V0 to V3) becomes the `var-engine` reactive connector (R5/V4) without new
machinery:

1. **The file.** `config/connectors/var-engine.yaml` with `plugin: reactive`, the kinds of section 5 (`var-result`, `var-contribution`,
   `var-path`, `var-history`, `what-if`) and their `params:` (`scope` as `type: scope`, `confidence` as `decimal` with `allowed`,
   `horizon` as `int`, `method` as `enum`, `whatif` as `sandbox-ref`), and `result:` rules (`required: [VAR, ES]`).
2. **The engine, either of two ways.**
   - *Java, in the server* (the vector store is Java): a class implementing `ComputeEngine` in `com.ash.drishti…` (so it may run in
     process), `engine: { type: java, class: …VarComputeEngine }`, with **no `reads:`**: the VaR builder's in-memory vector store is its
     input, and it must read it **for `request.viewer()`** (the entitlement filter of VAR_GUIDE.md section 9) and **for
     `request.asOf()`**. `compute` aggregates the scope's vectors and answers `VAR`, `ES` and the path as a table; `incremental`
     applies a write-back's `delta` vector (`ComputeEvent.payload`); `affected(event, "scopes_of(trade.TT_BOOK)")` returns the book and
     its ancestors' scope ids from the hierarchy. The vector store's version for the date goes into the pin through `dataVersion` of
     the kind it serves (`pnl-vector`), so a new load is a new key.
   - *Python, in a pool* (`compute/drishti-var`): `engine: { type: python, entry: drishti_var.connector:VarEngine, pool: risk }` with
     `reads: [{ kind: pnl-vector, as: vectors, ids: "{inputs.scope.MEMBERS}" }]` after a read of the scope's members; vectors arrive as
     fixed-size Arrow vectors; `split: vectors` and `reduce: { tables: { path: [PNL] }, then: { VAR: … } }` spread a bank-wide scope over
     every worker. Its `requires:` locks numpy and the package and waits for approval.
3. **What-if.** A `sandbox-ref` parameter names the trader's private sandbox; its items (the hypothetical trades) reach the engine as the
   input `sandbox`; the key holds the owner, so it is never shared. Pricing those items is C4/C5 (QLRisk) or C6 (QuantLib Java).
4. **Triggers.** `{ on: write-back, kinds: [trade], affects: "scopes_of(trade.TT_BOOK)", recompute: [var-result, var-contribution] }`
   (W6 publishes `edit.applied` to `ReactiveHub.publish`), `{ on: data-version, kinds: [pnl-vector], affects: all }`, and a schedule.
5. **Screens.** The explorer's panels name the kinds with `source: { kind: var-result, id: "'DESK:' + $.DESK_ID", params: { confidence:
   ctl.confidence } }` and get the computing, stale, failed and refused states for free. The pack routes `var-result: var-engine` beside
   `pnl-vector: <its store>`.

## 5. The content store: user content every node reaches

Status: **built (Designs; BI content and BI runs; collaboration and notes; embed apps, pack switches and audit; governance proposals; load history), released in 2.7.0.** Front-loaded from item 0 of write-back, the one Drishti database (2.7.0 item 0 when decided, now 2.8.0). Related:
[section 6](#6-the-one-drishti-database-design-not-built) (the one Drishti database), [CLUSTER_DEPLOYMENT.md](../admin/CLUSTER_DEPLOYMENT.md#1-drishtinet-many-servers-one-drishti) (several servers),
[CONFIGURATION.md](../admin/CONFIGURATION.md) (`config/store/store.yaml`).

### 1. The problem

User content (screen-designer Designs, workspace drafts, BI items, run records, shares, schedules, settings) was saved on the
node that served the request. In a Drishti*net cluster, when the console fails over to another node, that node has none of it,
and the user sees `404 DRS-5006 no design`. Content must be reachable from every node, and two nodes editing the same item at
the same moment must never silently lose one of the edits.

### 2. The interface

One small interface, `com.ash.drishti.identity.content.ContentStore` (drishti-identity, beside the Drishti database code that
already owns user state). Documents, not SQL: a key names one item; the store keeps its bytes and an opaque version.

| Call | Does |
|---|---|
| `get(key)` → `Optional<Item{bytes, version}>` | Reads one item. |
| `put(key, bytes, expectedVersion)` → new version | Compare-and-set. `expectedVersion` null means *must be absent* (create); otherwise the item must still have that version. A mismatch throws `409 DRS-5057` (content conflict). |
| `list(prefix)` → keys | Every key starting with `prefix`, sorted. |
| `delete(key, expectedVersion)` → boolean | Compare-and-set delete; false when there was nothing; a stale version is `409 DRS-5057`. |
| `changes(since)` → `Changes{keys, cursor}` | Keys written or deleted after `since` (epoch ms), for polling caches. At least once: pass `cursor` back next time (less a small margin for clock skew across nodes); a key may repeat. Push is a later addition for backends that have it (Postgres `LISTEN`, S3 events). |
| `root()` | Where the store is, as text (the cluster check, section 6). |

Keys are `/`-separated paths such as `designs/<owner>/<id>/design.json`: 1 to 16 segments, each 1 to 120 UTF-8 bytes, any
Unicode, no empty, `.` or `..` segment, no control characters, no `\`. A bad key is `400 DRS-5058`. `putLatest(key, bytes)` is a
helper (read the version, put, retry a few times) for writes whose last writer may win (sample documents).

### 3. Backends

Chosen in their own file, `config/store/store.yaml` (never `application.yaml`; the file is named by `drishti.store.file`),
examples in `config/store.examples/`. Every backend passes the same contract test suite (section 7).

**filesystem (the default).** Root `<data folder>/fstore` (`data/fstore`). An item is one file, `items/<encoded key>`; its version
is the SHA-256 of its bytes, so the data file alone is the truth and no read can see bytes and version out of step. A write goes
to a visible sibling `<name>~tmp-<uuid>` and is renamed into place; compare-and-set holds a lock file `<name>~lock`, made with
create-new (atomic on local disks, NFS v3+ and EFS) and broken when older than 30 s (a crashed writer). Renames and deletes retry
with back-off when Windows refuses them because a reader has the file open (the FileSwap pattern). Key segments are encoded
(`%XX` for every byte outside `A-Za-z0-9-_.`, a leading `.` and Windows device names too), so names are visible, portable to
Windows and never contain `~`, which is reserved for the store's own sidecars. A delete leaves an empty
`tombstones/<encoded key>` so `changes` reports it; a later put removes it. Since the version is a content hash, writing back
identical bytes keeps the version (harmless: the content is the same). No hidden files, nothing outside the root.

**sqlite and postgres.** One table, `drishti_content (k, v, version, updated_ms, deleted)`, in the two complete Drishti database
schema files (`drishti-identity/src/main/resources/db/schema-sqlite.sql` and `schema-postgres.sql`; no migrations,
SchemaParityTest keeps them equal). The backend applies the whole schema file at start (idempotent), so it may point at the
Drishti database itself (the usual choice) or at a database of its own. The version is a counter; compare-and-set is
`UPDATE … WHERE k = ? AND version = ?` (or an `INSERT` racing on the primary key for a create). A delete keeps a tombstone row
(`deleted = 1`) so `changes` reports it. Postgres is tested with Testcontainers.

**s3.** Objects `<prefix>/items/<key>`; the version is the ETag. Compare-and-set is the conditional PUT: `If-None-Match: *` for a
create, `If-Match: <etag>` for an update; `412` or `409` answers `DRS-5057`. A delete compares the ETag (a HEAD, then `If-Match`)
and writes a tombstone `<prefix>/tombstones/<key>`. `changes` lists by LastModified (second resolution). Works with AWS S3
(conditional writes) and compatible stores that honour the headers, such as MinIO; tested against an in-process S3 stand-in
that honours the conditional headers, Adobe S3Mock in the test JVM, and the MinIO server binary on Linux (section 7).

**Delta / Iceberg: not primary, design only.** They are poor at single-key compare-and-set and at small hot documents. Later they
may be an optional **history/audit sink**: a writer tails `changes`, appends `(key, version, bytes or hash, at, by)` to a Delta or
Iceberg table, and BI can query who changed what and when. Not built.

### 4. Configuration

```yaml
# config/store/store.yaml
backend: filesystem              # filesystem | sqlite | postgres | s3
filesystem:
  root: ${drishti.data.dir:./data}/fstore
sqlite:
  url: jdbc:sqlite:${drishti.data.dir:./data}/identity/drishti.db   # the Drishti database itself (the usual choice)
postgres:
  url: jdbc:postgresql://db:5432/drishti
  user: drishti
  password: ${DRISHTI_STORE_PASSWORD:}
  pool: 4
s3:
  bucket: drishti-content
  prefix: prod
  region: us-east-1
  endpoint: ""                   # MinIO or another S3-compatible store
  path-style: false
  access-key: ${DRISHTI_STORE_ACCESS_KEY:}
  secret-key: ${DRISHTI_STORE_SECRET_KEY:}
```

A missing file or key means the default shown. `config/store.examples/` has `laptop.yaml` (filesystem), `cluster-nfs.yaml`
(filesystem on a shared mount), `postgres.yaml` and `s3.yaml`.

### 5. Designs: the first user

`drishti.builder.designs.store` is now `content` by default: Designs are kept in the content store as
`designs/<owner>/<id>/design.json` and `designs/<owner>/<id>/samples/<hash of name>.json`. `ContentDesignStore` implements the
existing `DesignStore`, so `DesignService` (ownership, quotas, expiry, roles) is unchanged. Every Design read carries its store
version and every save is a compare-and-set against it, so two nodes saving the same Design at once give one success and one
`409 DRS-5057` (changed elsewhere: reload and try again). The older `file` (`data/designs`) and `jpa` stores can still be named,
for one node only.

**One-time import at start.** With `store: content`, the server imports what the old stores hold:

- **files** (`drishti.builder.designs.dir`, `data/designs`): each Design and its samples are put with *must be absent*, so an
  item already in the store is never overwritten and a rerun is harmless. When every Design is in, the folder is renamed
  `designs-imported-<yyyyMMdd-HHmmss>` (visible, kept, safe to delete later). A failure leaves the folder in place for the next
  start.
- **the identity database** (`drishti_design`, `drishti_design_sample`, used by `store: jpa`): the same, once; a marker item
  `system/imports/designs-jpa.json` records it, so a Design deleted later does not come back. The rows are left in place.

Each import logs how many Designs it brought in, skipped (already there) and failed.

### 6. Several servers

Every node of a cluster must use the **same** store configuration. A filesystem store on a cluster needs a **shared mount**
(NFS, EFS, SMB) at the same root on every node; a local `data/fstore` per node gives each node its own content again. Each node
writes `nodes/<node id>.json` into the store at start (its root as it sees it); a node that finds another member's record with a
different root logs a warning (`content store roots differ`): the same share mounted at different paths, or two configurations
pointing at one store. A node also logs its store at INFO (`content store: filesystem /mnt/drishti/fstore`). A local store per node
cannot see the other nodes' records, so that case is caught by the log line and this rule, not by the check (next step: carry
the root in the Drishti*net capability digest and compare it there). Postgres and S3 need no shared disk.

### 7. Tests

`ContentStoreContract` (drishti-identity tests) runs against every backend: concurrent creates and concurrent updates of one key
(exactly one wins, the rest `DRS-5057`), conflict codes, list by prefix, delete (stale and current), changes (puts and deletes),
binary-safe content (every byte value), a large item (6 MB), Unicode keys, bad keys (`DRS-5058`), and reopening the store keeps
everything. Backends: filesystem, sqlite, postgres (Testcontainers), s3 on three S3 simulators:

| Test | Simulator | Runs where | Conditional writes covered |
|---|---|---|---|
| `S3ContentStoreTest` | `FakeS3`, an in-process stand-in | everywhere | create-only, update-if-match, delete-if-match, racing writers |
| `S3MockContentStoreTest` | Adobe S3Mock 4.12.4 in the test JVM (a Maven test dependency: no Docker, no binary) | everywhere, Windows QA included | create-only, update-if-match, delete-if-match; racing creates are **not** atomic on S3Mock, so the race case is skipped there |
| `MinioContentStoreTest` | the MinIO server binary | Linux only | all, when the binary can be had (below) |

Each suite first probes its simulator (`com.ash.drishti.testkit.s3.ConditionalWrites`: a second `If-None-Match: *` create, a stale
`If-Match` put, a stale `If-Match` delete, then eight racing creates five times) and prints one line, for example
`[S3 conditional writes] S3Mock 4.12.4 (in-process): create-only covered, update-if-match covered, delete-if-match covered, racing
writers NOT atomic (skipped)`; a contract case that needs what the simulator does not honour is skipped with that reason, and the
stand-in still covers it. The Iceberg native engine's table on S3 has the same pair (`NativeOnS3SimulatorTest`: S3Mock in-process,
MinIO binary), beside the Docker-based `NativeOnS3Test`.

MinIO: the test (`com.ash.drishti.testkit.s3.MinioServer`) takes the binary from `DRISHTI_MINIO_BIN`, else from the git-ignored tools
cache (`DRISHTI_TOOLS_CACHE`, default `tools/cache/minio/`), else downloads it from `DRISHTI_MINIO_URL` (default: the official
release) and checks it against `DRISHTI_MINIO_SHA256` or the published `.sha256sum`. It starts it on a free port in 18750-18759 with
its data under `target/` and stops it by PID. It skips with the reason on Windows, offline, or when no binary is served: MinIO stopped
serving its community binaries (dl.min.io answers `410 Gone`), so put a binary you trust in `tools/cache/minio/` or point
`DRISHTI_MINIO_BIN` at it. How to run all S3 tests: [TOOLS.md §4.12](../guides/TOOLS.md#412-the-s3-tests-s3mock-moto-and-minio).
`ContentDesignStoreTest`: two in-process nodes on one filesystem root (a Design saved through A is read and updated through B;
concurrent saves give one conflict; owners stay isolated) and the import (idempotent, folder renamed, marker for the database).

### 8. Next steps: what is still kept on one node

Already shared between nodes, not through this store: users, roles and API tokens (IdentitySync); `drishti_preference` (settings,
saved searches, layouts, pivots, workspaces) and sessions (StateSync, ReplicatedPreferences).

The ordered migration list (the inventory of content kept on one node only, 2026-10-21). Items marked done are in the content store
(sections 5, 9 and 10); the rest move one at a time behind the same interface:

1. **Designer drafts** (Designs): done, section 5. Versions, About, form and share live inside the record; ids are random 12-hex.
2. **BI workspaces and items:** done, section 9. `BiStore`, the `drishti_bi_*` tables (workspace, member, item, version, revision, event,
   user_item, usage, setting).
3. **BI runs:** done, section 9. The `drishti_bi_run*` rows, and results and snapshots under `<data>/bi-runs`.
4. **Notes:** done (section 10.2). `drishti_note` is only the source of the one-time note import; notes live in comment threads.
5. **Collaboration:** done (section 10.3): threads, comments, revisions, mentions, follows, note links, shares and recipients, inbox,
   outbox, holds and seals. Still per node: the access log (`drishti_access`, who read what), next.
6. **Alerts and monitors:** NOT copied, on purpose (each node evaluates its own).
7. **Embed apps** (`drishti_embed_app`), **pack switches** (`drishti_pack_state`), **audit** (`drishti_audit` and `audit.jsonl`):
   done (section 10.4).
8. **Files:** governance proposals (`<data>/governance`): done (section 10.5); load history (`<data>/loads`): done (section 10.6).
   Next, in this order: report outputs (`<data>/reports`), designer bound files (`<data>/dev-sutras`), pack admin state (`<data>/packs/*`, `uninstalled`,
   `admin/bi-hidden.txt`; a pack jar stays a local file materialised from the store, the store being the truth). UI-written config
   (`config/connectors`, `config/bi`): done for connectors and data loads overrides, section 11.
9. **BI store Parquet:** a derived cache. Never migrated.

Then: a read cache driven by `changes`, push for Postgres and S3, the root in the Drishti*net capability digest (section 6), and
the optional Delta/Iceberg history sink (section 3).

### 9. BI content and BI runs

`drishti.bi.store` and `drishti.bi.runs.store` are `content` by default: BI workspaces, items and their history, and BI runs with their
results, live in the content store, so every node of a cluster sees the same BI content and the same runs. `ContentBiStore` implements
`BiStore` (now an interface; `JpaBiStore` is the old identity-database store), so `BiService` (workspace membership, item visibility,
audiences, governance) is unchanged; `ContentRunRecords`, `ContentRunRecords.Shares`, `ContentRunRecords.Schedules` and `ContentRunStore`
implement the runs' `RunRecords`, `RunShares`, `RunSchedules.Store` and `RunStore`, so run visibility, shares and the signature rule on
results are unchanged.

**Key layout.** Every segment is escaped (letters, digits, `-` and `.` kept, other bytes `_xx`; past 100 bytes a prefix and a hash);
numbers are zero-padded to ten digits so keys sort.

| Key | What |
|---|---|
| `bi/workspaces/<ws>.json` | a workspace |
| `bi/members/<ws>/<user>.json` | one member of a shared workspace |
| `bi/items/<item>.json` | an item with its draft body and catalogue fields |
| `bi/history/<item>/versions/<n>.json` | a published version |
| `bi/history/<item>/revisions/<seq>.json` | a saved state (every save, restore, publish, import) |
| `bi/history/<item>/events/<seq>.json` | a governance event (request, approve, certify, ...) |
| `bi/users/<user>/marks.json` | a person's favourites and last opens, by item |
| `bi/usage/<yyyy-mm-dd>/<node>.json` | the views one node counted that day, by item (`imported.json`: the import's) |
| `bi/settings/<name>.json` | a BI setting |
| `bi/runs/rows/<run id>.json` | a run (the old `drishti_bi_run` row) |
| `bi/runs/shares/<run id>/<recipient>.json` | a share of a run |
| `bi/runs/schedules/<id>.json` | a scheduled run |
| `bi/runs/results/<result key>/answer.json` | a result, with the signature it was computed for |
| `bi/runs/results/<result key>/meta.json` | its owner, size and last open (the budget and the sweeper read only these) |
| `bi/runs/snapshots/<name>/<version>.json` | a promoted snapshot |

Item documents sit apart from their history (`bi/history/`) so listing the items reads item documents only.

**Safe concurrent edits.** A read of an item or workspace remembers, for the request's thread, the version it returned (30 s); the save
that follows is a compare-and-set against it. Two nodes (or two requests) saving the same item give one success and one `409 DRS-5057`
(changed elsewhere: reload and try again), beside BI's own `baseEtag` check. A save of a key the thread has not read lately writes over
what is there, as the database did. New revision and event numbers, and new snapshot versions, are taken by a create that must not exist
yet, so two nodes never take the same number. Marks and the day's views are read-change-write loops on compare-and-set.

**Listing and search.** The catalogue's listings (all items, a workspace's items, the published ones, all workspaces, the runs lists) come
from a per-node snapshot of the prefix at most `drishti.bi.store-list-ttl` old (default `1s`); documents whose version did not change are not
parsed again, and this node's own writes drop the snapshot at once. A single item, workspace or run is always read from the store. A
listing on another node may lag by the TTL; an edit never does (the compare-and-set decides). Next: a snapshot refreshed from `changes`
instead of a re-list, once `changes` is cheap on every backend (section 8).

**Events and usage.** Governance events are per-item documents (`events/<seq>`): they are rare, belong to the item's audit trail, and must
be the same on every node. Views are append-heavy, so each node counts into its own document per day (`bi/usage/<day>/<node>.json`): no two
nodes write the same document, a write is a small compare-and-set on this node's segment, and `views` sums the segments of the days asked.

**Runs across nodes.** A run's status and result read from any node (the row and the result are in the store); `RunForwardFilter` still
sends a run's actions to the node that holds it, and is kept for nodes whose runs store is `local`. The lease is a compare-and-set on the
run's document: `renew` writes only while the row still names this node, `adopt` only while it is still the dead node's with its lease
lapsed, so exactly one node wins (BI_RUNS.md 13.3). A late save from a node that lost a run (a higher attempt on another node) is dropped.
A due schedule is taken by one node (`claim`: a compare-and-set on its `nextAt`), so a schedule fires once in a cluster.

**One-time import at start.** With `content`, the server imports, once, the identity database's `drishti_bi_*` rows (marker
`system/imports/bi-jpa.json`) and the `drishti_bi_run`, `drishti_bi_run_share`, `drishti_bi_run_schedule` rows with the results and snapshots
under `store.dir` (`<data>/bi-runs`; marker `system/imports/bi-runs.json`). Every document is created with "must be absent" (a rerun is
harmless; a person's marks already in the store win); the marker keeps an item or run deleted later from coming back. The rows and the files
are left in place.

**One node back on the old stores.** `drishti.bi.store: jpa` and `drishti.bi.runs.store: local` put one node back on its identity database
and `store.dir`; it then sees only its own BI content and runs (forwarding by node id still reaches the others' runs).

**Tests.** `ContentBiStoreTest` (drishti-identity): two nodes on one filesystem root (a workspace and an item made on A are read, edited and
published on B; marks, views and settings; delete), two saves of one item give one conflict, concurrent revisions never share a number, and
the import (once; a deleted item stays deleted; nothing counted twice; the rows kept). `ContentRunsTest` (drishti-server): a run submitted on
A has its status and result read on B (never across signatures); a dead node's run adopted by exactly one of two nodes through the lease and
finished; renew only by the holder and a loser's late save dropped; a share made on A seen on B; a schedule claimed once; the import of rows
and the result folder. `RunClusterTest` runs its two servers on one content store root.

### 10. Collaboration, notes and the admin data

Each moved store keeps its interface, so the services above it (rights, limits, retention, holds, seals) are unchanged; a switch per
type names the old store for one node only. At start each runs a **one-time import** of the old rows and files: every item keeps its
id, number and hashes, an item already in the store is skipped, a marker `system/imports/<what>.json` records the import (so
something deleted later does not come back), and the old tables and files are left in place.

#### 10.1 Several documents, one change: `TxContentStore`

Collaboration writes several documents for one action (a share, its recipients' indexes, their inbox rows; a comment, its index
entries). Two choices were open: one document per aggregate, or ordered compare-and-set writes with a recoverable intent record. Both
are used:

- **One document per thread** (`collab/threads/<id>.json`: the thread, its comments, the sealed revision chain, mentions, followers and
  note links). A reply is one compare-and-set of that document, so the hash chain can never fork between two nodes, and a conflict is
  retried on a fresh read (the work has no outside effect).
- **Transactions across documents** (`com.ash.drishti.identity.content.TxContentStore`, what `CollabTx` runs on). Inside `run` every
  write is buffered and reads see it; a failure writes nothing. The commit checks each document still has the version the work read
  (else `409 DRS-5057`, nothing written), writes an **intent record** `system/tx/<time>-<uuid>.json` holding every write (key, expected
  version, bytes) when there is more than one, applies the writes in order (each a compare-and-set), and removes the record. When
  another node wins one of the writes, the writes already applied are put back and the commit is `409 DRS-5057`. A process that dies
  mid-commit leaves the record; `recover()` (at start and every `drishti.identity.refresh-seconds`, on any node) rolls it forward after
  60 s: a write whose document still has the expected version is applied, one already there is skipped, one changed since by someone
  else is left out and logged.
- Index documents (`collab/entities/…`, `collab/comments/…`, `collab/mentions/…`, `collab/shares-sent/…`, `collab/shares-to/…`,
  `collab/inbox-seq/…`) are written in the same transaction, and readers check every entry against the document it points to, so an
  index without its document changes nothing.
- **Sequences** (inbox rows, outbox deliveries, holds, proposals) are counter documents (`…/counters/<name>`) advanced by
  compare-and-set outside any transaction: cluster-wide, never reused, never rolled back (a failed transaction leaves a gap).

#### 10.2 Notes

Notes already live in comment threads (the deprecated `/notes` API is a facade over them), so they moved with collaboration. A note's
number is a create of `collab/note-links/<number>`: two nodes adding a note at once cannot take one number (the loser takes the next).

#### 10.3 Collaboration (`drishti.collab.store`: `content`, default; `jpa` or `file` for one node)

| Key | Holds |
|---|---|
| `collab/threads/<thread>.json` | the thread document (above) |
| `collab/entities/<kind>/<sha1 of entity>/<thread>` | an entity's threads |
| `collab/comments/<comment>` | the comment's thread |
| `collab/mentions/<sha1 of target>/<comment>` | who was mentioned where |
| `collab/note-links/<note, 19 digits>` | legacy note number to comment |
| `collab/shares/<share>.json` | a share and its recipients |
| `collab/shares-sent/<sha1 of sender>/<share>`, `collab/shares-to/<sha1 of user>/<share>` | sent and received lists |
| `collab/inbox/<sha1 of user>.json` | a user's inbox rows (newest `inbox.keep`), one read for the bell |
| `collab/inbox-seq/<seq, 19 digits>` | the user of each row, for another node's live hub (`after(seq)`) |
| `collab/outbox/<seq, 19 digits>.json` | one delivery; a claim is a compare-and-set to `sending` with a lease, so two nodes' dispatchers send it once; an expired lease makes it due again |
| `collab/holds/<id, 19 digits>.json` | a legal hold; a release is a compare-and-set |
| `collab/seals/<seal key>-<hash>.json` | tamper-evidence seals (thread heads, comment rows, holds) |
| `collab/counters/inbox`, `outbox`, `holds` | the sequences |

The import reads the identity database's collaboration tables (marker `system/imports/collab-jpa.json`) and, when present, the files
under `drishti.collab.dir` (`collab-file.json`). The revision chains keep their hashes, so `GET /admin/collab/verify` still holds.

#### 10.4 Admin data in the identity database

| Data | Switch | Key |
|---|---|---|
| Embed apps (`drishti_embed_app`) | `drishti.embed.store` (`content` / `jpa`) | `admin/embed-apps/<id>.json`; a save is a compare-and-set against the version read, so two administrators editing one application on two nodes get one `409 DRS-5057` |
| Pack switches (`drishti_pack_state`) | `drishti.packs.state-store` (`content` / `jpa`) | `admin/pack-state.json` (every pack's state; nodes re-read it every refresh interval) |
| Audit (`drishti_audit`, and `audit.jsonl` through the earlier import into it) | `drishti.audit.store` (`content` / `jpa`) | `audit/<yyyy-MM-dd>/<node>-<nnnn>.jsonl` and `audit-heads/<node>.json` |

**Audit is append-only**, so no two nodes write one document: each node appends to its own per-day segments (a new segment past
512 KB), one JSON line per event, each chained by hash to that node's previous line; `audit-heads/<node>.json` holds the node's count
and newest hash, so removed newest lines show. A read merges every node's segments newest first; the compliance verifier walks each
node's chain. The node is `drishti.net.node-id` (else host and port). The table's rows are imported once (marker
`system/imports/audit-jpa.json`, written first so only one node copies).

#### 10.5 Governance proposals (`drishti.governance.store`: `content`, default; `file` for one node)

`governance/proposals/P-NNNNNN.json`, numbered from `governance/counters/proposals` (raised past the imported numbers), read from the
store every time. A decision is a compare-and-set: two approvers on two nodes cannot both decide one proposal (the second gets
`PROPOSAL_CONFLICT`). The folder `<data>/governance` is imported once (`system/imports/governance-files.json`).

#### 10.6 Load history (`drishti.loads.store`: `content`, default; `file` for one node)

`loads/<pack>.json` (the announcements and late flags of one pack), read every time and changed by compare-and-set with a retry on a
fresh read, so two nodes receiving announcements at once lose none, a batch announced on one node is a duplicate on the other, and
a late notice goes out once. A pack's old file `<data>/loads/<pack>.json` is imported the first time the pack is read (the document's
existence is the marker) and left in place. Per-pack overrides saved through Admin are the shared documents `loads/config/<pack>.yaml` (section 11; `drishti.loads.config-store`: `content`, default, or `file`); the folder `<data>/loads/config` is imported once (`system/imports/loads-config.json`) and kept.

#### 10.7 Left on each node, on purpose

Alerts and monitors (each node evaluates its own); the BI store's Parquet (a derived cache); users, roles, tokens and preferences
(IdentitySync and StateSync already share them).

#### 10.8 Backup

The content store root (`data/fstore`, or the `drishti_content` table, or the bucket) is now the backup of everything above, together
with the identity database for what is still there (users, roles, tokens, preferences, access log). Copy it with the server stopped,
or take a filesystem snapshot: every item is one file written by rename, so a copy taken while it runs holds whole items, but a
multi-document change may be half in (its intent record under `system/tx/` is copied too, and the next start finishes it).

### 11. UI-written configuration: shared overlays (as built for connectors and load overrides)

The decision (2026-10-21): **Admin updates are shared across the cluster; the files stay the base (GitOps), and every edit made
through Admin or the UI is an overlay in the content store that every node reads.** (Option 2 of the four that were listed: one
writer node, overlay, store as the truth, a shared config folder.)

**One poller per node.** `ContentWatch` (drishti-identity) calls `changes(cursor)` every `drishti.store.watch-ms` (2 s) and hands the
changed keys to the listeners whose prefix they start with; the window overlaps the last one by 2 s of clock skew, and a key is handed
over only when its version moved. One walk of the store per interval, however many areas listen.

**Connectors** (`drishti.connectors.store`: `content`, default; `file`). `ConnectorFiles` reads through a `ConnectorOverlay`:

| Key | Holds |
|---|---|
| `config/connectors/<name>.yaml` | the text saved through Admin, exactly as saved (`${VAR}` unresolved, masked credentials never written) |
| `config/connectors/<name>.hidden` | an Admin delete of a connector the files define (the file stays on disk, hidden on every node) |
| `config/connectors-history/<name>/<yyyyMMddTHHmmssSSS>.yaml` | every earlier effective text (Restore lists them with the file history) |

The effective text is the overlay if present, else the file. Every node's `ConnectorManager` gets the change from the watch and
hot-reloads as for a file edit; at start the overlaid names are applied by the first scan (the start-up properties come from the
files alone). A save compares the etag the edit was based on with the store's current effective text and writes by compare-and-set,
so two administrators on two nodes get one `409 DRS-5032`. Reset removes the overlay. There is nothing to import (the files were and
stay the base). Pack template updates (CONNECTOR_FILES.md, "Template updates") write through the same overlay.

**Data loads overrides** (`drishti.loads.config-store`: `content`, default; `file`): `loads/config/<pack>.yaml`, read on every use,
saved by compare-and-set; the folder `<data>/loads/config` imported once (`system/imports/loads-config.json`) and kept.

**`config/bi`**: nothing is written there by the UI (BI content is in the store, section 9), so there is nothing to share.

**Not built yet (next):** the pack admin state (`packs/added.yaml`, `packs/installed/` archives materialised to a local cache on each
node, `packs/settings/`, `packs/deploy-history.jsonl`, `packs/samples-mode`, `<data>/uninstalled/`, `<data>/admin/bi-hidden.txt`), with
the in-place restart (`Restarter`) triggered on every node when another node installs or uninstalls a pack; report outputs
(`<data>/reports`), designer bound files (`<data>/dev-sutras`) and the collaboration access log (`drishti_access`, per-node day segments
merged when read, like the audit). The pack state needs the store opened in the environment post-processor (packs load before the
Spring context), so each node materialises the shared state to its local folders before the packs are read.

## 6. The one Drishti database (design, not built)

Status: **design, not built** (`DrishtiDatabase`, `config/db/database.yaml`, the stamp and `db copy`); the write-back tables are built
(2.8.0, 2026-10-25) in the two existing complete schema files, on the identity database's pool
([WRITE_BACK.md › The design and its reasons, section 19](../guides/WRITE_BACK.md#19-as-built-280-2026-10-25)). To be built as **item 0 of write-back (2.8.0)**, before write-back itself (decided by the product owner,
2026-10-20, as 2.7.0 item 0; write-back moved to 2.8.0 on 2026-10-21). The content store ([section 5](#5-the-content-store-user-content-every-node-reaches),
2.7.0) was front-loaded from it. Related:
[WRITE_BACK.md](../guides/WRITE_BACK.md#16-the-design-and-its-reasons) (the edit log, lanes and overlays that live here), [CLUSTER_DEPLOYMENT.md](../admin/CLUSTER_DEPLOYMENT.md#1-drishtinet-many-servers-one-drishti) (several servers),
[CONFIGURATION.md](../admin/CONFIGURATION.md) (the settings).

### 1. The decision

All relational state that Drishti owns lives in **one database**, the *Drishti database*. Identity already lives in one; from 2.8.0
the write-back edit log, its lanes, overlays and Drishti-owned data live in the same one, and so does anything relational added later.
There is no second store to back up, secure, size or switch.

- **PostgreSQL is the long-term choice** (several servers, a real deployment, backups by the bank's DBAs).
- **SQLite is the default**, for single-node setups: a Windows laptop, a demo machine, a developer. One file, nothing to install.
- **You switch by configuration at any time**, through the planned `drishti.py db copy` (section 6). The code path is the same on both: the
  same tables, the same SQL (with the few dialect differences kept in the two schema files and one small dialect class).

```
                       ┌──────────────────────────── the Drishti database ───────────────────────────┐
  drishti-server ────▶ │ identity: users, roles, packs, preferences, tokens, sessions, audit, notes,  │
  (one pool,           │           shares, threads, alerts, BI workspaces …           (today)        │
   config/db/          │ write-back: drishti_edit_lane, drishti_edit_log, overlays, owned kinds      │
   database.yaml)      │                                                              (2.8.0)        │
                       │ drishti_schema: the version stamp (section 5)                               │
                       └────────────── SQLite file (default)  or  PostgreSQL (long term) ────────────┘

  not here:  DuckDB (the BI engine: compute) · the lakes (Delta, Iceberg) · RocksDB caches · source systems
```

### 2. What lives in it, and what does not

| Lives in the Drishti database | Does not |
|---|---|
| Identity: users, roles, packs per user, preferences, API tokens, sessions, the audit trail | **DuckDB**, the BI engine: it is compute over the lakes and BI files, rebuilt from them, never a system of record |
| Collaboration: notes, shares, threads, comments, mentions, inbox and outbox | The **lakes** (Delta, Iceberg): source data, written only by the loader (`lake build`, `lake publish`) |
| BI workspaces, items, versions, usage and BI runs only with `drishti.bi.store: jpa` / `drishti.bi.runs.store: local` (one node); by default they live in the content store and the `drishti_bi_*` and `drishti_bi_run*` tables are legacy, read once by the import and kept ([section 5](#5-the-content-store-user-content-every-node-reaches) section 9); the content store's table `drishti_content` when `config/store/store.yaml` says `sqlite` or `postgres` ([section 5](#5-the-content-store-user-content-every-node-reaches)) | **Caches** (RocksDB, layout and fingerprint caches): derived, safe to delete |
| Write-back (2.8.0, built): `drishti_edit_lane`, `drishti_edit_log`, `drishti_edit_request` (changes awaiting approval), `drishti_overlay`, `drishti_owned`, `drishti_edit_version` (each record's version) and `drishti_edit_cursor` (how far the reactive triggers and a follower of an edit primary have read each lane) ([WRITE_BACK.md](../guides/WRITE_BACK.md#16-the-design-and-its-reasons) sections 5, 7, 11 and 19). New tables only: the schema version does not move | **Source systems** (ENDUR, CALYPSO, MUREX, connector databases): Drishti reads them, and writes to them only through a connector's `writes:` |
| Compute approvals (2.8.0, built): `drishti_compute_request` (requests to approve what a connector or pool requires) and `drishti_compute_approval` (the approved items, revocable) ([PYTHON_COMPUTE.md](PYTHON_COMPUTE.md) section 6) | Locked Python environments, staged jars and request scratch folders: files under `<data dir>/compute/` |
| Administrators' switches (2.8.0, built): `drishti_switch` (commands and packs on or off, each with its hybrid-logical-clock stamp and node) and `drishti_switch_log` (every change, with who and why); the system of record that Drishti*net gossip spreads ([USER_MANAGEMENT.md (Functions)](../admin/USER_MANAGEMENT.md#where-it-is-kept)). New tables only: the schema version does not move | |
| Anything relational added later | Files that are documents by nature (Sutras, packs, connector and net configuration): they stay files under `config/` |

The rule for a new feature: **state Drishti owns and must not lose, with rows and keys, goes in the Drishti database.** A feature
never opens its own SQLite file or its own schema.

### 3. SQLite or PostgreSQL

| | SQLite (default) | PostgreSQL (long term) |
|---|---|---|
| For | one server: a laptop, Windows, a demo, a developer | several servers, production, managed backups |
| Install | nothing: a file under the data folder | a PostgreSQL 14+ server (or RDS / Aurora PostgreSQL) |
| Writers | one at a time (WAL: readers never wait; `busy_timeout`, `IMMEDIATE` transactions) | many |
| Several Drishti servers | each has its own file; nothing is shared (write-back: section 8 and WRITE_BACK.md › The design and its reasons, section 11) | all share one database: identity, roles and the edit log are cluster-wide |
| Backup | copy the file with the server stopped, or the planned `drishti.py db copy` to a new file | the DBA's backups, point-in-time recovery |

The two are equals in the code: every query is tested on both; neither is a "test" database.

### 4. No migrations: two complete schema files

**No database migrations, ever.** No Flyway, no Liquibase, no numbered `ALTER` scripts, no column fix-ups in Java.

- There are **exactly two schema files**, one per kind, each the **complete** schema: every table, column, key and index Drishti uses.
  They move from `drishti-identity` to the module that owns the Drishti database (`db/schema-sqlite.sql`, `db/schema-postgres.sql`).
- They are **applied at every start, idempotently**: `CREATE TABLE IF NOT EXISTS`, `CREATE INDEX IF NOT EXISTS`. A fresh database
  gets everything; an existing one at the same version is left as it is. A **new table** in a release is simply created by this step.
- A **parity test** reads both files and checks they define the same tables, the same columns (by name, nullability and a mapped type:
  `TEXT`↔`VARCHAR`, `INTEGER` 0/1↔`BOOLEAN`, `INTEGER PRIMARY KEY AUTOINCREMENT`↔`BIGSERIAL`), the same primary, unique and foreign keys
  and the same indexes. A change to one file without the other fails the build.
- A change to an **existing table's shape** (a column added, removed, retyped, a key changed) is made in both files, bumps the schema
  version (section 5), and is reached by an existing database through `db copy` (section 6), never by an `ALTER`.

```
 release N                       release N+1 (a column added to drishti_note)
 schema-sqlite.sql   (v4)        schema-sqlite.sql   (v5)   ─┐  parity test: same tables, columns, keys
 schema-postgres.sql (v4)        schema-postgres.sql (v5)   ─┘
          │                                 │
  existing db (v4) ── start N+1 ──▶ refused: "made by schema 4, this server needs 5: drishti.py db copy …"   (planned)
          └──────────── drishti.py db copy ──▶ fresh db (v5) built from the complete schema, rows copied ──▶ start   (planned)
```

Why: a complete file is the truth you can read in one place; there is no chain of scripts to replay, reorder or repair, and SQLite's
limited `ALTER TABLE` never shapes the design.

### 5. The schema version stamp

A one-row table holds the stamp:

```sql
CREATE TABLE IF NOT EXISTS drishti_schema (
    id          INTEGER   PRIMARY KEY CHECK (id = 1),
    version     INTEGER   NOT NULL,          -- the schema files' version, written in their first line
    written_by  TEXT      NOT NULL,          -- the Drishti release that built the database
    written_at  TIMESTAMP NOT NULL
);
```

At start, after the idempotent apply:

| The database | The server |
|---|---|
| empty (new) | builds it from the complete schema and stamps it with the files' version |
| stamped with the files' version | starts |
| stamped with another version (older or newer) | **refuses to start**, naming both versions and the command: `the Drishti database was made by schema 4; this server needs schema 5. Stop the servers and run: drishti.py db copy --from config/db/database.yaml --to <new database.yaml>` |
| not stamped (made before 2.8.0) | compares its tables with the schema; if every existing table already has the schema's columns and keys it stamps it and starts, otherwise it refuses as above |

The version moves only when an existing table changes shape; a release that only adds tables keeps it.

### 6. Switching and upgrading: `db copy`

Switching from SQLite to PostgreSQL, from PostgreSQL to another PostgreSQL, or upgrading across a schema version is **one command**:

```
drishti.py db copy --from config/db/database.yaml --to config/db/database.postgres.yaml [--check] [--batch 5000]   # planned, not built
```

1. **Refuses** while a server uses the source (it checks the cluster's members and the SQLite lock); the copy needs the servers stopped.
2. **Builds a fresh target** from the complete schema of the target's kind (it refuses a target that already has tables, unless
   `--replace`).
3. **Copies the rows** table by table in foreign-key order, in batches, columns matched by name: a column new in the target takes its
   declared default; a column the target no longer has is listed and needs `--drop-columns` to proceed. Sequences (PostgreSQL) are set
   past the highest copied key.
4. **Checks** row counts per table and a checksum of every key column; any difference fails the copy and leaves the source untouched.
5. **Stamps** the target with the schema version and prints the line to put in `database.yaml`.

The source is never changed, so going back is pointing `database.yaml` at it again. `--check` does everything except the writes and
reports what would be copied. Admin → Database shows the kind, the version, the size per table and the command for a copy.

### 7. Configuration: `config/db/database.yaml` (planned)

The database has its own file, like `config/connectors/` and `config/net/`, never in `application.yaml`. `application.yaml` keeps only
the pointer:

```yaml
drishti:
  db:
    file: ${DRISHTI_DB_FILE:./config/db/database.yaml}   # the Drishti database; ready-made files in config/db.examples/
```

The planned `config/db/database.yaml` (not shipped yet):

```yaml
kind: sqlite                     # sqlite (default, one server) | postgres (several servers, production)
sqlite:
  file: ${drishti.data.dir}/identity/drishti.db     # under the data folder; the same file identity uses today, so an upgrade keeps it
postgres:
  url: ${DRISHTI_DB_URL:${DRISHTI_IDENTITY_DB_URL:}}            # jdbc:postgresql://db:5432/drishti
  user: ${DRISHTI_DB_USER:${DRISHTI_IDENTITY_DB_USER:}}
  password: ${DRISHTI_DB_PASSWORD:${DRISHTI_IDENTITY_DB_PASSWORD:}}   # only a ${ENV} placeholder; a literal is refused at start
pool:
  size: 20                       # PostgreSQL; SQLite (one writer) keeps 8
  min-idle: 2
  idle-timeout: 10m
  max-lifetime: 30m
  connection-timeout: 10s
  leak-threshold: 60s
  statement-timeout: 5m          # PostgreSQL only
```

- **The identity database settings move here:** `drishti.identity.database-url`, `database-user`, `database-password` and
  `database-pool-size` leave `application.yaml`. The identity block keeps what is not about the database (passwords, lockout, the seed
  administrator, `refresh-seconds`). A server that still finds one of the moved keys set starts, uses it, and warns once naming
  `config/db/database.yaml` (planned); the old `DRISHTI_IDENTITY_DB_*` environment names keep working through the placeholders above.
- `drishti.db.pool` in `application.yaml` stays as the default pool of the **database connectors**; the Drishti database's own pool is
  the `pool:` above.
- The password is only ever a `${ENV}` placeholder (the same rule as connector files).

### 8. Several servers

| Setup | The Drishti database | Write-back's edit log ([WRITE_BACK.md](../guides/WRITE_BACK.md#16-the-design-and-its-reasons) section 11) |
|---|---|---|
| one server | SQLite (or PostgreSQL) | in the same database |
| several servers, PostgreSQL | **one shared database**: identity and the edit log cluster-wide | in it: the full guarantee |
| several servers on AWS, no PostgreSQL | SQLite per server | S3 conditional writes |
| several servers, neither | SQLite per server | one named *edit primary* holds it; the others forward |

PostgreSQL is what makes a cluster one system for everything Drishti owns; a cluster on SQLite works, and Admin → Edits and
Admin → Database say plainly what is not shared.

### 9. Today and the change

| Today (2.6.5) | After item 0 |
|---|---|
| `IdentityDatabase` in drishti-identity opens the pool and applies `db/schema-*.sql` (idempotent, "no migrations") | a `DrishtiDatabase` opens the one pool and applies the two complete files; identity, write-back and later features take their connections from it |
| on SQLite three column fix-ups run in Java (`ensureHoldRange`, `ensureTokenScopes`, `ensureShareViewState`) | removed: the columns are in the complete schema; an older database without them is stamped only if it has them, otherwise `db copy` |
| settings in `application.yaml` under `drishti.identity.database-*` | `config/db/database.yaml` (planned); a pointer in `application.yaml` |
| no stamp, no parity test | `drishti_schema` and the parity test |

### 10. Build plan (2.8.0 item 0)

| Step | What | Accepted when |
|---|---|---|
| 0.1 | `DrishtiDatabase` (pool, dialect, idempotent apply), the schema files moved, `drishti_schema`; identity uses it | every identity test passes on SQLite and PostgreSQL unchanged |
| 0.2 | planned: `config/db/database.yaml`, `config/db.examples/`, the pointer, the moved keys with their warning | a 2.6.5 data folder starts on 2.8.0 with its users, roles and tokens and no setting changed |
| 0.3 | the parity test; the column fix-ups removed | a column in one file and not the other fails the build |
| 0.4 | planned: `drishti.py db copy` (and `--check`), the refusal on a version mismatch, Admin → Database | SQLite → PostgreSQL → SQLite round trip gives identical rows; a mismatched stamp refuses to start with the command named |
| 0.5 | docs: CONFIGURATION, the admin guide, a tutorial step (UI, CLI, YAML) | the docs tests pass |

The edit-log tables of write-back ([WRITE_BACK.md](../guides/WRITE_BACK.md#16-the-design-and-its-reasons) section 7) are added to both files in W1, on this base (built
2026-10-25, in the files under `drishti-identity` until they move; the parity test covers them).

### 11. Tests

- **Parity:** the two files define the same tables, columns, keys and indexes.
- **Idempotent:** applying the schema twice changes nothing; a new table is created on an existing database.
- **Stamp:** a database stamped with another version refuses to start and names `db copy`; an unstamped one with the right shape is stamped.
- **Copy:** SQLite → PostgreSQL → SQLite keeps every row (counts and key checksums); a dropped column needs `--drop-columns`; a running
  server blocks the copy.
- **Upgrade:** a 2.6.5 data folder starts with its users and settings; the old environment names still reach PostgreSQL.

### 12. Decisions (product owner, 2026-10-20)

1. **One Drishti database** for all Drishti-owned relational state: identity, the write-back edit log, lanes, overlays and owned data,
   and anything relational later. PostgreSQL long term; SQLite the default for single-node setups; switch by configuration at any time.
2. **No migrations, ever:** two complete schema files (SQLite, PostgreSQL), applied idempotently at start, kept equal by a parity test.
3. **Switching or upgrading is `drishti.py db copy` (planned):** a fresh database from the complete schema with the rows copied; a schema version
   stamp makes the server refuse a mismatch and name the command.
4. **Its own configuration file** (planned), `config/db/database.yaml` (`kind: sqlite | postgres`, the SQLite file under the data folder, the
   PostgreSQL url, user and password as `${ENV}`, the pool); the identity database settings move there; `application.yaml` keeps a pointer.
5. **DuckDB is compute, not this store.**
6. Built as **2.8.0 item 0** (2.7.0 when decided).
