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
/* Server watch, on every page: when the page was rendered while the server was unreachable, or a call to it fails with DRS-5003
 * (502/503 "unreachable"), a compact banner says so and the page asks the console (GET /healthz/backend, which the console keeps
 * fresh itself) with a growing pause (2 s to 30 s). When the server is back the banner says "Connected again" and a page that
 * had failed reloads by itself, unless it holds unsaved edits (a beforeunload guard objects): then the banner offers "Reload".
 * No banner while everything is fine. Strict CSP: no inline script or style. */
(function () {
  'use strict';
  if (window.__drishtiServerWatch) { return; }
  window.__drishtiServerWatch = true;
  var realFetch = window.fetch ? window.fetch.bind(window) : null;
  var PROBE = '/healthz/backend';
  var MIN = 2000, MAX = 30000;
  var banner = null, text = null, retry = null, reloadBtn = null, close = null;
  var down = false, dismissed = false, mustReload = false, polling = false, delay = MIN, timer = null, hideTimer = null;
  var isLoginProbe = !!document.querySelector('[data-watch-probe]');

  function btn(label, cls, fn) {
    var b = document.createElement('button');
    b.type = 'button'; b.className = 'sw-btn ' + cls; b.textContent = label; b.addEventListener('click', fn);
    return b;
  }
  function build() {
    if (banner) { return; }
    banner = document.createElement('div');
    banner.className = 'sw-banner';
    banner.setAttribute('role', 'status');
    banner.setAttribute('aria-live', 'polite');
    banner.setAttribute('data-server-watch', '');
    text = document.createElement('span');
    text.className = 'sw-text';
    retry = btn('Retry now', 'sw-retry', function () { clearTimeout(timer); poll(); });
    reloadBtn = btn('Reload', 'sw-reload', function () { window.location.reload(); });
    close = btn('Dismiss', 'sw-close', function () { dismissed = true; banner.hidden = true; });
    close.setAttribute('aria-label', 'Dismiss this message');
    close.textContent = '×';
    banner.appendChild(text); banner.appendChild(retry); banner.appendChild(reloadBtn); banner.appendChild(close);
    document.body.insertBefore(banner, document.body.firstChild);
  }
  function show(msg, state) {
    build();
    clearTimeout(hideTimer);
    banner.setAttribute('data-state', state);
    text.textContent = msg;
    retry.hidden = state !== 'down';
    reloadBtn.hidden = state !== 'ask';
    banner.hidden = dismissed && state === 'down';
  }
  function dirty() {
    var e;
    try { e = new Event('beforeunload', { cancelable: true }); window.dispatchEvent(e); } catch (x) { return false; }
    return !!e.defaultPrevented;
  }
  function trigger(reload) {
    if (reload && !isLoginProbe) { mustReload = true; }
    if (!down) { down = true; dismissed = false; delay = MIN; }
    show('Server not reachable — retrying…', 'down');
    if (!polling) { polling = true; timer = setTimeout(poll, delay); }
  }
  function recovered() {
    down = false; polling = false;
    if (!mustReload) {
      show('Connected again', 'up');
      hideTimer = setTimeout(function () { if (banner) { banner.hidden = true; } }, 4000);
      return;
    }
    if (dirty()) { dismissed = false; show('Connected again — this page has unsaved changes, so it was not reloaded.', 'ask'); return; }
    show('Connected again — reloading…', 'up');
    setTimeout(function () { window.location.reload(); }, 600);
  }
  function again() {
    delay = Math.min(MAX, Math.round(delay * 1.6));
    timer = setTimeout(poll, delay);
  }
  function poll() {
    if (!realFetch) { return; }
    realFetch(PROBE, { cache: 'no-store', credentials: 'same-origin' }).then(function (r) {
      return r.ok ? r.json() : { reachable: false };
    }).then(function (s) {
      if (s && s.reachable === true) { recovered(); return; }
      again();
    }, again);
  }
  function unreachable(status, body) {
    return (status === 502 || status === 503) && body && (body.code === 'DRS-5003' || /unreachable/i.test(String(body.detail || body.message || '')));
  }
  // a call to the server that fails as "unreachable": fetch ...
  if (realFetch) {
    window.fetch = function () {
      var arg = arguments[0];
      var url = typeof arg === 'string' ? arg : (arg && arg.url) || '';
      return realFetch.apply(window, arguments).then(function (r) {
        if ((r.status === 502 || r.status === 503) && url.indexOf(PROBE) < 0) {
          r.clone().json().then(function (b) { if (unreachable(r.status, b)) { trigger(true); } }, function () {});
        }
        return r;
      });
    };
  }
  // ... XMLHttpRequest ...
  var xsend = XMLHttpRequest.prototype.send;
  XMLHttpRequest.prototype.send = function () {
    this.addEventListener('load', function () {
      if (this.status !== 502 && this.status !== 503) { return; }
      try { if (unreachable(this.status, JSON.parse(this.responseText))) { trigger(true); } } catch (e) { /* not JSON */ }
    });
    return xsend.apply(this, arguments);
  };
  // ... and live streams (they reconnect by themselves: the banner only says why they are quiet)
  if (window.EventSource) {
    var RealES = window.EventSource;
    var ES = function (url, opts) {
      var es = new RealES(url, opts);
      es.addEventListener('error', function () {
        if (!realFetch || down) { return; }
        realFetch(PROBE, { cache: 'no-store', credentials: 'same-origin' }).then(function (r) { return r.json(); })
          .then(function (s) { if (s && s.reachable === false) { trigger(false); } }, function () {});
      });
      return es;
    };
    ES.prototype = RealES.prototype; ES.CONNECTING = 0; ES.OPEN = 1; ES.CLOSED = 2;
    window.EventSource = ES;
  }
  function start() {
    var nav = (window.performance && performance.getEntriesByType) ? performance.getEntriesByType('navigation')[0] : null;
    var status = nav && nav.responseStatus;
    if (document.body.hasAttribute('data-server-down') || ((status === 502 || status === 503) && !isLoginProbe)) {
      trigger(true);
    } else if (isLoginProbe && realFetch) {          // the sign-in page: ask once, so it says so when the server is not there
      realFetch(PROBE, { cache: 'no-store', credentials: 'same-origin' }).then(function (r) { return r.json(); })
        .then(function (s) { if (s && s.reachable === false) { trigger(false); } }, function () {});
    }
  }
  if (document.readyState === 'loading') { document.addEventListener('DOMContentLoaded', start); } else { start(); }
})();
