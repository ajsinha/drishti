/*
 * Project Drishti · Any data. Any domain. One grammar.
 *
 * Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>.
 * All rights reserved.
 *
 * PROPRIETARY AND CONFIDENTIAL.
 *
 * This file is the confidential and proprietary property of Ashutosh Sinha.
 * Unauthorised copying, use, modification, distribution or disclosure of this
 * file, via any medium, is strictly prohibited except with the express prior
 * written permission of the copyright holder.
 *
 * See the LICENSE file in the root of this repository for the full terms.
 */
/* The browser's ONE live connection to the console (UX-01).

   Browsers open at most six connections to a site over HTTP/1.1. A live connection per tab used them all up with six
   tabs on live views, and the seventh page never loaded. So the tabs of one browser share a single channel
   (GET /api/channel): this hub holds it and multiplexes every tab's subscriptions over it. It runs in a SharedWorker
   (channel.js starts it), or, where a SharedWorker cannot hold an EventSource, in the one tab that holds the
   "drishti-live" Web Lock, relaying to the other tabs over a BroadcastChannel. Both are per origin and per browser
   profile, so the tabs that share it share the cookies the channel was opened with: one browser session.

   Protocol (tab -> hub): {t:'hello', tab, who}, {t:'sub', tab, key}, {t:'unsub', tab, key}, {t:'ping', tab},
   {t:'bye', tab}. (hub -> tab): {t:'hi'} (registered), {t:'ev', key, type, d} (an event of one subscription),
   {t:'error'} (the connection dropped or cannot be opened: it keeps trying), {t:'stale'} (the tab's page belongs to
   another session or server than the browser's current one: it gets nothing more), {t:'who'} (the hub does not know
   the tab: say hello again).

   `who` is the page's session fingerprint (meta drishti-live); the console's `channel` event says whose session the
   connection runs as. Frames are built by the console for that session's user, so they only go to tabs of that
   session. A subscription held by several tabs is subscribed once; a tab joining later is first sent what a fresh
   stream would have opened with (the view's generation, the alerts hello, a deleted entity's last frame, the end). */
