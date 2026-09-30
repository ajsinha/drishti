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
/* One live connection per browser tab. Browsers allow six connections to a site over HTTP/1.1; a stream per view and
   per bell used them up with a few tabs open, after which every other request (the command line's suggestions)
   waited forever. Views, the alerts bell, monitors and every workspace pane subscribe here instead; panes use their
   parent window's channel. A tab hidden for a while gives its connection back and reconnects when shown again
   (views then repaint from fresh data). */
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
          }
        };
        return;
      }
    } catch (e) { /* another origin: open our own */ }
  }
  var TYPES = ['view', 'frame', 'gone', 'alert', 'row', 'hello'];
  var HIDDEN_GRACE_MS = 10000;
  var subs = {};                       // key -> [handlers]
  var es = null, id = null, serverSubs = [], openTimer = null, syncTimer = null, hiddenTimer = null;

  function each(key, fn) { (subs[key] || []).slice().forEach(function (h) { try { fn(h); } catch (err) { if (window.console) { console.warn('drishti channel', err); } } }); }

  function close() {
    if (es) { es.close(); es = null; id = null; serverSubs = []; }
  }

  // Tell the open channel what changed; a channel the server no longer knows is replaced by a new one.
  function sync() {
    if (!es || !id) { return; }
    var want = Object.keys(subs);
    var add = want.filter(function (k) { return serverSubs.indexOf(k) < 0; });
    var remove = serverSubs.filter(function (k) { return want.indexOf(k) < 0; });
    if (!add.length && !remove.length) { return; }
    serverSubs = want.slice();
    fetch('/api/channel/' + encodeURIComponent(id), { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ add: add, remove: remove }) })
      .then(function (r) { if (r.status === 404) { close(); open(); } })
      .catch(function () { /* the channel's own reconnect will resync */ });
  }

  function open() {
    var keys = Object.keys(subs).sort();
    if (!keys.length || !window.EventSource) { close(); return; }
    if (es) { sync(); return; }
    es = new EventSource('/api/channel?' + keys.map(function (k) { return 's=' + encodeURIComponent(k); }).join('&'));
    es.addEventListener('channel', function (e) {
      var m = JSON.parse(e.data);                // on every (re)connection: the channel's id and what it carries
      id = m.d.id;
      serverSubs = m.d.subs || [];
      sync();                                    // anything subscribed since the URL was built
    });
    TYPES.forEach(function (t) {
      es.addEventListener(t, function (e) {
        var m;
        try { m = JSON.parse(e.data); } catch (err) { return; }
        each(m.ch, function (h) { if (h[t]) { h[t](m.d); } });
      });
    });
    es.addEventListener('end', function () { close(); });   // nothing left to stream: do not let the browser reconnect
    es.onerror = function () { id = null; Object.keys(subs).forEach(function (k) { each(k, function (h) { if (h.error) { h.error(); } }); }); };
  }

  function soon() {                    // subscriptions arrive together: open once, or send one change
    clearTimeout(openTimer);
    openTimer = setTimeout(open, 60);
  }

  window.DrishtiChannel = {
    subscribe: function (key, handlers) {
      (subs[key] = subs[key] || []).push(handlers);
      if (!document.hidden) { soon(); }
      return function () {
        subs[key] = (subs[key] || []).filter(function (h) { return h !== handlers; });
        if (!subs[key].length) { delete subs[key]; }
        clearTimeout(syncTimer);
        syncTimer = setTimeout(function () { if (!Object.keys(subs).length) { close(); } else { sync(); } }, 60);
      };
    }
  };

  document.addEventListener('visibilitychange', function () {
    if (document.hidden) {
      hiddenTimer = setTimeout(function () {
        close();
        Object.keys(subs).forEach(function (k) { each(k, function (h) { if (h.paused) { h.paused(); } }); });
      }, HIDDEN_GRACE_MS);
    } else {
      clearTimeout(hiddenTimer);
      if (!es) { soon(); }
    }
  });
  window.addEventListener('pagehide', close);
})();
