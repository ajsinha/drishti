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
/* Chart any table, the dialog (docs/guides/RACHANA_REFERENCE.md › Chart any table: the design, sections 5, 8, 12 to 15). open(source, options) shows a wide
   native <dialog> (full screen on a phone): the chart already drawn with the smart defaults, its caption (the text summary, also
   the chart's aria-label), the status line (what is charted, what was left out) and, beside it, the settings: type, X (and its
   date bucket), up to six series (column, aggregation, axis, type in a combo), split, sort, Top N with Others, labels, stacking.
   Every change redraws at once. Actions: View as table, PNG, SVG, CSV, Excel, JSONL (one row a line), Defaults; Chart all N where the source can.
   The rows come from a source adapter (table-chart-sources.js); the numbers from the engine (table-chart-engine.js over
   pivot-engine.js); the colours from the theme tokens (charts.js), redrawn on a theme change. Masked columns are offered greyed
   and never charted. Keys: Esc closes (focus back on the opener), 1-6 the type, T table, A chart all, E export, ? help; on the
   chart ← → points, ↑ ↓ series, Home/End. Settings are kept per table for the tab's life (saving them is a later step). */
(function () {
  'use strict';
  function E() { return window.drishtiTableChartEngine; }
  var MEMO = {};                                      // identity -> the settings a reader chose, for the tab's life
  var TYPES = [['line', 'Line', 'graph-up'], ['bar', 'Bar', 'bar-chart'], ['area', 'Area', 'graph-up-arrow'], ['pie', 'Pie', 'pie-chart'],
    ['scatter', 'Scatter', 'dot'], ['combo', 'Combo', 'bar-chart-line']];
  var AGG_TEXT = { sum: 'Sum', avg: 'Average', count: 'Count', min: 'Min', max: 'Max', none: 'Points (no aggregation)' };
  var BUCKET_TEXT = { auto: 'Automatic', day: 'By day', week: 'By week', month: 'By month', quarter: 'By quarter', year: 'By year' };
  var HELP = '/help/table-chart';
  var current = null;

  function el(tag, attrs, kids) {
    var e = document.createElement(tag);
    Object.keys(attrs || {}).forEach(function (k) {
      var v = attrs[k];
      if (v === null || v === undefined || v === false) { return; }
      if (k === 'text') { e.textContent = v; } else if (k === 'cls') { e.className = v; } else if (k.slice(0, 2) === 'on') { e.addEventListener(k.slice(2), v); } else { e.setAttribute(k, v === true ? '' : v); }
    });
    (kids || []).forEach(function (c) { if (c) { e.appendChild(typeof c === 'string' ? document.createTextNode(c) : c); } });
    return e;
  }
  function icon(name) { return el('i', { cls: 'bi bi-' + name, 'aria-hidden': 'true' }); }
  function group(n) { return String(n).replace(/\B(?=(\d{3})+(?!\d))/g, ','); }
  /** The theme's tokens (charts.js reads a shadow host's in an embedded view); the palette without repeats, since a theme may give
      two tones one colour (Crimson's link and accent), and two series must never share one. */
  function tokens(root) {
    var C = window.drishtiCharts, t;
    if (C && C.tokens) { t = C.tokens(root); } else {
      var s = getComputedStyle(document.documentElement);
      t = {};
      ['ink', 'muted', 'faint', 'border', 'link', 'accent', 'neg', 'pos', 'ok', 'warn', 'bad', 'surface'].forEach(function (k) { t[k] = s.getPropertyValue('--d-' + k).trim(); });
      t.mono = s.getPropertyValue('--d-font-mono').trim();
    }
    var seen = {};
    t.palette = [t.link, t.accent, t.ok, t.neg, t.warn, t.bad, t.muted, t.ink].filter(function (c) {
      var k = String(c || '').toLowerCase();
      if (!k || seen[k]) { return false; }
      seen[k] = true;
      return true;
    });
    return t;
  }
  function inField(e) { var t = e.target; return t && t.closest && !!t.closest('input, select, textarea, [contenteditable]'); }
  function fileName(s) { return String(s || 'table').replace(/[^\w.-]+/g, '-').replace(/^-+|-+$/g, '').slice(0, 60) || 'table'; }
  function today() { return new Date().toISOString().slice(0, 10); }
  function download(blob, name) {
    var a = el('a', { href: URL.createObjectURL(blob), download: name, hidden: true });
    document.body.appendChild(a); a.click(); a.remove();
    setTimeout(function () { URL.revokeObjectURL(a.href); }, 4000);
  }

  /** The chart dialog for one source. */
  function Dialog(source, opts) {
    this.src = source; this.opts = opts || {}; this.scope = 'shown'; this.allData = null; this.point = -1; this.si = 0; this.view = 'chart';
    this.build();
  }
  Dialog.prototype.build = function () {
    var self = this, s = this.src;
    var title = el('h2', { id: 'tcTitle', cls: 'tc-title', tabindex: '-1' }, ['Chart · ', el('span', { cls: 'tc-name', text: s.title })]);
    if (s.context && (s.context.id || s.context.asOf)) { title.appendChild(el('span', { cls: 'tc-ctx mono', text: ' (' + [s.context.id, s.context.asOf].filter(Boolean).join(' · ') + ')' })); }
    this.closeBtn = el('button', { type: 'button', cls: 'tc-x', 'aria-label': 'Close the chart (Esc)', title: 'Close (Esc)', 'data-tc-close': true, onclick: function () { self.close(); } }, [icon('x-lg')]);
    var head = el('header', { cls: 'tc-h' }, [title, el('span', { cls: 'tc-h-r' }, [
      el('a', { cls: 'tc-help', href: HELP, target: '_blank', rel: 'noopener', title: 'How charts work (?)', 'aria-label': 'Help on charts' }, [icon('question-circle')]), this.closeBtn])]);
    this.chartEl = el('div', { cls: 'tc-canvas', role: 'img', tabindex: '0', 'aria-label': 'Chart', 'aria-describedby': 'tcSum' });
    this.msg = el('div', { cls: 'tc-msg', role: 'status', hidden: true });
    this.tableBox = el('div', { cls: 'tc-table tbl-wrap', hidden: true });
    this.sum = el('p', { id: 'tcSum', cls: 'tc-sum' });
    this.status = el('p', { cls: 'tc-status mono', 'data-tc-status': true });
    this.allBtn = el('button', { type: 'button', cls: 'fk tc-all', hidden: true, 'aria-keyshortcuts': 'A', onclick: function () { self.toggleAll(); } });
    this.live = el('div', { cls: 'visually-hidden', 'aria-live': 'polite', 'data-tc-live': true });
    var stage = el('section', { cls: 'tc-stage', 'aria-label': 'The chart' }, [this.chartEl, this.msg, this.tableBox, this.sum,
      el('div', { cls: 'tc-statusline' }, [this.status, this.allBtn]), this.live]);
    this.pane = el('form', { cls: 'tc-pane', 'aria-label': 'Chart settings', onsubmit: function (e) { e.preventDefault(); } });
    var settings = el('details', { cls: 'tc-sheet', open: true }, [el('summary', { text: 'Settings' }), this.pane]);
    var act = function (label, key, fn, ic, extra) {
      return el('button', Object.assign({ type: 'button', cls: 'fk', 'data-tc-act': key, onclick: fn, title: label }, extra || {}), [ic ? icon(ic) : null, ' ' + label]);
    };
    this.tableBtn = act('View as table', 'table', function () { self.toggleTable(); }, 'table', { 'aria-pressed': 'false', 'aria-keyshortcuts': 'T' });
    this.exportBox = el('span', { cls: 'tc-exports', role: 'group', 'aria-label': 'Export the chart' }, [
      act('PNG', 'png', function () { self.png(); }, 'image'), act('SVG', 'svg', function () { self.svg(); }, 'filetype-svg'),
      act('CSV', 'csv', function () { self.grid('csv'); }, 'filetype-csv'), act('Excel', 'xlsx', function () { self.grid('xlsx'); }, 'file-earmark-excel'),
      act('JSONL', 'jsonl', function () { self.grid('jsonl'); }, 'braces', { title: 'JSON Lines: one row a line, the CSV\'s column names' })]);
    if (this.opts.exports === false) { this.exportBox.hidden = true; }
    var actions = el('footer', { cls: 'tc-actions' }, [this.tableBtn, this.exportBox,
      act('Defaults', 'reset', function () { self.reset(); }, 'arrow-counterclockwise', { title: 'Back to the chart this table opens with' })]);
    var body = el('div', { cls: 'tc-body' }, [stage, el('aside', { cls: 'tc-side' }, [settings])]);
    this.dlg = el('dialog', { cls: 'tc-dlg', 'aria-labelledby': 'tcTitle', 'data-table-chart': true }, [head, body, actions]);
    this.dlg.addEventListener('cancel', function (e) { e.preventDefault(); self.close(); });
    this.dlg.addEventListener('keydown', function (e) { self.key(e); });
    this.chartEl.addEventListener('keydown', function (e) { self.pointKey(e); });
  };

  // ---- opening, loading, closing -------------------------------------------------------------------------------------------------
  Dialog.prototype.show = function () {
    var self = this, host = (this.opts.root && this.opts.root !== document) ? this.opts.root : document.body;
    host.appendChild(this.dlg);
    if (this.dlg.showModal) { this.dlg.showModal(); } else { this.dlg.setAttribute('open', ''); }
    document.body.classList.add('tc-open');
    this.status.textContent = 'Reading ' + (this.src.shown != null ? group(this.src.shown) + ' ' : '') + 'rows…';
    this.closeBtn.focus();
    this.onTheme = function () { self.draw(); };
    this.onResize = function () { if (self.chart && !self.chart.isDisposed()) { self.chart.resize(); } };
    document.addEventListener('drishti:theme', this.onTheme);
    window.addEventListener('resize', this.onResize);
    return Promise.resolve().then(function () { return self.src.rows(); }).then(function (data) {
      self.data = data || { fields: [], rows: [] };
      self.cols = E().classify(self.data.fields, self.data);
      var ok = E().chartable(self.cols);
      if (!ok.ok) { self.nothing(ok.reason); return; }
      var d = E().defaults(self.cols, { saved: self.src.identity ? MEMO[self.src.identity] : null });
      self.defaults = d.settings;
      self.notes = d.notes || [];
      self.settings = d.settings;
      self.renderPane();
      self.draw();
      self.focusType();
      self.say(self.sum.textContent + ' Settings on the right.');
    }).catch(function (err) {
      self.status.textContent = '';
      self.message('The rows could not be read: ' + (err && err.message ? err.message : err), true);
    });
  };
  Dialog.prototype.nothing = function (reason) {
    this.message(reason + '. Choose another table, or ask your administrator which columns your role may see.', false);
    this.status.textContent = '';
    this.renderPane(true);
  };
  Dialog.prototype.close = function () {
    if (this.closed) { return; }
    this.closed = true;
    document.removeEventListener('drishti:theme', this.onTheme);
    window.removeEventListener('resize', this.onResize);
    if (this.chart && !this.chart.isDisposed()) { this.chart.dispose(); }
    if (this.dlg.open && this.dlg.close) { this.dlg.close(); }
    this.dlg.remove();
    document.body.classList.remove('tc-open');
    if (current === this) { current = null; }
    var o = this.opts.opener;
    if (o && o.isConnected && o.focus) { o.focus(); }
  };
  Dialog.prototype.focusType = function () {
    var b = this.pane.querySelector('[data-tc-type][aria-pressed="true"]') || this.pane.querySelector('[data-tc-type]');
    if (b) { b.focus(); }
  };
  Dialog.prototype.message = function (text, bad, buttons) {
    this.msg.textContent = '';
    this.msg.appendChild(el('p', { text: text }));
    if (buttons) { this.msg.appendChild(el('p', { cls: 'tc-msg-acts' }, buttons)); }
    this.msg.classList.toggle('bad', !!bad);
    this.msg.hidden = false;
    this.chartEl.hidden = true;
  };
  Dialog.prototype.say = function (text) { this.live.textContent = ''; var l = this.live; setTimeout(function () { l.textContent = text; }, 30); };

  // ---- the settings pane ---------------------------------------------------------------------------------------------------------
  Dialog.prototype.colOptions = function (sel, pickable, value, none) {
    if (none) { sel.appendChild(el('option', { value: '', text: none })); }
    this.cols.forEach(function (c) {
      var ok = c.chartable && pickable(c);
      if (!ok && c.chartable) { return; }                // not this role's kind (a text column as a Y): left out, not greyed
      sel.appendChild(el('option', { value: c.name, text: c.label + (c.chartable ? '' : ' (hidden for your role)'), disabled: !ok, selected: c.name === value }));
    });
    sel.value = value == null ? '' : value;
    return sel;
  };
  Dialog.prototype.field = function (label, control, cls) {
    var id = 'tc-' + Math.random().toString(36).slice(2, 8);
    control.id = id;
    return el('div', { cls: 'tc-f ' + (cls || '') }, [el('label', { for: id, text: label }), control]);
  };
  Dialog.prototype.renderPane = function (empty) {
    var self = this, s = this.settings, pane = this.pane, act = document.activeElement, keep = act && pane.contains(act) ? act.getAttribute('data-tc-k') : null;
    pane.textContent = '';
    if (empty || !s) { pane.appendChild(el('p', { cls: 'tc-note', text: 'Nothing here can be charted.' })); return; }
    var types = E().types(this.cols);
    var tg = el('div', { cls: 'tc-types', role: 'group', 'aria-label': 'Chart type (keys 1 to 6)' });
    TYPES.forEach(function (t, i) {
      var ok = types[t[0]].ok;
      tg.appendChild(el('button', { type: 'button', cls: 'tc-type', 'data-tc-type': t[0], 'data-tc-k': 'type-' + t[0], 'aria-pressed': s.type === t[0] ? 'true' : 'false',
        disabled: !ok, title: ok ? t[1] + ' (' + (i + 1) + ')' : types[t[0]].reason, 'aria-keyshortcuts': String(i + 1), onclick: function () { self.setType(t[0]); } }, [icon(t[2]), ' ' + t[1]]));
    });
    pane.appendChild(el('fieldset', { cls: 'tc-group' }, [el('legend', { text: 'Type' }), tg]));
    var xcol = this.col(s.x.field), roleX = xcol ? xcol.role : null;
    var xPick = s.type === 'pie' ? function (c) { return c.role !== 'number' || c.id; } : s.type === 'scatter' ? function (c) { return c.role === 'number'; } : function () { return true; };
    var xs = this.colOptions(el('select', { 'data-tc-k': 'x', onchange: function () { self.change(function (n) { n.x.field = this.value; n.x.bucket = 'auto'; }.bind(this)); } }), xPick, s.x.field);
    var xRow = [this.field('X', xs)];
    if (roleX === 'date') {
      var bk = el('select', { 'data-tc-k': 'bucket', onchange: function () { var v = this.value; self.change(function (n) { n.x.bucket = v; }); } });
      Object.keys(BUCKET_TEXT).forEach(function (b) { bk.appendChild(el('option', { value: b, text: BUCKET_TEXT[b], selected: s.x.bucket === b })); });
      xRow.push(this.field('Dates', bk));
      if (s.type === 'line' || s.type === 'area' || s.type === 'combo') {
        var gp = el('select', { 'data-tc-k': 'gaps', onchange: function () { var v = this.value; self.change(function (n) { n.x.gaps = v; }); } },
          [el('option', { value: 'connect', text: 'Connect across gaps', selected: s.x.gaps === 'connect' }), el('option', { value: 'break', text: 'Break at missing business days', selected: s.x.gaps === 'break' })]);
        xRow.push(this.field('Gaps', gp));
      }
    }
    pane.appendChild(el('fieldset', { cls: 'tc-group' }, [el('legend', { text: 'X axis' })].concat(xRow)));
    pane.appendChild(this.seriesGroup(s));
    var rest = [];
    if (s.type !== 'pie' && s.type !== 'combo') {
      var sp = this.colOptions(el('select', { 'data-tc-k': 'split', onchange: function () { var v = this.value; self.change(function (n) { n.split = v || null; }); } }),
        function (c) { return c.role !== 'number' && c.name !== s.x.field; }, s.split, 'None');
      rest.push(this.field('Split by', sp));
    }
    var sortBy = el('select', { 'data-tc-k': 'sort', onchange: function () { var v = this.value.split(':'); self.change(function (n) { n.sort = { by: v[0], dir: v[1], series: 0 }; }); } });
    [['x:asc', 'X, ascending'], ['x:desc', 'X, descending'], ['value:desc', 'Value, largest first'], ['value:asc', 'Value, smallest first']].forEach(function (o) {
      sortBy.appendChild(el('option', { value: o[0], text: o[1], selected: s.sort.by + ':' + s.sort.dir === o[0] }));
    });
    if (s.type !== 'scatter') { rest.push(this.field('Sort', sortBy)); }
    if (roleX !== 'date' && s.type !== 'scatter') {
      var top = el('input', { type: 'number', min: '0', max: '1000', step: '1', inputmode: 'numeric', value: String(s.top.n), 'data-tc-k': 'top', 'aria-describedby': 'tcTopHint',
        onchange: function () { var v = this.value; self.change(function (n) { n.top.n = v; }); } });
      var others = el('input', { type: 'checkbox', 'data-tc-k': 'others', checked: s.top.others, onchange: function () { var v = this.checked; self.change(function (n) { n.top.others = v; }); } });
      rest.push(el('div', { cls: 'tc-f tc-top' }, [el('label', { for: 'tcTop', text: 'Top N' }), top, el('label', { cls: 'tc-check' }, [others, ' Others']),
        el('span', { id: 'tcTopHint', cls: 'tc-hint', text: '0 shows every value' })]));
      top.id = 'tcTop';
    }
    var opts = [];
    if (s.type === 'bar' || s.type === 'combo') {
      opts.push(this.check('Stacked', s.bars === 'stacked', 'bars', function (n, v) { n.bars = v ? 'stacked' : 'grouped'; if (!v) { n.percent = false; } }));
    }
    if (s.type === 'bar' && s.bars === 'stacked') { opts.push(this.check('100%', s.percent, 'percent', function (n, v) { n.percent = v; })); }
    if (s.type === 'pie') { opts.push(this.check('Donut', s.donut, 'donut', function (n, v) { n.donut = v; })); }
    opts.push(this.check('Labels', s.labels, 'labels', function (n, v) { n.labels = v; }));
    rest.push(el('div', { cls: 'tc-f tc-opts' }, opts));
    pane.appendChild(el('fieldset', { cls: 'tc-group' }, [el('legend', { text: 'Arrange' })].concat(rest)));
    if (keep) { var f = pane.querySelector('[data-tc-k="' + keep + '"]'); if (f) { f.focus(); } }
  };
  Dialog.prototype.check = function (label, on, key, fn) {
    var self = this, box = el('input', { type: 'checkbox', checked: on, 'data-tc-k': key, onchange: function () { var v = this.checked; self.change(function (n) { fn(n, v); }); } });
    return el('label', { cls: 'tc-check' }, [box, ' ' + label]);
  };
  Dialog.prototype.seriesGroup = function (s) {
    var self = this, box = el('fieldset', { cls: 'tc-group tc-series' }, [el('legend', { text: s.type === 'pie' ? 'Value' : 'Y series' })]);
    var right = s.type !== 'pie' && s.type !== 'scatter';
    s.series.forEach(function (e, i) {
      var f = self.colOptions(el('select', { 'data-tc-k': 's' + i + '-field', 'aria-label': 'Series ' + (i + 1) + ' column',
        onchange: function () { var v = this.value; self.change(function (n) { var x = n.series[i]; if (v) { x.field = v; if (x.agg === 'count') { x.agg = 'sum'; } } else { x.field = null; x.agg = 'count'; } }); } }),
      function (c) { return c.role === 'number' && !c.id; }, e.field || '', s.type === 'scatter' ? null : 'Rows (count)');
      var aggs = s.type === 'pie' ? ['sum', 'count'] : s.type === 'scatter' ? ['none', 'sum', 'avg', 'count', 'min', 'max'] : ['sum', 'avg', 'count', 'min', 'max'];
      var ag = el('select', { 'data-tc-k': 's' + i + '-agg', 'aria-label': 'Series ' + (i + 1) + ' aggregation', disabled: !e.field,
        onchange: function () { var v = this.value; self.change(function (n) { n.series[i].agg = v; }); } });
      aggs.forEach(function (a) { ag.appendChild(el('option', { value: a, text: AGG_TEXT[a], selected: e.agg === a })); });
      var ctl = el('div', { cls: 'tc-sctl' }, [f, ag]);
      var row = el('div', { cls: 'tc-srow', role: 'group', 'aria-label': 'Series ' + (i + 1) }, [el('span', { cls: 'tc-snum mono', 'aria-hidden': 'true', text: String(i + 1) }), ctl]);
      if (right) {
        var ax = el('select', { 'data-tc-k': 's' + i + '-axis', 'aria-label': 'Series ' + (i + 1) + ' axis', onchange: function () { var v = this.value; self.change(function (n) { n.series[i].axis = v; }); } },
          [el('option', { value: 'left', text: 'Left axis', selected: e.axis === 'left' }), el('option', { value: 'right', text: 'Right axis', selected: e.axis === 'right' })]);
        ctl.appendChild(ax);
      }
      if (s.type === 'combo') {
        var ty = el('select', { 'data-tc-k': 's' + i + '-type', 'aria-label': 'Series ' + (i + 1) + ' drawn as', onchange: function () { var v = this.value; self.change(function (n) { n.series[i].type = v; }); } });
        ['bar', 'line', 'area'].forEach(function (t) { ty.appendChild(el('option', { value: t, text: t[0].toUpperCase() + t.slice(1), selected: e.type === t })); });
        ctl.appendChild(ty);
      }
      if (s.series.length > 1) {
        row.appendChild(el('button', { type: 'button', cls: 'tc-srm', 'data-tc-k': 's' + i + '-rm', 'aria-label': 'Remove series ' + (i + 1), title: 'Remove this series',
          onclick: function () { self.change(function (n) { n.series.splice(i, 1); }); self.focusType(); } }, [icon('x')]));
      }
      box.appendChild(row);
    });
    if (s.type !== 'pie' && s.type !== 'scatter' && s.series.length < E().LIMITS.seriesMax) {
      box.appendChild(el('button', { type: 'button', cls: 'fk tc-add', 'data-tc-k': 'add', onclick: function () { self.addSeries(); } }, [icon('plus'), ' Series']));
    }
    return box;
  };
  Dialog.prototype.col = function (name) { return (this.cols || []).filter(function (c) { return c.name === name; })[0] || null; };
  Dialog.prototype.nums = function () { return this.cols.filter(function (c) { return c.chartable && c.role === 'number' && !c.id; }); };

  // ---- changing the settings -----------------------------------------------------------------------------------------------------
  Dialog.prototype.change = function (fn, words) {
    var next = JSON.parse(JSON.stringify(this.settings));
    fn(next);
    var n = E().normalise(next, this.cols);
    this.settings = n.settings;
    if (this.src.identity) { MEMO[this.src.identity] = this.settings; }
    this.renderPane();
    if (this.scope === 'all' && this.allData && this.allData.cube) {     // the server aggregated for the old settings: ask again
      this.scope = 'shown'; this.allData = null; this.draw();
      if (this.src.all && !this.allBtn.disabled) { this.toggleAll(); return; }
    }
    this.draw();
    this.say(words || this.sum.textContent);
  };
  Dialog.prototype.setType = function (type) {
    var self = this, used = {};
    this.change(function (n) {
      var x = self.col(n.x.field), nums = self.nums();
      if (type === 'scatter') {
        if (!x || x.role !== 'number') { n.x.field = nums[0] ? nums[0].name : n.x.field; x = self.col(n.x.field); }
        var y = nums.filter(function (c) { return c.name !== n.x.field; })[0];
        n.series = [{ field: y ? y.name : null, agg: 'none' }];
        n.split = n.split && self.col(n.split) && self.col(n.split).role !== 'number' ? n.split : null;
      } else if (type === 'pie') {
        if (!x || x.role === 'number' || x.role === 'date') {
          var t = self.cols.filter(function (c) { return c.chartable && c.role === 'text' && !c.id; })[0] || self.cols.filter(function (c) { return c.chartable && c.role === 'text'; })[0];
          if (t) { n.x.field = t.name; }
        }
        n.series = n.series.slice(0, 1);
        n.sort = { by: 'value', dir: 'desc', series: 0 };
      } else if (type === 'combo') {
        n.series.forEach(function (e, i) { used[e.field] = true; e.type = i === 0 ? 'bar' : 'line'; });
        if (n.series.length < 2) {
          var more = nums.filter(function (c) { return !used[c.name] && c.name !== n.x.field; })[0];
          n.series.push(more ? { field: more.name, agg: 'sum', axis: 'right', type: 'line' } : { field: null, agg: 'count', axis: 'right', type: 'line' });
        }
      }
      if (self.settings.type === 'scatter' && type !== 'scatter') {
        n.series.forEach(function (e) { if (e.agg === 'none') { e.agg = 'sum'; } });
        if (x && x.role === 'number') {
          var cat = self.cols.filter(function (c) { return c.chartable && c.role !== 'number'; })[0];
          if (cat) { n.x.field = cat.name; }
        }
      }
      n.type = type;
    }, type[0].toUpperCase() + type.slice(1) + ' chart.');
    var b = this.pane.querySelector('[data-tc-type="' + type + '"]');
    if (b) { b.focus(); }
  };
  Dialog.prototype.addSeries = function () {
    var self = this;
    this.change(function (n) {
      var used = {};
      n.series.forEach(function (e) { used[e.field] = true; });
      var c = self.nums().filter(function (x) { return !used[x.name] && x.name !== n.x.field; })[0];
      n.series.push(c ? { field: c.name, agg: 'sum', axis: 'left', type: 'line' } : { field: null, agg: 'count', axis: 'left', type: 'line' });
    }, 'Series ' + (this.settings.series.length + 1) + ' added.');
    var f = this.pane.querySelector('[data-tc-k="s' + (this.settings.series.length - 1) + '-field"]');
    if (f) { f.focus(); }
  };
  Dialog.prototype.reset = function () {
    if (this.src.identity) { delete MEMO[this.src.identity]; }
    if (!this.defaults) { return; }
    this.settings = E().defaults(this.cols, {}).settings;
    this.renderPane(); this.draw(); this.say('Back to the defaults. ' + this.sum.textContent);
  };

  // ---- drawing -------------------------------------------------------------------------------------------------------------------
  Dialog.prototype.model = function () {
    var s = this.settings, all = this.scope === 'all' ? this.allData : null;
    if (all && all.cube) { return E().fromCube(all.cube, s, this.cols, {}); }
    return E().build(all && all.records ? all.records : this.data, this.cols, s, {});
  };
  Dialog.prototype.draw = function () {
    if (!this.settings || this.closed) { return; }
    var self = this, s = this.settings, m;
    try { m = this.model(); } catch (err) { this.message('This chart cannot be drawn: ' + err.message, true); return; }
    this.last = m;
    var shown = this.scope === 'all' ? (this.allData && this.allData.records ? this.allData.records.rows.length : m.rows) : this.src.shown;
    var info = { shown: shown, total: this.src.total, filters: this.src.filters };
    var text = E().summary(m, s, info);
    this.sum.textContent = text;
    this.chartEl.setAttribute('aria-label', text);
    this.statusLine(m);
    if (m.refusal) {
      this.message(m.refusal.message, false, [
        el('button', { type: 'button', cls: 'fk', 'data-tc-fix': 'bar', onclick: function () { self.setType('bar'); } }, ['Bar chart']),
        el('button', { type: 'button', cls: 'fk', 'data-tc-fix': 'abs', onclick: function () { self.change(function (n) { n.series[0].abs = true; }); } }, ['Absolute values'])]);
      this.disposeChart();
      return;
    }
    if (m.empty) {
      this.message('No rows to chart' + (this.src.filters ? ' (the table’s filters leave none): clear them, or close and choose other rows.' : '.') +
        (this.allBtn.hidden ? '' : ' Chart all rows is below.'), false);
      this.disposeChart();
      return;
    }
    this.msg.hidden = true;
    this.chartEl.hidden = this.view === 'table';
    if (this.view === 'table') { this.drawTable(m); return; }
    if (!window.echarts) { this.message('The charting library did not load.', true); return; }
    var t = tokens(this.opts.root);
    var option = E().option(m, s, t);
    if (!this.chart || this.chart.isDisposed()) { this.chart = window.echarts.init(this.chartEl, null, { renderer: 'svg' }); }
    this.chart.setOption(option, true);
    this.chart.resize();
    this.option = option;
    this.point = -1;
  };
  Dialog.prototype.disposeChart = function () { if (this.chart && !this.chart.isDisposed()) { this.chart.dispose(); } this.chart = null; };
  Dialog.prototype.statusLine = function (m) {
    var src = this.src, bits = [];
    if (this.scope === 'all' && this.allData) {
      var r = this.allData.records;
      if (r) { bits.push(r.truncated && r.total ? 'All ' + group(r.rows.length) + ' of ' + group(r.total) + ' rows (the server’s limit)' : 'All ' + group(r.rows.length) + ' rows'); }
      else { bits.push('Every match, aggregated on the server' + (m.partial ? ' (partial)' : '')); }
    } else if (src.total != null && src.total > src.shown) { bits.push('Showing ' + group(src.shown) + ' of ' + group(src.total) + ' rows'); }
    else { bits.push(group(src.shown) + ' row' + (src.shown === 1 ? '' : 's')); }
    if (src.filters && this.scope !== 'all') { bits.push('after ' + src.filters); }
    if (src.context && src.context.asOf && src.context.asOf !== 'live') { bits.push('on ' + src.context.asOf); }
    if (src.approximate) { bits.push('figures as displayed (rounded)'); }
    var hidden = this.cols.filter(function (c) { return !c.chartable; }).map(function (c) { return c.label; });
    if (hidden.length) { bits.push(hidden.join(', ') + ' hidden for your role, not charted'); }
    bits = bits.concat(this.notes || [], m.notes || []);
    if (m.moreRows) { bits.push('more X values than a chart holds: the first ' + group(m.x.length)); }
    this.status.textContent = bits.join(' · ');
    var can = src.all && src.total != null && src.total > src.shown, why = can && src.allFor ? src.allFor(this.settings) : null;
    this.allBtn.hidden = !can;
    this.allBtn.disabled = !!why && this.scope !== 'all';
    this.allBtn.title = why || '';
    this.allBtn.textContent = this.scope === 'all' ? 'Chart the shown rows' : 'Chart all ' + group(src.total) + ' rows';
  };
  Dialog.prototype.toggleAll = function () {
    var self = this;
    if (this.scope === 'all') { this.scope = 'shown'; this.allData = null; this.draw(); this.say(this.sum.textContent); return; }
    if (!this.src.all || this.allBtn.disabled) { return; }
    this.status.textContent = (this.src.kind === 'search' ? 'Aggregating every match on the server…' : 'Reading ' + group(this.src.total) + ' rows…');
    this.allBtn.disabled = true;
    var arr = E().toArrangement(this.settings, null).arrangement;
    Promise.resolve(this.src.all(this.settings, arr)).then(function (ans) {
      self.allData = ans; self.scope = 'all'; self.allBtn.disabled = false;
      self.draw(); self.say(self.sum.textContent);
    }, function (err) {
      self.allBtn.disabled = false; self.draw();
      self.status.textContent += ' · Chart all failed: ' + (err && err.message ? err.message : err);
    });
  };
  Dialog.prototype.drawTable = function (m) {
    var g = E().exportGrid(m, this.settings, this.src.title), PE = window.drishtiPivotEngine || (window.drishtiModules || {}).pivotEngine;
    var t = el('table', { cls: 'tbl', 'data-plain': true, 'data-no-chart': true, 'aria-label': 'The chart’s data' });
    t.appendChild(el('thead', {}, [el('tr', {}, g.header.map(function (h) { return el('th', { scope: 'col', text: h }); }))]));
    var b = el('tbody');
    g.rows.forEach(function (r) {
      b.appendChild(el('tr', {}, r.map(function (v, i) {
        var n = typeof v === 'number';
        return el(i ? 'td' : 'th', { scope: i ? null : 'row', cls: n ? 'num mono' : 'mono', text: v == null ? '' : n && PE ? PE.format(v, 'compact') : String(v) });
      })));
    });
    t.appendChild(b);
    this.tableBox.textContent = '';
    this.tableBox.appendChild(t);
  };
  Dialog.prototype.toggleTable = function () {
    this.view = this.view === 'table' ? 'chart' : 'table';
    this.tableBox.hidden = this.view !== 'table';
    this.tableBtn.setAttribute('aria-pressed', this.view === 'table' ? 'true' : 'false');
    this.tableBtn.lastChild.textContent = this.view === 'table' ? ' View as chart' : ' View as table';
    this.draw();
    this.say(this.view === 'table' ? 'The chart’s data as a table.' : 'The chart.');
  };

  // ---- the chart from the keyboard -----------------------------------------------------------------------------------------------
  Dialog.prototype.pointKey = function (e) {
    var m = this.last;
    if (!m || !this.chart || e.altKey || e.ctrlKey || e.metaKey) { return; }
    var n = m.axis === 'value' ? ((m.series[this.si] || {}).data || []).length : m.x.length, k = e.key;
    if (!n) { return; }
    if (k === 'ArrowRight') { this.point = Math.min(n - 1, this.point + 1); } else if (k === 'ArrowLeft') { this.point = Math.max(0, this.point < 0 ? 0 : this.point - 1); }
    else if (k === 'Home') { this.point = 0; } else if (k === 'End') { this.point = n - 1; }
    else if (k === 'ArrowDown') { this.si = (this.si + 1) % Math.max(1, m.series.length); if (this.point < 0) { this.point = 0; } }
    else if (k === 'ArrowUp') { this.si = (this.si + m.series.length - 1) % Math.max(1, m.series.length); if (this.point < 0) { this.point = 0; } }
    else { return; }
    e.preventDefault(); e.stopPropagation();
    var PE = window.drishtiPivotEngine || (window.drishtiModules || {}).pivotEngine, j = this.point, self = this;
    var fmt = function (v, d) { return v == null ? 'no value' : PE ? PE.format(v, d && d.fmt === 'pct' ? 'pct' : 'compact') : String(v); };
    var text;
    if (m.axis === 'value') {
      var d = m.series[this.si], p = d.data[j];
      text = d.name + ': ' + m.xLabel + ' ' + fmt(p[0]) + ', ' + fmt(p[1], d) + ' (' + (j + 1) + ' of ' + n + ')';
    } else if (this.settings.type === 'pie') {
      text = m.x[j] + ': ' + fmt(m.series[0].data[j], m.series[0]) + ' (' + (j + 1) + ' of ' + n + ')';
    } else {
      var at = m.series[this.si] ? m.series[this.si].name + ' ' + fmt(m.series[this.si].data[j], m.series[this.si]) : '';
      var others = m.series.filter(function (d, i) { return i !== self.si; }).map(function (d) { return d.name + ' ' + fmt(d.data[j], d); });
      text = m.x[j] + ': ' + [at].concat(others).filter(Boolean).join(', ') + ' (' + (j + 1) + ' of ' + n + ')';
    }
    try { this.chart.dispatchAction({ type: 'showTip', seriesIndex: this.settings.type === 'pie' ? 0 : this.si, dataIndex: j }); } catch (err) { /* a tooltip is a nicety */ }
    this.say(text);
  };
  Dialog.prototype.key = function (e) {
    if (e.ctrlKey || e.metaKey || e.altKey) { return; }
    var k = e.key;
    e.stopPropagation();                                                  // the page's own keys (Z, Esc, ?, /, F-keys) stay out of the dialog
    if (k === 'Escape') { return; }                                       // the dialog's cancel closes it
    if (inField(e) || !this.settings) { return; }
    var t = TYPES[parseInt(k, 10) - 1];
    if (t && k.length === 1) { var b = this.pane.querySelector('[data-tc-type="' + t[0] + '"]'); if (b && !b.disabled) { e.preventDefault(); this.setType(t[0]); } return; }
    switch (k) {
      case 't': case 'T': e.preventDefault(); this.toggleTable(); break;
      case 'a': case 'A': if (!this.allBtn.hidden && !this.allBtn.disabled) { e.preventDefault(); this.toggleAll(); } break;
      case 'e': case 'E': if (!this.exportBox.hidden) { e.preventDefault(); this.exportBox.querySelector('button').focus(); this.say('Export: PNG, SVG, CSV, Excel or JSON Lines.'); } break;
      case '?': e.preventDefault(); window.open(HELP, '_blank', 'noopener'); break;
      default:
    }
  };

  // ---- export --------------------------------------------------------------------------------------------------------------------
  Dialog.prototype.name = function (ext) { return fileName(this.src.title) + '-chart-' + today() + '.' + ext; };
  Dialog.prototype.png = function () {
    if (!this.option || !window.echarts) { return; }
    var t = tokens(this.opts.root), w = Math.max(640, this.chartEl.clientWidth || 960), h = Math.max(400, this.chartEl.clientHeight || 540);
    var box = el('div', { cls: 'tc-offscreen' });
    document.body.appendChild(box);
    var c = window.echarts.init(box, null, { renderer: 'canvas', width: w, height: h });
    c.setOption(E().option(this.last, this.settings, t), true);
    var url = c.getDataURL({ type: 'png', pixelRatio: 2, backgroundColor: t.surface });
    c.dispose(); box.remove();
    var bin = atob(url.split(',')[1]), bytes = new Uint8Array(bin.length);
    for (var i = 0; i < bin.length; i++) { bytes[i] = bin.charCodeAt(i); }
    download(new Blob([bytes], { type: 'image/png' }), this.name('png'));
    this.say('Downloaded ' + this.name('png'));
  };
  Dialog.prototype.svg = function () {
    if (!this.chart || this.chart.isDisposed()) { return; }
    var t = tokens(this.opts.root), text;
    this.chart.setOption({ backgroundColor: t.surface });
    text = this.chart.renderToSVGString ? this.chart.renderToSVGString() : decodeURIComponent(this.chart.getDataURL({ type: 'svg' }).split(',')[1]);
    this.chart.setOption({ backgroundColor: 'transparent' });
    download(new Blob([text], { type: 'image/svg+xml' }), this.name('svg'));
    this.say('Downloaded ' + this.name('svg'));
  };
  Dialog.prototype.gridData = function () { return E().exportGrid(this.last, this.settings, fileName(this.src.title) + '-chart'); };
  Dialog.prototype.grid = function (ext) {
    var self = this;
    if (!this.last) { return; }
    var g = this.gridData();
    this.say('Preparing the ' + (ext === 'xlsx' ? 'Excel workbook' : ext === 'jsonl' ? 'JSON Lines file' : 'CSV file'));
    fetch('/export/grid.' + ext, { method: 'POST', headers: { 'Content-Type': 'application/json' }, credentials: 'same-origin', body: JSON.stringify(g) }).then(function (r) {
      if (!r.ok) { return r.text().then(function (x) { self.status.textContent += ' · Export failed: ' + x; }); }
      var cd = r.headers.get('Content-Disposition') || '', mm = /filename="?([^";]+)"?/.exec(cd);
      return r.blob().then(function (b) { var n = mm ? mm[1] : g.name + '.' + ext; download(b, n); self.say('Downloaded ' + n); });
    }, function () { self.status.textContent += ' · Export failed: the console could not be reached'; });
  };

  /** Opens the chart of a source (table-chart-sources.js). options: opener (focus returns there), root, exports (false: none). */
  function open(source, options) {
    if (current) { current.close(); }
    current = new Dialog(source, options);
    current.show();
    return current;
  }
  var api = { open: open, current: function () { return current; }, memo: MEMO };
  (window.drishtiModules = window.drishtiModules || {}).tableChart = api;
  window.drishtiTableChart = api;
})();
