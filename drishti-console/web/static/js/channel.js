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
/* One live connection per BROWSER (UX-01). Browsers allow six connections to a site over HTTP/1.1; a connection per
   tab used them up with six tabs on live views, after which no other page of the site loaded at all. Views, the alerts
   bell, monitors and every workspace pane subscribe here (panes through their parent window); every tab's
   subscriptions go to one hub (live-hub.js) that holds the browser's single channel to the console:

     1. a SharedWorker (one per origin and browser profile), when it can hold an EventSource;
     2. otherwise the tab holding the "drishti-live" Web Lock, relaying to the others over a BroadcastChannel (when it
        closes, the next tab takes over);
     3. otherwise (very old browsers) a hub inside this tab.

   A page never waits for any of this: it loads and works without a live connection, and says "Reconnecting…" when
   the connection cannot be had. A tab hidden for a while gives its subscriptions back and takes them again when
   shown (views then repaint from fresh data). */
(function () {
  'use strict';
  // a workspace pane shares the page's channel
  if (window.parent !== window) {
    try {
      if (window.parent.DrishtiChannel) {
        var parent = window.parent.DrishtiChannel;
        window.DrishtiChannel = {
          subscribe: function (key, handlers) {
            var off = parent.subscribe(key, handlers);
            window.addEventListener('pagehide', off);
            return off;
          },
          transport: function () { return parent.transport(); }
        };
        return;
      }
    } catch (e) { /* another origin: open our own */ }
  }
  var HIDDEN_GRACE_MS = 10000, PING_MS = 10000, WORKER_WAIT_MS = 3000, HI_WAIT_MS = 5000;
  var me = document.currentScript;
  var HUB_URL = me && me.src ? me.src.replace(/channel\.js(\?|$)/, 'live-hub.js$1') : '/static/js/live-hub.js';
  var meta = document.querySelector('meta[name="drishti-live"]');
  var WHO = meta ? meta.getAttribute('content') || '' : '';
  var TAB = Math.random().toString(36).slice(2) + Date.now().toString(36);
  var subs = {};                       // key -> [handlers]
  var link = null;                     // {kind, send(msg)} once a hub is reachable
  var holding = !document.hidden;      // whether this tab's keys are with the hub (not while hidden)
  var stale = false, hiTimer = null, hiddenTimer = null;

  function each(key, fn) { (subs[key] || []).slice().forEach(function (h) { try { fn(h); } catch (err) { if (window.console) { console.warn('drishti channel', err); } } }); }
  function all(name) { Object.keys(subs).forEach(function (k) { each(k, function (h) { if (h[name]) { h[name](); } }); }); }
  function send(m) { if (link && !stale) { m.tab = TAB; link.send(m); } }

  // what the hub says to this tab
  function deliver(m) {
    if (!m) { return; }
    if (m.t === 'ev') { clearTimeout(hiTimer); each(m.key, function (h) { if (h[m.type]) { h[m.type](m.d); } }); } else if (m.t === 'hi') { clearTimeout(hiTimer); } else if (m.t === 'error') { all('error'); } else if (m.t === 'stale') { stale = true; all('error'); } else if (m.t === 'who') { hello(); }
  }

  // register with the hub, with every key this tab holds; no answer in time: say the connection is not there
  function hello() {
    if (!link || stale) { return; }
    send({ t: 'hello', who: WHO });
    if (holding) { Object.keys(subs).forEach(function (k) { send({ t: 'sub', key: k }); }); }
    clearTimeout(hiTimer);
    if (Object.keys(subs).length) { hiTimer = setTimeout(function () { all('error'); }, HI_WAIT_MS); }
  }

  function connected(kind, sender) {
    link = { kind: kind, send: sender };
    hello();
    setInterval(function () { send({ t: 'ping' }); }, PING_MS);
  }

  // 3. this tab only
  function own() {
    if (!window.DrishtiLiveHub || !window.EventSource) { return; }
    var hub = new window.DrishtiLiveHub({ EventSource: window.EventSource, fetch: window.fetch.bind(window) });
    connected('tab', function (m) { hub.receive(m, deliver); });
  }

  // 2. one tab of the browser holds the connection; the others reach it over a BroadcastChannel
  function elect() {
    if (!(navigator.locks && navigator.locks.request && window.BroadcastChannel && window.DrishtiLiveHub && window.EventSource)) { own(); return; }
    var name = 'drishti-live|' + HUB_URL.replace(/^.*\?/, '');      // tabs of one console version share it
    var bc = new window.BroadcastChannel(name), hub = null;
    bc.onmessage = function (e) {
      var m = e.data || {};
      if (m.leader) { if (!hub) { hello(); } return; }        // a new leader: register again
      if (m.hub) { if (hub) { hub.receive(m.m, function (out) { bc.postMessage({ to: m.m.tab, m: out }); }); } return; }
      if (m.to === TAB) { deliver(m.m); }
    };
    connected('follower', function (m) { if (hub) { hub.receive(m, deliver); } else { bc.postMessage({ hub: true, m: m }); } });
    navigator.locks.request(name, function () {
      hub = new window.DrishtiLiveHub({ EventSource: window.EventSource, fetch: window.fetch.bind(window) });
      link.kind = 'leader';
      hello();
      bc.postMessage({ leader: true });
      return new Promise(function () { /* held while this tab lives */ });
    });
  }

  // 1. a SharedWorker for the whole browser
  function start() {
    if (!window.SharedWorker) { elect(); return; }
    var settled = false, port;
    function fallback() { if (!settled) { settled = true; try { port.close(); } catch (e) { /* never opened */ } elect(); } }
    try {
      var worker = new window.SharedWorker(HUB_URL, { name: 'drishti-live' });
      port = worker.port;
      worker.onerror = fallback;
      port.onmessage = function (e) {
        if (settled) { deliver(e.data); return; }
        if (e.data && e.data.t === 'ready') {
          if (!e.data.es) { fallback(); return; }               // no EventSource in workers here
          settled = true;
          connected('shared-worker', function (m) { port.postMessage(m); });
        }
      };
      port.start();
      setTimeout(fallback, WORKER_WAIT_MS);                    // the worker did not start (blocked, failed to load)
    } catch (e) {
      fallback();
    }
  }

  window.DrishtiChannel = {
    subscribe: function (key, handlers) {
      var first = !subs[key];
      (subs[key] = subs[key] || []).push(handlers);
      if (first && holding) { send({ t: 'sub', key: key }); }
      return function () {
        if (!subs[key]) { return; }
        subs[key] = subs[key].filter(function (h) { return h !== handlers; });
        if (!subs[key].length) { delete subs[key]; if (holding) { send({ t: 'unsub', key: key }); } }
      };
    },
    transport: function () { return link ? link.kind : 'none'; }
  };

  document.addEventListener('visibilitychange', function () {
    if (document.hidden) {
      hiddenTimer = setTimeout(function () {
        holding = false;
        Object.keys(subs).forEach(function (k) { send({ t: 'unsub', key: k }); });
        all('paused');
      }, HIDDEN_GRACE_MS);
    } else {
      clearTimeout(hiddenTimer);
      if (!holding) { holding = true; hello(); }
    }
  });
  window.addEventListener('pagehide', function () { send({ t: 'bye' }); });
  window.addEventListener('pageshow', function (e) { if (e.persisted) { hello(); } });   // back from the back-forward cache
  start();
})();
