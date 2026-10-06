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
# Live updates

Some entities change while you look at them: a trade's mark-to-market, a curve, an FX spot. When the source
that holds such an entity can push changes, Drishti keeps the open view up to date. Only the cells that
changed are repainted, and each one flashes briefly. This document explains how that works, how to watch it
happen with `curl`, what the events look like, and which settings control it.

If you only want to *use* live views, read the first section and stop. Operators should read
[Try it yourself](#try-it-yourself) and [Settings](#settings). The full path is described in
[How a tick reaches the screen](#how-a-tick-reaches-the-screen).

## What you see in the browser

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

## Try it yourself

The server streams over Server-Sent Events (SSE): a plain HTTP response that never ends, made of text
events. `curl -N` (no buffering) shows them as they arrive. The examples run against a local server with
security off (`drishti.security.enabled: false`); with security on, add an `Authorization: Bearer …`
header (see [API_GUIDE.md](../guides/API_GUIDE.md)).

### 1. Stream one view from the server

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

### 2. Ask for a past business date

```bash
curl -N "http://localhost:18480/api/v1/views/trade/END-1000008/stream?asOf=2026-09-28"
```

You should see exactly one `view` event whose provenance says `"live":false,"businessDate":"2026-09-28"`,
after which the server ends the response and `curl` returns. A past date never ticks and holds no stream slot.
The header `X-Drishti-As-Of: 2026-09-28` does the same as the `asOf` parameter.

### 3. Check the live counters

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

### 4. Watch what the browser receives

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

## Event reference

### Server: one view (`GET /api/v1/views/{kind}/{id}/stream`)

| Event | `id:` | Data |
|---|---|---|
| `view` | `0` | The full ViewModel, the same JSON as `GET /api/v1/views/{kind}/{id}`. Always the first event, on every connect and reconnect. |
| `frame` | the frame's `seq` | `{"seq", "generation", "patches": [...], "latencyMs", "p99Ms"}`. |
| comment `:hb` | none | Heartbeat every `drishti.live.heartbeat` (15 s) when no frame was sent. |

A frame's `patches` are a list of these:

| `op` | Fields | Apply it by |
|---|---|---|
| `strip` | `index`, `cell` | Replacing header strip cell number `index` (0-based) with `cell`. |
| `panel` | `panel` | Replacing the panel with the same `panel.id`. |
| `provenance` | `provenance` | Replacing the view's provenance (generation, source, fetched-at). |
| `deleted` | `at` (ISO-8601 instant) | Saying the entity was deleted at `at`; keeping what is shown; applying no later patches. |
| `restored` | none | Repainting the whole view: a deleted entity came back (the patches after it bring it up to date). |

### Server: a monitor (`GET /api/v1/me/monitors/{name}/stream`)

A monitor is a saved list of 1 to 50 entities shown as rows. Its stream is **one** connection for all rows:

| Event | Data |
|---|---|
| `hello` | `{"rows": 3}`, the number of rows. |
| `row` | `{"kind", "id", "patches", "p99Ms"}`. Only `strip` patches are sent, because a row shows only the strip. |
| comment `:hb` | Every 15 s when quiet. |

Rows whose entity is missing, not live, or not visible to you simply never tick.

### Server: alerts (`GET /api/v1/me/alerts/stream`)

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

### Console: the browser's channel (`GET /api/channel?s=...`)

| Subscription `s=` | Relays |
|---|---|
| `view:<kind>/<id>` | The view stream above. `frame` patches for panels other than line and area charts gain an `html` field with the rendered panel. |
| `alerts` | The alerts stream. |
| `monitor:<name>` | A monitor's stream. |

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

## How a tick reaches the screen

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

### One connection per browser

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

#### Hub protocol

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

#### In the browser: `channel.js`

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

#### In the console: lifetime of a channel

Each `GET /api/channel` gets a random id and a task per subscription that reads the server's stream through the
console's pooled HTTP client. The channel ends, and every upstream stream is closed within about a second, when
the browser disconnects, when nothing has read the channel for 5 s (a browser that vanished without closing it),
or after `end`. A channel opened with no subscriptions stays open, waiting for `POST … add`.

Open channels live in the memory of the console process that served them. If you run several console
processes behind a load balancer, make sessions sticky, so that the browser's `POST /api/channel/{id}` reaches the
process holding its channel. Otherwise it gets `404` and the browser's hub reopens its channel.

## Guarantees and limits

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

### Deleted entities

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

A source plugin that deletes (see [§5.2 of the developer guide](../guides/DEVELOPER_GUIDE.md#52-add-a-source-plugin))
pushes `EntityDocument.deleted(ref, provenance)` to its listeners; one that never deletes needs no change. A listener
that does not care about deletes skips documents whose `deleted()` is true. Eviction from a full state store is not a
deletion and is not pushed.

## Monitors and alerts

- A **monitor** stream holds one `ViewStream` per row and multiplexes their strip patches as `row` events
  over one connection, with a latest-wins mailbox per row.
- The **alert engine** subscribes to the topics of every entity that some enabled rule watches, whether
  or not a browser is open. It evaluates each rule's Rachana-EL condition on every new document of that entity,
  fires on the false → true edge, and pushes `alert` events to that user's open streams. A rule's subscription
  keeps the source subscription open too, so an entity watched by a rule ticks even with no viewer.

### Worked example: a monitor

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

### Worked example: an alert that fires

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
`GET /api/v1/me/alerts/suggestions/netting-set` returns, with the finance pack enabled,
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

## Latency

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

## Settings

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

Or for one run: `java -jar drishti-server-1.18.0-exec.jar --drishti.live.frame=100ms`.

The console has one live setting, in `drishti-console/config/application.yaml`:

| Key | Default | Effect |
|---|---|---|
| `live.max_subscriptions` (`DRISHTI_LIVE_MAX_SUBSCRIPTIONS`) | `32` | Subscriptions one browser's channel carries at most: views across all its tabs and workspace panes, the alerts bell, monitors (a view open in several tabs counts once). Each holds one stream to the server from the console's pool (`backend.pool_size`, 64), so keep it well below that. One more is answered with `gone`, `DRS-5003`, and the view shows `Static`. |

Behind a reverse proxy, turn off response buffering for SSE (`proxy_buffering off;` in nginx; the console
already sends `X-Accel-Buffering: no`) and set the read timeout above the heartbeat interval. See
[OPERATIONS.md](../admin/OPERATIONS.md).

## Streaming sources

A plugin that declares `live` pushes each new generation of an entity through `subscribe`. The `kafka` plugin does
this from a topic: it rebuilds the latest document per entity from the start of the topic, then forwards every new
message to the views that are open on that entity, through the same TopicHub, frames and patches as any live source.
For Live, the router asks live sources first, and a real stream before the demo samples; a picked business date
never streams.

`GET /api/v1/sources` lists every source with `"live": true` or `false`. In the sample configuration only
`demo` is live; the `trading` pack's Kafka connector `trading-stream` becomes live when you set
`DRISHTI_STREAM_TRADING=true` and point `DRISHTI_KAFKA_BOOTSTRAP` at your brokers.

### Worked example: making trades live from Kafka

The trading pack declares the connector in `packs/trading/pack.yaml`:

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

## The demo source

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

## Implementation note

The architecture first proposed re-binding only the panels whose expressions read the changed
paths. Every expression can report its paths (`Expr.paths`), and every cell and row carries its
`path`. Measurement showed that rebuilding the whole view with the cached layout and diffing takes a
few hundred microseconds, well inside the budget. The simpler rebuild-and-diff design was therefore
kept. The path index remains available if a very large view ever needs it.
