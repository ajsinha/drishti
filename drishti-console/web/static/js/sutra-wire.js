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
/* Sutra Next events and children (RACHANA_REFERENCE.md 7, 8). A panel that emits (data-wire on its section) has rows or bars with data-ev:
   a click (or Enter / Space) posts {panel, event, row, selections} to /v/<kind>/<id>/events; the server evaluates the payload as the
   reader and answers with a token for the selection, the panels that read it (swapped in place, nothing else moves) and the panel's
   children as ready HTML, opened where the Sutra places them: under the row (expand-row), beside the panel (detail-right), under it
   (detail-below) or as tabs. A new pick replaces them; a selection the click made stale closes its children. The page holds only the
   server's tokens, never a payload, so a masked id never reaches it. Groups that show always load with the page; on-expand ones open
   from a button. Below 700 px a detail is a full-width sheet with Back (wire.css). */
(function () {
  'use strict';
  var view = document.querySelector('[data-view]');
  if (!view || !(view.querySelector('section.pnl[data-wire]') || view.hasAttribute('data-customizing'))) { return; }   // Customize may wire a panel later
  var base = '/v/' + encodeURIComponent(view.dataset.kind) + '/' + view.dataset.id.split('/').map(encodeURIComponent).join('/');
  var held = {};                                     // panel id -> { event: token }
  var picks = {};                                    // panel id -> the last click { event, row }: a refresh replays it (refresh.js)
  var live = document.createElement('p');
  live.className = 'visually-hidden'; live.setAttribute('role', 'status'); live.setAttribute('aria-live', 'polite');
  view.appendChild(live);

  function say(text) { live.textContent = text; }
  function wireOf(sec) { try { return JSON.parse(sec.getAttribute('data-wire') || '{}'); } catch (e) { return {}; } }
  function css(id) { return window.CSS && CSS.escape ? CSS.escape(id) : id; }
  function query(extra) {
    var q = new URLSearchParams(location.search), out = new URLSearchParams();
    q.forEach(function (v, k) { if (k.indexOf('ctl.') === 0) { out.append(k, v); } });
    if (view.dataset.chosenSutra) { out.set('sutra', view.dataset.chosenSutra); }
    if (view.dataset.design) { out.set('design', view.dataset.design); }       // the Sutra designer: the reader's own page or draft
    (extra || []).forEach(function (kv) { out.append(kv[0], kv[1]); });
    var s = out.toString();
    return s ? '?' + s : '';
  }
  function tokens() {
    var out = [];
    Object.keys(held).forEach(function (p) { Object.keys(held[p]).forEach(function (e) { out.push(held[p][e]); }); });
    return out;
  }
  function json(r) {
    return r.json().catch(function () { return {}; }).then(function (j) {
      if (!r.ok) { var e = new Error((j && (j.detail || j.code)) || ('HTTP ' + r.status)); e.reset = j && j.reset ? j.message : null; throw e; }
      return j;
    });
  }
  function fragment(html) {
    var t = document.createElement('template');
    t.innerHTML = (html || '').trim();
    return t.content;
  }
  /** A panel of an answer as nodes: drawn by the client-side renderer from its JSON when the page is drawn by it (ui.render: client,
      CLIENT_RENDERER.md section 16), else the console's HTML. */
  function nodes(p) {
    var n = p.panel && window.drishtiPanelNode ? window.drishtiPanelNode(p.panel) : null;
    if (!n) { return fragment(p.html); }
    var f = document.createDocumentFragment(); f.appendChild(n); return f;
  }
  function enhance(node) {
    if (window.drishti && window.drishti.enhance) { window.drishti.enhance(node); }
  }

  // ---- children ---------------------------------------------------------------------------------------------------------------------
  function forget(node) {                                   // panels inside a closed container lose their own selections
    node.querySelectorAll('section.pnl[data-panel]').forEach(function (s) { close(s.getAttribute('data-panel')); delete held[s.getAttribute('data-panel')]; });
  }
  function close(panelId) {
    document.querySelectorAll('[data-wire-kids="' + css(panelId) + '"]').forEach(function (n) { forget(n); n.remove(); });
    var split = document.querySelector('[data-wire-split="' + css(panelId) + '"]');
    if (split && !split.querySelector('.wire-detail')) {
      var sec = split.querySelector('section.pnl');
      if (sec) { split.replaceWith(sec); }
    }
  }
  function back(panelId, picked) {
    var b = document.createElement('button');
    b.type = 'button'; b.className = 'wire-back'; b.innerHTML = '<i class="bi bi-arrow-left" aria-hidden="true"></i> Back';
    b.addEventListener('click', function () { close(panelId); if (picked && picked.isConnected) { picked.focus(); } });
    return b;
  }
  function tabbed(group, box) {
    var heads = document.createElement('div');
    heads.className = 'wire-tabs'; heads.setAttribute('role', 'tablist');
    box.appendChild(heads);
    group.panels.forEach(function (p, i) {
      var tab = document.createElement('button');
      tab.type = 'button'; tab.className = 'wire-tab'; tab.setAttribute('role', 'tab'); tab.id = 'wt-' + group.parent + '-' + p.id;
      tab.setAttribute('aria-selected', i === 0 ? 'true' : 'false'); tab.tabIndex = i === 0 ? 0 : -1; tab.textContent = p.title || p.id;
      var pane = document.createElement('div');
      pane.setAttribute('role', 'tabpanel'); pane.setAttribute('aria-labelledby', tab.id); pane.hidden = i !== 0;
      pane.appendChild(nodes(p));
      tab.addEventListener('click', function () {
        heads.querySelectorAll('[role="tab"]').forEach(function (t, k) { t.setAttribute('aria-selected', 'false'); t.tabIndex = -1; box.querySelectorAll('[role="tabpanel"]')[k].hidden = true; });
        tab.setAttribute('aria-selected', 'true'); tab.tabIndex = 0; pane.hidden = false; enhance(pane);
      });
      tab.addEventListener('keydown', function (e) {
        var all = Array.prototype.slice.call(heads.querySelectorAll('[role="tab"]')), at = all.indexOf(tab);
        var to = e.key === 'ArrowRight' ? all[(at + 1) % all.length] : e.key === 'ArrowLeft' ? all[(at - 1 + all.length) % all.length] : null;
        if (to) { e.preventDefault(); to.focus(); to.click(); }
      });
      heads.appendChild(tab);
      box.appendChild(pane);
    });
  }
  function open(sec, picked, group) {
    var id = group.parent;
    if (group.hidden || !(group.panels || []).length) { return; }
    if (group.place === 'expand-row' && picked && picked.tagName === 'TR') {
      var tr = document.createElement('tr'), td = document.createElement('td');
      tr.className = 'wire-kids'; tr.setAttribute('data-wire-kids', id);
      td.colSpan = picked.children.length || 1;
      group.panels.forEach(function (p) { td.appendChild(nodes(p)); });
      tr.appendChild(td);
      picked.after(tr);
      enhance(tr);
      return;
    }
    var box = document.createElement('div');
    box.className = 'wire-detail'; box.setAttribute('data-wire-kids', id);
    box.setAttribute('data-place', group.place === 'expand-row' ? 'detail-below' : group.place);
    box.setAttribute('role', 'region'); box.setAttribute('aria-label', 'Details of ' + (sec.querySelector('.pnl-h h3') || {}).textContent);
    box.appendChild(back(id, picked));
    if (group.place === 'tabs') { tabbed(group, box); } else { group.panels.forEach(function (p) { box.appendChild(nodes(p)); }); }
    if (group.place === 'detail-right') {
      var split = sec.closest('[data-wire-split="' + css(id) + '"]');
      if (!split) {
        split = document.createElement('div');
        split.className = 'wire-split'; split.setAttribute('data-wire-split', id);
        sec.replaceWith(split);
        split.appendChild(sec);
      }
      split.appendChild(box);
    } else {
      sec.after(box);
    }
    enhance(box);
    if (window.matchMedia && window.matchMedia('(max-width: 700px)').matches) { var b = box.querySelector('.wire-back'); if (b) { b.focus(); } }
  }

  // ---- a click ----------------------------------------------------------------------------------------------------------------------
  function swap(p) {
    var old = document.getElementById('p-' + p.id);
    if (!old) { return; }
    var fresh = nodes(p).firstElementChild;
    if (!fresh) { return; }
    var y = window.scrollY;
    old.replaceWith(fresh);
    if (window.scrollY !== y) { window.scrollTo(window.scrollX, y); }
    if (window.drishtiLayout && window.drishtiLayout.decorate) { window.drishtiLayout.decorate(fresh); }
    enhance(fresh);
  }
  function apply(sec, picked, j) {
    var note = view.querySelector('.wire-reset');
    if (note) { note.remove(); }
    (j.cleared || []).forEach(function (id) { close(id); delete held[id]; });
    var s = j.selection || {};
    held[s.panel] = held[s.panel] || {};
    held[s.panel][s.event] = s.token;
    sec.querySelectorAll('[data-ev]').forEach(function (r) {
      var on = r === picked;
      if (r.tagName === 'TR') { r.setAttribute('aria-selected', on ? 'true' : 'false'); } else { r.setAttribute('aria-pressed', on ? 'true' : 'false'); }
    });
    (j.panels || []).forEach(swap);
    close(s.panel);
    (j.children || []).forEach(function (g) { open(sec, picked, g); });
    var names = (j.panels || []).map(function (p) { return p.title || p.id; });
    say((names.length ? names.join(', ') + ' updated' : 'Selected') + ((j.children || []).some(function (g) { return !g.hidden; }) ? '; details opened.' : '.'));
    view.dispatchEvent(new CustomEvent('drishti:wire', { bubbles: true, detail: { panel: s.panel, event: s.event, panels: (j.panels || []).map(function (p) { return p.id; }) } }));
  }
  function fire(el) {
    var sec = el.closest('section.pnl[data-wire]');
    if (!sec) { return; }
    var on = el.tagName === 'TR' ? 'row-click' : 'bar-click';
    var em = (wireOf(sec).emits || []).filter(function (e) { return e.on === on; })[0];
    if (!em) { return; }
    picks[sec.getAttribute('data-panel')] = { event: em.name, row: Number(el.getAttribute('data-ev')) };
    sec.classList.add('wire-busy'); view.setAttribute('aria-busy', 'true');
    fetch(base + '/events' + query(), {
      method: 'POST', credentials: 'same-origin', headers: { 'Content-Type': 'application/json', Accept: 'application/json' },
      body: JSON.stringify({ panel: sec.getAttribute('data-panel'), event: em.name, row: Number(el.getAttribute('data-ev')), selections: tokens() })
    }).then(json).then(function (j) { apply(sec, el, j); })
      .catch(function (e) {
        if (e.reset) { reset(e.reset); return; }                        // the server that held the selections went away: pick again
        say('The selection could not be applied: ' + e.message);
      })
      .then(function () { sec.classList.remove('wire-busy'); view.removeAttribute('aria-busy'); });
  }
  /** The selections cannot be used any more (one server per screen: its server went away): forget them, close what they opened, unmark the
   *  picked rows, and say so in words above the panels until the next pick. */
  function reset(message) {
    Object.keys(held).forEach(close);
    held = {};
    view.querySelectorAll('[data-ev][aria-selected="true"], [data-ev][aria-pressed="true"]').forEach(function (r) {
      r.setAttribute(r.tagName === 'TR' ? 'aria-selected' : 'aria-pressed', 'false');
    });
    var note = view.querySelector('.wire-reset');
    if (!note) {
      note = document.createElement('p');
      note.className = 'wire-reset'; note.setAttribute('role', 'alert'); note.setAttribute('data-wire-reset', '');
      view.insertBefore(note, view.firstChild);
    }
    note.textContent = message;
    say(message);
  }
  function target(e) {
    if (e.target.closest('a, button, input, select, label, summary')) { return null; }   // a link in the row still opens its entity
    var el = e.target.closest('[data-ev]');
    return el && el.closest('section.pnl[data-wire]') ? el : null;
  }
  document.addEventListener('click', function (e) { var el = target(e); if (el) { fire(el); } });
  document.addEventListener('keydown', function (e) {
    if (e.key !== 'Enter' && e.key !== ' ') { return; }
    var el = e.target.matches && e.target.matches('[data-ev]') ? e.target : null;
    if (el && el.closest('section.pnl[data-wire]')) { e.preventDefault(); e.stopPropagation(); fire(el); }
  }, true);                                      // first: the table's own keys would follow the row's link on Enter

  // ---- children that show always, or when opened ------------------------------------------------------------------------------------
  function load(sec) {
    var id = sec.getAttribute('data-panel');
    return fetch(base + '/children/' + encodeURIComponent(id) + query(tokens().map(function (t) { return ['sel', t]; })), {
      credentials: 'same-origin', headers: { Accept: 'application/json' }
    }).then(json).then(function (j) { close(id); (j.children || []).forEach(function (g) { open(sec, null, g); }); });
  }
  function rewire(sec, refreshed) {             // refreshed: a panel swapped in by refresh.js keeps what is open, it only gets its button back
    var kids = wireOf(sec).children || [];
    if (!refreshed && kids.some(function (g) { return g.show === 'always'; })) { load(sec).catch(function (e) { if (e.reset) { reset(e.reset); } }); }
    if (kids.some(function (g) { return g.show === 'on-expand'; }) && !sec.querySelector('.wire-open')) {
      var b = document.createElement('button');
      b.type = 'button'; b.className = 'wire-tab wire-open'; b.setAttribute('aria-expanded', 'false');
      b.innerHTML = '<i class="bi bi-chevron-down" aria-hidden="true"></i> Details';
      b.addEventListener('click', function () {
        var opened = b.getAttribute('aria-expanded') === 'true';
        if (opened) { close(sec.getAttribute('data-panel')); b.setAttribute('aria-expanded', 'false'); return; }
        load(sec).then(function () { b.setAttribute('aria-expanded', 'true'); }).catch(function (e) { if (e.reset) { reset(e.reset); } else { say('The details could not be loaded: ' + e.message); } });
      });
      if (refreshed && document.querySelector('[data-wire-kids="' + css(sec.getAttribute('data-panel')) + '"]')) { b.setAttribute('aria-expanded', 'true'); }
      (sec.querySelector('.pnl-b') || sec).appendChild(b);
    }
  }
  view.querySelectorAll('section.pnl[data-wire]').forEach(function (sec) { rewire(sec, false); });
  /** A refresh of the panels a selection drives (RACHANA_REFERENCE.md 9): the last click of {@code panel} sent again with every selection held,
   *  and only the listening panels swapped; what the click opened stays open. Resolves with the ids swapped. */
  function replay(panelId) {
    var p = picks[panelId];
    if (!p) { return Promise.resolve([]); }
    return fetch(base + '/events' + query(), {
      method: 'POST', credentials: 'same-origin', headers: { 'Content-Type': 'application/json', Accept: 'application/json' },
      body: JSON.stringify({ panel: panelId, event: p.event, row: p.row, selections: tokens() })
    }).then(json).then(function (j) {
      var s = j.selection || {};
      if (s.panel) { held[s.panel] = held[s.panel] || {}; held[s.panel][s.event] = s.token; }
      (j.panels || []).forEach(swap);
      return (j.panels || []).map(function (q) { return q.id; });
    });
  }
  window.drishtiWire = { held: function () { return JSON.parse(JSON.stringify(held)); }, fire: fire, open: open, close: close, replay: replay, rewire: rewire,
                         picked: function () { return JSON.parse(JSON.stringify(picks)); } };
})();