(function (root) {
  'use strict';
  var TYPES = ['view', 'frame', 'gone', 'alert', 'row', 'hello'];
  var REPLAY = ['view', 'hello', 'deleted', 'gone'];

  function Hub(opts) {
    this.o = opts || {};
    this.url = this.o.url || '/api/channel';
    this.tabs = {};              // tab id -> {send, who, keys: {key: true}, pending: {key: true}, seen}
    this.keys = {};              // key -> {tabs: {tab id: true}, replay: {view, hello, deleted, gone}}
    this.who = null;             // the session the channel runs (or will run) as
    this.checking = false;       // asking the console which session the browser has now
    this.es = null; this.id = null; this.serverSubs = [];
    this.syncTimer = null; this.connectTimer = null; this.retryTimer = null; this.pruneTimer = null; this.retryMs = 0;
  }

  Hub.prototype.now = function () { return this.o.now ? this.o.now() : Date.now(); };
  Hub.prototype.later = function (fn, ms) { return (this.o.setTimeout || setTimeout)(fn, ms); };
  Hub.prototype.cancel = function (t) { if (t) { (this.o.clearTimeout || clearTimeout)(t); } };
  function size(o) { return Object.keys(o).length; }

  // -- tabs ---------------------------------------------------------------------------------------------------------
  Hub.prototype.receive = function (m, send) {
    if (!m || !m.tab) { return; }
    if (m.t === 'hello') { this.hello(String(m.tab), String(m.who || ''), send); return; }
    var tab = this.tabs[m.tab];
    if (!tab) { if (m.t !== 'bye') { send({ t: 'who' }); } return; }
    tab.seen = this.now();
    if (m.t === 'sub') { this.sub(m.tab, String(m.key)); } else if (m.t === 'unsub') { this.unsub(m.tab, String(m.key)); } else if (m.t === 'bye') { this.drop(m.tab); }
  };

  Hub.prototype.hello = function (id, who, send) {
    var tab = this.tabs[id];
    if (tab && tab.who === who) {                               // known (pruned and back, or a new leader): keys stay
      tab.send = send; tab.seen = this.now();
    } else {
      if (tab) { this.drop(id); }
      this.tabs[id] = { send: send, who: who, keys: {}, pending: {}, seen: this.now() };
    }
    send({ t: 'hi' });
    if (this.who === null) { this.who = who; } else if (who !== this.who) { this.checkWho(); }
    this.pruneLater();
  };

  // A page of another session or server than the channel's: either the cookies changed (signed in again, switched
  // server) and it is the newest page, or it is an old page of the previous session. The console says which session
  // the browser has now; the channel is reopened as that one when it changed, and pages of any other get nothing more.
  Hub.prototype.checkWho = function () {
    var self = this;
    if (this.checking) { return; }
    this.checking = true;
    this.o.fetch(this.url + '/who', { credentials: 'same-origin' })
      .then(function (r) { return r.json(); })
      .then(function (b) {
        self.checking = false;
        var changed = typeof b.who === 'string' && b.who !== self.who;
        if (changed) { self.who = b.who; }
        self.settlePending();
        if (changed) { self.reopen(); } else { self.staleOthers(); }
      })
      .catch(function () {
        self.checking = false;
        Object.keys(self.tabs).forEach(function (id) { if (size(self.tabs[id].pending)) { self.tabs[id].send({ t: 'error' }); } });
        self.later(function () { if (self.anyPending()) { self.checkWho(); } }, 5000);
      });
  };

  Hub.prototype.anyPending = function () {
    return Object.keys(this.tabs).some(function (id) { return size(this.tabs[id].pending) > 0; }, this);
  };

  Hub.prototype.settlePending = function () {
    Object.keys(this.tabs).forEach(function (id) {
      var tab = this.tabs[id], keys = Object.keys(tab.pending);
      if (tab.who !== this.who) { return; }                     // told it is stale below
      tab.pending = {};
      keys.forEach(function (k) { this.sub(id, k); }, this);
    }, this);
  };

  Hub.prototype.sub = function (id, key) {
    var tab = this.tabs[id];
    if (tab.who !== this.who) {
      if (this.checking) { tab.pending[key] = true; } else { tab.send({ t: 'stale' }); }
      return;
    }
    if (tab.keys[key]) { return; }
    tab.keys[key] = true;
    var k = this.keys[key];
    if (!k) {
      this.keys[key] = { tabs: {}, replay: {} };
      this.keys[key].tabs[id] = true;
      this.syncSoon();
      return;
    }
    k.tabs[id] = true;
    // a later tab: what a stream of its own would have started with
    REPLAY.forEach(function (t) {
      if (k.replay[t] !== undefined) { tab.send({ t: 'ev', key: key, type: t === 'deleted' ? 'frame' : t, d: k.replay[t] }); }
    });
  };

  Hub.prototype.unsub = function (id, key) {
    var tab = this.tabs[id], k = this.keys[key];
    if (tab) { delete tab.keys[key]; delete tab.pending[key]; }
    if (!k) { return; }
    delete k.tabs[id];
    if (!size(k.tabs)) { delete this.keys[key]; this.syncSoon(); }
  };

  Hub.prototype.drop = function (id) {
    var tab = this.tabs[id];
    if (!tab) { return; }
    Object.keys(tab.keys).forEach(function (k) { this.unsub(id, k); }, this);
    delete this.tabs[id];
  };

  // A tab that went away without saying so (crashed, killed) stops pinging: its subscriptions are dropped. A tab that
  // was only quiet (hidden and throttled) holds none, and says hello again when it is next shown or pings.
  Hub.prototype.pruneLater = function () {
    var self = this, every = this.o.pruneMs || 45000;
    if (this.pruneTimer) { return; }
    this.pruneTimer = this.later(function () {
      self.pruneTimer = null;
      var cutoff = self.now() - every;
      Object.keys(self.tabs).forEach(function (id) { if (self.tabs[id].seen < cutoff) { self.drop(id); } });
      if (size(self.tabs)) { self.pruneLater(); }
    }, every / 3);
  };

  Hub.prototype.post = function (id, msg) {
    try { this.tabs[id].send(msg); } catch (e) { this.drop(id); }   // a closed port
  };

  Hub.prototype.toTabs = function (key, msg) {
    var k = this.keys[key];
    if (!k) { return; }
    Object.keys(k.tabs).forEach(function (id) { if (this.tabs[id] && this.tabs[id].who === this.who) { this.post(id, msg); } }, this);
  };

  Hub.prototype.toAll = function (msg) {
    Object.keys(this.tabs).forEach(function (id) { if (size(this.tabs[id].keys)) { this.post(id, msg); } }, this);
  };

  // Tabs of another session than the connection's: told once, and their subscriptions dropped.
  Hub.prototype.staleOthers = function () {
    Object.keys(this.tabs).forEach(function (id) {
      var tab = this.tabs[id];
      if (tab.who !== this.who && (size(tab.keys) || size(tab.pending))) {
        tab.pending = {};
        Object.keys(tab.keys).forEach(function (k) { this.unsub(id, k); }, this);
        this.post(id, { t: 'stale' });
      }
    }, this);
  };

  // -- the connection -----------------------------------------------------------------------------------------------
  Hub.prototype.syncSoon = function () {                   // subscriptions arrive together: one change, or one open
    var self = this;
    this.cancel(this.syncTimer);
    this.syncTimer = this.later(function () { self.syncTimer = null; self.sync(); }, this.o.debounceMs === undefined ? 60 : this.o.debounceMs);
  };

  Hub.prototype.wanted = function () { return Object.keys(this.keys).sort(); };

  Hub.prototype.sync = function () {
    var want = this.wanted();
    if (!want.length) { this.close(); return; }
    if (!this.es) { if (!this.retryTimer) { this.open(); } return; }
    if (!this.id) { return; }                                   // the `channel` event will sync
    var add = want.filter(function (k) { return this.serverSubs.indexOf(k) < 0; }, this);
    var remove = this.serverSubs.filter(function (k) { return want.indexOf(k) < 0; });
    if (!add.length && !remove.length) { return; }
    this.serverSubs = want.slice();
    var self = this, id = this.id;
    this.o.fetch(this.url + '/' + encodeURIComponent(id), { method: 'POST', credentials: 'same-origin', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ add: add, remove: remove }) })
      .then(function (r) { if (r.status === 404 && self.id === id) { self.reopen(); } })
      .catch(function () { /* the connection's own reconnect resyncs */ });
  };

  Hub.prototype.open = function () {
    var self = this, keys = this.wanted();
    if (!keys.length || !this.o.EventSource) { return; }
    var es = this.es = new this.o.EventSource(this.url + '?' + keys.map(function (k) { return 's=' + encodeURIComponent(k); }).join('&'));
    this.armConnectTimer();
    es.addEventListener('channel', function (e) {               // on every (re)connection: the channel's id and what it carries
      if (es !== self.es) { return; }
      var m;
      try { m = JSON.parse(e.data); } catch (err) { return; }
      self.cancel(self.connectTimer); self.connectTimer = null;
      self.retryMs = 0;
      self.id = m.d.id;
      self.serverSubs = m.d.subs || [];
      Object.keys(self.keys).forEach(function (k) { self.keys[k].replay = {}; });   // a new stream starts afresh
      if (typeof m.d.who === 'string' && m.d.who !== self.who) {
        self.who = m.d.who;                                     // the cookies changed under us: the console is right
        self.staleOthers();
      }
      self.sync();                                              // anything subscribed since the URL was built
    });
    TYPES.forEach(function (t) {
      es.addEventListener(t, function (e) {
        if (es !== self.es) { return; }
        var m;
        try { m = JSON.parse(e.data); } catch (err) { return; }
        self.event(m.ch, t, m.d);
      });
    });
    es.addEventListener('end', function () {                    // nothing left to stream: do not let the browser reconnect
      if (es !== self.es) { return; }
      self.close();                                             // a new subscription opens a new channel
    });
    es.onerror = function () {
      if (es !== self.es) { return; }
      self.id = null;
      self.toAll({ t: 'error' });
      if (es.readyState === 2) {                                // given up (refused, not an event stream): try again later
        self.es = null;
        self.retry();
      }
    };
  };

  Hub.prototype.event = function (key, type, d) {
    var k = this.keys[key];
    if (!k) { return; }
    if (type === 'view' || type === 'hello' || type === 'gone') { k.replay[type] = d; }
    if (type === 'frame' && d && d.patches) {
      d.patches.forEach(function (p) {
        if (p.op === 'deleted') { k.replay.deleted = { seq: d.seq, generation: d.generation, p99Ms: d.p99Ms, patches: [p] }; }
        if (p.op === 'restored') { delete k.replay.deleted; }
      });
    }
    this.toTabs(key, { t: 'ev', key: key, type: type, d: d });
  };

  // No `channel` event in time (the console is unreachable, or the browser cannot open another connection): say so
  // rather than wait silently. The EventSource keeps trying.
  Hub.prototype.armConnectTimer = function () {
    var self = this;
    this.cancel(this.connectTimer);
    this.connectTimer = this.later(function () {
      self.connectTimer = null;
      if (self.es && !self.id) { self.toAll({ t: 'error' }); self.armConnectTimer(); }
    }, this.o.connectTimeoutMs || 10000);
  };

  Hub.prototype.retry = function () {
    var self = this;
    this.retryMs = Math.min(Math.max(this.retryMs * 2, this.o.retryMs || 2000), this.o.maxRetryMs || 30000);
    this.cancel(this.retryTimer);
    this.retryTimer = this.later(function () { self.retryTimer = null; if (!self.es && self.wanted().length) { self.open(); } }, this.retryMs);
  };

  Hub.prototype.close = function () {
    this.cancel(this.connectTimer); this.connectTimer = null;
    this.cancel(this.retryTimer); this.retryTimer = null;
    if (this.es) { this.es.close(); }
    this.es = null; this.id = null; this.serverSubs = [];
  };

  Hub.prototype.reopen = function () {
    this.close();
    this.staleOthers();
    if (this.wanted().length) { this.open(); }
  };

  root.DrishtiLiveHub = Hub;
  if (typeof module !== 'undefined' && module.exports) { module.exports = Hub; }

  // -- as a SharedWorker: one hub for every tab of this origin in this browser --------------------------------------
  if (typeof root.SharedWorkerGlobalScope !== 'undefined' && root instanceof root.SharedWorkerGlobalScope) {
    var hub = new Hub({ EventSource: root.EventSource, fetch: root.fetch && root.fetch.bind(root) });
    root.onconnect = function (e) {
      var port = e.ports[0];
      port.onmessage = function (m) { hub.receive(m.data, function (out) { port.postMessage(out); }); };
      port.start();
      port.postMessage({ t: 'ready', es: typeof root.EventSource === 'function' });   // no EventSource here: the tabs elect a leader instead
    };
  }
})(typeof self !== 'undefined' ? self : this);
