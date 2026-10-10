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
/* Excel-style column filters (RUPAKA.md 8.1). Every column heading of a table, matrix (tree rows) or pivot grows a funnel
   button; its menu sorts, lists the column's distinct values with counts (search, Select all, Blanks) or filters by a
   condition for the column's detected type (numbers, dates, text). Filters combine across columns (AND).
   Two halves: pure functions (value, kindOf, detect, test, matches, summarise, encode, decode: testable on their own) and
   attach(options), which draws the buttons and the menu for one table. tables.js and tree-rows.js call attach; the
   filter state lives in the caller's own state object (options.state.f: column index -> filter), so it survives a live
   patch that replaces the table. In the console the filters are in the URL (?f.<panel>.<column>=...); in an element
   they are kept in memory. Masked cells are one "(masked)" entry in a checklist, never match a condition and never show a
   raw value. */
(function () {
  'use strict';
  var MASK = '•••', LIST_MAX = 500;

  // ---- values and types ----------------------------------------------------------------------------------------
  var MULT = { k: 1e3, m: 1e6, mm: 1e6, bn: 1e9, b: 1e9, tn: 1e12 };
  /** A cell as a sortable value: {n} for a number (1.5m, 250k, -30,205,543, 12%) or a date, else {s} (lower-case text). */
  function value(text) {
    var t = text.replace(/[−–]/g, '-').replace(/,/g, '').replace(/\s/g, '');
    var m = t.match(/^([+-]?)(\d+(?:\.\d+)?)(k|m|mm|bn|b|tn)?(%|bp)?$/i);
    if (m) { var n = parseFloat(m[2]) * (m[3] ? MULT[m[3].toLowerCase()] : 1); return { n: m[1] === '-' ? -n : n }; }
    if (/^\d{4}-\d{2}-\d{2}/.test(t) && !isNaN(Date.parse(t.slice(0, 10)))) { return { n: Date.parse(t.slice(0, 10)) }; }
    return { s: text.toLowerCase() };
  }
  function compare(a, b) {
    if (a.n != null && b.n != null) { return a.n - b.n; }
    if (a.n != null) { return -1; }
    if (b.n != null) { return 1; }
    return a.s < b.s ? -1 : a.s > b.s ? 1 : 0;
  }
  var ISO = /^\d{4}-\d{2}-\d{2}/;
  function norm(text) { return String(text == null ? '' : text).replace(/\s+/g, ' ').trim(); }
  /** 'blank', 'mask', 'date', 'num' or 'text' for one cell's text. */
  function kindOf(text) {
    var t = norm(text);
    if (!t) { return 'blank'; }
    if (t.indexOf(MASK) >= 0) { return 'mask'; }
    if (ISO.test(t) && !isNaN(Date.parse(t.slice(0, 10)))) { return 'date'; }
    return value(t).n != null ? 'num' : 'text';
  }
  /** The column's type from its cells: 'number' or 'date' when at least 80% of the cells that have a value agree, else 'text'. */
  function detect(texts) {
    var c = { num: 0, date: 0, text: 0 }, total = 0;
    texts.forEach(function (t) { var k = kindOf(t); if (c[k] != null) { c[k]++; total++; } });
    if (!total) { return 'text'; }
    return c.num / total >= 0.8 ? 'number' : c.date / total >= 0.8 ? 'date' : 'text';
  }
  function dayNum(text) { return Math.floor(Date.parse(String(text).slice(0, 10)) / 864e5); }
  var api = {};
  /** Today as a day number (tests replace this to pin the calendar). */
  api.today = function () { var d = new Date(); return Date.UTC(d.getFullYear(), d.getMonth(), d.getDate()) / 864e5; };
  function monthKey(dn) { var d = new Date(dn * 864e5); return d.getUTCFullYear() * 12 + d.getUTCMonth(); }

  // ---- conditions: [op, label, number of arguments] ----------------------------------------------------------------
  var OPS = {
    number: [['eq', 'equals', 1], ['ne', 'does not equal', 1], ['gt', 'greater than', 1], ['ge', 'greater than or equal to', 1], ['lt', 'less than', 1],
      ['le', 'less than or equal to', 1], ['bt', 'between', 2], ['top', 'top N', 1], ['bot', 'bottom N', 1], ['avga', 'above average', 0], ['avgb', 'below average', 0]],
    date: [['today', 'today', 0], ['yest', 'yesterday', 0], ['thisweek', 'this week', 0], ['lastweek', 'last week', 0], ['thismonth', 'this month', 0],
      ['lastmonth', 'last month', 0], ['last7', 'last 7 days', 0], ['last30', 'last 30 days', 0], ['bt', 'between', 2], ['bf', 'before', 1], ['af', 'after', 1]],
    text: [['eq', 'equals', 1], ['ne', 'does not equal', 1], ['ct', 'contains', 1], ['nc', 'does not contain', 1], ['bw', 'begins with', 1], ['ew', 'ends with', 1]]
  };
  function opInfo(type, op) { var l = OPS[type] || OPS.text; for (var i = 0; i < l.length; i++) { if (l[i][0] === op) { return l[i]; } } return null; }
  function num(s) { return s == null ? NaN : (function (v) { return v.n == null ? NaN : v.n; })(value(norm(s))); }

  /** What a condition needs from the whole column (an average, the top-N cut-off, today) before rows are tested. */
  function context(c, texts) {
    var x = { t0: api.today() };
    if (c && (c.op === 'avga' || c.op === 'avgb' || c.op === 'top' || c.op === 'bot')) {
      var ns = [];
      texts.forEach(function (t) { if (kindOf(t) === 'num') { ns.push(value(norm(t)).n); } });
      if (c.op === 'avga' || c.op === 'avgb') { x.avg = ns.length ? ns.reduce(function (a, b) { return a + b; }, 0) / ns.length : 0; }
      else {
        var n = Math.max(1, parseInt(c.a, 10) || 10);
        ns.sort(function (a, b) { return c.op === 'top' ? b - a : a - b; });
        x.cut = ns.length ? ns[Math.min(n, ns.length) - 1] : null;
      }
    }
    return x;
  }
  /** Does one cell's text satisfy the condition c = {op, a, b} for a column of this type? x is context(). Masked cells never do. */
  function test(type, c, text, x) {
    var k = kindOf(text);
    if (k === 'mask') { return false; }
    var s = norm(text).toLowerCase(), a = norm(c.a).toLowerCase();
    if (type === 'text' || (type === 'number' && k !== 'num')) {
      if (type === 'number') { return c.op === 'ne'; }
      switch (c.op) {
        case 'eq': return s === a;
        case 'ne': return s !== a;
        case 'ct': return s.indexOf(a) >= 0;
        case 'nc': return s.indexOf(a) < 0;
        case 'bw': return s.indexOf(a) === 0;
        case 'ew': return a.length <= s.length && s.slice(s.length - a.length) === a;
        default: return false;
      }
    }
    if (type === 'number') {
      var v = value(s).n, w = num(c.a), w2 = num(c.b);
      switch (c.op) {
        case 'eq': return v === w;
        case 'ne': return v !== w;
        case 'gt': return v > w;
        case 'ge': return v >= w;
        case 'lt': return v < w;
        case 'le': return v <= w;
        case 'bt': return v >= Math.min(w, w2) && v <= Math.max(w, w2);
        case 'top': return x.cut != null && v >= x.cut;
        case 'bot': return x.cut != null && v <= x.cut;
        case 'avga': return v > x.avg;
        case 'avgb': return v < x.avg;
        default: return false;
      }
    }
    if (k !== 'date') { return false; }
    var d = dayNum(s), t0 = x.t0, wd = (new Date(t0 * 864e5).getUTCDay() + 6) % 7, mk = monthKey(t0), da = dayNum(c.a), db = dayNum(c.b);
    switch (c.op) {
      case 'today': return d === t0;
      case 'yest': return d === t0 - 1;
      case 'thisweek': return d >= t0 - wd && d <= t0 - wd + 6;
      case 'lastweek': return d >= t0 - wd - 7 && d <= t0 - wd - 1;
      case 'thismonth': return monthKey(d) === mk;
      case 'lastmonth': return monthKey(d) === mk - 1;
      case 'last7': return d >= t0 - 6 && d <= t0;
      case 'last30': return d >= t0 - 29 && d <= t0;
      case 'bt': return !isNaN(da) && !isNaN(db) && d >= Math.min(da, db) && d <= Math.max(da, db);
      case 'bf': return d < da;
      case 'af': return d > da;
      default: return false;
    }
  }
  /** One filter {v, b, m} (a checked list) or {c: {op, a, b}} (a condition) against one cell's text. x is context() for a condition. */
  function matches(type, f, text, x) {
    if (f.c) { return test(type, f.c, text, x); }
    var k = kindOf(text);
    if (k === 'blank') { return !!f.b; }
    if (k === 'mask') { return !!f.m; }
    return f.v.indexOf(norm(text)) >= 0;
  }
  /** Words for a funnel's tooltip: "Greater than 100", "Between 2026-01-01 and 2026-03-31", "3 values: A, B, C". */
  function summarise(type, f) {
    if (!f) { return ''; }
    if (f.c) {
      var o = opInfo(type, f.c.op), name = o ? o[1] : f.c.op, lead = name.charAt(0).toUpperCase() + name.slice(1);
      if (f.c.op === 'top' || f.c.op === 'bot') { return (f.c.op === 'top' ? 'Top ' : 'Bottom ') + (parseInt(f.c.a, 10) || 10); }
      if (o && o[2] === 2) { return lead + ' ' + f.c.a + ' and ' + f.c.b; }
      return o && o[2] === 1 ? lead + ' ' + f.c.a : lead;
    }
    var items = f.v.slice();
    if (f.b) { items.push('(Blanks)'); }
    if (f.m) { items.push('(masked)'); }
    return items.length + (items.length === 1 ? ' value: ' : ' values: ') + items.slice(0, 4).join(', ') + (items.length > 4 ? ', …' : '');
  }

  // ---- the compact URL form: eq:5, bt:1~9, top:10, today, in:a,b,~b ------------------------------------------------
  function enc(s) { return encodeURIComponent(s).replace(/~/g, '%7E'); }
  function dec(s) { try { return decodeURIComponent(s); } catch (e) { return s; } }
  function encode(f) {
    if (!f) { return ''; }
    if (f.c) { return f.c.op + (f.c.a != null && f.c.a !== '' ? ':' + enc(String(f.c.a)) + (f.c.b != null && f.c.b !== '' ? '~' + enc(String(f.c.b)) : '') : ''); }
    var items = f.v.map(enc);
    if (f.b) { items.push('~b'); }
    if (f.m) { items.push('~m'); }
    return 'in:' + items.join(',');
  }
  function decode(s) {
    if (!s) { return null; }
    var i = s.indexOf(':'), op = i < 0 ? s : s.slice(0, i), rest = i < 0 ? '' : s.slice(i + 1);
    if (op === 'in') {
      var f = { v: [], b: false, m: false };
      rest.split(',').forEach(function (p) { if (p === '~b') { f.b = true; } else if (p === '~m') { f.m = true; } else if (p !== '') { f.v.push(dec(p)); } });
      return f;
    }
    if (!/^[a-z0-9]+$/.test(op)) { return null; }
    var parts = rest ? rest.split('~') : [], c = { op: op };
    if (parts[0]) { c.a = dec(parts[0]); }
    if (parts[1]) { c.b = dec(parts[1]); }
    return { c: c };
  }
  api.value = value; api.compare = compare; api.kindOf = kindOf; api.detect = detect; api.test = test; api.context = context; api.matches = matches;
  api.summarise = summarise; api.encode = encode; api.decode = decode; api.OPS = OPS;

  // ---- the URL: ?f.<panel>.<column>=... (the console only) and the optional per-user memory --------------------------
  var PREFIX = 'f.';
  /** The filters in the address, as {panel: {column: filter}}. */
  api.readUrl = function () {
    var out = {};
    new URLSearchParams(location.search).forEach(function (val, key) {
      if (key.indexOf(PREFIX) !== 0) { return; }
      var rest = key.slice(PREFIX.length), d = rest.indexOf('.'), f = d > 0 ? decode(val) : null;
      if (f) { (out[rest.slice(0, d)] = out[rest.slice(0, d)] || {})[rest.slice(d + 1)] = f; }
    });
    return out;
  };
  function writeUrl(panel, column, f) {
    var u = new URL(location.href), key = PREFIX + panel + '.' + column;
    if (f) { u.searchParams.set(key, encode(f)); } else { u.searchParams.delete(key); }
    history.replaceState(history.state, '', u.pathname + u.search + u.hash);
  }
  var MEM = 'drishti.columnFilters';
  /** Off unless the page says <body data-remember-filters="on"> (a per-user setting) or this browser has drishti.rememberFilters = on. */
  function remembering() {
    try { if (localStorage.getItem('drishti.rememberFilters') === 'on') { return true; } } catch (e) { /* private window */ }
    return !!document.body && document.body.getAttribute('data-remember-filters') === 'on';
  }
  function memGet(k) { try { return (JSON.parse(localStorage.getItem(MEM) || '{}'))[k] || null; } catch (e) { return null; } }
  function memSet(k, obj) {
    try { var m = JSON.parse(localStorage.getItem(MEM) || '{}'); if (obj && Object.keys(obj).length) { m[k] = obj; } else { delete m[k]; } localStorage.setItem(MEM, JSON.stringify(m)); }
    catch (e) { /* private window: not remembered */ }
  }

  // ---- small DOM helpers ------------------------------------------------------------------------------------------
  function el(tag, cls, text) { var e = document.createElement(tag); if (cls) { e.className = cls; } if (text != null) { e.textContent = text; } return e; }
  var registry = {};                                    // table id -> its latest controller (a live patch replaces the table, not the open menu)
  var open = null;                                      // the one open menu: {menu, btn, close}
  function closeMenu(refocus) { if (open) { var o = open; open = null; o.close(refocus); } }
  api.close = closeMenu;
  /** Says something to screen readers, politely, from one live region per root. */
  api.say = function (root, msg) {
    var host = root === document ? document.body : root, r = host.querySelector(':scope > .cf-live');
    if (!r) { r = el('div', 'cf-live sr-only'); r.setAttribute('role', 'status'); r.setAttribute('aria-live', 'polite'); host.appendChild(r); }
    r.textContent = r.textContent === msg ? msg + ' ' : msg;
  };

  /** attach(o): column-filter buttons on o.heads and the menu behind them.
      o.id (unique per table), o.key (the panel's id in the URL), o.table, o.root, o.heads (th elements), o.state (its `f` holds the filters),
      o.rows() (all body rows), o.text(row, i), o.onChange() (re-filter and redraw), o.base(row) (does the row pass the other filters, such as
      the quick search), o.sort(i, dir) or null (sorting is not offered), o.sorted() ({col, dir}), o.useUrl (write the address).
      Returns {pass, active, clear, prepare, refresh, summary, set, button, destroy}. */
  api.attach = function (o) {
    var st = o.state, f = st.f = st.f || {}, ctx = {}, types = {}, urlCols = {};
    function name(i) { return norm(o.heads[i].getAttribute('data-cf-name') || o.heads[i].textContent) || ('column ' + (i + 1)); }
    function texts(i) { return o.rows().map(function (r) { return o.text(r, i); }); }
    function typeOf(i) { return types[i] || (types[i] = detect(texts(i))); }
    function persist(i, spec) {                           // the address (console) and the per-user memory (only when switched on)
      if (!o.useUrl) { return; }
      if (i == null) { Object.keys(urlCols).forEach(function (c) { writeUrl(o.key, c, null); }); urlCols = {}; }
      else { writeUrl(o.key, name(i), spec); if (spec) { urlCols[name(i)] = true; } else { delete urlCols[name(i)]; } }
      if (remembering()) { var m = {}; Object.keys(f).forEach(function (c) { m[name(+c)] = encode(f[c]); }); memSet(o.id, m); }
    }
    /** The hook for a table paged by the server: every change of the filters is announced on the table, with the filters in their address form. */
    function announce() {
      var enc = {};
      Object.keys(f).forEach(function (c) { enc[name(+c)] = encode(f[c]); });
      o.table.dispatchEvent(new CustomEvent('drishti:column-filter', { bubbles: true, composed: true, detail: { panel: o.key, filters: enc } }));
    }
    var self = {
      active: function () { return Object.keys(f).length > 0; },
      /** Recompute what the conditions need from the whole column (call before testing rows). */
      prepare: function () {
        types = {}; ctx = {};
        Object.keys(f).forEach(function (i) { ctx[i] = context(f[i].c, texts(+i)); });
      },
      /** Does the row pass every column filter (except column `skip`)? */
      pass: function (r, skip) {
        for (var k in f) {
          if (+k === skip || !r.cells[+k]) { continue; }
          if (!matches(typeOf(+k), f[k], o.text(r, +k), ctx[k] || (ctx[k] = context(f[k].c, texts(+k))))) { return false; }
        }
        return true;
      },
      summary: function (i) { return f[i] ? summarise(typeOf(i), f[i]) : ''; },
      clear: function (quiet) {
        Object.keys(f).forEach(function (i) { delete f[i]; });
        announce();
        persist(null);
        if (remembering() && o.useUrl) { memSet(o.id, null); }
        self.refresh();
        if (!quiet) { o.onChange(); }
      },
      refresh: function () {
        o.heads.forEach(function (h, i) {
          var b = h.querySelector('.cf-btn');
          if (!b) { return; }
          var on = !!f[i], s = on ? self.summary(i) : '';
          b.classList.toggle('cf-on', on);
          b.firstChild.className = 'bi bi-funnel' + (on ? '-fill' : '');
          b.title = on ? 'Filtered: ' + s + ' (click to change)' : 'Filter ' + name(i);
          b.setAttribute('aria-label', on ? 'Filter ' + name(i) + ', filtered: ' + s : 'Filter ' + name(i));
        });
      },
      set: function (i, spec) {
        if (spec) { f[i] = spec; } else { delete f[i]; }
        self.prepare();
        persist(i, spec);
        self.refresh();
        announce();
        o.onChange(i);
      },
      button: function (i) { var h = o.heads[i]; return h && h.querySelector('.cf-btn'); },
      destroy: function () { if (registry[o.id] === self) { delete registry[o.id]; } }
    };
    registry[o.id] = self;
    // restore from the address (or the memory) the first time this table is seen; a live patch finds the state in place
    if (!st.cfInit) {
      st.cfInit = true;
      var seed = o.useUrl ? api.readUrl()[o.key] : null;
      if (!seed && o.useUrl && remembering()) {
        var mem = memGet(o.id);
        if (mem) { seed = {}; Object.keys(mem).forEach(function (c) { seed[c] = decode(mem[c]); }); }
      }
      if (seed) {
        o.heads.forEach(function (h, i) {
          if (!seed[name(i)]) { return; }
          f[i] = seed[name(i)]; urlCols[name(i)] = true;
          writeUrl(o.key, name(i), f[i]);
        });
      }
    } else {
      Object.keys(f).forEach(function (i) { urlCols[name(+i)] = true; });
    }

    // ---- the buttons -------------------------------------------------------------------------------------------
    o.heads.forEach(function (h, i) {
      var old = h.querySelector('.cf-btn'), b = el('button', 'cf-btn'), glyph = el('i', 'bi bi-funnel');
      if (old) { old.remove(); }
      b.type = 'button'; b.setAttribute('aria-haspopup', 'dialog'); b.setAttribute('aria-expanded', 'false'); b.setAttribute('data-cf-col', String(i));
      glyph.setAttribute('aria-hidden', 'true'); b.appendChild(glyph);
      b.addEventListener('click', function (e) { e.stopPropagation(); e.preventDefault(); if (open && open.btn === b) { closeMenu(true); } else { show(i, b); } });
      h.appendChild(b);
    });
    self.refresh();

    // ---- the menu ----------------------------------------------------------------------------------------------
    function show(i, btn) {
      closeMenu();
      var current = f[i] || null, type = typeOf(i);
      var rows = o.rows().filter(function (r) { return r.cells[i] && o.base(r) && self.pass(r, i); });
      var counts = {}, order = [], blanks = 0, masked = 0;
      rows.forEach(function (r) {
        var t = norm(o.text(r, i)), k = kindOf(t);
        if (k === 'blank') { blanks++; } else if (k === 'mask') { masked++; } else { if (counts[t] == null) { counts[t] = 0; order.push(t); } counts[t]++; }
      });
      order.sort(function (a, b) { return compare(value(a), value(b)); });
      var up = type === 'number' ? 'smallest→largest' : type === 'date' ? 'oldest→newest' : 'A→Z';
      var down = type === 'number' ? 'largest→smallest' : type === 'date' ? 'newest→oldest' : 'Z→A';

      var menu = el('div', 'cf-menu');
      menu.setAttribute('role', 'dialog'); menu.setAttribute('aria-label', 'Filter ' + name(i)); menu.setAttribute('data-cf-menu', String(i));
      function act(label, fn, cls) { var x = el('button', cls || 'cf-act', label); x.type = 'button'; x.addEventListener('click', fn); return x; }
      function focusables() { return Array.prototype.filter.call(menu.querySelectorAll('button, input, select'), function (x) { return !x.disabled && !x.hidden && x.offsetParent !== null; }); }
      if (o.sort) {
        var so = o.sorted ? o.sorted() : {}, bar = el('div', 'cf-sort');
        [['Sort ' + up, 1], ['Sort ' + down, -1]].forEach(function (p) {
          var x = act(p[0], function () { closeMenu(true); o.sort(i, p[1]); });
          x.setAttribute('aria-pressed', so.col === i && so.dir === p[1] ? 'true' : 'false');
          bar.appendChild(x);
        });
        menu.appendChild(bar);
      }
      // a condition
      var cw = el('div', 'cf-cond'), sel = el('select', 'cf-op'), none = el('option', null, '(no condition)');
      sel.setAttribute('aria-label', 'Filter by condition'); none.value = ''; sel.appendChild(none);
      OPS[type].forEach(function (p) { var op = el('option', null, p[1].charAt(0).toUpperCase() + p[1].slice(1)); op.value = p[0]; sel.appendChild(op); });
      var ia = el('input', 'cf-a'), ib = el('input', 'cf-b'), err = el('div', 'cf-err');
      err.setAttribute('role', 'alert');
      function showArgs() {
        var p = opInfo(type, sel.value), n = p ? p[2] : 0, top = sel.value === 'top' || sel.value === 'bot';
        ia.hidden = n < 1; ib.hidden = n < 2;
        ia.type = top ? 'number' : type === 'date' ? 'date' : 'text'; ib.type = type === 'date' ? 'date' : 'text';
        ia.setAttribute('aria-label', top ? 'How many items' : n === 2 ? 'From' : 'Value'); ib.setAttribute('aria-label', 'To');
        ia.placeholder = type === 'number' && !top ? 'e.g. 1m' : ''; ib.placeholder = ia.placeholder;
        if (top && !ia.value) { ia.value = '10'; }
      }
      if (current && current.c) { sel.value = current.c.op; ia.value = current.c.a || ''; ib.value = current.c.b || ''; }
      cw.appendChild(sel); cw.appendChild(ia); cw.appendChild(ib); menu.appendChild(cw); menu.appendChild(err);
      showArgs();
      sel.addEventListener('change', function () { showArgs(); err.textContent = ''; if (sel.value && !ia.hidden) { ia.focus(); } });

      // the checklist
      var q = el('input', 'cf-q'), list = el('div', 'cf-list'), items = [], typed = false;
      q.type = 'search'; q.placeholder = 'Search'; q.setAttribute('aria-label', 'Search the values of ' + name(i));
      list.setAttribute('role', 'group'); list.setAttribute('aria-label', 'Values of ' + name(i));
      var all = el('label', 'cf-item cf-all'), allBox = el('input');
      allBox.type = 'checkbox'; all.appendChild(allBox); all.appendChild(el('span', 'cf-t', '(Select all)'));
      function visibleItems() { return items.filter(function (x) { return !x.label.hidden; }); }
      function syncAll() {
        var vis = visibleItems(), on = vis.filter(function (x) { return x.box.checked; }).length;
        allBox.checked = on > 0 && on === vis.length; allBox.indeterminate = on > 0 && on < vis.length;
      }
      function item(label, count, key, checked, cls) {
        var l = el('label', 'cf-item' + (cls ? ' ' + cls : '')), c = el('input');
        c.type = 'checkbox'; c.checked = checked;
        l.appendChild(c); l.appendChild(el('span', 'cf-t', label)); l.appendChild(el('span', 'cf-n mono', String(count)));
        items.push({ box: c, label: l, key: key, text: label.toLowerCase() }); list.appendChild(l);
        c.addEventListener('change', function () { sel.value = ''; showArgs(); syncAll(); });
      }
      var listF = current && !current.c ? current : null;
      function chosen(t, key) { return !listF || (key === 'b' ? listF.b : key === 'm' ? listF.m : listF.v.indexOf(t) >= 0); }
      if (blanks) { item('(Blanks)', blanks, 'b', chosen('', 'b'), 'cf-sp'); }
      if (masked) { item('(masked)', masked, 'm', chosen('', 'm'), 'cf-sp'); }
      order.slice(0, LIST_MAX).forEach(function (t) { item(t, counts[t], t, chosen(t, 'v')); });
      var more = el('div', 'cf-more', order.length > LIST_MAX ? 'The first ' + LIST_MAX + ' of ' + order.length + ' values; search to narrow the list.' : '');
      allBox.addEventListener('change', function () {
        visibleItems().forEach(function (x) { x.box.checked = allBox.checked; });
        sel.value = ''; showArgs(); syncAll();
      });
      q.addEventListener('input', function () {            // as in Excel, a search leaves just its results ticked
        var s = q.value.trim().toLowerCase();
        typed = !!s;
        if (s) { order.slice(LIST_MAX).forEach(function (t) { if (t.toLowerCase().indexOf(s) >= 0 && !items.some(function (x) { return x.key === t; })) { item(t, counts[t], t, true); } }); }
        items.forEach(function (x) { x.label.hidden = !!s && x.text.indexOf(s) < 0; if (s) { x.box.checked = !x.label.hidden; } });
        sel.value = ''; showArgs(); syncAll();
        api.say(o.root, visibleItems().length + ' values match');
      });
      menu.appendChild(q); menu.appendChild(all); menu.appendChild(list); menu.appendChild(more);
      syncAll();

      // OK / Cancel / Clear
      function apply() {
        var spec = null;
        if (sel.value) {
          var p = opInfo(type, sel.value), a = ia.value.trim(), b = ib.value.trim(), top = sel.value === 'top' || sel.value === 'bot';
          if ((p[2] >= 1 && !a) || (p[2] === 2 && !b)) { err.textContent = p[2] === 2 ? 'Enter both values.' : 'Enter a value.'; (a ? ib : ia).focus(); return; }
          if (type === 'number' && p[2] >= 1 && !top && (isNaN(num(a)) || (p[2] === 2 && isNaN(num(b))))) { err.textContent = 'Enter a number such as 100 or 1.5m.'; ia.focus(); return; }
          spec = { c: { op: sel.value } };
          if (p[2] >= 1) { spec.c.a = a; }
          if (p[2] === 2) { spec.c.b = b; }
        } else {
          var on = items.filter(function (x) { return x.box.checked; });
          if (on.length < items.length) {
            spec = { v: on.filter(function (x) { return x.key !== 'b' && x.key !== 'm'; }).map(function (x) { return x.key; }),
              b: on.some(function (x) { return x.key === 'b'; }), m: on.some(function (x) { return x.key === 'm'; }) };
          }
        }
        closeMenu(true);
        (registry[o.id] || self).set(i, spec);          // the table may have been replaced by a live patch while the menu was open
      }
      var foot = el('div', 'cf-foot');
      foot.appendChild(act('OK', apply, 'cf-act cf-ok'));
      foot.appendChild(act('Cancel', function () { closeMenu(true); }));
      foot.appendChild(act('Clear', function () { closeMenu(true); (registry[o.id] || self).set(i, null); }));
      menu.appendChild(foot);

      // keys: Esc closes (and is not heard by a zoomed panel or a drawer), Tab stays inside, arrows and type-ahead in the list, Enter applies
      var buf = '', bufT = 0;
      menu.addEventListener('keydown', function (e) {
        var tg = e.target;
        if (e.key === 'Escape') { e.preventDefault(); e.stopPropagation(); closeMenu(true); return; }
        if (e.key === 'Tab') {
          var fs = focusables();
          if (e.shiftKey && tg === fs[0]) { e.preventDefault(); fs[fs.length - 1].focus(); } else if (!e.shiftKey && tg === fs[fs.length - 1]) { e.preventDefault(); fs[0].focus(); }
          return;
        }
        var inList = tg.type === 'checkbox';
        if (inList && /^(ArrowDown|ArrowUp|Home|End)$/.test(e.key)) {
          var boxes = [allBox].concat(visibleItems().map(function (x) { return x.box; })), at = boxes.indexOf(tg);
          var to = e.key === 'Home' ? 0 : e.key === 'End' ? boxes.length - 1 : Math.min(boxes.length - 1, Math.max(0, at + (e.key === 'ArrowDown' ? 1 : -1)));
          e.preventDefault(); boxes[to].focus();
          return;
        }
        if (tg === q && e.key === 'ArrowDown') { e.preventDefault(); allBox.focus(); return; }
        if (inList && e.key.length === 1 && e.key !== ' ' && !e.ctrlKey && !e.altKey && !e.metaKey) {      // type-ahead: the next value that begins with what was typed
          buf += e.key.toLowerCase(); clearTimeout(bufT); bufT = setTimeout(function () { buf = ''; }, 800);
          var vis = visibleItems(), at2 = vis.findIndex(function (x) { return x.box === tg; });
          var hit = vis.slice(at2 + 1).concat(vis.slice(0, at2 + 1)).filter(function (x) { return x.text.indexOf(buf) === 0; })[0];
          if (hit) { hit.box.focus(); }
          e.preventDefault();
          return;
        }
        if (e.key === 'Enter' && tg.tagName !== 'BUTTON' && tg.tagName !== 'SELECT') { e.preventDefault(); apply(); }
      });

      // placed under the button and kept on screen; position:fixed, measured against its own origin (an element's box can be the containing block)
      (o.root === document ? document.body : o.root).appendChild(menu);
      menu.style.left = '0px'; menu.style.top = '0px';
      var origin = menu.getBoundingClientRect(), r = btn.getBoundingClientRect(), vw = window.innerWidth, vh = window.innerHeight;
      menu.style.maxHeight = Math.max(160, vh - 16) + 'px';
      var w = menu.offsetWidth, h = menu.offsetHeight, left = Math.min(Math.max(8, r.left), Math.max(8, vw - w - 8)), top = r.bottom + 4;
      if (top + h > vh - 8) { top = Math.max(8, r.top - h - 4); }
      menu.style.left = (left - origin.left) + 'px'; menu.style.top = (top - origin.top) + 'px';
      btn.setAttribute('aria-expanded', 'true');
      var doc = menu.ownerDocument;
      function away(e) {
        var t = e.composedPath ? e.composedPath()[0] : e.target;
        if (!t || menu.contains(t) || (t.closest && t.closest('.cf-btn'))) { return; }
        closeMenu(false);
      }
      function onResize() { closeMenu(false); }
      doc.addEventListener('pointerdown', away, true);
      window.addEventListener('resize', onResize);
      open = {
        menu: menu, btn: btn,
        close: function (refocus) {
          doc.removeEventListener('pointerdown', away, true);
          window.removeEventListener('resize', onResize);
          menu.remove();
          var b = (registry[o.id] && registry[o.id].button(i)) || btn;
          btn.setAttribute('aria-expanded', 'false'); b.setAttribute('aria-expanded', 'false');
          if (refocus && b.isConnected) { b.focus(); }
        }
      };
      (o.sort ? menu.querySelector('.cf-act') : sel).focus();
    }
    return self;
  };
  api.registry = registry;
  var reg = (window.drishtiModules = window.drishtiModules || {});
  reg.colFilter = api;
})();
