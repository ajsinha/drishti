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
/* Refresh and freshness on a view page (SUTRA_NEXT.md 9). Every panel says how fresh it is in its header ("updated 12 s ago", ticking;
   "stale" in amber past twice its interval or the source's staleAfter; "trade-endur isn't answering; showing data from 10:42" when a
   refresh fails; "live" on a page the live channel updates). A panel with its own source: says these of that source (its provenance,
   data-prov and the panels endpoint's), any other of the page's. A panel whose Sutra says refresh: (data-refresh, seconds) is fetched again
   on its own: only the panels that are due, through /v/<kind>/<id>/panels (the panels a selection drives replay that selection through
   sutra-wire.js), swapped in place when they changed, with the picked row, what it opened and the scroll kept. The reader pauses,
   resumes and slows a panel (never faster than the Sutra's interval or views.refresh_min_seconds), or pauses the whole page; the
   choices are kept per person and per screen (the user's settings, refresh.<kind>/<sutra>). A hidden tab fetches nothing. */
(function () {
  'use strict';
  var view = document.querySelector('[data-view]');
  if (!view) { return; }
  var d = view.dataset;
  var MIN = Math.max(1, +d.refreshMin || 5);
  var LIVE = view.hasAttribute('data-live'), STILL = view.hasAttribute('data-static') || view.hasAttribute('data-customizing');
  var base = '/v/' + encodeURIComponent(d.kind) + '/' + d.id.split('/').map(encodeURIComponent).join('/');
  var CHOICES = [5, 10, 15, 30, 60, 120, 300, 600, 900, 1800, 3600];
  var prefs = parse(d.refreshPrefs) || {};
  prefs.panels = prefs.panels || {};
  var fetched0 = Date.parse(d.fetched) || Date.now();
  var source = d.source || 'The source';
  var panels = {};                                   // id -> { every, floor, can, paused, fetched, failed, next, busy, html }
  var driven = {};                                   // a listening panel's id -> the panel whose selection drives it
  var bar = null, saveTimer = null, ticker = null, ready = false;

  function parse(s) { try { return JSON.parse(s || 'null'); } catch (e) { return null; } }
  function css(id) { return window.CSS && CSS.escape ? CSS.escape(id) : id; }
  function now() { return Date.now(); }
  /** ISO-8601 duration (PT2M, PT30S, PT1H) in milliseconds, or 0. */
  function duration(s) {
    var m = /^P(?:T(?:(\d+(?:\.\d+)?)H)?(?:(\d+(?:\.\d+)?)M)?(?:(\d+(?:\.\d+)?)S)?)?$/.exec(s || '');
    return m ? ((+m[1] || 0) * 3600 + (+m[2] || 0) * 60 + (+m[3] || 0)) * 1000 : 0;
  }
  function ago(ms) {
    var s = Math.max(0, Math.round(ms / 1000));
    return s < 60 ? s + ' s ago' : s < 3600 ? Math.floor(s / 60) + ' min ago' : Math.floor(s / 3600) + ' h ago';
  }
  function clock(t) { var x = new Date(t); return ('0' + x.getHours()).slice(-2) + ':' + ('0' + x.getMinutes()).slice(-2); }
  function every(sec) { return sec < 60 ? sec + ' s' : sec < 3600 ? (sec / 60) + ' min' : (sec / 3600) + ' h'; }
  function interval(p) { return Math.max(p.floor, p.chosen || p.floor); }
  function pageStale() {
    if (view.hasAttribute('data-stale')) { return true; }
    var after = duration(d.staleAfter), upd = Date.parse(d.updated);
    return !!(after && upd && now() - upd > after);
  }
  /* A panel with its own source: (data-prov, the server's panel provenance) answers for itself: its connector, when that had new data,
     its threshold; any other panel reads the page's record and the page's provenance stands for it. */
  function staleOf(p) {
    var o = p.prov;
    if (!o) { return pageStale(); }
    if (o.stale) { return true; }
    var after = duration(o.staleAfter), upd = Date.parse(o.updatedAt);
    return !!(after && upd && now() - upd > after);
  }
  function sourceOf(p) { return (p.prov && p.prov.source) || source; }
  function updatedOf(p) { return Date.parse(p.prov ? p.prov.updatedAt : d.updated); }
  function allPaused() { return !!prefs.all; }

  // ---- each panel -------------------------------------------------------------------------------------------------------------------
  function el(tag, cls, text) { var e = document.createElement(tag); if (cls) { e.className = cls; } if (text) { e.textContent = text; } return e; }
  function decorate(sec) {
    var id = sec.getAttribute('data-panel'), head = sec.querySelector(':scope > .pnl-h');
    if (!id || !head || head.querySelector(':scope > .fresh')) { return; }
    var p = panels[id];
    var secs = +sec.getAttribute('data-refresh') || 0;
    var prov = parse(sec.getAttribute('data-prov'));
    if (p && prov) { p.prov = prov; }                // swapped in by a refresh: its source's freshness as now drawn
    if (!p) {
      p = panels[id] = { fetched: ready ? now() : (Date.parse((prov || {}).fetchedAt) || fetched0), failed: null, busy: false, prov: prov };   // drawn after the page: just fetched
      if (secs && !LIVE && !STILL) {
        p.every = secs; p.floor = Math.max(MIN, secs); p.can = sec.getAttribute('data-refresh-pause') !== '0';
        var mine = prefs.panels[id] || {};
        p.chosen = mine.every ? Math.max(p.floor, +mine.every) : p.floor;
        p.paused = p.can && !!mine.paused;
        p.next = now() + interval(p) * 1000;
      }
    }
    var box = el('span', 'fresh');
    box.setAttribute('data-fresh', id);
    var text = el('span', 'fresh-t');
    text.setAttribute('data-fresh-text', '');
    text.setAttribute('aria-live', 'off');
    box.appendChild(text);
    if (p.every) {
      sec.setAttribute('data-refreshing', '');
      if (p.can) {
        var b = el('button', 'fresh-btn');
        b.type = 'button'; b.setAttribute('data-refresh-toggle', '');
        b.addEventListener('click', function () { toggle(id); });
        box.appendChild(b);
      }
      var pick = el('select', 'fresh-pick');
      pick.setAttribute('data-refresh-every', ''); pick.setAttribute('aria-label', 'Refresh ' + (sec.querySelector('.pnl-h h3') || {}).textContent + ' every');
      CHOICES.filter(function (c) { return c > p.floor; }).concat([p.floor]).sort(function (a, b) { return a - b; }).forEach(function (c) {
        var o = el('option', null, 'every ' + every(c)); o.value = String(c); pick.appendChild(o);
      });
      pick.value = String(interval(p));
      pick.addEventListener('change', function () { p.chosen = +pick.value; p.next = now() + interval(p) * 1000; remember(); paint(); });
      box.appendChild(pick);
    }
    head.insertBefore(box, head.querySelector(':scope > .pnl-code'));
    paintOne(id);
  }
  function paintOne(id) {
    var p = panels[id], box = document.querySelector('[data-fresh="' + css(id) + '"]');
    if (!p || !box) { return; }
    var text = box.querySelector('[data-fresh-text]'), t = now(), age = t - p.fetched, state = 'ok', words;
    var paused = p.every && p.can && (p.paused || allPaused());
    if (LIVE) { state = 'live'; words = 'live'; }
    else if (p.failed) { state = 'down'; words = p.failed.source + ' isn’t answering; showing data from ' + clock(p.fetched); }
    else if (staleOf(p)) { state = 'stale'; words = 'stale · ' + sourceOf(p) + ' last had new data ' + (updatedOf(p) ? clock(updatedOf(p)) : 'a while ago'); }
    else if (p.every && !paused && age > 2 * interval(p) * 1000) { state = 'stale'; words = 'stale · updated ' + ago(age); }
    else { words = (paused ? 'paused · ' : '') + 'updated ' + ago(age); if (paused) { state = 'paused'; } }
    box.setAttribute('data-state', state);
    if (text.textContent !== words) { text.textContent = words; }
    text.title = 'Fetched ' + new Date(p.fetched).toLocaleString() + (p.every ? '; refreshes every ' + every(interval(p)) : '');
    var b = box.querySelector('[data-refresh-toggle]');
    if (b) {
      var on = p.paused || allPaused();
      b.setAttribute('aria-pressed', on ? 'true' : 'false');
      b.disabled = allPaused();
      b.title = on ? 'Resume refreshing' : 'Pause refreshing';
      b.setAttribute('aria-label', b.title);
      b.innerHTML = on ? '<i class="bi bi-play-fill" aria-hidden="true"></i>' : '<i class="bi bi-pause-fill" aria-hidden="true"></i>';
    }
  }
  function paint() {
    Object.keys(panels).forEach(paintOne);
    if (bar) {
      var any = Object.keys(panels).some(function (id) { return panels[id].every && panels[id].can; });
      bar.hidden = !any;
      var btn = bar.querySelector('[data-refresh-all]');
      btn.setAttribute('aria-pressed', allPaused() ? 'true' : 'false');
      btn.innerHTML = allPaused() ? '<i class="bi bi-play-fill" aria-hidden="true"></i> Resume all' : '<i class="bi bi-pause-fill" aria-hidden="true"></i> Pause all';
      bar.querySelector('[data-refresh-said]').textContent = allPaused() ? 'Refresh paused on this page' : 'Panels refresh on their own';
      view.toggleAttribute('data-refresh-paused', allPaused());
    }
  }
  function toggle(id) {
    var p = panels[id];
    if (!p || !p.can || allPaused()) { return; }
    p.paused = !p.paused;
    if (!p.paused) { p.next = now(); }            // resumed: fetch now, then on the interval
    remember(); paint(); tick();
  }
  function pauseAll(on) {
    prefs.all = on;
    if (!on) { Object.keys(panels).forEach(function (id) { if (panels[id].every && !panels[id].paused) { panels[id].next = Math.min(panels[id].next, now()); } }); }
    remember(); paint(); tick();
  }
  function remember() {
    prefs.panels = {};
    Object.keys(panels).forEach(function (id) {
      var p = panels[id];
      if (p.every && (p.paused || p.chosen !== p.floor)) { prefs.panels[id] = { paused: !!p.paused, every: interval(p) }; }
    });
    clearTimeout(saveTimer);
    saveTimer = setTimeout(function () {
      var body = { refresh: {} };
      body.refresh[d.screen] = { all: !!prefs.all, panels: prefs.panels };
      fetch('/api/settings', { method: 'PATCH', credentials: 'same-origin', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body) })
        .catch(function () {});
    }, 400);
  }

  // ---- fetching ---------------------------------------------------------------------------------------------------------------------
  function query(extra) {
    var q = new URLSearchParams(location.search), out = new URLSearchParams();
    q.forEach(function (v, k) { if (k.indexOf('ctl.') === 0) { out.append(k, v); } });
    if (d.chosenSutra) { out.set('sutra', d.chosenSutra); }
    if (d.design) { out.set('design', d.design); }
    (extra || []).forEach(function (kv) { out.append(kv[0], kv[1]); });
    return '?' + out.toString();
  }
  function fragment(html) { var t = document.createElement('template'); t.innerHTML = (html || '').trim(); return t.content.firstElementChild; }
  /** One panel swapped in place: nothing moves, the picked row stays picked, what it opened under it stays, the scroll stays. */
  function swap(p) {
    var old = document.getElementById('p-' + p.id), st = panels[p.id];
    if (!old || (st && st.html === p.html) || old.hasAttribute('data-editing')) { return; }   // a form being edited stays (record-forms.js)
    var fresh = fragment(p.html);
    if (!fresh) { return; }
    var y = window.scrollY, scrolls = [];
    old.querySelectorAll('.pnl-b, .tbl-wrap, [data-scroll]').forEach(function (n, i) { if (n.scrollTop || n.scrollLeft) { scrolls.push([i, n.scrollTop, n.scrollLeft]); } });
    var picked = old.querySelector('[data-ev][aria-selected="true"], [data-ev][aria-pressed="true"]');
    var pickedRow = picked ? picked.getAttribute('data-ev') : null;
    var kids = Array.prototype.slice.call(old.querySelectorAll('tr.wire-kids'));
    kids.forEach(function (k) { k.remove(); });
    fresh.style.minHeight = old.offsetHeight + 'px';           // no flicker: the new panel takes the old one's room until it is drawn
    old.replaceWith(fresh);
    if (pickedRow !== null) {
      var row = fresh.querySelector('[data-ev="' + css(pickedRow) + '"]');
      if (row) {
        row.setAttribute(row.tagName === 'TR' ? 'aria-selected' : 'aria-pressed', 'true');
        kids.reverse().forEach(function (k) { if (row.tagName === 'TR') { row.after(k); } });
      }
    }
    var nodes = fresh.querySelectorAll('.pnl-b, .tbl-wrap, [data-scroll]');
    scrolls.forEach(function (s) { if (nodes[s[0]]) { nodes[s[0]].scrollTop = s[1]; nodes[s[0]].scrollLeft = s[2]; } });
    if (window.drishtiLayout && window.drishtiLayout.decorate) { window.drishtiLayout.decorate(fresh); }
    if (window.drishti && window.drishti.enhance) { window.drishti.enhance(fresh); }
    if (window.drishtiWire && window.drishtiWire.rewire && fresh.hasAttribute('data-wire')) { window.drishtiWire.rewire(fresh, true); }
    decorate(fresh);
    requestAnimationFrame(function () { fresh.style.minHeight = ''; if (window.scrollY !== y) { window.scrollTo(window.scrollX, y); } });
    if (st) { st.html = p.html; }
  }
  function done(ids, ok, why) {
    var t = now();
    ids.forEach(function (id) {
      var p = panels[id];
      if (!p) { return; }
      p.busy = false;
      p.next = t + interval(p) * 1000;
      if (ok) { p.fetched = t; p.failed = null; } else { p.failed = { source: (p.prov && p.prov.source) || why || source }; }   // the panel's own source when it has one
    });
    paint();
  }
  function fetchPanels(ids) {
    ids.forEach(function (id) { panels[id].busy = true; });
    return fetch(base + '/panels' + query([['panels', ids.join(',')]]), { credentials: 'same-origin', headers: { Accept: 'application/json' } })
      .then(function (r) { return r.json().catch(function () { return {}; }).then(function (j) { if (!r.ok) { throw j; } return j; }); })
      .then(function (j) {
        var prov = j.provenance || {};
        if (prov.source) { source = prov.source; d.source = prov.source; }
        if (prov.updatedAt) { d.updated = prov.updatedAt; }
        if (prov.staleAfter) { d.staleAfter = prov.staleAfter; }
        view.toggleAttribute('data-stale', !!prov.stale);
        (j.panels || []).forEach(function (x) { swap(x); if (panels[x.id]) { panels[x.id].prov = x.provenance || null; } });
        done(ids, true);
      })
      .catch(function () { done(ids, false, source); });
  }
  function fetchDriven(emitter, ids) {
    ids.forEach(function (id) { panels[id].busy = true; });
    return window.drishtiWire.replay(emitter).then(function () { done(ids, true); }).catch(function () { done(ids, false, source); });
  }
  function tick() {
    paint();
    if (LIVE || STILL || document.hidden || allPaused()) { return; }
    var t = now(), plain = [], byEmitter = {};
    Object.keys(panels).forEach(function (id) {
      var p = panels[id];
      if (!p.every || p.paused || p.busy || t < p.next || !document.getElementById('p-' + id)) { return; }
      var em = driven[id];
      if (em && window.drishtiWire && window.drishtiWire.replay && (window.drishtiWire.picked() || {})[em]) { (byEmitter[em] = byEmitter[em] || []).push(id); }
      else { plain.push(id); }
    });
    if (plain.length) { fetchPanels(plain); }
    Object.keys(byEmitter).forEach(function (em) { fetchDriven(em, byEmitter[em]); });
  }

  // ---- the page ---------------------------------------------------------------------------------------------------------------------
  function decorateAll(root) { (root || view).querySelectorAll('section.pnl[data-panel]').forEach(decorate); }
  decorateAll();
  ready = true;
  if (Object.keys(panels).some(function (id) { return panels[id].every; })) {
    bar = el('div', 'fresh-bar');
    bar.setAttribute('role', 'region'); bar.setAttribute('aria-label', 'Refresh');
    var said = el('span', 'fresh-said'); said.setAttribute('data-refresh-said', ''); said.setAttribute('role', 'status');
    var all = el('button', 'fresh-btn fresh-all');
    all.type = 'button'; all.setAttribute('data-refresh-all', '');
    all.addEventListener('click', function () { pauseAll(!allPaused()); });
    bar.appendChild(el('i', 'bi bi-arrow-repeat')).setAttribute('aria-hidden', 'true');
    bar.appendChild(said); bar.appendChild(all);
    var grid = view.querySelector('.vgrid');
    if (grid) { grid.parentNode.insertBefore(bar, grid); } else { view.insertBefore(bar, view.firstChild); }
  }
  view.addEventListener('drishti:wire', function (e) {          // a click: the panels it filled now follow that selection on refresh
    var det = e.detail || {};
    (det.panels || []).forEach(function (id) { driven[id] = det.panel; panels[id] && (panels[id].fetched = now()); });
  });
  if (window.MutationObserver) {                                // panels drawn later (a control, a click, children, the designer) get their line too
    new MutationObserver(function (list) {
      list.forEach(function (m) { m.addedNodes.forEach(function (n) { if (n.nodeType === 1) { if (n.matches('section.pnl[data-panel]')) { decorate(n); } decorateAll(n); } }); });
    }).observe(view, { childList: true, subtree: true });
  }
  document.addEventListener('visibilitychange', function () { if (!document.hidden) { tick(); } });
  paint();
  ticker = setInterval(tick, 1000);
  window.drishtiRefresh = {
    state: function () { return JSON.parse(JSON.stringify(panels, function (k, v) { return k === 'html' ? undefined : v; })); },
    tick: tick, pauseAll: pauseAll, toggle: toggle, min: MIN
  };
})();
