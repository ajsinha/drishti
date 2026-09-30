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

Views of live entities tick. The path from a source tick to a changed cell on screen:

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
            console /api/channel: ONE stream per browser tab carrying all its views (every workspace pane),
                              │ the alerts bell and monitors; panels re-rendered to HTML with the same Jinja
                              │ macros; charts sent as data
                              ▼
            channel.js → live.js: strip cells updated in place, panels swapped, charts moved; changes flash
```

### One connection per tab

Browsers open at most six connections to one site over HTTP/1.1. A stream per view and one for the alerts bell
used them up with three tabs open (or a workspace and a tab), and every other request, the command line's
suggestions included, then waited forever: the page looked alive but did nothing. So each tab opens **one**
channel (`/api/channel?s=view:trade/T-1&s=alerts`), a workspace's panes share their page's channel, and
subscriptions that arrive later are added to the open channel (`POST /api/channel/{id}`) instead of reconnecting.
A tab hidden for 10 s gives its connection back and reconnects, repainting from fresh data, when shown.

Upstream, the console reads the server's streams through its pooled HTTP client and closes each one within
seconds of the tab going away, busy or quiet: it closes the HTTP response itself from a task outside the
request's cancel scope, because Starlette's scope cancels every clean-up await of a finished request. A test
guards that no page opens its own `EventSource`.

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
  a fresh `view` event, so a client that missed frames repaints from it; nothing is replayed.
- **Heartbeats.** An SSE comment every 15 s keeps proxies from closing idle streams.
- **Capacity.** `drishti.live.max-streams` (20,000) caps the streams per server, view and monitor streams together. A slot is taken atomically before any work, so concurrent requests never overshoot it, and released exactly once however the stream ends.

## Monitors and alerts

- A **monitor** stream holds one `ViewStream` per row and multiplexes their strip patches as `row` events
  over one connection, with a latest-wins mailbox per row.
- The **alert engine** subscribes to the topics of every entity that some enabled rule watches, whether
  or not a browser is open. It evaluates each rule's Rachana-EL condition on every frame, fires on the
  false → true edge, and pushes `alert` events to that user's open streams.

## Latency

The top bar shows `Live, p99 N ms`: the rolling (30 s) p99 of source-tick → frame-built across the
server, recorded lock-free in an HdrHistogram. `GET /api/v1/health/live` reports streams, topics,
frames, p50 and p99.

## Streaming sources

A plugin that declares `live` pushes each new generation of an entity through `subscribe`. The `kafka` plugin does
this from a topic: it rebuilds the latest document per entity from the start of the topic, then forwards every new
message to the views that are open on that entity, through the same TopicHub, frames and patches as any live source.
For Live, the router asks live sources first, and a real stream before the demo samples; a picked business date
never streams.

## The demo source

The `demo` plugin ticks entities whose fixture says `live` (IRS and FX trades, curves, spot, netting
sets) every `tick-ms` (400 ms), but only while someone watches. It takes a random-walk step and keeps
dependent values consistent: for the future, MTM follows the last price, and the intraday settlement
row and variation margin follow too. Setting `drishti.sources.plugins.demo.settings.ticking: false`
freezes it.

## Implementation note

The architecture first proposed re-binding only the panels whose expressions read the changed
paths. Every expression can report its paths (`Expr.paths`), and every cell and row carries its
`path`. Measurement showed that rebuilding the whole view with the cached layout and diffing takes a
few hundred microseconds, well inside the budget. The simpler rebuild-and-diff design was therefore
kept. The path index remains available if a very large view ever needs it.
