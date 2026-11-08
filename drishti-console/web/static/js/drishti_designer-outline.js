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
(function (g) { g.Drishti = g.Drishti || {}; })(typeof window !== 'undefined' ? window : globalThis); /* one namespace for everything the console puts on window (tools/dev/asset_prefix.py) */
/* The Sutra designer's Outline mode (docs/guides/RACHANA_REFERENCE.md › Screen designer and the Build workbench, section 31; core/designer_outline.py). The screen as a list: its
   sections, the panels in each and what a click opens under a table, each in plain words. Drag an item to reorder it or move it to
   another section; drop it on a table (or its "opens on a click" line) to nest it (a short sentence asks where it opens); drag a child
   out (or press Un-nest) to put it back on the page; "+ Add a section". Picking an item shows its description and settings. The tree is
   the one outline (drishti-tree's TreeOutline, drishti-designer's Outline: its keys, Alt+arrows and drags); this script turns its moves into the server's steps. After every step
   the live preview is drawn again in place (its panels swapped, no reload), and the outline is read again. */
(function () {
  'use strict';
  var root = document.querySelector('[data-outline]');
  if (!root || !root.dataset.draft) { return; }
  var draft = root.dataset.draft, kind = root.dataset.kind, id = root.dataset.id;
  var tree = root.querySelector('[data-ol-tree]'), detail = root.querySelector('[data-ol-detail]'), msg = root.querySelector('[data-ol-msg]');
  var frame = root.querySelector('[data-ol-preview]'), sutra = root.querySelector('[data-ol-sutra]');
  var D = window.Drishti.elements.designer, state = null, rev = 0, items = {};
  var picks = new D.Selection();                                     // the outline's picked item: its settings shown beside it
  var outline = new D.Outline(tree, { label: 'The screen, as an outline', selection: picks, canDrop: canDrop });

  function el(tag, attrs, kids) {
    var e = document.createElement(tag);
    Object.keys(attrs || {}).forEach(function (k) {
      if (k === 'text') { e.textContent = attrs[k]; } else if (k === 'on') { Object.keys(attrs.on).forEach(function (ev) { e.addEventListener(ev, attrs.on[ev]); }); }
      else if (attrs[k] != null && attrs[k] !== false) { e.setAttribute(k, attrs[k] === true ? '' : attrs[k]); }
    });
    (kids || []).forEach(function (c) { if (c) { e.appendChild(typeof c === 'string' ? document.createTextNode(c) : c); } });
    return e;
  }
  function api(method, url, body) {
    return fetch(url, { method: method, credentials: 'same-origin', headers: body ? { 'Content-Type': 'application/json' } : {}, body: body ? JSON.stringify(body) : undefined })
      .then(function (r) { return r.json().catch(function () { return {}; }).then(function (j) { if (!r.ok) { throw new Error(j.detail || 'Something went wrong'); } return j; }); });
  }
  function say(t, ok) { msg.textContent = t || ''; msg.className = 'dz-msg' + (ok ? ' dz-ok' : ''); }

  function step(body) {
    body.kind = kind; body.id = id;
    say('Saving…', true);
    return api('POST', '/designer/outline/op/' + encodeURIComponent(draft), body)
      .then(function () { return Promise.all([load(), redraw()]); })
      .then(function () { say('Saved', true); })
      .catch(function (e) { say(e.message); });
  }

  /* the preview, drawn again in place: the new page's grid swapped into the frame (its scripts set it up), else a reload */
  function redraw() {
    var w = frame.contentWindow, d = frame.contentDocument;
    var url = '/v/' + encodeURIComponent(kind) + '/' + encodeURIComponent(id) + '?design=' + encodeURIComponent(draft) + '&embed=1';
    return fetch(url, { credentials: 'same-origin', cache: 'no-store', headers: { Accept: 'text/html' } }).then(function (r) { return r.text(); }).then(function (html) {
      var fresh = new DOMParser().parseFromString(html, 'text/html').querySelector('[data-view] .vgrid'), old = d && d.querySelector('[data-view] .vgrid');
      var needsGrid = fresh && fresh.hasAttribute('data-sgrid') && !(w && (w.Drishti || {}).screenGrid);
      var needsWire = fresh && fresh.querySelector('section.pnl[data-wire]') && !(w && (w.Drishti || {}).wire);   // the first click wired: its script starts with the page
      if (!fresh || !old || needsGrid || needsWire) { frame.setAttribute('src', url); return; }
      var next = fresh.ownerDocument === d ? fresh : d.importNode(fresh, true);
      old.replaceWith(next);
      if ((w.Drishti || {}).screenGrid && next.hasAttribute('data-sgrid')) { (w.Drishti || {}).screenGrid.init(next); }
      if ((w.Drishti || {}).view && (w.Drishti || {}).view.enhance) { (w.Drishti || {}).view.enhance(next); }
      frame.setAttribute('data-ol-rev', String(rev));
    });
  }

  /* the tree: sections (s:), their panels (p:), what a click on a table opens (c:) and the table's drop line (n:) */
  function canDrop(from, to, where) {
    var d = items[from], t = items[to];
    if (!d || !t) { return false; }
    if (t.kind === 'child') { return 'A panel a click opens moves with its table: drop it on the page to un-nest it'; }
    if (d.kind === 'child') { return t.kind === 'nest' ? 'That already opens on a click: un-nest it first' : true; }
    if (t.kind === 'panel' && where === 'inside' && !t.data.table) { return 'Only a table opens a panel on a click: drop it above or below ' + t.data.title; }
    return true;
  }
  function moveTo(d, to, section) {
    var at = d.index < to ? to - 1 : to;
    return step({ op: 'move', index: d.index, to: at, section: section });
  }
  function moved(e) {                                                 /* the outline's move, as the server's step */
    var d = items[e.id], t = items[e.target];
    if (d.kind === 'child') { step({ op: 'unnest', parent: d.data.parentId }); return; }       // a child dragged onto the page: back on the page
    if (t.kind === 'nest') { ask(d.data, t.data); return; }
    if (t.kind === 'section') { moveTo(d.data, t.end, t.data.id || ''); return; }
    if (e.where === 'inside') { ask(d.data, t.data); return; }                                // onto a table: it opens on a click there
    moveTo(d.data, e.where === 'after' ? t.data.index + 1 : t.data.index, t.section);
  }
  outline.on('move', moved);
  outline.on('open', function (e) { show(e.id); });
  picks.on('change', function () { show(picks.primary()); });

  function show(key) {
    var it = key && items[key];
    if (!it || it.kind === 'nest' || (it.kind === 'section' && !it.data.id)) { return; }
    showDetail(it.kind, it.data);
  }

  function draw() {
    items = {};
    var all = [], nodes = [];
    state.sections.forEach(function (s) { s.panels.forEach(function (p) { all.push(p); }); });
    state.sections.forEach(function (s, si) {
      if (si === 0 && !s.panels.length && state.sections.length > 1) { return; }
      var end = s.panels.length ? s.panels[s.panels.length - 1].index + 1 : (all.length ? all[all.length - 1].index + 1 : 0);
      var sk = 's:' + (s.id || ''), panels = [];
      items[sk] = { kind: 'section', data: s, end: end };
      s.panels.forEach(function (p) {
        var pk = 'p:' + (p.id || p.index), kids = [];
        items[pk] = { kind: 'panel', data: p, section: s.id || '' };
        p.children.forEach(function (c) {
          items['c:' + c.id] = { kind: 'child', data: { parent: p.index, parentId: p.id, child: c } };
          kids.push({ id: 'c:' + c.id, label: c.title, detail: c.description + ' · opens ' + c.opens, icon: 'bi bi-arrow-return-right', attrs: { 'data-ol-child': c.id } });
        });
        if (p.table) {
          items['n:' + p.id] = { kind: 'nest', data: p };
          kids.push({ id: 'n:' + p.id, label: 'Drop a panel here: it opens on a click on a row of ' + p.title, fixed: true, cls: 'ol-nest', attrs: { 'data-ol-nest': p.id } });
        }
        panels.push({ id: pk, label: p.title, detail: p.description, children: kids, attrs: { 'data-ol-panel': p.id || String(p.index), 'data-ol-in': s.id || '' } });
      });
      nodes.push({ id: sk, label: s.title, detail: s.panels.length + ' panel' + (s.panels.length === 1 ? '' : 's'), icon: 'bi ' + (s.id ? 'bi-collection' : 'bi-window'),
        fixed: true, cls: 'ol-sec', attrs: { 'data-ol-sec': s.id || '' }, children: panels });
    });
    outline.setNodes(nodes);
    picks.prune(function (k) { return !!items[k]; });
    sutra.textContent = state.yaml || '';
    root.setAttribute('data-ol-rev', String(++rev));
  }

  /* nesting asks which click opens it, with the sentence's own places */
  function ask(d, parent) {
    var p = null;
    state.sections.forEach(function (s) { s.panels.forEach(function (x) { if (x.index === d.index) { p = x; } }); });
    var where = el('select', { 'class': 'dz-pill', 'aria-label': 'where it opens', 'data-ol-place': '' });
    Object.keys(state.places).forEach(function (k) { where.appendChild(el('option', { value: k, text: state.places[k] })); });
    where.value = 'detail-below';
    detail.innerHTML = '';
    detail.appendChild(el('h3', { 'class': 'dz-h', text: 'Open it on a click' }));
    detail.appendChild(el('div', { 'class': 'dz-sentence' }, ['a row in ', el('b', { text: parent.title }), ' shows ', el('b', { text: (p || {}).title || 'the panel' }), ' in ', where]));
    detail.appendChild(el('div', { 'class': 'dz-row' }, [
      el('button', { type: 'button', 'class': 'dz-btn dz-primary', 'data-ol-nest-apply': '', text: 'Make it so', on: { click: function () {
        step({ op: 'nest', index: d.index, under: parent.id, place: where.value }).then(function () {   // the new child picked: its settings shown
          if (p && items['c:' + p.id]) { picks.set(['c:' + p.id], 'step'); } }); } } }),
      el('button', { type: 'button', 'class': 'dz-btn', text: 'Cancel', on: { click: function () { detail.innerHTML = ''; } } })]));
    where.focus();
  }

  function settingSelect(label, options, value, onChange, attr) {
    var s = el('select', { 'class': 'dz-pill', 'aria-label': label });
    s.setAttribute(attr, '');
    options.forEach(function (o) { s.appendChild(el('option', { value: o[0], text: o[1] })); });
    s.value = String(value);
    s.addEventListener('change', function () { onChange(s.value); });
    return el('label', { 'class': 'ol-set' }, [label + ' ', s]);
  }

  function showDetail(kindOf, d) {
    detail.innerHTML = '';
    if (kindOf === 'section') {
      var name = el('input', { 'class': 'dz-search', value: d.title, 'aria-label': 'Section title', 'data-ol-sec-title': '' });
      detail.appendChild(el('h3', { 'class': 'dz-h', text: 'Section: ' + d.title }));
      detail.appendChild(el('p', { 'class': 'dz-note', text: 'A section folds open and shut on the page; each reader’s choice is kept for them.' }));
      detail.appendChild(el('div', { 'class': 'dz-row' }, [name,
        el('button', { type: 'button', 'class': 'dz-btn', text: 'Rename', on: { click: function () { step({ op: 'rename-section', section: d.id, title: name.value }); } } }),
        el('button', { type: 'button', 'class': 'dz-btn', 'data-ol-sec-remove': '', text: 'Remove section', on: { click: function () { step({ op: 'remove-section', section: d.id }); } } })]));
      return;
    }
    if (kindOf === 'child') {
      var c = d.child;
      detail.appendChild(el('h3', { 'class': 'dz-h', text: c.title }));
      detail.appendChild(el('p', { 'class': 'ol-what', text: c.description }));
      detail.appendChild(el('p', { 'class': 'dz-note', text: 'Opens on a click on a row of the table above it.' }));
      detail.appendChild(settingSelect('Where it opens', Object.keys(state.places).map(function (k) { return [k, state.places[k]]; }), c.settings.place,
        function (v) { step({ op: 'set', index: d.parent, group: c.group, key: 'place', value: v }); }, 'data-ol-set-place'));
      detail.appendChild(settingSelect('When it shows', Object.keys(state.shows).map(function (k) { return [k, state.shows[k]]; }), c.settings.show,
        function (v) { step({ op: 'set', index: d.parent, group: c.group, key: 'show', value: v }); }, 'data-ol-set-show'));
      detail.appendChild(el('div', { 'class': 'dz-row' }, [el('button', { type: 'button', 'class': 'dz-btn', 'data-ol-unnest': '', text: 'Un-nest (back on the page)',
        on: { click: function () { step({ op: 'unnest', parent: d.parentId }); } } })]));
      return;
    }
    var p = d, set = p.settings || {};
    detail.appendChild(el('h3', { 'class': 'dz-h', text: p.title }));
    detail.appendChild(el('p', { 'class': 'ol-what', text: p.description }));
    var widths = []; for (var n = 12; n >= 2; n--) { widths.push([String(n), n === 12 ? 'the whole width' : n + ' of 12 columns']); }
    detail.appendChild(settingSelect('Width', widths, set.span || 12, function (v) { step({ op: 'set', index: p.index, key: 'span', value: +v }); }, 'data-ol-set-span'));
    if (state.areas.length) {
      detail.appendChild(settingSelect('Area', state.areas.map(function (a) { return [a, a.replace(/-/g, ' ')]; }), set.area || state.areas[0],
        function (v) { step({ op: 'set', index: p.index, key: 'area', value: v }); }, 'data-ol-set-area'));
    }
    if (state.sections.length > 1) {
      var here = state.sections.filter(function (s) { return s.panels.some(function (x) { return x.index === p.index; }); })[0];
      detail.appendChild(settingSelect('Section', state.sections.map(function (s) { return [s.id, s.id ? s.title : '(none)']; }), here ? here.id : '',
        function (v) { step({ op: 'move', index: p.index, to: p.index, section: v }); }, 'data-ol-set-section'));
    }
    var fold = el('input', { type: 'checkbox', 'data-ol-set-fold': '' });
    fold.checked = !!set.collapsible;
    fold.addEventListener('change', function () { step({ op: 'set', index: p.index, key: 'collapsible', value: fold.checked }); });
    detail.appendChild(el('label', { 'class': 'ol-set' }, [fold, ' The reader may fold it']));
    if (set.refresh != null) {
      var r = el('input', { type: 'number', min: '1', max: '86400', value: String(set.refresh), 'class': 'dz-pill', 'aria-label': 'Refresh every (seconds)' });
      r.addEventListener('change', function () { step({ op: 'set', index: p.index, key: 'refresh', value: +r.value }); });
      detail.appendChild(el('label', { 'class': 'ol-set' }, ['Refresh every ', r, ' seconds']));
    }
    detail.appendChild(el('div', { 'class': 'dz-row' }, [
      el('button', { type: 'button', 'class': 'dz-btn', 'data-ol-up': '', text: 'Move up', disabled: p.index === 0, on: { click: function () { step({ op: 'move', index: p.index, to: p.index - 1 }); } } }),
      el('button', { type: 'button', 'class': 'dz-btn', 'data-ol-down': '', text: 'Move down', on: { click: function () { step({ op: 'move', index: p.index, to: p.index + 1 }); } } })]));
  }

  function load() {
    return api('GET', '/designer/outline/state/' + encodeURIComponent(draft)).then(function (s) {
      state = s; draw();
      show(picks.primary());                                                     // the picked item stays picked after a step, if it is still there
    });
  }

  root.querySelector('[data-ol-add-section]').addEventListener('click', function () {
    var t = window.prompt('The new section’s title', 'New section');
    if (t != null) { step({ op: 'add-section', title: t }); }
  });
  var yamlBtn = root.querySelector('[data-ol-yaml]');
  yamlBtn.addEventListener('click', function () { sutra.hidden = !sutra.hidden; yamlBtn.setAttribute('aria-expanded', String(!sutra.hidden)); yamlBtn.textContent = sutra.hidden ? 'Show the Sutra' : 'Hide the Sutra'; });
  root.querySelector('[data-ol-cancel]').addEventListener('click', function () {
    api('POST', '/designer/cancel/' + encodeURIComponent(draft)).finally(function () { location.href = '/v/' + encodeURIComponent(kind) + '/' + encodeURIComponent(id); });
  });
  root.querySelector('[data-ol-done]').addEventListener('click', function () {
    say('Keeping your page…', true);
    api('POST', '/designer/done/' + encodeURIComponent(draft), { kind: kind }).then(function () { location.href = '/v/' + encodeURIComponent(kind) + '/' + encodeURIComponent(id); })
      .catch(function (e) { say(e.message); });
  });
  load().catch(function (e) { say(e.message); });
})();
