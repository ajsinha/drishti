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
/* A painted view's live stream over drishti-events (paint's option live). The view's topic (live.topic, default "views.<the view's id>")
   is subscribed through the page's Events (live.events) or one connected to live.url; drishti-events is loaded only then. A message's
   kind says what it carries: "view" (a whole ViewModel, painted again), "view.frame" (a frame of patches), "view.piece" (one patch).
   drishti-events orders the stream, drops what was heard and, after a reconnect, asks for what was missed; a gap it cannot fill is a
   resync notice, and the view is read again (live.resync(), else the address it was painted from) and painted. A plain stream (a
   server that speaks no protocol: Server-Sent Events named "view" and "frame") is read through drishti-events' raw mode (live.raw).
   The connection's state is a chip in the view's status line; while the line is down the view is marked stale. */

/**
 * @typedef {Object} LiveOptions
 * @property {Object} [events] the page's Events (drishti-events) to subscribe through
 * @property {string} [url] the server's events address, connected for this view when no events is given
 * @property {string} [topic] the view's topic (default "views.<ref.id>")
 * @property {boolean} [raw=false] the address is a plain stream: SSE events named "view" and "frame", no protocol, nothing replayed
 * @property {function(): *} [resync] the ViewModel again (a ViewModel, an address or a promise of either), after a gap that could not be
 *   filled; default the address the view was painted from
 * @property {Object} [connect] more options for drishti-events' connect (auth, headers, namespace, share)
 */

/** The message kinds a view's stream carries. */
export const LIVE_KINDS = Object.freeze(['view', 'view.frame', 'view.piece']);

/** The connection states drawn as a chip in the status line (and the view marked stale), with their words' names. */
const DOWN = { connecting: 'linkConnecting', reconnecting: 'linkReconnecting', offline: 'linkOffline', refused: 'linkRefused', closed: 'linkClosed' };

/** One view's subscription to its stream. */
export class LiveLink {
  /**
   * @param {Object} handle the view's handle (ViewHandle)
   * @param {LiveOptions} o
   */
  constructor(handle, o) {
    this.handle = handle;
    this.o = Object.assign({}, o);
    this.offs = [];
    this.own = null;
    this.stopped = false;
    this.state = null;
    const vm = handle.viewModel || {};
    this.topic = typeof this.o.topic === 'string' && this.o.topic ? this.o.topic : 'views.' + String((vm.ref && vm.ref.id) || 'view');
    /** @type {Promise<void>} the subscription is made */
    this.ready = this.events().then((ev) => { if (!this.stopped) { this.subscribe(ev); } });
  }

  /** @private the Events to subscribe through */
  events() {
    if (this.o.events) { return Promise.resolve(this.o.events); }
    if (typeof this.o.url !== 'string' || !this.o.url) { return Promise.reject(new Error('live: give events (the page\'s Events) or url (the server\'s events address)')); }
    return this.handle.library('drishti-events').then((EV) => {
      const topic = this.topic;
      const opts = Object.assign({}, this.o.connect || {}, this.o.raw ? { protocol: 'raw', transport: 'sse', events: ['view', 'frame'], topicOf: () => topic,
        kindOf: (v, meta) => (meta && meta.event === 'view' ? 'view' : 'view.frame') } : {});
      this.own = EV.connect(this.o.url, opts);
      return this.own;
    });
  }

  /** @private */
  subscribe(ev) {
    this.ev = ev;
    const sub = ev.subscribe(this.topic, (m) => this.message(m));
    this.offs.push(() => { if (sub && typeof sub.off === 'function') { sub.off(); } });
    this.offs.push(ev.on('$events.state', (m) => this.setState(m.data && m.data.state)));
    const s = ev.metrics && ev.metrics();
    if (s && s.server && s.server.state) { this.setState(s.server.state); }
  }

  /** @private one message of the stream */
  message(m) {
    if (this.stopped || !m) { return; }
    const h = this.handle;
    if (m.kind === 'resync') { this.resync(); return; }
    if (m.kind === 'view') { h.patcher.lastSeq = null; h.update(m.data); return; }
    if (m.kind === 'view.piece') { h.patch([m.data]); return; }
    h.patch(m.data);
  }

  /**
   * Reads the view again and paints it (a gap the stream could not fill, or a new epoch).
   * @returns {void}
   */
  resync() {
    const h = this.handle, src = typeof this.o.resync === 'function' ? this.o.resync() : h.src;
    h.stats.resyncs = (h.stats.resyncs || 0) + 1;
    if (src === undefined || src === null) { return; }
    h.patcher.lastSeq = null;
    h.update(src);
  }

  /** @private the connection's state on the view */
  setState(state) {
    if (!state || state === this.state) { return; }
    this.state = state;
    this.handle.setLink(DOWN[state] ? DOWN[state] : null);
  }

  /**
   * Stops the subscription (and the connection it made).
   * @returns {void}
   */
  stop() {
    this.stopped = true;
    this.offs.forEach((f) => { try { f(); } catch (e) { /* stopped */ } });
    this.offs = [];
    if (this.own && typeof this.own.destroy === 'function') { this.own.destroy(); }
  }
}
