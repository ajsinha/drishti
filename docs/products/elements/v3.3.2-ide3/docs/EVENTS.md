<!--
  Drishti Elements

  Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>.
  All rights reserved.

  PROPRIETARY.

  This file is the proprietary property of Ashutosh Sinha.
  Copying, use, modification or distribution of this file, via any medium,
  is not permitted except with the express prior written permission of the
  copyright holder.

  See the LICENSE file in the root of this repository.
-->
# Events: drishti-events

One event model for the page, its tabs and a server: topics with wildcards (`*` one segment, `>` the rest), publish and subscribe, request and
reply, streams, typed topics, and routes that decide whether a message stays in the page, goes to the other tabs or goes to a server. The
library's own happenings are topics too (`$events.state`, `$events.error`, ...), so there is nothing else to learn.

Load it with the bundle, or import it:

```js
await Drishti.elements.load('drishti-events');
const events = Drishti.elements.events.connect();            // no server: the page and its tabs
// or: import { connect } from '/elements/drishti-events/src/drishti_index.js';
```

**1. Publish and subscribe in the page**

```js
const events = connect();
const off = events.on('prices.*.USDCAD', (m) => console.log(m.topic, m.data));   // `prices.>` hears every depth
events.publish('prices.fx.USDCAD', { bid: 1.36, ask: 1.37 });
off();
```

**2. A server over Server-Sent Events, with a status badge**

```js
const events = connect('/events');                           // ws: or wss: gives a WebSocket
events.on('$events.state', (m) => { badge.textContent = m.data.state; });   // connecting, open, reconnecting, ...
events.subscribe('prices.>', (m) => grid.applyTransaction({ update: [m.data] }));
```

Subscriptions made before the connection opens are sent when it does; a reconnect restores them and asks for what was missed.

**3. One connection for all tabs**

```js
const events = connect('/events', { share: true, namespace: 'my-app.' + userId });   // a SharedWorker holds the one connection
```

A subscription held by several tabs is one subscription to the server (it matters for SSE over HTTP/1.1, six connections per site). Where there
is no `SharedWorker`, the tab holding a Web Lock relays to the others, and when it closes the next tab takes over and resumes. `namespace` is
required with `share`: one per application and signed-in person, so two people's tabs never share a connection.

**4. The inspector**

```js
events.inspect();                                            // a drawer at the page's side; or events.inspect(element), a dock's panel
```

Messages (filter by a topic pattern such as `prices.>`, or by words in the topic, kind or outcome; pause), subscriptions, the connection
timeline, the queue, and "why didn't I get it?" (`events.explain({ topic })`). Keys: `/` to filter, `Space` to pause, `Enter` to open the
message under focus, `Esc` to close it, then the inspector. The inspector is a part of its own, loaded when opened.

**5. A queue that survives a reload, and the status badge**

```js
const events = connect('/events', { namespace: 'my-app.' + userId, outbox: { persist: true } });
events.publish('notes.edit', { text: 'edit 1' }, { queue: 'persist' });   // waits while offline, sent once and in order
Drishti.elements.events.statusBadge(document.getElementById('badge'), events);   // "Live", "Reconnecting in 4 s", "Offline: 5 changes waiting"
```

Reference: [API.md](API.md#drishti-events) (every call and option), the wire protocol for a server in
[CONTRACTS.md](CONTRACTS.md#the-events-contract-drishti-events-protocol-version-1), and how it is built in [DESIGN.md](DESIGN.md#8-drishti-events).
The gallery's Eventing pages run every example on the mock server.
