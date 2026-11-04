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
/* The browser's handle on the pivot engine (docs/guides/PIVOT_GUIDE.md; docs/architecture/PIVOT_ENGINE.md section 13): it never adds up a
   figure. It asks the server's engine (drishti-bicompute, through the console's /api/pivot/...) for the cells of an arrangement
   ({rows, columns, values}) over a source (query) or over rows the page already holds (inline: a chart of a table's shown rows),
   and lays the answer out as the CUBE the chart engine draws: the leaf row and column keys and one cell per pair of key prefixes
   (every subtotal and the grand total), each with one figure per value. Also the key texts and number formats by the Sutra's
   format names. Loaded in the page (window.Drishti.pivotEngine) and under node for tests (module.exports). */
(function (root) {
  'use strict';
  var US = '\u001f', RS = '\u001e', BLANK = '(blank)';
  var AGG_LABEL = { sum: 'Sum', count: 'Count', avg: 'Average', min: 'Min', max: 'Max', distinct: 'Distinct count' };
  /** The chart's aggregations as the engine's functions. */
  var FN = { sum: 'sum', count: 'count', avg: 'avg', min: 'min', max: 'max', distinct: 'count_distinct' };
  var ROWS = '·rows';

  /* Numbers as keys: written plainly (never 4.2e+21), at most 15 significant digits (0.1 + 0.2 reads 0.3). */
  var PLAIN = (typeof Intl !== 'undefined' && Intl.NumberFormat) ? new Intl.NumberFormat('en-US', { useGrouping: false, maximumSignificantDigits: 15 }) : null;
  /** A value as a key: text; null, missing and empty text are "(blank)"; whole numbers without ".0". */
  function keyText(v) {
    if (v === null || v === undefined || v === '') { return BLANK; }
    if (typeof v === 'number') { return !isFinite(v) ? BLANK : v === 0 ? '0' : PLAIN ? PLAIN.format(v) : String(Number(v.toPrecision(15))); }
    if (typeof v === 'boolean') { return v ? 'true' : 'false'; }
    if (typeof v === 'object') { return JSON.stringify(v); }
    return String(v);
  }
  function cellKey(rk, ck) { return rk.join(US) + RS + ck.join(US); }

  /** The arrangement's values as the engine's measures, {measure, as}; field(name) names a value's field in the engine's terms. */
  function measures(arrangement, field) {
    return arrangement.values.map(function (v, i) {
      var f = v.field === ROWS ? null : field(v.field), fn = v.field === ROWS ? 'count' : FN[v.agg] || 'sum';
      return { measure: fn === 'count' && !f ? 'count()' : fn + '(' + f + ')', as: 'v' + i };
    });
  }
  var OTHERS_KEY = '\u0000Others';
  /** The arrangement's top ({field: {n, by: value index}}) as the view's, by the engine's field and measure names. */
  function topOf(arrangement, field, ms) {
    var t = {};
    Object.keys(arrangement.top || {}).forEach(function (f) { var x = arrangement.top[f], d = field(f); if (d) { t[d] = { n: x.n, by: ms[x.by].as }; } });
    return t;
  }
  /**
   * An engine answer as the cube: the members' keys in the engine's order, one figure per measure in each cell; a level's Others (the
   * engine's own member, its figures from its own rows) under the key OTHERS_KEY, with how many members it holds (rowOthers, columnOthers).
   */
  function fromAnswer(a, arrangement, ms) {
    if (!a || !a.cells) { throw new Error((a && (a.detail || a.message)) || 'The pivot engine did not answer'); }
    var cells = {}, rk = [], ck = [];
    var keep = function (x) { return x.kind === 'node' || x.kind === 'grand' || x.kind === 'others'; };
    var at = function (x) { return x.kind === 'others' ? [OTHERS_KEY] : x.kind === 'node' ? [x.value] : []; };
    var rows = (a.rows || []).filter(keep), cols = (a.columns || []).filter(keep), ro = 0, co = 0;
    rows.forEach(function (r) { if (r.kind === 'node') { rk.push([r.value]); } else if (r.kind === 'others') { ro = r.others || 1; } });
    cols.forEach(function (c) { if (c.kind === 'node') { ck.push([c.value]); } else if (c.kind === 'others') { co = c.others || 1; } });
    rows.forEach(function (r) {
      cols.forEach(function (c) {
        var cell = a.cells[r.key + '|' + c.key];
        if (!cell) { return; }
        cells[cellKey(at(r), at(c))] =
          ms.map(function (m) { var x = cell.values ? cell.values[m.as] : null; return x == null ? null : x; });
      });
    });
    var grand = a.cells['*|*'], n = grand && typeof grand.members === 'number' ? grand.members : null;
    return { rows: arrangement.rows, columns: arrangement.columns, values: arrangement.values, rowKeys: rk, columnKeys: ck, cells: cells,
      count: n, total: n, rowOthers: ro, columnOthers: co, partial: !!a.partial };
  }
  function postJson(url, init) {
    return fetch(url, { method: 'POST', credentials: 'same-origin', headers: { 'Accept': 'application/json', 'Content-Type': 'application/json' },
      body: JSON.stringify(init.body) }).then(function (r) { return r.json().catch(function () { return {}; }); });
  }
  /** Every row of a source aggregated by the engine (POST /api/pivot/query); field(name) is the source's field of a chart field. */
  function query(source, arrangement, field, fetchJson) {
    var ms = measures(arrangement, field);
    var view = { source: source, rows: arrangement.rows.map(field), columns: arrangement.columns.map(field), values: ms, expandTo: { rows: 1, columns: 1 },
      top: topOf(arrangement, field, ms) };
    return (fetchJson || postJson)('/api/pivot/query', { method: 'POST', body: { view: view } }).then(function (a) { return fromAnswer(a, arrangement, ms); });
  }
  /** The body of an inline pivot: the arrangement's fields only, renamed d0.. (rows and columns, as key texts) and v0.. (values). */
  function inlineBody(records, arrangement) {
    var at = {}, names = {}, fields = [], take = [], vals = [];
    (records.fields || []).forEach(function (f, i) { at[f.name] = i; });
    arrangement.rows.concat(arrangement.columns).forEach(function (d, i) {
      names['d:' + d] = 'd' + i; fields.push({ name: 'd' + i, type: 'string', label: d }); take.push({ i: at[d], key: true });
    });
    arrangement.values.forEach(function (v) { if (v.field !== ROWS && vals.indexOf(v.field) < 0) { vals.push(v.field); } });
    vals.forEach(function (f, i) {
      var text = arrangement.values.some(function (v) { return v.field === f && v.agg === 'distinct'; });
      names['v:' + f] = 'v' + i;
      fields.push({ name: 'v' + i, type: text ? 'string' : 'number', label: f });
      take.push({ i: at[f], key: text });
    });
    var rows = (records.rows || []).map(function (r) {
      return take.map(function (t) {
        var v = t.i == null ? null : r[t.i];
        if (t.key) { return v === null || v === undefined || v === '' ? null : keyText(v); }
        return typeof v === 'number' && isFinite(v) ? v : null;
      });
    });
    var ms = measures(arrangement, function (n) { return names['v:' + n] || null; });
    var view = { rows: arrangement.rows.map(function (d) { return names['d:' + d]; }), columns: arrangement.columns.map(function (d) { return names['d:' + d]; }),
      values: ms, expandTo: { rows: 1, columns: 1 }, top: topOf(arrangement, function (d) { return names['d:' + d]; }, ms) };
    return { body: { fields: fields, rows: rows, view: view }, measures: ms };
  }
  /** Rows the page holds ({fields, rows}) aggregated by the engine (POST /api/pivot/inline): the cube of the arrangement. */
  function inline(records, arrangement, fetchJson) {
    var b = inlineBody(records, arrangement);
    return (fetchJson || postJson)('/api/pivot/inline', { method: 'POST', body: b.body }).then(function (a) { return fromAnswer(a, arrangement, b.measures); });
  }

  // ---- numbers as the Sutra's formats show them ---------------------------------------------------------------------
  function group(s) { return s.replace(/\B(?=(\d{3})+(?!\d))/g, ','); }
  function fixed(v, dp) {
    var parts = Math.abs(v).toFixed(dp).split('.');
    return group(parts[0]) + (parts[1] ? '.' + parts[1] : '');
  }
  function compact(v) {
    var a = Math.abs(v), sign = v < 0 && Math.round(a * 100) > 0 ? '−' : '';
    var t = function (x, dp) { return String(Number(x.toFixed(dp))); };
    if (a >= 1e12) { return sign + t(a / 1e12, 1) + 'tn'; }
    if (a >= 1e9) { return sign + t(a / 1e9, 1) + 'bn'; }
    if (a >= 1e6) { return sign + t(a / 1e6, 1) + 'm'; }
    if (a >= 1e3) { return sign + t(a / 1e3, 1) + 'k'; }
    return sign + t(a, 2);
  }
  /**
   * {@code v} formatted: "pct" shows a fraction as a percentage (show-as), "int" a whole number, a format name as the
   * Sutra's (amount0, signed0, pct2, price2, compact, …), none: separators and up to two decimals. Null is an em dash.
   */
  function format(v, fmt) {
    if (v === null || v === undefined || (typeof v === 'number' && !isFinite(v))) { return '—'; }
    if (typeof v !== 'number') { return String(v); }
    var neg = function (x, dp) { return x < 0 && Math.round(Math.abs(x) * Math.pow(10, dp)) > 0 ? '−' : ''; };      // a value that rounds to zero is zero: never "-0.00"
    if (fmt === 'pct') { return neg(v * 100, 1) + fixed(v * 100, 1) + '%'; }
    if (fmt === 'int') { return neg(v, 0) + fixed(v, 0); }
    if (fmt === 'compact') { return compact(v); }
    var m = /^(pct|signed|amount|price|rate|df|pips)(\d)$/.exec(fmt || '');
    if (m) {
      var dp = +m[2];
      if (m[1] === 'pct') { return neg(v * 100, dp) + fixed(v * 100, dp) + '%'; }
      return (m[1] === 'signed' ? (neg(v, dp) || (Math.round(Math.abs(v) * Math.pow(10, dp)) > 0 ? '+' : '')) : neg(v, dp)) + fixed(v, dp);
    }
    var whole = Math.abs(v - Math.round(v)) < 1e-9, dps = whole || Math.abs(v) >= 100 ? 0 : 2;
    return neg(v, dps) + fixed(v, dps);
  }

  var api = { US: US, RS: RS, BLANK: BLANK, OTHERS_KEY: OTHERS_KEY, AGG_LABEL: AGG_LABEL, keyText: keyText, cellKey: cellKey, format: format,
    measures: measures, fromAnswer: fromAnswer, query: query, inlineBody: inlineBody, inline: inline };
  if (typeof module !== 'undefined' && module.exports) { module.exports = api; } else {
    var cs = typeof document !== 'undefined' && document.currentScript;
    (root.drishtiModules = root.drishtiModules || {}).pivotEngine = api;
    if (!(cs && cs.hasAttribute('data-manual'))) { (root.Drishti = root.Drishti || {}).pivotEngine = api; }
  }
})(this);
