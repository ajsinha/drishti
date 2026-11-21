/*
 * Drishti Elements
 *
 * Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>.
 * All rights reserved.
 *
 * PROPRIETARY.
 *
 * This file is the proprietary property of Ashutosh Sinha.
 * Copying, use, modification or distribution of this file, via any medium,
 * is not permitted except with the express prior written permission of the
 * copyright holder.
 *
 * See the LICENSE file in the root of this repository.
 */
/* The gallery's Eventing section (drishti-events): every page runs from the file system on the mock server (the library's mock part,
   loaded by drishti-core's loadPart), shows its code, the status badge and a button that opens the inspector.
   Connection (a server's own JSON by hand), Ticker (2,000 instruments at up to 10,000 messages a second, conflated per frame), Chat
   (publish, subscribe, request and reply with a timeout and a cancel), Across tabs (MockTabs: two Events joined as two tabs; a real second
   tab where the browser allows), Offline (the queue kept on disk across a reload), Unreliable network (the mock's faults), Why (a message
   that never arrives, explained) and Inspector (docked). The sample data is made up. */
(function (root) {
  'use strict';
  const NS = (root.Drishti = root.Drishti || {});
  const GAL = (NS.gallery = NS.gallery || {});
  const demos = (GAL.demos = GAL.demos || []);
  const ev = (spec) => Object.assign({ library: 'drishti-events', kind: 'function', ag: false, keys: [], events: [], sutra: () => null, types: [] }, spec);
  const IMPORT = "import { connect } from '/elements/drishti-events/src/drishti_index.js';\n";
  const MOCK = "import { MockServer } from '/elements/drishti-events/src/drishti_mock.js';   // the mock part (drishti_events-mock.js)\n";

  /** The mock part, then fn(api): every page's start. Resolves with what fn answered. */
  function withMock(ctx, fn) { return ctx.E.core.loadPart('drishti-events', 'mock').then(fn); }

  /** A toolbar of buttons: [label, action, run]. */
  function toolbar(ctx, doc, label, buttons, extra) {
    const h = ctx.h;
    return h(doc, 'div', { cls: 'gx-demo-bar', attrs: { role: 'toolbar', 'aria-label': label } },
      buttons.map((b) => h(doc, 'button', { cls: 'gx-btn', text: b[0], attrs: { type: 'button', 'data-action': b[1] }, on: { click: b[2] } })).concat(extra || []));
  }

  /** The status badge and the inspector's button: what every Eventing page carries. */
  function chrome(ctx, doc, events) {
    const h = ctx.h, badge = h(doc, 'span', { cls: 'gx-ev-badge', attrs: { 'data-badge': '' } });
    const stopBadge = ctx.E.events.statusBadge(badge, events);
    let ins = null;
    const open = h(doc, 'button', { cls: 'gx-btn', text: 'Open the inspector', attrs: { type: 'button', 'data-action': 'inspect' },
      on: { click: () => { if (ins && !ins.closed) { ins.close(); } events.inspect().then((i) => { ins = i; ctx.log('inspector', 'opened'); }); } } });
    const bar = h(doc, 'div', { cls: 'gx-demo-bar gx-ev-chrome' }, [badge, open]);
    return { bar: bar, destroy: () => { stopBadge(); if (ins && !ins.closed) { ins.close(); } bar.remove(); } };
  }

  /** A short list that keeps the newest n lines (text only). */
  function lines(ctx, doc, label, n) {
    const ol = ctx.h(doc, 'ol', { cls: 'gx-ev-lines', attrs: { 'aria-label': label } });
    return { el: ol, add: (text, cls) => { ol.appendChild(ctx.h(doc, 'li', { cls: cls || null, text: text })); while (ol.children.length > (n || 12)) { ol.removeChild(ol.firstChild); } } };
  }

  /** A page whose parts are made once the mock part is loaded: mount answers at once, ready when drawn. */
  function later(host, ctx, make) {
    const box = ctx.h(host.ownerDocument, 'div', { cls: 'gx-ev' });
    host.appendChild(box);
    let made = null, gone = false;
    const ready = withMock(ctx, (M) => { if (gone) { return null; } made = make(box, M); return made && made.ready; });
    return { target: box, element: box, ready: ready, destroy: () => { gone = true; if (made && made.destroy) { made.destroy(); } box.remove(); } };
  }

  /* ---------------- Connection: a server's own JSON (raw mode), by hand ---------------- */
  const SYMBOLS = ['USDCAD', 'EURUSD', 'GBPUSD', 'USDJPY', 'AUDUSD', 'USDCHF', 'NZDUSD', 'EURGBP', 'EURJPY', 'USDMXN'];
  demos.push(ev({
    id: 'connection', title: 'Connection (a live feed)', about: 'drishti_connection.js',
    summary: 'A connection to a server over Server-Sent Events or a WebSocket: reconnect with back-off, a heartbeat, resume from the last event id; a subscription is a feed, with backpressure into the grid. Here a server\'s own JSON (raw mode) by hand: Push sends a message, Drop cuts the line.',
    extra: [{ name: 'heartbeatMs', control: { kind: 'select', values: ['0', '2000'] }, desc: 'Silence allowed before the connection is taken as dead (0: no watch).' }],
    values: { heartbeatMs: '0' }, examples: [{ title: 'A manual feed into a grid', values: {} }],
    mount: function (host, values, ctx) {
      const doc = host.ownerDocument, EV = ctx.E.events, h = ctx.h;
      let es = null, n = 0;
      class ManualEventSource extends root.EventTarget {
        constructor(url) { super(); this.url = url; this.readyState = 0; es = this; setTimeout(() => { if (this.readyState !== 2) { this.readyState = 1; this.dispatchEvent(new root.Event('open')); } }, 5); }
        close() { this.readyState = 2; }
      }
      const rows = SYMBOLS.map((s, i) => ({ ID: s, BID: 1 + i / 10, TICKS: 0 }));
      const state = h(doc, 'span', { cls: 'gx-note', attrs: { 'aria-live': 'polite' } });
      const push = () => {
        if (!es || es.readyState !== 1) { ctx.log('note', 'the line is down: the connection is reconnecting'); return; }
        const r = rows[(n++) % rows.length];
        r.BID = Math.round((r.BID + (Math.random() - 0.5) / 50) * 1e4) / 1e4;
        r.TICKS++;
        es.dispatchEvent(new root.MessageEvent('message', { data: JSON.stringify({ type: 'transaction', update: [Object.assign({}, r)] }), lastEventId: String(n) }));
        ctx.log('push', { id: n, row: r.ID });
      };
      const bar = toolbar(ctx, doc, 'Feed', [['Push', 'push', push], ['Drop the line', 'drop', () => { if (es) { es.readyState = 2; es.dispatchEvent(new root.Event('error')); } }]], [state]);
      host.appendChild(bar);
      const g = doc.createElement('drishti-grid');
      g.setAttribute('density', 'terminal');
      host.appendChild(g);
      g.options = { columns: [{ field: 'ID', header: 'Symbol', width: 90 }, { field: 'BID', header: 'Bid', type: 'number', format: 'number4' }, { field: 'TICKS', header: 'Updates', type: 'number' }],
        rows: rows.map((r) => Object.assign({}, r)), getRowId: (r) => r.ID, title: 'Live (a manual feed)', checkboxes: false, live: { asyncWaitMs: 20, flashMs: 600 } };
      const client = EV.connect('/gallery/live/rows', { protocol: 'raw', transport: 'sse', env: { EventSource: ManualEventSource }, heartbeatMs: Number(values.heartbeatMs) || 0,
        backoff: { baseMs: 200, maxMs: 1000, jitter: false } });
      client.on('$events.state', (m) => { state.textContent = 'state: ' + m.data.state + (m.data.waitMs ? ' (again in ' + m.data.waitMs + ' ms)' : ''); ctx.log('state', m.data.state); });
      const pipe = client.subscribe('feed', null, { deliver: 'frame' }).toGrid(g.grid, { getRowId: (r) => r.ID });
      return { target: host, element: g, push: push, ready: Promise.resolve(), destroy: () => { pipe.stop(); client.destroy(); g.grid.destroy(); g.remove(); bar.remove(); } };
    },
    code: () => ({ js: IMPORT + "\nconst events = connect('https://example.org/feed', { protocol: 'raw' });   // a server's own JSON over SSE; wss:// for a WebSocket\nevents.on('$events.state', (m) => console.log(m.data.state));\nevents.subscribe('feed', null, { deliver: 'frame' }).toGrid(grid, { getRowId: (r) => r.ID });   // at most one batch in the grid at a time" })
  }));

  /* ---------------- Ticker: 2,000 instruments, conflated per frame ---------------- */
  /** The made-up instruments: 2,000 four-letter codes, seeded. */
  function instruments(n) {
    const A = 'ABCDEFGHIJKLMNOPQRSTUVWXYZ', out = [], seen = new Set();
    let s = 7;
    const rnd = () => { s = (s * 16807) % 2147483647; return s / 2147483647; };
    while (out.length < n) {
      const code = [0, 1, 2, 3].map(() => A[Math.floor(rnd() * 26)]).join('');
      if (!seen.has(code)) { seen.add(code); out.push({ ID: code, BID: Math.round((10 + rnd() * 190) * 100) / 100 }); }
    }
    return out;
  }

  /** Frame times from requestAnimationFrame: the last n intervals, the 95th percentile. */
  function frameMeter(win, n) {
    const times = [];
    let last = 0, id = 0, on = true;
    const step = (t) => { if (!on) { return; } if (last) { times.push(t - last); if (times.length > n) { times.shift(); } } last = t; id = win.requestAnimationFrame(step); };
    id = win.requestAnimationFrame(step);
    return {
      p95: () => { if (times.length < 10) { return null; } const s = times.slice().sort((a, b) => a - b); return s[Math.min(s.length - 1, Math.floor(s.length * 0.95))]; },
      stop: () => { on = false; win.cancelAnimationFrame(id); }
    };
  }

  demos.push(ev({
    id: 'events-ticker', title: 'Ticker', about: 'drishti_flow.js',
    summary: '2,000 made-up instruments ticking into a grid with flashing cells, conflated by key and delivered once per animation frame: the grid gets at most one batch a frame however fast the server sends. The frame time and the drop counts are live.',
    extra: [{ name: 'rate', control: { kind: 'select', values: ['10000', '1000', '100'] }, desc: 'Messages a second the mock server sends.' },
      { name: 'deliver', control: { kind: 'select', values: ['frame', 'now', '250'] }, desc: 'Delivery: once per animation frame, at once, or at most every 250 ms.' }],
    values: { rate: '10000', deliver: 'frame' }, examples: [{ title: '10,000 a second, once a frame', values: {} }, { title: '100 a second, at once', values: { rate: '100', deliver: 'now' } }],
    mount: function (host, values, ctx) {
      return later(host, ctx, (box, M) => {
        const doc = box.ownerDocument, win = doc.defaultView, h = ctx.h, EV = ctx.E.events;
        const list = ctx.memo('ticker-instruments', () => instruments(2000));
        const server = new M.MockServer({ address: null, heartbeatMs: 30000 });
        const events = EV.connect(server, { bus: new ctx.E.core.EventBus() });
        const c = chrome(ctx, doc, events);
        const figures = h(doc, 'span', { cls: 'gx-note gx-ev-figures', attrs: { 'data-figures': '' } });
        const g = doc.createElement('drishti-grid');
        g.setAttribute('density', 'terminal');
        g.classList.add('gx-ev-grid');
        box.append(c.bar, h(doc, 'div', { cls: 'gx-demo-bar' }, [figures]), g);
        g.options = { columns: [{ field: 'ID', header: 'Instrument', width: 100 }, { field: 'BID', header: 'Bid', type: 'number', format: 'number2' },
          { field: 'MOVE', header: 'Move', type: 'number', format: 'number2' }, { field: 'N', header: 'Updates', type: 'number' }],
        rows: list.map((r) => ({ ID: r.ID, BID: r.BID, MOVE: 0, N: 0 })), getRowId: (r) => r.ID, title: 'Ticker (mock server)', checkboxes: false, live: { asyncWaitMs: 0, flashMs: 400 } };
        const state = new Map(list.map((r) => [r.ID, { BID: r.BID, N: 0 }]));
        const rate = Number(values.rate) || 1000, perTick = Math.max(1, Math.round(rate / 100));
        let sent = 0, seen = 0;
        const stopTicks = server.every(10, () => {
          for (let i = 0; i < perTick; i++) {
            const r = list[Math.floor(Math.random() * list.length)], s = state.get(r.ID);
            const move = Math.round((Math.random() - 0.5) * 20) / 100;
            s.BID = Math.round((s.BID + move) * 100) / 100;
            s.N++;
            server.publish('ticks.' + r.ID, { type: 'transaction', update: [{ ID: r.ID, BID: s.BID, MOVE: move, N: s.N }] }, { key: r.ID, stream: 'ticks', retain: false });
            sent++;
          }
        });
        const deliver = values.deliver === 'frame' || values.deliver === 'now' ? values.deliver : { everyMs: Number(values.deliver) || 250 };
        const sub = events.subscribe('ticks.>', null, { conflate: true, deliver: deliver });
        const counter = events.on('ticks.>', () => { seen++; });
        const pipe = sub.toGrid(g.grid, { getRowId: (r) => r.ID });
        const meter = frameMeter(win, 120);
        const show = () => {
          const m = events.metrics(), p = meter.p95(), d = m.drops || {};
          const dropped = Object.keys(d).map((k) => k + ' ' + d[k].toLocaleString('en-US')).join(', ') || 'none';
          figures.textContent = 'sent ' + sent.toLocaleString('en-US') + ' · arrived ' + seen.toLocaleString('en-US') + ' · frame time p95 ' + (p === null ? '…' : p.toFixed(1) + ' ms') +
            ' · dropped: ' + dropped + ' · rows updated in the grid ' + pipe.sink.stats.applied.toLocaleString('en-US');
          figures.setAttribute('data-sent', String(sent));
          figures.setAttribute('data-p95', p === null ? '' : p.toFixed(2));
        };
        const timer = win.setInterval(show, 500);
        show();
        ctx.log('ticker', { rate: rate, deliver: values.deliver });
        return { ready: sub.ready, destroy: () => { win.clearInterval(timer); meter.stop(); stopTicks(); counter(); pipe.stop(); c.destroy(); events.destroy(); server.close(); g.grid.destroy(); } };
      });
    },
    code: (values) => ({ js: IMPORT + MOCK + "\nconst server = new MockServer();\nserver.every(10, () => server.publish('ticks.' + id, { type: 'transaction', update: [row] }, { key: id }));   // " + values.rate + " a second\nconst events = connect(server);   // a real server: connect('wss://example.org/events')\nconst sub = events.subscribe('ticks.>', null, { conflate: true, deliver: " + (values.deliver === 'frame' || values.deliver === 'now' ? "'" + values.deliver + "'" : '{ everyMs: ' + values.deliver + ' }') + " });\nsub.toGrid(grid, { getRowId: (r) => r.ID });   // the latest value per key, one batch a frame\nstatusBadge(document.getElementById('badge'), events);" })
  }));

  /* ---------------- Chat: publish, subscribe, request and reply ---------------- */
  demos.push(ev({
    id: 'events-chat', title: 'Chat', about: 'drishti_events-hub.js',
    summary: 'Two people on one room: publish and subscribe on chat.lobby, and a request ("who is here?") answered by the server after a while, with a timeout and a Cancel button that tells the server to stop.',
    extra: [{ name: 'answerMs', control: { kind: 'select', values: ['800', '3000'] }, desc: 'How long the server takes to answer "who is here?" (the request\'s timeout is 2 s).' }],
    values: { answerMs: '800' }, examples: [{ title: 'Answered in time', values: {} }, { title: 'Too slow: the request times out', values: { answerMs: '3000' } }],
    mount: function (host, values, ctx) {
      return later(host, ctx, (box, M) => {
        const doc = box.ownerDocument, h = ctx.h, EV = ctx.E.events, win = doc.defaultView;
        const server = new M.MockServer({ address: null });
        const answerMs = Number(values.answerMs) || 800;
        server.reply('chat.who', (m) => new Promise((resolve, reject) => {
          const t = win.setTimeout(() => resolve({ here: ['Ann', 'Ben'] }), answerMs);
          if (m.signal) { m.signal.addEventListener('abort', () => { win.clearTimeout(t); reject(new Error('cancelled')); }); }
        }));
        const people = ['Ann', 'Ben'].map((name) => {
          const events = EV.connect(server, { bus: new ctx.E.core.EventBus(), source: name });
          const said = lines(ctx, doc, name + '\'s room', 10);
          const off = events.on('chat.lobby', (m) => said.add(m.data.from + ': ' + m.data.text));
          const input = h(doc, 'input', { cls: 'gx-ev-input', attrs: { type: 'text', 'aria-label': name + '\'s message', value: 'Hello from ' + name } });
          const send = () => { const text = input.value.trim(); if (!text) { return; } events.publish('chat.lobby', { from: name, text: text }); ctx.log('publish', { from: name, text: text }); };
          input.addEventListener('keydown', (e) => { if (e.key === 'Enter') { send(); } });
          const pane = h(doc, 'section', { cls: 'gx-ev-pane', attrs: { 'aria-label': name, 'data-person': name } }, [h(doc, 'h3', { text: name }), said.el,
            h(doc, 'div', { cls: 'gx-demo-bar' }, [input, h(doc, 'button', { cls: 'gx-btn', text: 'Send', attrs: { type: 'button', 'data-action': 'send' }, on: { click: send } })])]);
          return { name: name, events: events, pane: pane, off: off };
        });
        const ann = people[0].events;
        const answer = h(doc, 'output', { cls: 'gx-note gx-ev-answer', attrs: { 'aria-live': 'polite' } });
        let ask = null;
        const who = () => {
          if (ask) { ask.abort(); }
          ask = new win.AbortController();
          answer.textContent = 'Asking the server…';
          ann.request('chat.who', {}, { timeoutMs: 2000, signal: ask.signal }).then((r) => { answer.textContent = 'Here: ' + r.data.here.join(', '); ctx.log('reply', r.data); },
            (e) => { answer.textContent = (e.code === 'timeout' ? 'No answer in 2 s (timed out)' : e.code === 'aborted' ? 'Cancelled: the server was told to stop' : 'Failed: ' + (e.detail || e.message)); ctx.log('request', e.code); });
        };
        const c = chrome(ctx, doc, ann);
        box.append(c.bar, toolbar(ctx, doc, 'Request', [['Who is here?', 'who', who], ['Cancel', 'cancel', () => { if (ask) { ask.abort(); } }]], [answer]),
          h(doc, 'div', { cls: 'gx-ev-panes' }, people.map((p) => p.pane)));
        return { ready: Promise.resolve(), destroy: () => { if (ask) { ask.abort(); } c.destroy(); people.forEach((p) => { p.off(); p.events.destroy(); }); server.close(); } };
      });
    },
    code: () => ({ js: IMPORT + "\nconst events = connect('wss://example.org/events');\nevents.on('chat.lobby', (m) => show(m.data.from + ': ' + m.data.text));\nevents.publish('chat.lobby', { from: 'Ann', text: 'Hello' });\n\nconst ask = new AbortController();   // cancel.onclick = () => ask.abort()\nconst who = await events.request('chat.who', {}, { timeoutMs: 2000, signal: ask.signal });\n\n// a server (or another page) answers:\nevents.reply('chat.who', () => ({ here: ['Ann', 'Ben'] }));" })
  }));

  /* ---------------- Across tabs: two Events joined as two tabs ---------------- */
  const COLOURS = ['Red', 'Green', 'Blue', 'Amber'];
  demos.push(ev({
    id: 'events-tabs', title: 'Across tabs', about: 'drishti_tabs.js',
    summary: 'Two panes in one page joined as two tabs (MockTabs: the in-memory BroadcastChannel and Web Locks of the tests): a selection made in one appears in the other through the tabs scope. Open a real second tab of this page to see the same between real tabs, where the browser allows it.',
    values: {}, examples: [{ title: 'A selection shared across tabs', values: {} }],
    mount: function (host, values, ctx) {
      return later(host, ctx, (box, M) => {
        const doc = box.ownerDocument, h = ctx.h, EV = ctx.E.events, win = doc.defaultView;
        const tabs = new M.MockTabs({ worker: false });
        const panes = ['First tab', 'Second tab'].map((name, i) => {
          const events = EV.createEvents({ env: tabs.tab(), namespace: 'gallery-tabs', bus: new ctx.E.core.EventBus(), source: 'tab-' + (i + 1) });
          const unshare = events.share('ui.selection.>', { to: 'tabs' });
          const chosen = h(doc, 'output', { cls: 'gx-note', attrs: { 'aria-live': 'polite', 'data-chosen': '' }, text: 'Nothing chosen yet' });
          const off = events.on('ui.selection.colour', (m) => { chosen.textContent = 'Chosen: ' + m.data.ids.join(', ') + (m.scope === 'tabs' ? ' (from the other tab)' : ' (here)'); });
          const btns = COLOURS.map((c) => h(doc, 'button', { cls: 'gx-btn', text: c, attrs: { type: 'button', 'data-colour': c },
            on: { click: () => { events.publish('ui.selection.colour', { ids: [c] }); ctx.log('selection', { tab: name, colour: c }); } } }));
          const pane = h(doc, 'section', { cls: 'gx-ev-pane', attrs: { 'aria-label': name, 'data-tab': String(i + 1) } }, [h(doc, 'h3', { text: name }),
            h(doc, 'div', { cls: 'gx-demo-bar', attrs: { role: 'group', 'aria-label': 'Choose a colour in the ' + name.toLowerCase() } }, btns), chosen]);
          return { events: events, pane: pane, off: () => { off(); unshare(); } };
        });
        /* a real tab of this page (a real BroadcastChannel): what another tab chooses shows here */
        const real = EV.createEvents({ namespace: 'gallery-real-tabs', bus: new ctx.E.core.EventBus() });
        const realOut = h(doc, 'output', { cls: 'gx-note', attrs: { 'aria-live': 'polite' } });
        const realOff = real.on('ui.selection.colour', (m) => { realOut.textContent = 'Your other tab chose ' + m.data.ids.join(', '); });
        real.share('ui.selection.>', { to: 'tabs' });
        const can = typeof win.BroadcastChannel === 'function' && win.location.protocol !== 'file:';
        const realBar = h(doc, 'div', { cls: 'gx-demo-bar', attrs: { role: 'group', 'aria-label': 'Real tabs' } }, [
          h(doc, 'a', { cls: 'gx-linkbtn', text: 'Open a real second tab', attrs: { href: win.location.href, target: '_blank', rel: 'noopener' } }),
          h(doc, 'button', { cls: 'gx-btn', text: 'Choose Blue in every real tab', attrs: { type: 'button', 'data-action': 'real' }, on: { click: () => real.publish('ui.selection.colour', { ids: ['Blue'] }) } }),
          realOut,
          h(doc, 'span', { cls: 'gx-note', text: can ? 'Real tabs of this page share selections through a BroadcastChannel.' : 'Opened from the file system: some browsers keep such pages apart, so real tabs may not hear each other here.' })]);
        box.append(h(doc, 'div', { cls: 'gx-ev-panes' }, panes.map((p) => p.pane)), realBar);
        return { ready: Promise.resolve(), destroy: () => { panes.forEach((p) => { p.off(); p.events.destroy(); }); realOff(); real.destroy(); } };
      });
    },
    code: () => ({ js: "import { createEvents } from '/elements/drishti-events/src/drishti_index.js';\n\nconst events = createEvents({ namespace: 'my-app' });   // one namespace per application and signed-in person\nevents.share('ui.selection.>', { to: 'tabs' });   // selections reach the other tabs, and still this page\nevents.on('ui.selection.colour', (m) => show(m.data.ids));\nevents.publish('ui.selection.colour', { ids: ['Blue'] });" })
  }));

  /* ---------------- Offline: the queue, kept across a reload ---------------- */
  demos.push(ev({
    id: 'events-offline', title: 'Offline', about: 'drishti_outbox.js',
    summary: 'Switch the network off, make five edits, watch them wait in the queue (kept on disk: IndexedDB), reload the page, and they leave in order, once, when the connection opens. The badge says how many changes wait.',
    values: {}, examples: [{ title: 'Edits that wait', values: {} }],
    mount: function (host, values, ctx) {
      return later(host, ctx, (box, M) => {
        const doc = box.ownerDocument, h = ctx.h, EV = ctx.E.events, win = doc.defaultView;
        const server = new M.MockServer({ address: null });
        const events = EV.connect(server, { bus: new ctx.E.core.EventBus(), namespace: 'gallery-offline', outbox: { persist: true }, backoff: { baseMs: 300, maxMs: 1500 } });
        const c = chrome(ctx, doc, events);
        const heard = lines(ctx, doc, 'What the server took, in order', 12);
        const off = events.on('notes.>', (m) => heard.add('#' + m.data.n + ' ' + m.data.text + (m.data.at !== stamp ? ' (made before the reload)' : '')));
        const waiting = h(doc, 'output', { cls: 'gx-note', attrs: { 'data-waiting': '' } });
        let n = 0;
        const stamp = String(Date.now());
        const edit = () => { n++; events.publish('notes.edit', { n: n, text: 'edit ' + n, at: stamp }, { queue: 'persist' }).catch(() => undefined); ctx.log('edit', n); };
        const five = () => { for (let i = 0; i < 5; i++) { edit(); } };
        const tick = win.setInterval(() => { const m = events.metrics(); waiting.textContent = ((m.outbox && m.outbox.count) || 0) + ' waiting'; }, 250);
        const net = toolbar(ctx, doc, 'Network', [['Network off', 'off', () => { server.partition(1e12); ctx.log('network', 'off'); }],
          ['Network on', 'on', () => { server.partition(0); events.reconnect(); ctx.log('network', 'on'); }],
          ['Make five edits', 'edits', five], ['Reload the page', 'reload', () => win.location.reload()]], [waiting]);
        box.append(c.bar, net, h(doc, 'h3', { text: 'What the server took' }), heard.el);
        return { ready: Promise.resolve(), destroy: () => { win.clearInterval(tick); off(); c.destroy(); events.destroy(); server.close(); } };
      });
    },
    code: () => ({ js: IMPORT + "\nconst events = connect('wss://example.org/events', { namespace: 'my-app', outbox: { persist: true } });\nevents.publish('notes.edit', { text: 'edit 1' }, { queue: 'persist' });   // waits while offline, survives a reload, sent once in order\nstatusBadge(document.getElementById('badge'), events);   // \"Offline: 5 changes waiting\"" })
  }));

  /* ---------------- Unreliable network: the mock's faults ---------------- */
  demos.push(ev({
    id: 'events-faults', title: 'Unreliable network', about: 'drishti_sequence.js',
    summary: 'A numbered stream, and buttons for the mock server\'s faults: drop the connection, lose 3 (a gap the client fills by replay), duplicate 3 (each delivered once), reorder 3 (put back in order), slow (a gap the server owns), restart the server (a new epoch: the client resyncs). The badge, the gaps and the replays as they happen.',
    values: {}, examples: [{ title: 'Faults on purpose', values: {} }],
    mount: function (host, values, ctx) {
      return later(host, ctx, (box, M) => {
        const doc = box.ownerDocument, h = ctx.h, EV = ctx.E.events;
        const server = new M.MockServer({ address: null });
        const events = EV.connect(server, { bus: new ctx.E.core.EventBus(), backoff: { baseMs: 200, maxMs: 1000 } });
        const c = chrome(ctx, doc, events);
        let k = 0;
        const stop = server.every(400, () => { k++; server.publish('counter.value', { n: k }, { stream: 'counter' }); });
        const got = lines(ctx, doc, 'Arrived, in order', 14);
        const said = lines(ctx, doc, 'What the library said', 10);
        const off = [events.on('counter.>', (m) => got.add('seq ' + m.seq + ': ' + m.data.n + (m.replayed ? ' (replayed)' : ''), m.replayed ? 'gx-ev-replayed' : null)),
          events.on('$events.state', (m) => said.add('state: ' + m.data.state + (m.data.reason ? ' (' + m.data.reason + ')' : ''))),
          events.on('$events.gap', (m) => said.add('gap ' + m.data.from + '–' + m.data.to + (m.data.recoverable ? ': replaying' : ': lost'))),
          events.on('$events.subscription', (m) => { said.add('subscription ' + m.data.pattern + ': ' + m.data.state + (m.data.reason ? ' (' + m.data.reason + ')' : '')); })];
        const fault = (label, fn) => () => { fn(); said.add('fault: ' + label); ctx.log('fault', label); };
        box.append(c.bar, toolbar(ctx, doc, 'Faults', [['Drop', 'drop', fault('drop', () => server.drop())], ['Lose 3', 'lose', fault('lose 3', () => server.lose(3))],
          ['Duplicate 3', 'duplicate', fault('duplicate 3', () => server.duplicate(3))], ['Reorder 3', 'reorder', fault('reorder 3', () => server.reorder(3))],
          ['Slow', 'slow', fault('slow', () => server.slow(3))], ['Restart the server', 'restart', fault('restart', () => { server.restart(); k = 0; })]]),
        h(doc, 'div', { cls: 'gx-ev-panes' }, [h(doc, 'section', { cls: 'gx-ev-pane', attrs: { 'aria-label': 'Arrived' } }, [h(doc, 'h3', { text: 'Arrived' }), got.el]),
          h(doc, 'section', { cls: 'gx-ev-pane', attrs: { 'aria-label': 'What the library said' } }, [h(doc, 'h3', { text: 'What the library said' }), said.el])]));
        const sub = events.subscribe('counter.>', null);
        return { ready: sub.ready, destroy: () => { stop(); off.forEach((f) => f()); sub.off(); c.destroy(); events.destroy(); server.close(); } };
      });
    },
    code: () => ({ js: IMPORT + MOCK + "\nconst server = new MockServer();\nconst events = connect(server);\nevents.on('counter.>', (m) => show(m.seq, m.data, m.replayed));\nevents.on('$events.gap', (m) => note(m.data));\nserver.lose(3);      // a gap: the client asks for a replay\nserver.duplicate(3); // each delivered once\nserver.restart();    // a new epoch: the client resyncs" })
  }));

  /* ---------------- Why: a message that never arrives ---------------- */
  demos.push(ev({
    id: 'events-why', title: 'Why', about: 'drishti_trace.js',
    summary: 'The page listens to weather.town.* but the server publishes weather.city.Paris; a second listener drops what it is not asked for. explain() walks the steps (a listener matched, the route, the subscription, the connection, arrival, validity, kept, the listener ran) and marks the first that failed.',
    extra: [{ name: 'topic', control: { kind: 'select', values: ['weather.city.Paris', 'weather.town.Lyon', 'weather.town.Nice'] }, desc: 'The topic asked about.' }],
    values: { topic: 'weather.city.Paris' }, examples: [{ title: 'No listener matches', values: {} }, { title: 'Delivered', values: { topic: 'weather.town.Lyon' } }, { title: 'Filtered out', values: { topic: 'weather.town.Nice' } }],
    mount: function (host, values, ctx) {
      return later(host, ctx, (box, M) => {
        const doc = box.ownerDocument, h = ctx.h, EV = ctx.E.events, C = ctx.E.core;
        const server = new M.MockServer({ address: null });
        const events = EV.connect(server, { bus: new C.EventBus() });
        const c = chrome(ctx, doc, events);
        const got = lines(ctx, doc, 'Arrived', 8);
        const sub = events.subscribe('weather.town.*', (m) => got.add(m.topic + ': ' + m.data.sky), { where: (m) => m.topic !== 'weather.town.Nice' });
        const out = h(doc, 'div', { cls: 'gx-explain gx-ev-why', attrs: { 'data-why': '' } });
        const why = () => {
          const r = events.explain({ topic: values.topic });
          C.renderExplain(out, { row: 0, col: 0, loading: false, result: r, error: null }, 'card', { prefix: 'dg' });
          out.setAttribute('data-failed', r.failed ? r.failed.label : '');
          ctx.log('explain', r.failed ? r.failed.label : 'delivered');
        };
        const publish = () => ['weather.city.Paris', 'weather.town.Lyon', 'weather.town.Nice'].forEach((t, i) => server.publish(t, { sky: ['rain', 'sun', 'cloud'][i] }));
        box.append(c.bar, toolbar(ctx, doc, 'Why', [['Publish the three', 'publish', publish], ['Why (' + values.topic + ')?', 'why', why]]), got.el, out);
        const ready = sub.ready.then(() => { publish(); return new Promise((r) => doc.defaultView.setTimeout(r, 30)); }).then(why);
        return { ready: ready, destroy: () => { sub.off(); c.destroy(); events.destroy(); server.close(); } };
      });
    },
    code: (values) => ({ js: IMPORT + "\nconst events = connect('wss://example.org/events');\nevents.subscribe('weather.town.*', show, { where: (m) => m.topic !== 'weather.town.Nice' });\nconst why = events.explain({ topic: '" + values.topic + "' });   // the steps, the first failed one marked\nDrishti.elements.core.renderExplain(el, { result: why }, 'card');" })
  }));

  /* ---------------- Inspector: docked ---------------- */
  demos.push(ev({
    id: 'events-inspector', title: 'Inspector', about: 'drishti_inspector.js',
    summary: 'The inspector docked in the page: Messages (filter by a topic pattern or words; / filters, Space pauses, Enter opens the message under focus, Escape closes), Subscriptions, Connection, Queue and Why. It is the library\'s own part, loaded when opened, drawn with drishti-grid.',
    values: {}, examples: [{ title: 'Docked', values: {} }],
    mount: function (host, values, ctx) {
      return later(host, ctx, (box, M) => {
        const doc = box.ownerDocument, h = ctx.h, EV = ctx.E.events;
        const server = new M.MockServer({ address: null });
        const events = EV.connect(server, { bus: new ctx.E.core.EventBus(), recorder: { payloads: true } });
        let k = 0;
        const stop = server.every(500, () => { k++; server.publish('prices.fx.' + SYMBOLS[k % 4], { bid: 1 + (k % 50) / 100 }, { key: SYMBOLS[k % 4], stream: 'prices.fx' }); });
        const sub = events.subscribe('prices.fx.*', () => undefined);
        const dock = h(doc, 'div', { cls: 'gx-ev-dock', attrs: { 'data-dock': '' } });
        const badge = h(doc, 'span', { cls: 'gx-ev-badge' });
        const stopBadge = EV.statusBadge(badge, events);
        box.append(h(doc, 'div', { cls: 'gx-demo-bar' }, [badge]), dock);
        let ins = null;
        const ready = sub.ready.then(() => events.inspect(dock, { refreshMs: 500 })).then((i) => { ins = i; });
        return { ready: ready, destroy: () => { if (ins) { ins.close(); } stop(); sub.off(); stopBadge(); events.destroy(); server.close(); } };
      });
    },
    code: () => ({ js: IMPORT + "\nconst events = connect('wss://example.org/events');\nevents.inspect(document.getElementById('dock'));   // into a dock's panel; inspect() alone opens a drawer\n// or from the command palette: palette.register(inspectorCommand(events))" })
  }));
}(typeof window !== 'undefined' ? window : globalThis));
