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
/* The Pivot tab (USER_GUIDE.md, The Pivot tab), only on table and ladder panels whose Sutra says pivot (and on search
   results whose kind's pack says so). Table | Pivot switches the panel between its table and an Excel-like pivot:
   - Fields (only those the Sutra or pack allows) are dragged with Pointer Events (mouse, pen, touch) into Rows,
     Columns, Values and Filters, reordered there, or dragged back to the list to remove them. Keys on a field: R, C,
     V, F put it in a zone, Delete takes it out, Alt+arrows reorder it, Enter opens its settings (a value's
     aggregation and show-as; a filter's pick list and range). Every change is announced.
   - The grid (pivot-grid.js): subtotals and totals, collapsible groups, rows sorted by any value column, heat
     shading; arrows move between cells and Enter (or a click) shows the rows under a cell.
   - Chart (ECharts: bar, line, heatmap), Export CSV and Excel, Print, Save (the user's own arrangement), Reset (back
     to the Sutra's), and for authors Promote to Sutra… (the arrangement as the Sutra's next version, with its diff).
   A panel's pivot is computed here from the panel's rows (pivot-engine.js), read whole once from
   /api/pivot/records/…; a search's pivot is computed by the server over the whole day (/api/pivot/search/…). */
(function () {
  'use strict';
  var me = document.currentScript, reg0 = window.drishtiModules || {};
  var E = reg0.pivotEngine || window.drishtiPivotEngine, G = reg0.pivotGrid || window.drishtiPivotGrid;
  if (!E || !G) { return; }
  /** init(root, options): the Pivot tab of every opted-in panel under root (the document or a ShadowRoot), now and as panels
      are swapped in. options: fetch(url, init) and url(path) (default: the page's own, same origin), save (false: no Save,
      Reset or Promote, the arrangement is the Sutra's; an embedded view), exports (false: no CSV, Excel or Print).
      Returns {state, scan, dispose}. Dialogs, drag ghosts and keys stay inside root. */
  function init(root, options) {
  options = options || {};
  var doFetch = options.fetch || function (u, o) { return fetch(u, o); };
  var toUrl = options.url || function (p) { return p; };
  var canSave = options.save !== false, canExport = options.exports !== false;
  var layer = root === document ? document.body : root;           // where a dialog or a drag ghost goes
  var undo = [];
  function on(t, type, fn, cap) { t.addEventListener(type, fn, cap); undo.push(function () { t.removeEventListener(type, fn, cap); }); }
  function origin() {                                             // an element's host is the containing block of fixed boxes (it contains layout)
    if (!root.host) { return { x: 0, y: 0 }; }
    var r = root.host.getBoundingClientRect();
    return { x: r.left, y: r.top };
  }
  var ZONES = ['rows', 'columns', 'values', 'filters'];
  var ZONE_LABEL = { rows: 'Rows', columns: 'Columns', values: 'Values', filters: 'Filters', fields: 'the field list' };
  var ZONE_KEY = { r: 'rows', c: 'columns', v: 'values', f: 'filters' };
  var MAX = { rows: E.LIMITS.rows, columns: E.LIMITS.columns, values: E.LIMITS.values, filters: 12 };
  var STATE = {};                   // per panel (or searched kind): arrangement, grid state, records, open tab
  var view = root.querySelector('.view[data-view]');

  function el(tag, cls, text) { var e = document.createElement(tag); if (cls) { e.className = cls; } if (text != null) { e.textContent = text; } return e; }
  function button(label, title, cls) {
    var b = el('button', 'btn-pill btn-ghost btn-sm-pill pv-btn' + (cls ? ' ' + cls : ''), label);
    b.type = 'button'; if (title) { b.title = title; }
    return b;
  }
  function parse(s) { try { return s ? JSON.parse(s) : null; } catch (e) { return null; } }
  function json(method, url, body) {
    var o = { method: method, headers: { Accept: 'application/json' } };
    if (body !== undefined) { o.headers['Content-Type'] = 'application/json'; o.body = JSON.stringify(body); }
    return doFetch(toUrl(url), o).then(function (r) {
      return r.text().then(function (t) { var b = parse(t) || {}; return { ok: r.ok, status: r.status, b: b }; });
    }, function () { return { ok: false, status: 0, b: { code: 'DRS-5003', detail: 'the console could not be reached' } }; });
  }
  function problem(res) { return (res.b && res.b.detail) || ('HTTP ' + res.status); }
  function tokens() {
    var s = getComputedStyle(root.host || document.documentElement), t = {};
    ['ink', 'muted', 'faint', 'border', 'link', 'accent', 'neg', 'pos', 'ok', 'warn', 'bad', 'surface', 'bg-2'].forEach(function (k) {
      t[k] = s.getPropertyValue('--d-' + k).trim();
    });
    t.mono = s.getPropertyValue('--d-font-mono').trim();
    return t;
  }

  // ---- one pivot ------------------------------------------------------------------------------------------------
  function Pivot(box) {
    var self = this;
    this.box = box;
    this.host = box.querySelector('.pv-host');
    this.spec = parse(this.host.getAttribute('data-pivot')) || { fields: [] };
    this.fields = this.spec.fields || [];
    this.search = this.host.getAttribute('data-pivot-search') || '';
    this.panelId = this.host.getAttribute('data-pivot-panel') || '';
    this.q = this.host.getAttribute('data-pivot-q') || '';
    this.sutra = view ? view.getAttribute('data-view-sutra') || '' : '';
    this.promote = !this.search && !!this.sutra && !!(view && view.hasAttribute('data-pivot-promote'));
    var key = this.search ? 'search:' + this.search : 'panel:' + this.panelId;
    var saved = canSave ? parse(this.host.getAttribute('data-pivot-saved')) : null;
    this.st = STATE[key] || (STATE[key] = {
      arr: E.normalise(saved || this.spec, this.fields), saved: !!saved, open: false, records: null, documents: false,
      grid: { collapsedRows: {}, collapsedCols: {}, sort: null, totals: this.spec.totals !== false, heat: !!(saved || this.spec).heat, chartValue: 0 }
    });
    this.st.grid.heat = !!this.st.arr.heat;
    this.tabs = Array.prototype.slice.call(box.querySelectorAll('[data-pv-tab]'));
    this.tablePane = box.querySelector('.pv-pane-table');
    this.tabs.forEach(function (t) {
      t.addEventListener('click', function () { self.show(t.getAttribute('data-pv-tab'), true); });
      t.addEventListener('keydown', function (e) {
        if (e.key === 'ArrowLeft' || e.key === 'ArrowRight' || e.key === 'Home' || e.key === 'End') {
          e.preventDefault();
          var other = self.tabs[t === self.tabs[0] ? 1 : 0];
          other.focus(); self.show(other.getAttribute('data-pv-tab'), true);
        }
      });
    });
    if (this.st.open) { this.show('pivot', false); }
  }

  Pivot.prototype.url = function (tail) {
    if (this.search) { return '/api/pivot/saved/search/' + encodeURIComponent(this.search) + (tail || ''); }
    return '/api/pivot/saved/panel/' + encodeURIComponent(this.sutra) + '/' + encodeURIComponent(this.panelId) + (tail || '');
  };
  Pivot.prototype.mayKeep = function () { return canSave && (!!this.search || !!this.sutra); };
  Pivot.prototype.say = function (text) { if (this.live) { this.live.textContent = ''; this.live.textContent = text; } };
  Pivot.prototype.field = function (name) { return this.fields.filter(function (f) { return f.name === name; })[0] || { name: name, label: name }; };
  Pivot.prototype.labelOf = function (name) { return this.field(name).label || name; };

  Pivot.prototype.show = function (which, user) {
    var pivot = which === 'pivot', pnl = this.box.closest('.pnl');
    this.st.open = pivot;
    this.tabs.forEach(function (t) {
      var on = t.getAttribute('data-pv-tab') === which;
      t.setAttribute('aria-selected', on ? 'true' : 'false');
      t.tabIndex = on ? 0 : -1;
      t.classList.toggle('on', on);
    });
    if (this.tablePane) { this.tablePane.hidden = pivot; }
    this.host.hidden = !pivot;
    if (pnl) { pnl.classList.toggle('pv-on', pivot); }     // pivot.css hides the table's paging bar in the heading
    if (pivot) {
      if (!this.built) { this.build(); }
      this.load();
      if (this.chartBox && !this.chartBox.hidden && this.chart) { this.chart.resize(); }
    }
    if (user) { this.say(pivot ? 'Pivot shown' : 'Table shown'); }
  };

  // ---- the pane -------------------------------------------------------------------------------------------------
  Pivot.prototype.build = function () {
    var self = this, h = this.host;
    this.built = true;
    h.textContent = '';
    this.live = el('span', 'visually-hidden'); this.live.setAttribute('role', 'status'); this.live.setAttribute('aria-live', 'polite');
    var top = el('div', 'pv-top');
    this.list = el('div', 'pv-zone pv-fields');
    this.list.setAttribute('data-zone', 'fields');
    this.zones = {};
    top.appendChild(this.list);
    var zs = el('div', 'pv-zones');
    ZONES.forEach(function (z) {
      var box = el('div', 'pv-zone pv-z-' + z);
      box.setAttribute('data-zone', z);
      box.setAttribute('role', 'group');
      box.setAttribute('aria-label', ZONE_LABEL[z]);
      zs.appendChild(box);
      self.zones[z] = box;
    });
    top.appendChild(zs);
    var help = el('p', 'pv-help text-muted-d', 'Drag fields into Rows, Columns, Values and Filters, or focus one and press R, C, V or F; ' +
      'Delete removes it, Alt+arrows move it, Enter opens its settings. In the grid: arrows move, Enter shows the rows under a cell.');
    help.id = 'pv-help-' + (this.search || this.panelId);
    this.helpId = help.id;
    // toolbar
    var bar = el('div', 'pv-bar');
    bar.setAttribute('role', 'toolbar');
    bar.setAttribute('aria-label', 'Pivot tools');
    function check(label, title, on, fn) {
      var l = el('label', 'pv-check'), c = el('input');
      c.type = 'checkbox'; c.checked = on; l.title = title;
      c.setAttribute('data-pv-' + label.toLowerCase(), '');
      c.addEventListener('change', function () { fn(c.checked); });
      l.appendChild(c); l.appendChild(document.createTextNode(' ' + label));
      return l;
    }
    bar.appendChild(check('Totals', 'Subtotals and grand totals', this.st.grid.totals, function (on) { self.st.grid.totals = on; self.draw(); self.say(on ? 'Totals shown' : 'Totals hidden'); }));
    var heat = check('Heat', 'Shade each cell by its value', this.st.grid.heat, function (on) { self.st.grid.heat = on; self.st.arr.heat = on; self.draw(); self.say(on ? 'Heat shading on' : 'Heat shading off'); });
    this.heatBox = heat.querySelector('input');
    bar.appendChild(heat);
    var chartSel = el('select', 'studio-select pv-chart-kind');
    chartSel.setAttribute('aria-label', 'Chart');
    [['', 'No chart'], ['bar', 'Bar chart'], ['line', 'Line chart'], ['heatmap', 'Heatmap']].forEach(function (o) {
      var op = el('option', null, o[1]); op.value = o[0]; chartSel.appendChild(op);
    });
    chartSel.value = this.st.arr.chart || '';
    chartSel.addEventListener('change', function () { self.st.arr.chart = chartSel.value || null; self.drawChart(); self.say(chartSel.value ? chartSel.options[chartSel.selectedIndex].text + ' shown' : 'Chart hidden'); });
    this.chartSel = chartSel;
    bar.appendChild(chartSel);
    this.chartValueSel = el('select', 'studio-select pv-chart-value');
    this.chartValueSel.setAttribute('aria-label', 'Value charted');
    this.chartValueSel.hidden = true;
    this.chartValueSel.addEventListener('change', function () { self.st.grid.chartValue = +self.chartValueSel.value; self.drawChart(); });
    bar.appendChild(this.chartValueSel);
    var expand = button('Expand all', 'Open every group'), collapse = button('Collapse all', 'Close every group to its total');
    expand.setAttribute('data-pv-expand', ''); collapse.setAttribute('data-pv-collapse', '');
    expand.addEventListener('click', function () { self.st.grid.collapsedRows = {}; self.st.grid.collapsedCols = {}; self.draw(); self.say('Every group expanded'); });
    collapse.addEventListener('click', function () { self.collapseAll(); });
    bar.appendChild(expand); bar.appendChild(collapse);
    var csv = button('CSV', 'Download the pivot as CSV'), xlsx = button('Excel', 'Download the pivot as an Excel workbook'), print = button('Print', 'Print this pivot');
    csv.setAttribute('data-pv-export', 'csv'); xlsx.setAttribute('data-pv-export', 'xlsx'); print.setAttribute('data-pv-print', '');
    csv.addEventListener('click', function () { self.exportAs('csv'); });
    xlsx.addEventListener('click', function () { self.exportAs('xlsx'); });
    print.addEventListener('click', function () { self.print(); });
    if (canExport) { [csv, xlsx, print].forEach(function (b) { bar.appendChild(b); }); }
    if (this.mayKeep()) {
      var save = button('Save', 'Keep this arrangement for yourself: it opens like this next time', 'btn-accent'), reset = button('Reset', 'Back to the default arrangement (forgets yours)');
      save.setAttribute('data-pv-save', ''); reset.setAttribute('data-pv-reset', '');
      save.addEventListener('click', function () { self.save(); });
      reset.addEventListener('click', function () { self.reset(); });
      bar.appendChild(save); bar.appendChild(reset);
      if (this.promote) {
        var pr = button('Promote to Sutra…', 'Propose this arrangement as the default of ' + this.sutra + ' (its next version)');
        pr.setAttribute('data-pv-promote', '');
        pr.addEventListener('click', function () { self.openPromote(); });
        bar.appendChild(pr);
      }
    }
    this.msg = el('div', 'pv-msg'); this.msg.setAttribute('role', 'status');
    this.status = el('div', 'pv-status text-muted-d mono');
    this.pop = el('div', 'pv-pop'); this.pop.hidden = true;
    this.gridHost = el('div', 'pv-gridhost');
    this.chartBox = el('div', 'chart pv-chart'); this.chartBox.hidden = true;
    this.chartBox.setAttribute('role', 'img');
    [top, help, this.pop, bar, this.msg, this.status, this.gridHost, this.chartBox, this.live].forEach(function (x) { h.appendChild(x); });
    this.drawFields();
    this.wireDrag();
    this.wireGrid();
  };

  // ---- fields and zones -----------------------------------------------------------------------------------------
  Pivot.prototype.chip = function (name, zone, i) {
    var f = this.field(name), b = el('button', 'pv-chip' + (zone === 'fields' ? '' : ' pv-in'));
    b.type = 'button';
    b.setAttribute('data-field', name);
    b.setAttribute('data-zone', zone);
    b.setAttribute('data-i', String(i));
    b.setAttribute('aria-describedby', this.helpId);
    var txt = f.label || name;
    if (zone === 'filters') {
      var flt = this.st.arr.filters[i] || {}, bits = [];
      if (flt.values && flt.values.length) { bits.push(flt.values.length === 1 ? flt.values[0] : flt.values.length + ' values'); }
      if (flt.min != null) { bits.push('≥ ' + flt.min); }
      if (flt.max != null) { bits.push('≤ ' + flt.max); }
      txt += bits.length ? ': ' + bits.join(', ') : ': all';
      b.title = 'Enter to choose values; Delete removes the filter';
    }
    b.appendChild(el('span', 'pv-chip-t', txt));
    if (f.numeric) { b.appendChild(el('span', 'pv-badge', '#')); }
    if (f.promoted === false) {
      var d = el('span', 'pv-badge pv-doc', 'doc');
      d.title = 'not kept as a column: needs a document read';
      b.appendChild(d);
      b.title = (b.title ? b.title + ' · ' : '') + 'not kept as a column: needs a document read';
    }
    b.setAttribute('aria-label', txt + (zone === 'fields' ? ', field' : ', in ' + ZONE_LABEL[zone] + ', ' + (i + 1) + ' of ' + this.zoneItems(zone).length));
    return b;
  };
  Pivot.prototype.zoneItems = function (z) {
    var a = this.st.arr;
    return z === 'values' ? a.values.map(function (v) { return v.field; }) : z === 'filters' ? a.filters.map(function (f) { return f.field; }) : a[z] || [];
  };
  Pivot.prototype.drawFields = function () {
    var self = this, a = this.st.arr;
    this.list.textContent = '';
    this.list.appendChild(el('h4', 'pv-zh', 'Fields'));
    var ul = el('div', 'pv-chips');
    this.fields.forEach(function (f, i) { ul.appendChild(self.chip(f.name, 'fields', i)); });
    this.list.appendChild(ul);
    ZONES.forEach(function (z) {
      var box = self.zones[z], items = self.zoneItems(z);
      box.textContent = '';
      box.appendChild(el('h4', 'pv-zh', ZONE_LABEL[z] + (items.length ? '' : '')));
      var chips = el('div', 'pv-chips');
      items.forEach(function (name, i) {
        if (z !== 'values') { chips.appendChild(self.chip(name, z, i)); return; }
        var v = a.values[i], row = el('div', 'pv-val');
        row.appendChild(self.chip(name, z, i));
        var agg = el('select', 'studio-select pv-agg'), show = el('select', 'studio-select pv-show');
        agg.setAttribute('aria-label', 'Aggregation of ' + self.labelOf(name));
        show.setAttribute('aria-label', 'Show ' + self.labelOf(name) + ' as');
        E.AGGS.forEach(function (g) { var o = el('option', null, E.AGG_LABEL[g].toLowerCase()); o.value = g; agg.appendChild(o); });
        E.SHOWS.forEach(function (s) { var o = el('option', null, E.SHOW_LABEL[s]); o.value = s; show.appendChild(o); });
        agg.value = v.agg; show.value = v.show;
        agg.setAttribute('data-i', String(i)); show.setAttribute('data-i', String(i));
        agg.addEventListener('change', function () { v.agg = agg.value; self.changed(self.labelOf(name) + ': ' + E.AGG_LABEL[v.agg].toLowerCase()); });
        show.addEventListener('change', function () { v.show = show.value; self.changed(self.labelOf(name) + ' shown as ' + E.SHOW_LABEL[v.show]); });
        [agg, show].forEach(function (s) {
          s.addEventListener('keydown', function (e) { if (e.key === 'Escape') { e.preventDefault(); var c = row.querySelector('.pv-chip'); if (c) { c.focus(); } } });
        });
        row.appendChild(agg); row.appendChild(show);
        chips.appendChild(row);
      });
      if (!items.length) { chips.appendChild(el('span', 'pv-empty text-muted-d', z === 'values' ? 'Drop a number here (count of rows otherwise)' : 'Drop a field here')); }
      box.appendChild(chips);
    });
  };

  /** Moves a field between the list and the zones; {@code to} 'fields' takes it out. Returns what happened, in words. */
  Pivot.prototype.move = function (name, from, fi, to, ti) {
    var a = this.st.arr, f = this.field(name), label = f.label || name;
    if (to === from && to !== 'fields') {
      var list = to === 'values' ? a.values : to === 'filters' ? a.filters : a[to];
      if (ti == null || ti < 0) { return null; }
      ti = Math.max(0, Math.min(list.length - 1, ti > fi ? ti - 1 : ti));
      if (ti === fi) { return null; }
      var item = list.splice(fi, 1)[0];
      list.splice(ti, 0, item);
      return label + ' is now ' + (ti + 1) + ' of ' + list.length + ' in ' + ZONE_LABEL[to];
    }
    if (to !== 'fields' && to !== 'values' && from !== to) {
      var room = to === 'filters' ? a.filters : a[to];
      var already = to === 'filters' ? a.filters.some(function (x) { return x.field === name; }) : a[to].indexOf(name) >= 0;
      if (!already && room.length >= MAX[to]) { return 'Not moved: ' + ZONE_LABEL[to] + ' holds at most ' + MAX[to] + ' fields'; }
    }
    if (to === 'values' && from !== 'values' && a.values.length >= MAX.values) { return 'Not moved: Values holds at most ' + MAX.values; }
    if (from === 'rows' || from === 'columns') { a[from].splice(fi, 1); }
    else if (from === 'values') { a.values.splice(fi, 1); }
    else if (from === 'filters') { a.filters.splice(fi, 1); }
    if (to === 'fields') { return label + ' removed from ' + ZONE_LABEL[from]; }
    var at = function (list) { return ti == null || ti < 0 || ti > list.length ? list.length : ti; };
    if (to === 'rows' || to === 'columns') {
      ['rows', 'columns'].forEach(function (z) { var k = a[z].indexOf(name); if (k >= 0) { a[z].splice(k, 1); } });
      a[to].splice(at(a[to]), 0, name);
    } else if (to === 'values') {
      a.values.splice(at(a.values), 0, { field: name, agg: f.numeric ? 'sum' : 'count', show: 'value' });
    } else if (to === 'filters') {
      var k = -1;
      a.filters.forEach(function (x, j) { if (x.field === name) { k = j; } });
      if (k >= 0) { a.filters.splice(k, 1); }
      a.filters.splice(at(a.filters), 0, { field: name });
    }
    return label + ' added to ' + ZONE_LABEL[to];
  };
  Pivot.prototype.apply = function (said, focus) {
    if (!said) { return; }
    this.drawFields();
    if (focus) {
      var c = this.host.querySelector('.pv-chip[data-zone="' + focus.zone + '"][data-field="' + focus.field.replace(/"/g, '\\"') + '"]')
        || this.host.querySelector('.pv-chip[data-zone="fields"][data-field="' + focus.field.replace(/"/g, '\\"') + '"]');
      if (c) { c.focus(); }
    }
    if (/^Not moved/.test(said)) { this.say(said); this.note(said, true); return; }
    this.changed(said);
  };
  Pivot.prototype.changed = function (said) {
    if (said) { this.say(said); }
    this.st.dirty = true;
    this.recompute();
  };
  Pivot.prototype.note = function (text, bad) {
    this.msg.textContent = text || '';
    this.msg.classList.toggle('t-bad', !!bad);
  };

  // ---- dragging (Pointer Events) and the keys on a field ---------------------------------------------------------
  Pivot.prototype.wireDrag = function () {
    var self = this, drag = null;
    function zoneAt(x, y) {
      var t = root.elementFromPoint(x, y);
      var z = t && t.closest ? t.closest('.pv-zone') : null;
      return z && self.host.contains(z) ? z : null;
    }
    function indexIn(z, x, y) {
      var chips = Array.prototype.slice.call(z.querySelectorAll('.pv-chip')), i;
      for (i = 0; i < chips.length; i++) {
        var r = chips[i].getBoundingClientRect();
        if (y < r.top) { return i; }
        if (y <= r.bottom && x < r.left + r.width / 2) { return i; }
      }
      return chips.length;
    }
    function clear() { self.host.querySelectorAll('.pv-over, .pv-ins').forEach(function (e) { e.classList.remove('pv-over', 'pv-ins'); }); }
    this.host.addEventListener('pointerdown', function (e) {
      var c = e.target.closest && e.target.closest('.pv-chip');
      if (!c || e.button !== 0 || !self.host.contains(c)) { return; }
      drag = { chip: c, x: e.clientX, y: e.clientY, id: e.pointerId, on: false };
    });
    this.host.addEventListener('pointermove', function (e) {
      if (!drag || e.pointerId !== drag.id) { return; }
      if (!drag.on) {
        if (Math.abs(e.clientX - drag.x) + Math.abs(e.clientY - drag.y) < 6) { return; }
        drag.on = true;
        try { drag.chip.setPointerCapture(e.pointerId); } catch (x) { /* already released */ }
        drag.ghost = drag.chip.cloneNode(true);
        drag.ghost.classList.add('pv-ghost');
        drag.ghost.removeAttribute('id');
        layer.appendChild(drag.ghost);
        drag.chip.classList.add('pv-dragging');
        self.host.classList.add('pv-drag-on');
      }
      e.preventDefault();
      var o = origin();
      drag.ghost.style.left = (e.clientX + 8 - o.x) + 'px';
      drag.ghost.style.top = (e.clientY + 8 - o.y) + 'px';
      clear();
      var z = zoneAt(e.clientX, e.clientY);
      if (z) {
        z.classList.add('pv-over');
        var i = indexIn(z, e.clientX, e.clientY), chips = z.querySelectorAll('.pv-chip');
        if (chips[i] && z.getAttribute('data-zone') !== 'fields') { chips[i].classList.add('pv-ins'); }
      }
    });
    function end(e) {
      if (!drag || e.pointerId !== drag.id) { return; }
      var d = drag;
      drag = null;
      if (!d.on) { return; }
      d.ghost.remove();
      d.chip.classList.remove('pv-dragging');
      self.host.classList.remove('pv-drag-on');
      clear();
      self.suppressClick = true;
      setTimeout(function () { self.suppressClick = false; }, 0);
      if (e.type === 'pointercancel') { return; }
      var z = zoneAt(e.clientX, e.clientY);
      if (!z) { return; }
      var to = z.getAttribute('data-zone'), from = d.chip.getAttribute('data-zone'), name = d.chip.getAttribute('data-field');
      if (to === 'fields' && from === 'fields') { return; }
      self.apply(self.move(name, from, +d.chip.getAttribute('data-i'), to, indexIn(z, e.clientX, e.clientY)), { zone: to, field: name });
    }
    this.host.addEventListener('pointerup', end);
    this.host.addEventListener('pointercancel', end);
    this.host.addEventListener('click', function (e) {
      var c = e.target.closest && e.target.closest('.pv-chip');
      if (!c || self.suppressClick) { return; }
      var zone = c.getAttribute('data-zone');
      if (zone === 'filters') { self.openFilter(+c.getAttribute('data-i'), c); }
      else if (zone === 'values') { var s = c.parentNode.querySelector('.pv-agg'); if (s) { s.focus(); } }
    });
    this.host.addEventListener('keydown', function (e) {
      var c = e.target.closest && e.target.closest('.pv-chip');
      if (!c || e.ctrlKey || e.metaKey) { return; }
      var zone = c.getAttribute('data-zone'), name = c.getAttribute('data-field'), i = +c.getAttribute('data-i'), k = e.key.toLowerCase();
      if (!e.altKey && ZONE_KEY[k]) {
        e.preventDefault();
        self.apply(self.move(name, zone, i, ZONE_KEY[k], null) || self.labelOf(name) + ' is already in ' + ZONE_LABEL[ZONE_KEY[k]], { zone: ZONE_KEY[k], field: name });
      } else if ((e.key === 'Delete' || e.key === 'Backspace') && zone !== 'fields') {
        e.preventDefault();
        self.apply(self.move(name, zone, i, 'fields', null), { zone: 'fields', field: name });
      } else if (e.altKey && zone !== 'fields' && /^Arrow/.test(e.key)) {
        e.preventDefault();
        var dir = e.key === 'ArrowUp' || e.key === 'ArrowLeft' ? -1 : 1;
        var to = i + dir;
        if (to < 0 || to >= self.zoneItems(zone).length) { self.say(self.labelOf(name) + ' is already ' + (dir < 0 ? 'first' : 'last')); return; }
        self.apply(self.move(name, zone, i, zone, dir > 0 ? to + 1 : to), { zone: zone, field: name });
      } else if (/^Arrow/.test(e.key) && !e.altKey) {
        var all = Array.prototype.slice.call(self.host.querySelectorAll('.pv-chip')), at = all.indexOf(c);
        var next = all[at + (e.key === 'ArrowDown' || e.key === 'ArrowRight' ? 1 : -1)];
        if (next) { e.preventDefault(); next.focus(); }
      } else if (e.key === 'Enter' || e.key === ' ') {
        e.preventDefault();
        if (zone === 'filters') { self.openFilter(i, c); }
        else if (zone === 'values') { var s = c.parentNode.querySelector('.pv-agg'); if (s) { s.focus(); } }
        else if (zone === 'fields') {
          var f = self.field(name), target = f.numeric ? 'values' : 'rows';
          self.apply(self.move(name, 'fields', i, target, null), { zone: target, field: name });
        }
      }
    });
  };

  // ---- filters: a pick list of values, and a range for numbers and dates ---------------------------------------------
  Pivot.prototype.distinct = function (name) {
    if (!this.search) {
      return Promise.resolve(this.st.records ? E.distinct(this.st.records, name, 1000) : { values: [], numeric: false });
    }
    return json('POST', '/api/pivot/search/' + encodeURIComponent(this.search) + '/values',
      { q: this.q, field: name, documents: !!this.st.documents }).then(function (res) {
      if (!res.ok) { throw new Error(problem(res)); }
      return res.b;
    });
  };
  Pivot.prototype.openFilter = function (i, chip) {
    var self = this, flt = this.st.arr.filters[i];
    if (!flt) { return; }
    var pop = this.pop, label = this.labelOf(flt.field);
    pop.textContent = '';
    pop.hidden = false;
    pop.setAttribute('role', 'dialog');
    pop.setAttribute('aria-label', 'Filter ' + label);
    var head = el('div', 'pv-pop-h');
    head.appendChild(el('strong', null, 'Filter: ' + label));
    var x = el('button', 'raw-x', '×'); x.type = 'button'; x.setAttribute('aria-label', 'Close');
    head.appendChild(x);
    pop.appendChild(head);
    var body = el('div', 'pv-pop-b', 'Loading values…');
    pop.appendChild(body);
    function close(back) { pop.hidden = true; pop.textContent = ''; if (back) { var c = self.host.querySelector('.pv-chip[data-zone="filters"][data-i="' + i + '"]'); if (c) { c.focus(); } } }
    x.addEventListener('click', function () { close(true); });
    pop.onkeydown = function (e) { if (e.key === 'Escape') { e.preventDefault(); close(true); } };
    x.focus();
    this.distinct(flt.field).then(function (d) {
      body.textContent = '';
      var find = el('input', 'studio-in pv-find');
      find.type = 'search'; find.placeholder = 'Find a value'; find.setAttribute('aria-label', 'Find a value of ' + label);
      var all = button('All', 'Tick every value'), none = button('None', 'Untick every value');
      var tools = el('div', 'pv-pop-tools');
      [find, all, none].forEach(function (n) { tools.appendChild(n); });
      body.appendChild(tools);
      var list = el('div', 'pv-pick'), boxes = [];
      list.setAttribute('role', 'group');
      list.setAttribute('aria-label', 'Values of ' + label);
      var chosen = {};
      (flt.values || []).forEach(function (v) { chosen[v] = true; });
      (d.values || []).forEach(function (v) {
        var l = el('label', 'pv-pick-i'), c = el('input');
        c.type = 'checkbox'; c.value = v.value; c.checked = !flt.values || !flt.values.length || !!chosen[v.value];
        l.appendChild(c); l.appendChild(document.createTextNode(' ' + v.value + ' '));
        l.appendChild(el('span', 'text-muted-d mono', '(' + v.count + ')'));
        list.appendChild(l); boxes.push({ c: c, l: l, v: String(v.value).toLowerCase() });
      });
      if (!boxes.length) { list.appendChild(el('span', 'text-muted-d', 'No values')); }
      if (d.more) { list.appendChild(el('span', 'text-muted-d', 'More values exist than are listed: use the range or a narrower search')); }
      body.appendChild(list);
      find.addEventListener('input', function () { var q = find.value.trim().toLowerCase(); boxes.forEach(function (b) { b.l.hidden = q && b.v.indexOf(q) < 0; }); });
      all.addEventListener('click', function () { boxes.forEach(function (b) { if (!b.l.hidden) { b.c.checked = true; } }); });
      none.addEventListener('click', function () { boxes.forEach(function (b) { if (!b.l.hidden) { b.c.checked = false; } }); });
      var lo = null, hi = null;
      if (d.numeric || d.date) {
        var range = el('div', 'pv-range');
        lo = el('input', 'studio-in'); hi = el('input', 'studio-in');
        lo.type = hi.type = d.numeric ? 'number' : 'date';
        if (d.numeric) { lo.step = hi.step = 'any'; }
        lo.setAttribute('aria-label', label + ' from'); hi.setAttribute('aria-label', label + ' to');
        lo.placeholder = d.min != null ? String(d.min) : 'from'; hi.placeholder = d.max != null ? String(d.max) : 'to';
        if (flt.min != null) { lo.value = flt.min; }
        if (flt.max != null) { hi.value = flt.max; }
        range.appendChild(el('span', null, 'From')); range.appendChild(lo); range.appendChild(el('span', null, 'to')); range.appendChild(hi);
        body.appendChild(range);
      }
      var acts = el('div', 'pv-pop-tools'), ok = button('Apply', 'Filter by these values', 'btn-accent'), clr = button('Clear', 'Every value');
      ok.setAttribute('data-pv-apply', '');
      acts.appendChild(ok); acts.appendChild(clr);
      body.appendChild(acts);
      ok.addEventListener('click', function () {
        var ticked = boxes.filter(function (b) { return b.c.checked; }).map(function (b) { return b.c.value; });
        if (ticked.length === boxes.length || !boxes.length) { delete flt.values; } else { flt.values = ticked; }
        var num = function (v) { return d.numeric ? parseFloat(v) : v; };
        if (lo && lo.value !== '') { flt.min = num(lo.value); } else { delete flt.min; }
        if (hi && hi.value !== '') { flt.max = num(hi.value); } else { delete flt.max; }
        close(false);
        self.drawFields();
        var c = self.host.querySelector('.pv-chip[data-zone="filters"][data-i="' + i + '"]');
        if (c) { c.focus(); }
        self.changed('Filter on ' + label + ': ' + (flt.values ? flt.values.length + ' of ' + boxes.length + ' values' : 'every value') +
          (flt.min != null ? ', from ' + flt.min : '') + (flt.max != null ? ', to ' + flt.max : ''));
      });
      clr.addEventListener('click', function () {
        delete flt.values; delete flt.min; delete flt.max;
        close(false); self.drawFields();
        var c = self.host.querySelector('.pv-chip[data-zone="filters"][data-i="' + i + '"]');
        if (c) { c.focus(); }
        self.changed('Filter on ' + label + ' cleared');
      });
      find.focus();
    }, function (err) { body.textContent = err.message; body.classList.add('t-bad'); });
  };

  // ---- data and the result --------------------------------------------------------------------------------------
  Pivot.prototype.load = function () {
    var self = this;
    if (this.search) { this.recompute(); return; }
    if (this.st.records) { this.recompute(); if (this.st.stale) { this.st.stale = false; this.fetchRecords(true); } return; }
    this.fetchRecords(false);
  };
  Pivot.prototype.fetchRecords = function (quiet) {
    var self = this;
    if (!view) { this.note('This pivot needs its view.', true); return; }
    if (!quiet) { this.status.textContent = 'Reading the panel’s rows…'; }
    var url = '/api/pivot/records/' + encodeURIComponent(view.getAttribute('data-kind')) + '/' + encodeURIComponent(view.getAttribute('data-id')) +
      '/' + encodeURIComponent(this.panelId);
    json('GET', url).then(function (res) {
      if (!res.ok) { self.status.textContent = ''; self.note(problem(res) + (res.b.code ? ' (' + res.b.code + ')' : ''), true); return; }
      var labels = {};
      self.fields.forEach(function (f) { labels[f.name] = f; });
      (res.b.fields || []).forEach(function (f) {
        var s = labels[f.name];
        if (s) { if (f.kind) { s.kind = f.kind; } if (!s.fmt && f.fmt) { s.fmt = f.fmt; } }
      });
      self.st.records = res.b;
      self.recompute();
    });
  };
  Pivot.prototype.body = function () {
    var a = this.st.arr;
    return { q: this.q, rows: a.rows, columns: a.columns, values: a.values, filters: a.filters, documents: !!this.st.documents };
  };
  Pivot.prototype.recompute = function () {
    var self = this;
    if (!this.built) { return; }
    if (!this.search) {
      if (!this.st.records) { return; }
      this.note('');
      this.cube = E.cube(this.st.records, this.st.arr);
      this.draw();
      return;
    }
    clearTimeout(this.timer);
    this.timer = setTimeout(function () {
      var seq = self.seq = (self.seq || 0) + 1;
      self.status.textContent = 'Computing over the day’s ' + self.search + 's…';
      json('POST', '/api/pivot/search/' + encodeURIComponent(self.search), self.body()).then(function (res) {
        if (seq !== self.seq) { return; }
        if (!res.ok) {
          self.status.textContent = '';
          var d = problem(res), refused = /not kept as columns|no source/i.test(d);
          self.note(d + (res.b.code ? ' (' + res.b.code + ')' : ''), true);
          if (refused && !self.st.documents) {
            var b = button('Read documents instead', 'Compute from the documents (slower; at most the search scan limit, so it may be partial)');
            b.setAttribute('data-pv-documents', '');
            b.addEventListener('click', function () { self.st.documents = true; self.note(''); self.recompute(); });
            self.msg.appendChild(document.createTextNode(' '));
            self.msg.appendChild(b);
          }
          return;
        }
        self.note('');
        self.cube = res.b;
        self.draw();
      });
    }, this.cube ? 200 : 0);
  };

  Pivot.prototype.draw = function () {
    if (!this.cube) { return; }
    var c = this.cube, out = G.render(this.gridHost, c, this.st.grid, this.fields);
    this.lay = out.lay; this.toggles = out.toggles;
    var bits = [c.count + ' row' + (c.count === 1 ? '' : 's'), 'source ' + (c.source || 'records')];
    if (this.st.records && this.st.records.truncated) { bits.push('the first ' + this.st.records.rows.length + ' of ' + this.st.records.total + ' rows (panel limit)'); }
    if (c.partial && c.source === 'documents') { bits.push('partial: the first ' + c.total + ' documents'); }
    else if (c.partial) { bits.push('partial'); }
    if (c.moreRows) { bits.push('more row keys than shown'); }
    if (c.moreColumns) { bits.push('more column keys than shown'); }
    if (c.masked && c.masked.length) { bits.push('hidden from your role: ' + c.masked.map(this.labelOf, this).join(', ')); }
    if (c.elapsedMs != null) { bits.push(c.elapsedMs + ' ms'); }
    this.status.textContent = bits.join(' · ');
    this.status.classList.toggle('t-warn', !!(c.partial || c.moreRows || c.moreColumns));
    if (!c.count) { this.note('No rows match: change the filters.', false); }
    this.chartValueSel.textContent = '';
    var self = this;
    c.values.forEach(function (v, i) { var o = el('option', null, v.label); o.value = String(i); self.chartValueSel.appendChild(o); });
    this.st.grid.chartValue = Math.min(this.st.grid.chartValue || 0, c.values.length - 1);
    this.chartValueSel.value = String(this.st.grid.chartValue);
    this.chartValueSel.hidden = !this.st.arr.chart || c.values.length < 2;
    this.drawChart();
  };
  Pivot.prototype.drawChart = function () {
    var kind = this.st.arr.chart;
    this.chartValueSel.hidden = !kind || !this.cube || this.cube.values.length < 2;
    if (!kind || !this.cube) {
      this.chartBox.hidden = true;
      if (this.chart) { this.chart.dispose(); this.chart = null; }
      return;
    }
    this.chartBox.hidden = false;
    if (!window.echarts) { this.chartBox.textContent = 'Charts are not available on this page.'; return; }
    var lines = G.layout(this.cube, this.st.grid).lines.length;          // a heatmap gives every row of the grid room
    this.chartBox.style.height = kind === 'heatmap' ? Math.min(1400, Math.max(340, lines * 22 + 110)) + 'px' : '';
    this.chart = window.echarts.getInstanceByDom(this.chartBox) || window.echarts.init(this.chartBox, null, { renderer: 'svg' });
    this.chart.setOption(G.chart(this.cube, this.st.grid, kind, tokens()), true);
    this.chart.resize();
    this.chartBox.setAttribute('aria-label', kind + ' chart of ' + this.cube.values[this.st.grid.chartValue || 0].label + ' (the grid holds the same numbers)');
  };
  Pivot.prototype.collapseAll = function () {
    var g = this.st.grid, c = this.cube;
    if (!c) { return; }
    g.collapsedRows = {}; g.collapsedCols = {};
    if (c.rows.length > 1) { c.rowKeys.forEach(function (k) { g.collapsedRows[k[0]] = true; }); }
    if (c.columns.length > 1) { c.columnKeys.forEach(function (k) { g.collapsedCols[k[0]] = true; }); }
    this.draw();
    this.say('Every group collapsed to its total');
  };

  // ---- the grid's events: toggles, sorting, moving between cells, drill-down -------------------------------------
  Pivot.prototype.wireGrid = function () {
    var self = this, gh = this.gridHost;
    function sortBy(th) {
      var g = self.st.grid, s = self.lay.slots[+th.getAttribute('data-si')], vi = +th.getAttribute('data-vi'), c = s.path.join(E.US);
      if (g.sort && g.sort.c === c && g.sort.v === vi) { g.sort.dir = g.sort.dir === 1 ? -1 : g.sort.dir === -1 ? 0 : 1; }
      else { g.sort = { c: c, v: vi, dir: 1 }; }
      self.draw();
      var again = gh.querySelector('th.pv-sortable[data-si="' + th.getAttribute('data-si') + '"][data-vi="' + vi + '"]');
      if (again) { again.focus(); }
      self.say(g.sort.dir ? 'Rows sorted by ' + th.textContent + (g.sort.dir > 0 ? ', smallest first' : ', largest first') : 'Rows in key order');
    }
    function toggle(b) {
      var t = self.toggles[+b.getAttribute('data-t')], g = self.st.grid, map = t.axis === 'r' ? g.collapsedRows : g.collapsedCols;
      var open = !map[t.key];
      if (open) { map[t.key] = true; } else { delete map[t.key]; }
      self.draw();
      var again = Array.prototype.filter.call(gh.querySelectorAll('.pv-tog'), function (x) {
        var y = self.toggles[+x.getAttribute('data-t')]; return y.axis === t.axis && y.key === t.key;
      })[0];
      if (again) { again.focus(); }
      self.say(t.key.split(E.US).join(' / ') + (open ? ' collapsed' : ' expanded'));
    }
    gh.addEventListener('click', function (e) {
      var b = e.target.closest('.pv-tog');
      if (b) { toggle(b); return; }
      var th = e.target.closest('th.pv-sortable');
      if (th) { sortBy(th); return; }
      var td = e.target.closest('td.pv-c');
      if (td) { self.focusCell(td); self.drill(td); }
    });
    gh.addEventListener('keydown', function (e) {
      var th = e.target.closest && e.target.closest('th.pv-sortable');
      if (th && (e.key === 'Enter' || e.key === ' ')) { e.preventDefault(); sortBy(th); return; }
      var td = e.target.closest && e.target.closest('td.pv-c');
      if (!td) { return; }
      if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); self.drill(td); return; }
      var tr = td.parentNode, cells = Array.prototype.slice.call(tr.querySelectorAll('td.pv-c')), i = cells.indexOf(td), next = null;
      var rows = Array.prototype.slice.call(tr.parentNode.rows), r = rows.indexOf(tr);
      switch (e.key) {
        case 'ArrowRight': next = cells[i + 1]; break;
        case 'ArrowLeft': next = cells[i - 1]; break;
        case 'ArrowDown': next = rows[r + 1] && rows[r + 1].querySelectorAll('td.pv-c')[i]; break;
        case 'ArrowUp': next = rows[r - 1] && rows[r - 1].querySelectorAll('td.pv-c')[i]; break;
        case 'Home': next = cells[0]; break;
        case 'End': next = cells[cells.length - 1]; break;
        case 'PageDown': next = rows[Math.min(rows.length - 1, r + 10)].querySelectorAll('td.pv-c')[i]; break;
        case 'PageUp': next = rows[Math.max(0, r - 10)].querySelectorAll('td.pv-c')[i]; break;
        default: return;
      }
      e.preventDefault();
      if (next) { self.focusCell(next); }
    });
  };
  Pivot.prototype.focusCell = function (td) {
    this.gridHost.querySelectorAll('td.pv-c[tabindex="0"]').forEach(function (x) { x.tabIndex = -1; });
    td.tabIndex = 0;
    td.focus();
  };

  /** The rows under a cell, in a dialog: from the panel's rows here, or paged from the server for a search. */
  Pivot.prototype.drill = function (td) {
    var self = this, l = this.lay.lines[+td.getAttribute('data-li')], s = this.lay.slots[+td.getAttribute('data-si')];
    var v = this.cube.values[+td.getAttribute('data-vi')];
    var what = (l.type === 'grand' ? 'all rows' : l.path.join(' / ')) + (this.cube.columns.length && s.type !== 'grand' ? ' · ' + s.path.join(' / ') : '');
    var dlg = dialog('Rows under ' + what, v.label + ': ' + td.textContent, td);
    if (!this.search) {
      var recs = this.st.records, rows = E.drill(recs, this.st.arr, l.path, s.path);
      dlg.body.appendChild(rowsTable(recs.fields.map(function (f) { return { name: f.name, label: self.labelOf(f.name), kind: (self.field(f.name).kind || f.kind) }; }),
        rows.map(function (r) { return { values: r }; }), false));
      dlg.note.textContent = rows.length + ' row' + (rows.length === 1 ? '' : 's');
      dlg.focus();
      return;
    }
    var size = 50;
    function page(offset) {
      dlg.note.textContent = 'Loading…';
      var body = self.body();
      body.cell = { rows: l.path, columns: s.path }; body.offset = offset; body.size = size;
      json('POST', '/api/pivot/search/' + encodeURIComponent(self.search) + '/drill', body).then(function (res) {
        dlg.body.textContent = '';
        if (!res.ok) { dlg.note.textContent = problem(res); dlg.note.classList.add('t-bad'); return; }
        var d = res.b, cols = (d.fields || []).map(function (p) { return { name: p, label: (d.labels || {})[p] || self.labelOf(p) }; });
        dlg.body.appendChild(rowsTable(cols, (d.rows || []).map(function (r) {
          return { id: r.id, kind: r.kind || self.search, values: cols.map(function (c) { return (r.values || {})[c.name]; }) };
        }), true));
        var from = d.total ? d.offset + 1 : 0, to = Math.min(d.total, d.offset + (d.rows || []).length);
        dlg.note.textContent = from + '–' + to + ' of ' + d.total;
        var pager = el('div', 'pv-pager');
        var prev = button('‹ Previous', 'Previous page'), next = button('Next ›', 'Next page');
        prev.disabled = d.offset <= 0; next.disabled = to >= d.total;
        prev.addEventListener('click', function () { page(Math.max(0, d.offset - size)); });
        next.addEventListener('click', function () { page(d.offset + size); });
        pager.appendChild(prev); pager.appendChild(next);
        dlg.body.appendChild(pager);
        dlg.focus();
      });
    }
    page(0);
  };
  function rowsTable(cols, rows, withId) {
    var wrap = el('div', 'tbl-wrap pv-drill-wrap'), t = el('table', 'tbl pv-drill');
    if (withId) { t.setAttribute('data-plain', ''); }
    var head = el('tr');
    if (withId) { head.appendChild(el('th', null, 'Id')); }
    cols.forEach(function (c) { head.appendChild(el('th', null, c.label)); });
    var thead = el('thead'); thead.appendChild(head); t.appendChild(thead);
    var tb = el('tbody');
    rows.forEach(function (r) {
      var tr = el('tr');
      if (withId) {
        var td0 = el('td', 'mono'), a = el('a', 'lnk', r.id);
        a.href = '/v/' + encodeURIComponent(r.kind) + '/' + encodeURIComponent(r.id);
        td0.appendChild(a); tr.appendChild(td0);
      }
      cols.forEach(function (c, i) {
        var v = r.values[i], numeric = typeof v === 'number', td = el('td', 'mono' + (numeric ? ' num' : ''));
        if (c.kind && v != null && v !== '') {
          var link = el('a', 'lnk', String(v));
          link.href = '/v/' + encodeURIComponent(c.kind) + '/' + encodeURIComponent(String(v));
          td.appendChild(link);
        } else { td.textContent = numeric ? E.format(v, null) : v == null ? '' : String(v); }
        tr.appendChild(td);
      });
      tb.appendChild(tr);
    });
    t.appendChild(tb);
    wrap.appendChild(t);
    return wrap;
  }
  function dialog(title, sub, back) {
    var shade = el('div', 'pv-shade'), d = el('div', 'pv-dlg');
    d.setAttribute('role', 'dialog'); d.setAttribute('aria-modal', 'true');
    var id = 'pv-dlg-' + Date.now();
    var h = el('header', 'pv-dlg-h'), t = el('strong', null, title);
    t.id = id; d.setAttribute('aria-labelledby', id);
    var x = el('button', 'raw-x', '×'); x.type = 'button'; x.setAttribute('aria-label', 'Close');
    h.appendChild(t); h.appendChild(el('span', 'text-muted-d mono pv-dlg-sub', sub)); h.appendChild(x);
    var note = el('p', 'pv-dlg-note text-muted-d'), body = el('div', 'pv-dlg-b');
    note.setAttribute('role', 'status');
    d.appendChild(h); d.appendChild(note); d.appendChild(body);
    shade.appendChild(d);
    layer.appendChild(shade);
    function close() { shade.remove(); root.removeEventListener('keydown', esc, true); if (back && back.isConnected) { back.focus(); } }
    function esc(e) {
      if (e.key === 'Escape') { e.preventDefault(); e.stopPropagation(); close(); return; }
      if (e.key === 'Tab') {                                // keep the focus inside the dialog
        var f = Array.prototype.filter.call(d.querySelectorAll('a[href], button:not([disabled]), input, select, [tabindex="0"]'), function (n) { return n.offsetParent !== null; });
        if (!f.length) { return; }
        if (e.shiftKey && root.activeElement === f[0]) { e.preventDefault(); f[f.length - 1].focus(); }
        else if (!e.shiftKey && root.activeElement === f[f.length - 1]) { e.preventDefault(); f[0].focus(); }
      }
    }
    x.addEventListener('click', close);
    shade.addEventListener('click', function (e) { if (e.target === shade) { close(); } });
    root.addEventListener('keydown', esc, true);
    x.focus();
    return { body: body, note: note, close: close, focus: function () { if (!d.contains(root.activeElement)) { x.focus(); } } };
  }

  // ---- export, print ---------------------------------------------------------------------------------------------
  Pivot.prototype.exportAs = function (ext) {
    var self = this;
    if (!this.cube) { return; }
    var flat = G.flatten(this.cube, this.st.grid, this.fields);
    var name = (this.search ? this.search + '-search' : (view ? view.getAttribute('data-id') : 'view') + '-' + this.panelId) + '-pivot';
    this.say('Preparing the ' + (ext === 'xlsx' ? 'Excel workbook' : 'CSV file'));
    fetch('/export/grid.' + ext, { method: 'POST', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ name: name, header: flat.header, rows: flat.rows }) }).then(function (r) {
      if (!r.ok) { return r.text().then(function (t) { self.note('Export failed: ' + t, true); }); }
      var cd = r.headers.get('Content-Disposition') || '', m = /filename="?([^";]+)"?/.exec(cd);
      return r.blob().then(function (b) {
        var a = el('a'), u = URL.createObjectURL(b);
        a.href = u; a.download = m ? m[1] : name + '.' + ext; a.hidden = true;
        document.body.appendChild(a); a.click(); a.remove();
        setTimeout(function () { URL.revokeObjectURL(u); }, 4000);
        self.say('Downloaded ' + a.download);
      });
    }, function () { self.note('Export failed: the console could not be reached', true); });
  };
  Pivot.prototype.print = function () {
    var self = this, done = false;
    document.body.classList.add('pv-printing');
    this.box.classList.add('pv-print-this');
    if (this.chart) { this.chart.resize(); }
    function after() {
      if (done) { return; }
      done = true;
      document.body.classList.remove('pv-printing');
      self.box.classList.remove('pv-print-this');
      window.removeEventListener('afterprint', after);
    }
    window.addEventListener('afterprint', after);
    window.print();
    setTimeout(after, 1000);
  };

  // ---- save, reset, promote --------------------------------------------------------------------------------------
  Pivot.prototype.arrangement = function () {
    var a = this.st.arr;
    return { rows: a.rows.slice(), columns: a.columns.slice(), values: a.values.map(function (v) { return { field: v.field, agg: v.agg, show: v.show }; }),
      filters: a.filters.map(function (f) { var o = { field: f.field }; ['values', 'min', 'max'].forEach(function (k) { if (f[k] != null) { o[k] = f[k]; } }); return o; }),
      heat: !!this.st.grid.heat, chart: a.chart || null };
  };
  Pivot.prototype.save = function (quiet) {
    var self = this;
    return json('PUT', this.url(), this.arrangement()).then(function (res) {
      if (!res.ok) { self.note('Not saved: ' + problem(res), true); self.say('Not saved'); return false; }
      self.st.saved = true;
      if (!quiet) { self.note('Saved: this pivot opens like this for you from now on.'); self.say('Saved'); }
      return true;
    });
  };
  Pivot.prototype.reset = function () {
    var self = this;
    json('DELETE', this.url()).then(function (res) {
      if (!res.ok && res.status !== 404) { self.note('Not reset: ' + problem(res), true); return; }
      self.st.saved = false;
      self.st.arr = E.normalise(self.spec, self.fields);
      self.st.grid.heat = !!self.spec.heat;
      self.st.grid.collapsedRows = {}; self.st.grid.collapsedCols = {}; self.st.grid.sort = null;
      self.heatBox.checked = self.st.grid.heat;
      self.chartSel.value = self.st.arr.chart || '';
      self.drawFields();
      self.note('Back to the default arrangement.');
      self.changed('Back to the default arrangement');
    });
  };
  Pivot.prototype.openPromote = function () {
    var self = this, drawer = this.drawer;
    if (!drawer) {
      drawer = this.drawer = el('aside', 'raw lay-promote pv-promote');
      drawer.setAttribute('aria-label', 'Promote pivot to Sutra');
      var h = el('header');
      h.appendChild(el('strong', null, 'Promote pivot to Sutra '));
      this.prTitle = el('span', 'mono', this.sutra);
      h.appendChild(this.prTitle);
      var x = el('button', 'raw-x', '×'); x.type = 'button'; x.setAttribute('aria-label', 'Close');
      x.addEventListener('click', function () { drawer.hidden = true; var b = self.host.querySelector('[data-pv-promote]'); if (b) { b.focus(); } });
      h.appendChild(x);
      drawer.appendChild(h);
      var b = el('div', 'lay-promote-body');
      var review = view && view.hasAttribute('data-pivot-review');
      b.appendChild(el('p', 'text-muted-d', 'Your arrangement becomes the default pivot of panel ' + this.panelId + ' in the next version of ' + this.sutra +
        ', for everyone who opens it. ' + (review ? 'It is a proposal: an approver reviews it before it goes live.' : 'It goes live as soon as it is saved, unless the server asks for review.')));
      this.prChanges = el('ul', 'lay-changes');
      this.prDiff = el('pre', 'diff mono'); this.prDiff.setAttribute('aria-label', 'Differences against the latest version');
      var form = el('form', 'lay-promote-form'), note = el('input', 'studio-in');
      note.name = 'note'; note.maxLength = 500; note.placeholder = 'Why (for the reviewer)'; note.setAttribute('aria-label', 'Note for the reviewer');
      var sub = el('button', 'btn-pill btn-accent btn-sm-pill', review ? 'Submit for review' : 'Publish'); sub.type = 'submit';
      this.prMsg = el('span', 'adm-msg'); this.prMsg.setAttribute('role', 'status'); this.prMsg.setAttribute('aria-live', 'polite');
      [note, sub, this.prMsg].forEach(function (n) { form.appendChild(n); });
      [this.prChanges, this.prDiff, form].forEach(function (n) { b.appendChild(n); });
      drawer.appendChild(b);
      drawer.addEventListener('keydown', function (e) { if (e.key === 'Escape') { e.preventDefault(); x.click(); } });
      form.addEventListener('submit', function (e) {
        e.preventDefault();
        sub.disabled = true; self.prMsg.textContent = 'Submitting…'; self.prMsg.classList.remove('t-bad');
        json('POST', self.url('/promotion'), { note: note.value }).then(function (res) {
          sub.disabled = false; self.prMsg.textContent = '';
          if (!res.ok) { self.prMsg.textContent = drsMessage({ code: res.b.code, detail: problem(res) }); self.prMsg.classList.add('t-bad'); return; }
          if (res.b.proposal) {
            self.prMsg.appendChild(document.createTextNode('Proposed as ' + res.b.proposal.name + ' v' + res.b.proposal.version + ' (' + res.b.proposal.id + '): '));
            var a = el('a', 'lnk', 'open the review'); a.href = res.b.href || '/build/reviews'; self.prMsg.appendChild(a);
          } else if (res.b.saved) { self.prMsg.textContent = res.b.saved.name + ' v' + res.b.saved.version + ' is live.'; }
          self.say(self.prMsg.textContent);
        });
      });
      this.prNote = note;
      layer.appendChild(drawer);
    }
    drawer.hidden = false;
    this.prDiff.textContent = 'Saving your arrangement…';
    this.prChanges.textContent = '';
    this.save(true).then(function (ok) {
      if (!ok) { self.prDiff.textContent = 'Your arrangement could not be saved, so it cannot be promoted.'; return; }
      self.prDiff.textContent = 'Loading…';
      json('GET', self.url('/promotion')).then(function (res) {
        if (!res.ok) { self.prDiff.textContent = drsMessage({ code: res.b.code, detail: problem(res) }); return; }
        var p = res.b;
        self.prTitle.textContent = p.sutra + ' v' + p.fromVersion + ' → v' + p.version + ' · ' + (p.panel || self.panelId);
        ((p.changes || []).length ? p.changes : ['Only the version changes: the arrangement is the Sutra’s.']).forEach(function (c) {
          self.prChanges.appendChild(el('li', null, c));
        });
        self.prDiff.textContent = '';
        var lines = p.diff || [];
        if (!lines.length) { self.prDiff.textContent = 'No differences.'; }
        lines.forEach(function (l) { var s = el('span', 'd-' + l[0], l[1]); self.prDiff.appendChild(s); });
        self.prNote.focus();
      });
    });
  };

  // ---- start: every opted-in panel and search result; panels a live update or a workspace pane replaces, too ------
  function scan(root) {
    var boxes = root.querySelectorAll ? Array.prototype.slice.call(root.querySelectorAll('.pv-box')) : [];
    if (root.matches && root.matches('.pv-box')) { boxes.push(root); }
    boxes.forEach(function (b) {
      if (b.__pivot) { return; }
      var host = b.querySelector('.pv-host'), key = host && (host.getAttribute('data-pivot-search') ? 'search:' + host.getAttribute('data-pivot-search') : 'panel:' + host.getAttribute('data-pivot-panel'));
      if (host && STATE[key] && STATE[key].records) { STATE[key].stale = true; }       // a live update: read the rows again
      b.__pivot = new Pivot(b);
    });
  }
  scan(root);
  var mo = new MutationObserver(function (list) {
    list.forEach(function (m) { m.addedNodes.forEach(function (n) { if (n.nodeType === 1) { scan(n); } }); });
  });
  mo.observe(layer, { childList: true, subtree: true });
  on(root, 'drishti:theme', function () {
    root.querySelectorAll('.pv-box').forEach(function (b) { if (b.__pivot && b.__pivot.chart) { b.__pivot.drawChart(); } });
  });
  function resize() { root.querySelectorAll('.pv-box').forEach(function (b) { if (b.__pivot && b.__pivot.chart) { b.__pivot.chart.resize(); } }); }
  if (root === document) { on(window, 'resize', resize); }
  else if (window.ResizeObserver) { var ro = new ResizeObserver(function () { requestAnimationFrame(resize); }); ro.observe(root.host); undo.push(function () { ro.disconnect(); }); }
  return { state: STATE, scan: scan, dispose: function () { mo.disconnect(); undo.splice(0).forEach(function (f) { f(); }); } };
  }
  (window.drishtiModules = window.drishtiModules || {}).pivot = { init: init };
  if (!(me && me.hasAttribute('data-manual'))) { window.drishtiPivot = init(document); }
})();
