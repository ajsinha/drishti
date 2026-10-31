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
/* The Controls tab: the Sutra's `controls:` edited visually, two ways (BI_DESIGN.md 8.1, RACHANA_REFERENCE.md "The Controls tab").
 *
 *   cards     a card per control: id, kind, label, options (a list, an expression over the record such as $.legs[*].id, or a lookup kind), default,
 *             placement (strip, header of a panel, side), remember, required; a cascading control names its parent, a number range or a slider its bounds (a slider also its step and whether it has two handles).
 *             Drag a card by its handle, or focus it and press Alt+Up or Alt+Down (the buttons do the same); Delete removes the focused card.
 *   YAML      the same controls as text, below. Edit either: the cards write the YAML, and typed YAML (once it reads) rebuilds the cards.
 *   keeping   after a pause the controls go to the server as ONE operation, setControls: a step of the design's log (undo, redo and revisions
 *             cover it), validated as the Sutra is (DRS-2034..2039 appear here and in Problems). The text of the Sutra elsewhere is untouched.
 *   preview   the panels that read a control, drawn from the previewed sample with the REAL controls (the console's own controls.js), so a
 *             control can be tried before the design is saved; options come from the sample.
 * All text from the server goes in as text.
 *
 *   new WB.ControlsTab(pane, store) -> {show(), flush(), pending(), value(), model()}                                                          */
(function () {
  'use strict';
  var WB = window.DrishtiWB = window.DrishtiWB || {};
  var SAVE = 700, KINDS = ['dropdown', 'multi-select', 'cascading', 'toggle', 'segmented', 'date', 'date-range', 'number-range', 'slider', 'top-n', 'search'];
  var LISTED = { dropdown: 1, 'multi-select': 1, cascading: 1, toggle: 1, segmented: 1, 'top-n': 1 };
  var KEYS = ['id', 'kind', 'label', 'options', 'default', 'remember', 'required', 'placement', 'panel', 'parent', 'min', 'max', 'step', 'range'];
  var PLACES = ['strip', 'header', 'side'];

  /** An option field's text ("a, b, c" or an expression or "kind: customer") as the Sutra stores it. */
  function optionsOut(t) {
    var s = (t || '').trim();
    if (!s) { return undefined; }
    var m = /^kind:\s*([A-Za-z0-9_-]+)$/.exec(s);
    if (m) { return { kind: m[1] }; }
    if (/[$@()\[\]]/.test(s)) { return s; }                                             // an expression
    return s.split(',').map(function (x) { return x.trim(); }).filter(Boolean).map(function (x) { return /^-?\d+$/.test(x) ? +x : x; });
  }
  function optionsIn(o) {
    if (o == null) { return ''; }
    if (typeof o === 'string') { return o; }
    if (Array.isArray(o)) { return o.map(function (x) { return x && typeof x === 'object' ? x.value : x; }).join(', '); }
    return o.kind ? 'kind: ' + o.kind : '';
  }

  WB.ControlsTab = function (pane, store) {
    var $ = function (s) { return pane.querySelector(s); };
    var list = $('[data-ctl-list]'), src = $('[data-ctl-src]'), probBox = $('[data-ctl-problems]'), prev = $('[data-ctl-preview]'), statusEl = $('[data-ctl-status]');
    var base = '/build/designs/' + encodeURIComponent(store.state.id);
    var items = [], saveT = 0, applying = false, dragFrom = -1, mine = 0, runtime = null, previewToken = 0, loaded = false;

    // ---- the model and its YAML ---------------------------------------------------------------------------------------------------
    function q(v) { return JSON.stringify(v); }
    function flowValue(v) { return Array.isArray(v) ? '[' + v.map(q).join(', ') + ']' : v && typeof v === 'object' ? '{ ' + Object.keys(v).map(function (k) { return k + ': ' + q(v[k]); }).join(', ') + ' }' : typeof v === 'boolean' || typeof v === 'number' ? String(v) : q(String(v)); }
    function line(c) {
      var parts = [];
      KEYS.forEach(function (k) { var v = c[k]; if (v === undefined || v === '' || v === false || (Array.isArray(v) && !v.length)) { return; } parts.push(k + ': ' + flowValue(v)); });
      return '- { ' + parts.join(', ') + ' }';
    }
    function toYaml() { return items.map(line).join('\n') + (items.length ? '\n' : ''); }
    /** What goes to the server: the cards as plain objects, in the keys' order (remember is user or none). */
    function toOps() {
      return items.map(function (c) {
        var o = {};
        KEYS.forEach(function (k) {
          var v = c[k];
          if (v === undefined || v === '' || v === false || (Array.isArray(v) && !v.length)) { return; }
          o[k] = k === 'remember' ? 'user' : v;
        });
        return o;
      });
    }
    function fromSutra(list) {
      return (list || []).filter(function (c) { return c && typeof c === 'object' && !Array.isArray(c); }).map(function (c) {
        var o = {};
        KEYS.forEach(function (k) { if (c[k] !== undefined && c[k] !== null) { o[k] = k === 'remember' ? c[k] === 'user' || c[k] === true : c[k]; } });
        if (typeof c.default !== 'undefined' && c.default !== null) { o.default = Array.isArray(c.default) ? c.default.join(',') : c.default; }
        if (o.required !== undefined) { o.required = o.required === true || o.required === 'true'; }
        return o;
      });
    }
    function parseText(text) {
      var m = WB.model('controls:\n' + text.split('\n').map(function (l) { return '  ' + l; }).join('\n'));
      return fromSutra(m.controls);
    }
    function setText(t) { applying = true; src.value = t; applying = false; }
    function value() { return src.value; }
    function panelIds() { return WB.model(store.state.yaml).ids; }

    // ---- painting -------------------------------------------------------------------------------------------------------------------
    function labelled(text, control, cls) { var l = WB.el('label', 'wbf-lbl ' + (cls || '')); l.appendChild(WB.el('span', null, text)); l.appendChild(control); return l; }
    function field(c, key, type, extra) {
      var i = WB.el('input', 'wbf-in', null, { type: type || 'text', 'aria-label': (c.id || 'control') + ' ' + key });
      if (extra) { Object.keys(extra).forEach(function (k) { i.setAttribute(k, extra[k]); }); }
      i.value = c[key] === undefined ? '' : c[key];
      i.addEventListener('input', function () { if (i.value === '') { delete c[key]; } else { c[key] = i.value; } changed(false); });
      return i;
    }
    function select(c, key, values, blank) {
      var s = WB.el('select', 'wbf-in', null, { 'aria-label': (c.id || 'control') + ' ' + key });
      if (blank !== undefined) { s.appendChild(WB.el('option', null, blank, { value: '' })); }
      values.forEach(function (v) { var o = WB.el('option', null, v, { value: v }); if (v === c[key]) { o.selected = true; } s.appendChild(o); });
      return s;
    }
    function check(c, key, text) {
      var k = WB.el('input', null, null, { type: 'checkbox' }), l = WB.el('label', 'wbf-chk');
      k.checked = !!c[key]; k.addEventListener('change', function () { if (k.checked) { c[key] = true; } else { delete c[key]; } changed(false); });
      l.appendChild(k); l.appendChild(WB.el('span', null, text)); return l;
    }
    function card(c, i) {
      var li = WB.el('li', 'wbf-card', null, { tabindex: '0', draggable: 'true', 'data-i': String(i), 'aria-label': (c.label || c.id || 'new control') + ', ' + (c.kind || 'dropdown') + ', control ' + (i + 1) + ' of ' + items.length });
      var head = WB.el('div', 'wbf-head');
      head.appendChild(WB.el('span', 'wbf-grip', '⠿', { 'aria-hidden': 'true', title: 'Drag to reorder' }));
      head.appendChild(WB.el('code', 'mono wbf-path', 'ctl.' + (c.id || '?')));
      var up = WB.el('button', 'btn-pill btn-ghost', '↑', { type: 'button', 'aria-label': 'Move ' + c.id + ' up' }), down = WB.el('button', 'btn-pill btn-ghost', '↓', { type: 'button', 'aria-label': 'Move ' + c.id + ' down' }),
        del = WB.el('button', 'btn-pill btn-ghost', '✕', { type: 'button', 'aria-label': 'Remove ' + c.id });
      up.addEventListener('click', function () { move(i, -1); }); down.addEventListener('click', function () { move(i, 1); }); del.addEventListener('click', function () { remove(i); });
      [up, down, del].forEach(function (b) { head.appendChild(b); });
      li.appendChild(head);
      var body = WB.el('div', 'wbf-body');
      body.appendChild(labelled('Id', field(c, 'id', 'text', { pattern: '[A-Za-z][A-Za-z0-9_]*', maxlength: '32' })));
      var kind = select(c, 'kind', KINDS);
      kind.addEventListener('change', function () { c.kind = kind.value; if (!LISTED[c.kind]) { delete c.options; } if (c.kind !== 'cascading') { delete c.parent; } changed(true, i); });
      body.appendChild(labelled('Kind', kind));
      body.appendChild(labelled('Label', field(c, 'label')));
      if (LISTED[c.kind || 'dropdown']) {
        var o = WB.el('input', 'wbf-in', null, { type: 'text', 'aria-label': c.id + ' options', placeholder: 'a, b, c  or  $.legs[*].id  or  kind: customer' });
        o.value = optionsIn(c.options);
        o.addEventListener('input', function () { var v = optionsOut(o.value); if (v === undefined) { delete c.options; } else { c.options = v; } changed(false); });
        body.appendChild(labelled('Options', o, 'wbf-wide'));
      }
      if (c.kind === 'cascading') {
        var par = select(c, 'parent', items.slice(0, i).map(function (x) { return x.id; }).filter(Boolean), '(choose)');
        par.addEventListener('change', function () { if (par.value) { c.parent = par.value; } else { delete c.parent; } changed(false); });
        body.appendChild(labelled('Parent', par));
      }
      if (c.kind === 'number-range' || c.kind === 'slider') { body.appendChild(labelled('Min', field(c, 'min'))); body.appendChild(labelled('Max', field(c, 'max'))); }
      if (c.kind === 'slider') { body.appendChild(labelled('Step', field(c, 'step'))); }
      body.appendChild(labelled('Default', field(c, 'default')));
      var place = select(c, 'placement', PLACES);
      place.addEventListener('change', function () { c.placement = place.value; if (c.placement !== 'header') { delete c.panel; } if (c.placement === 'strip') { delete c.placement; } changed(true, i); });
      if (!c.placement) { place.value = 'strip'; }
      body.appendChild(labelled('Placement', place));
      if (c.placement === 'header') {
        var pn = select(c, 'panel', panelIds(), '(choose a panel)');
        pn.addEventListener('change', function () { if (pn.value) { c.panel = pn.value; } else { delete c.panel; } changed(false); });
        body.appendChild(labelled('Panel', pn));
      }
      var flags = WB.el('div', 'wbf-flags');
      if (c.kind === 'slider') { flags.appendChild(check(c, 'range', 'Two handles (a range)')); }
      flags.appendChild(check(c, 'remember', 'Remember per reader')); flags.appendChild(check(c, 'required', 'Required'));
      body.appendChild(flags);
      li.appendChild(body);
      var idIn = body.querySelector('input[aria-label$=" id"]'), shown = li.querySelector('.wbf-path'), was = c.id || 'control';
      idIn.addEventListener('input', function () {        // the names a screen reader gives the card's fields follow the id as it is typed
        var now = c.id || 'control';
        li.querySelectorAll('[aria-label]').forEach(function (e) { var a = e.getAttribute('aria-label'); if (a.indexOf(was + ' ') === 0 || a.indexOf(' ' + was) > 0) { e.setAttribute('aria-label', a.split(was).join(now)); } });
        shown.textContent = 'ctl.' + (c.id || '?'); was = now;
      });
      li.addEventListener('dragstart', function (e) { dragFrom = i; li.classList.add('wbf-drag'); if (e.dataTransfer) { e.dataTransfer.effectAllowed = 'move'; e.dataTransfer.setData('text/plain', c.id || ''); } });
      li.addEventListener('dragend', function () { dragFrom = -1; li.classList.remove('wbf-drag'); });
      li.addEventListener('dragover', function (e) { if (dragFrom >= 0) { e.preventDefault(); } });
      li.addEventListener('drop', function (e) { e.preventDefault(); if (dragFrom >= 0 && dragFrom !== i) { reorder(dragFrom, i); } dragFrom = -1; });
      li.addEventListener('keydown', function (e) {
        if (e.target !== li) { return; }
        if (e.altKey && e.key === 'ArrowUp') { e.preventDefault(); move(i, -1); } else if (e.altKey && e.key === 'ArrowDown') { e.preventDefault(); move(i, 1); }
        else if (e.key === 'Delete') { e.preventDefault(); remove(i); }
      });
      return li;
    }
    function paintList(focus) {
      list.textContent = '';
      if (!items.length) { list.appendChild(WB.el('li', 'text-muted-d', 'No controls yet. Add one: a dropdown over the record\'s own data ($.legs[*].id), a date range, a search box. Panels read them as ctl.<id> in rows, where, limit and title.')); }
      items.forEach(function (c, i) { list.appendChild(card(c, i)); });
      if (focus !== undefined && list.children[focus] && list.children[focus].tabIndex === 0) { list.children[focus].focus(); }
    }
    function paintProblems(ps) {
      probBox.textContent = '';
      if (!ps.length) { probBox.appendChild(WB.el('p', 'text-muted-d', items.length ? 'The controls read without problems.' : '')); return; }
      var ul = WB.el('ul', 'bs-list', null, { 'aria-label': 'Control problems' });
      ps.forEach(function (p) { var li = WB.el('li', 'wb-problem pr-yaml'); li.appendChild(WB.el('span', 'bs-role', p.code)); li.appendChild(document.createTextNode(' ' + WB.problemText(p))); ul.appendChild(li); });
      probBox.appendChild(ul);
    }

    // ---- edits ----------------------------------------------------------------------------------------------------------------------
    function changed(repaint, focus) {
      if (repaint) { paintList(focus); }
      setText(toYaml());
      clearTimeout(saveT); saveT = setTimeout(save, SAVE);
    }
    function nextId() { var n = 1; while (items.some(function (c) { return c.id === 'control' + n; })) { n++; } return 'control' + n; }
    function add(kind) {
      var c = { id: nextId(), kind: kind || 'dropdown' };
      if (LISTED[c.kind]) { c.options = c.kind === 'top-n' ? [5, 10, 25] : []; }
      items.push(c); changed(true, items.length - 1); store.emit('say', 'Added control ' + c.id + '.');
    }
    function remove(i) { var c = items.splice(i, 1)[0]; changed(true, Math.min(i, items.length - 1)); store.emit('say', 'Removed control ' + c.id + '.'); }
    function move(i, d) { var j = i + d; if (j >= 0 && j < items.length) { reorder(i, j); } }
    function reorder(from, to) { var c = items.splice(from, 1)[0]; items.splice(to, 0, c); changed(true, to); store.emit('say', 'Control ' + c.id + ' is now ' + (to + 1) + ' of ' + items.length + '.'); }

    function save() {
      clearTimeout(saveT); saveT = 0;
      var mineNow = ++mine;
      sending = true;
      return store.send([{ op: 'setControls', items: toOps() }], 'Controls updated').then(function (r) {
        sending = false;
        if (mineNow !== mine) { return; }
        paintProblems(r.problems || []);
        statusEl.textContent = r.ok ? '' : 'Not applied: fix the problems listed.';
        if (r.ok) { refreshPreview(); }
      });
    }
    function pending() { return !!saveT; }
    function flush() { return saveT ? save() : Promise.resolve(); }

    // ---- the Sutra changed elsewhere (undo, the YAML tab, the canvas): the cards follow ---------------------------------------------------------
    var sending = false;
    store.on('doc', function (d) {
      if (!d.changed || sending || saveT) { return; }                                  // our own change, or the person is editing here: their cards win
      var now = fromSutra(WB.model(d.yaml).controls);
      if (JSON.stringify(now) === JSON.stringify(items)) { return; }
      if (document.activeElement && list.contains(document.activeElement)) { return; }
      items = now; paintList(); setText(toYaml()); paintProblems([]);
      if (loaded) { refreshPreview(); }
    });

    // ---- typed YAML -------------------------------------------------------------------------------------------------------------------
    src.addEventListener('input', function () {
      if (applying) { return; }
      var parsed;
      try { parsed = parseText(src.value); } catch (e) { statusEl.textContent = 'The YAML does not read yet.'; return; }
      statusEl.textContent = '';
      items = parsed; paintList(); clearTimeout(saveT); saveT = setTimeout(save, SAVE);
    });
    src.addEventListener('blur', function () { if (saveT) { save(); } });

    // ---- the small live preview ---------------------------------------------------------------------------------------------------------------
    function rewrite(html) { return html.replace(/\b(id|aria-labelledby|aria-controls|for)="(p-|h-|pv-)/g, '$1="wbc-$2'); }
    function fetchJson(url) { return fetch(url, { credentials: 'same-origin', headers: { Accept: 'application/json' } }).then(function (r) { return r.json().then(function (j) { if (!r.ok) { throw new Error(j.detail || 'HTTP ' + r.status); } return j; }); }); }
    function refreshPreview() {
      loaded = true;
      var mineP = ++previewToken, sample = store.state.sample || '';
      if (runtime) { runtime.dispose(); runtime = null; }
      prev.textContent = '';
      if (!items.length) { prev.appendChild(WB.el('p', 'text-muted-d', 'The controls appear here, working, over the panels that read them.')); return; }
      if (!sample && !store.state.file) { prev.appendChild(WB.el('p', 'text-muted-d', 'Add a sample under Data to try the controls on it.')); return; }
      var m = WB.model(store.state.yaml), ctlIds = items.map(function (c) { return c.id; });
      var reading = m.panels.filter(function (p) { return JSON.stringify(p.values).match(/ctl\.([A-Za-z]\w*)/g) && ctlIds.some(function (id) { return JSON.stringify(p.values).indexOf('ctl.' + id) >= 0; }); }).map(function (p) { return p.id; });
      var headers = items.filter(function (c) { return c.placement === 'header' && c.panel; }).map(function (c) { return c.panel; });
      var ids = reading.concat(headers.filter(function (x) { return reading.indexOf(x) < 0; }));
      var url = base + '/preview/panels?sample=' + encodeURIComponent(sample) + (ids.length ? '&panels=' + ids.map(encodeURIComponent).join(',') : '&panels=-');
      fetchJson(url).then(function (r) {
        if (mineP !== previewToken) { return; }
        var view = WB.el('div', 'view wbc-view', null, { 'data-view': '', 'data-controls': JSON.stringify(r.controls || []), 'data-selects': JSON.stringify(r.selects || []) });
        var head = WB.el('section', 'vhead'), grid = WB.el('div', 'vgrid'), main = WB.el('div', 'vmain');
        grid.appendChild(main); view.appendChild(head); view.appendChild(grid);
        prev.appendChild(view);
        var holder = document.createElement('template');
        holder.innerHTML = rewrite((r.panels || []).map(function (p) { return p.html; }).join(''));
        main.appendChild(holder.content);
        if (!(r.panels || []).length) { main.appendChild(WB.el('p', 'text-muted-d', 'No panel reads a control yet: use ctl.<id> in a panel\'s rows, where, limit or title.')); }
        var mods = window.drishtiModules || {};
        if (mods.controls && (r.controls || []).length) {
          runtime = mods.controls.init(document, {
            view: view, urlState: false, scope: 'wb-' + store.state.id, idPrefix: 'wbc-p-', rewrite: rewrite,
            panelsUrl: function (qs) { return base + '/preview/panels?sample=' + encodeURIComponent(sample) + '&' + qs; }, fetchJson: fetchJson,
            afterSwap: function (fresh) { if (window.drishti) { window.drishti.enhance(fresh); } }
          });
        }
      }).catch(function (e) {
        if (mineP !== previewToken) { return; }
        prev.appendChild(WB.el('p', 'text-muted-d', 'The preview could not be drawn: ' + e.message));
      });
    }
    store.on('sample', function () { if (loaded) { refreshPreview(); } });

    // ---- starters -------------------------------------------------------------------------------------------------------------------------
    $('[data-ctl-add]').addEventListener('click', function () { var k = $('[data-ctl-new-kind]'); add(k.value); });
    $('[data-ctl-starter]').addEventListener('click', function () {
      var m = WB.model(store.state.yaml), go = function () {
        var first = m.panels.filter(function (p) { return p.kind === 'table' || p.kind === 'ladder'; })[0];
        items = [first ? { id: 'q', kind: 'search', label: 'Find', placement: 'header', panel: first.id } : { id: 'q', kind: 'search', label: 'Find' }];
        paintList(); changed(false);
        store.emit('say', 'A search box was added' + (first ? ' to the header of panel ' + first.id : '') + '. Use contains(@.field, ctl.q) in that panel\'s where.');
      };
      if (!items.length) { go(); return; }
      WB.ask({ title: 'Replace the controls?', message: 'This replaces the controls. Undo (Ctrl+Z) brings them back.', ok: 'Replace' }).then(function (yes) { if (yes) { go(); } });
    });

    items = fromSutra(WB.model(store.state.yaml).controls);
    setText(toYaml());
    return { show: function () { paintList(); if (!loaded) { refreshPreview(); } }, flush: flush, pending: pending, value: value, model: function () { return items; }, preview: refreshPreview };
  };
})();
