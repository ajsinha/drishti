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
/* The interactive pivot's client engine (USER_GUIDE.md, The Pivot tab): pure functions, no DOM. It turns a panel's
   records ({fields, rows}) and an arrangement ({rows, columns, values, filters}) into a CUBE: the leaf row and column
   keys, and one cell per pair of key PREFIXES (so every subtotal and the grand total are there too), each holding one
   aggregate per value. The server's search pivot (POST /api/v1/search/pivot/{kind}) answers with the same shape, so
   pivot.js and pivot-grid.js draw both alike. Also: the rows under one cell (drill-down), a field's distinct values
   (filter pick lists), and number formatting by the Sutra's format names. Loaded in the page (window.drishtiPivotEngine)
   and under node for tests (module.exports). */
(function (root) {
  'use strict';
  var US = '\u001f', RS = '\u001e', BLANK = '(blank)';
  var AGGS = ['sum', 'count', 'avg', 'min', 'max', 'distinct'];
  var SHOWS = ['value', 'pctRow', 'pctColumn', 'pctTotal'];
  var AGG_LABEL = { sum: 'Sum', count: 'Count', avg: 'Average', min: 'Min', max: 'Max', distinct: 'Distinct count' };
  var SHOW_LABEL = { value: 'value', pctRow: '% of row', pctColumn: '% of column', pctTotal: '% of total' };
  var LIMITS = { rows: 4, columns: 4, values: 6, rowKeys: 2000, columnKeys: 200 };

  function num(v) { return typeof v === 'number' && isFinite(v); }
  /** A value as a key: text; null, missing and empty text are "(blank)"; whole numbers without ".0". */
  function keyText(v) {
    if (v === null || v === undefined || v === '') { return BLANK; }
    if (typeof v === 'number') { return isFinite(v) ? String(v) : BLANK; }
    if (typeof v === 'boolean') { return v ? 'true' : 'false'; }
    if (typeof v === 'object') { return JSON.stringify(v); }
    return String(v);
  }
  var NUMERIC = /^[+-]?\d+(\.\d+)?([eE][+-]?\d+)?$/;
  /** Numbers in number order, then text (case-insensitive), "(blank)" last. */
  function compareKey(a, b) {
    if (a === b) { return 0; }
    if (a === BLANK) { return 1; }
    if (b === BLANK) { return -1; }
    var na = NUMERIC.test(a), nb = NUMERIC.test(b);
    if (na && nb) { return parseFloat(a) - parseFloat(b) || (a < b ? -1 : 1); }
    if (na) { return -1; }
    if (nb) { return 1; }
    return natural(a, b) || (a < b ? -1 : a > b ? 1 : 0);
  }
  /** Text in natural order: runs of digits by value, the rest alphabetically in any case ("2-5Y" before "10Y+"). The
      server's PivotCube sorts the same way. */
  function natural(a, b) {
    var x = a.toLowerCase().match(/\d+|\D+/g) || [], y = b.toLowerCase().match(/\d+|\D+/g) || [];
    for (var i = 0; i < Math.min(x.length, y.length); i++) {
      var dx = /^\d/.test(x[i]), dy = /^\d/.test(y[i]);
      if (dx && dy) { var d = parseInt(x[i], 10) - parseInt(y[i], 10); if (d) { return d; } }
      else if (x[i] !== y[i]) { return x[i] < y[i] ? -1 : 1; }
    }
    return x.length - y.length;
  }
  function compareKeys(a, b) {
    for (var i = 0; i < Math.min(a.length, b.length); i++) { var c = compareKey(a[i], b[i]); if (c) { return c; } }
    return a.length - b.length;
  }
  function cellKey(rk, ck) { return rk.join(US) + RS + ck.join(US); }

  function index(records) {
    var at = {};
    (records.fields || []).forEach(function (f, i) { at[f.name] = i; });
    return at;
  }
  function getter(at, name) { var i = at[name]; return i == null ? function () { return null; } : function (row) { return row[i]; }; }
  function has(x) { return x !== null && x !== undefined && x !== ''; }

  /** Whether a row passes every filter: values (its key text in the list), min/max (inclusive; numbers, or ISO text). */
  function passer(at, filters) {
    var tests = (filters || []).filter(function (f) { return f && f.field && at[f.field] != null; }).map(function (f) {
      var get = getter(at, f.field), set = null, lo = f.min, hi = f.max;
      if (f.values && f.values.length) { set = {}; f.values.forEach(function (v) { set[keyText(v)] = true; }); }
      var numericBounds = (!has(lo) || NUMERIC.test(String(lo).trim())) && (!has(hi) || NUMERIC.test(String(hi).trim()));
      return function (row) {
        var v = get(row);
        if (set && !set[keyText(v)]) { return false; }
        if (has(lo) || has(hi)) {
          if (!has(v)) { return false; }
          if (numericBounds && (num(v) || (typeof v === 'string' && NUMERIC.test(v.trim())))) {
            var n = typeof v === 'number' ? v : parseFloat(v);
            if (has(lo) && n < parseFloat(lo)) { return false; }
            if (has(hi) && n > parseFloat(hi)) { return false; }
          } else {
            var s = String(v);
            if (has(lo) && s < String(lo)) { return false; }
            if (has(hi) && s.slice(0, String(hi).length) > String(hi)) { return false; }
          }
        }
        return true;
      };
    });
    return function (row) { for (var i = 0; i < tests.length; i++) { if (!tests[i](row)) { return false; } } return true; };
  }

  function Acc() { this.n = 0; this.k = 0; this.sum = 0; this.min = null; this.max = null; this.set = null; }
  Acc.prototype.add = function (v, distinct) {
    if (!has(v)) { return; }
    this.n++;
    if (distinct) { (this.set || (this.set = {}))[keyText(v)] = true; }
    if (num(v)) {
      this.k++; this.sum += v;
      if (this.min === null || v < this.min) { this.min = v; }
      if (this.max === null || v > this.max) { this.max = v; }
    }
  };
  Acc.prototype.result = function (agg) {
    switch (agg) {
      case 'count': return this.n;
      case 'distinct': return this.set ? Object.keys(this.set).length : 0;
      case 'avg': return this.k ? this.sum / this.k : null;
      case 'min': return this.min;
      case 'max': return this.max;
      default: return this.k ? this.sum : null;
    }
  };

  function label(fields, name) {
    var f = (fields || []).filter(function (x) { return x.name === name; })[0];
    return (f && f.label) || name;
  }
  /** "Sum of MTM (USD)", "Sum of MTM (USD) (% of total)". */
  function valueLabel(v, fields) {
    var base = (AGG_LABEL[v.agg] || 'Sum') + ' of ' + label(fields, v.field);
    return v.show && v.show !== 'value' ? base + ' (' + SHOW_LABEL[v.show] + ')' : base;
  }
  /** The arrangement as the engine reads it: only known fields, within the limits, defaults filled in. */
  function normalise(arr, fields) {
    var known = {};
    (fields || []).forEach(function (f) { known[f.name] = true; });
    var ok = function (n) { return typeof n === 'string' && known[n]; };
    var a = arr || {}, seen = {};
    var uniq = function (n) { if (seen[n]) { return false; } seen[n] = true; return true; };
    var rows = (a.rows || []).filter(ok).filter(uniq).slice(0, LIMITS.rows);
    var cols = (a.columns || []).filter(ok).filter(uniq).slice(0, LIMITS.columns);
    var values = (a.values || []).map(function (v) { return typeof v === 'string' ? { field: v } : v; })
      .filter(function (v) { return v && ok(v.field); }).slice(0, LIMITS.values).map(function (v) {
        return { field: v.field, agg: AGGS.indexOf(v.agg) >= 0 ? v.agg : 'sum', show: SHOWS.indexOf(v.show) >= 0 ? v.show : 'value' };
      });
    var fseen = {};
    var filters = (a.filters || []).map(function (f) { return typeof f === 'string' ? { field: f } : f; })
      .filter(function (f) { return f && ok(f.field) && !fseen[f.field] && (fseen[f.field] = true); }).map(function (f) {
        var o = { field: f.field };
        if (f.values && f.values.length) { o.values = f.values.map(keyText); }
        if (has(f.min)) { o.min = f.min; }
        if (has(f.max)) { o.max = f.max; }
        return o;
      });
    return { rows: rows, columns: cols, values: values, filters: filters, heat: !!a.heat,
      chart: ['bar', 'line', 'heatmap'].indexOf(a.chart) >= 0 ? a.chart : null };
  }

  /**
   * The cube of {@code records} under {@code arrangement}. With no values, each cell counts its rows.
   * @param opts {maxRowKeys, maxColumnKeys}
   */
  function cube(records, arrangement, opts) {
    var t0 = Date.now(), o = opts || {};
    var fields = records.fields || [], at = index(records), a = normalise(arrangement, fields);
    var maxR = o.maxRowKeys || LIMITS.rowKeys, maxC = o.maxColumnKeys || LIMITS.columnKeys;
    var values = a.values.length ? a.values : [{ field: null, agg: 'count', show: 'value' }];
    var rg = a.rows.map(function (n) { return getter(at, n); }), cg = a.columns.map(function (n) { return getter(at, n); });
    var vg = values.map(function (v) { return v.field ? getter(at, v.field) : function () { return 1; }; });
    var distinct = values.map(function (v) { return v.agg === 'distinct'; });
    var pass = passer(at, a.filters), accs = {}, leafR = {}, leafC = {}, count = 0, rows = records.rows || [];
    var mk = function () { return values.map(function () { return new Acc(); }); };
    for (var i = 0; i < rows.length; i++) {
      var row = rows[i];
      if (!pass(row)) { continue; }
      count++;
      var rk = [], ck = [], x;
      for (x = 0; x < rg.length; x++) { rk.push(keyText(rg[x](row))); }
      for (x = 0; x < cg.length; x++) { ck.push(keyText(cg[x](row))); }
      leafR[rk.join(US)] = rk; leafC[ck.join(US)] = ck;
      var vs = [];
      for (x = 0; x < vg.length; x++) { vs.push(vg[x](row)); }
      for (var r = 0; r <= rk.length; r++) {
        var rp = rk.slice(0, r).join(US) + RS;
        for (var c = 0; c <= ck.length; c++) {
          var key = rp + ck.slice(0, c).join(US), list = accs[key] || (accs[key] = mk());
          for (var v = 0; v < vs.length; v++) { list[v].add(vs[v], distinct[v]); }
        }
      }
    }
    var rowKeys = Object.keys(leafR).map(function (k) { return leafR[k]; }).sort(compareKeys);
    var colKeys = Object.keys(leafC).map(function (k) { return leafC[k]; }).sort(compareKeys);
    var cells = {};
    Object.keys(accs).forEach(function (k) { cells[k] = accs[k].map(function (acc, n) { return acc.result(values[n].agg); }); });
    if (!count) { cells[RS] = values.map(function (v) { return v.agg === 'count' || v.agg === 'distinct' ? 0 : null; }); }
    return {
      rows: a.rows, columns: a.columns,
      values: values.map(function (v) { return { field: v.field, agg: v.agg, show: v.show, label: v.field ? valueLabel(v, fields) : 'Count of rows' }; }),
      rowKeys: rowKeys.slice(0, maxR), columnKeys: colKeys.slice(0, maxC), cells: cells,
      count: count, total: rows.length, partial: !!records.truncated, moreRows: rowKeys.length > maxR, moreColumns: colKeys.length > maxC,
      masked: [], source: 'records', elapsedMs: Date.now() - t0
    };
  }

  /** The records under one cell: those that pass the filters and whose keys start with the given prefixes. */
  function drill(records, arrangement, rowPrefix, colPrefix) {
    var at = index(records), a = normalise(arrangement, records.fields || []), pass = passer(at, a.filters);
    var rg = a.rows.map(function (n) { return getter(at, n); }), cg = a.columns.map(function (n) { return getter(at, n); });
    var rp = rowPrefix || [], cp = colPrefix || [];
    return (records.rows || []).filter(function (row) {
      if (!pass(row)) { return false; }
      for (var i = 0; i < rp.length; i++) { if (!rg[i] || keyText(rg[i](row)) !== rp[i]) { return false; } }
      for (var j = 0; j < cp.length; j++) { if (!cg[j] || keyText(cg[j](row)) !== cp[j]) { return false; } }
      return true;
    });
  }

  /** A field's distinct values with their counts (in key order), and its range when it holds numbers or ISO dates. */
  function distinctValues(records, field, cap) {
    var at = index(records), get = getter(at, field), counts = {}, numeric = true, dates = true, any = false, lo = null, hi = null;
    (records.rows || []).forEach(function (row) {
      var v = get(row), k = keyText(v);
      counts[k] = (counts[k] || 0) + 1;
      if (!has(v)) { return; }
      any = true;
      if (!num(v)) { numeric = false; }
      if (!(typeof v === 'string' && /^\d{4}-\d{2}-\d{2}/.test(v))) { dates = false; }
      if (lo === null || (num(v) && num(lo) ? v < lo : String(v) < String(lo))) { lo = v; }
      if (hi === null || (num(v) && num(hi) ? v > hi : String(v) > String(hi))) { hi = v; }
    });
    var keys = Object.keys(counts).sort(compareKey), max = cap || 1000, ranged = any && (numeric || dates);
    return { field: field, values: keys.slice(0, max).map(function (k) { return { value: k, count: counts[k] }; }),
      numeric: any && numeric, date: any && dates, min: ranged ? lo : null, max: ranged ? hi : null, more: keys.length > max };
  }

  // ---- numbers as the Sutra's formats show them ---------------------------------------------------------------------
  function group(s) { return s.replace(/\B(?=(\d{3})+(?!\d))/g, ','); }
  function fixed(v, dp) {
    var parts = Math.abs(v).toFixed(dp).split('.');
    return group(parts[0]) + (parts[1] ? '.' + parts[1] : '');
  }
  function compact(v) {
    var a = Math.abs(v), sign = v < 0 ? '−' : '';
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
    var neg = v < 0 ? '−' : '';
    if (fmt === 'pct') { return neg + fixed(v * 100, 1) + '%'; }
    if (fmt === 'int') { return neg + fixed(v, 0); }
    if (fmt === 'compact') { return compact(v); }
    var m = /^(pct|signed|amount|price|rate|df|pips)(\d)$/.exec(fmt || '');
    if (m) {
      var dp = +m[2];
      if (m[1] === 'pct') { return neg + fixed(v * 100, dp) + '%'; }
      return (m[1] === 'signed' ? (v < 0 ? '−' : v > 0 ? '+' : '') : neg) + fixed(v, dp);
    }
    var whole = Math.abs(v - Math.round(v)) < 1e-9;
    return neg + fixed(v, whole || Math.abs(v) >= 100 ? 0 : 2);
  }
  /** The format a value cell uses: counts as whole numbers, show-as as percentages, other aggregates as the field's. */
  function valueFormat(v, fields) {
    if (v.show && v.show !== 'value') { return 'pct'; }
    if (v.agg === 'count' || v.agg === 'distinct') { return 'int'; }
    var f = (fields || []).filter(function (x) { return x.name === v.field; })[0];
    var fmt = f && f.fmt;
    return fmt === 'date' || fmt === 'text' || fmt === 'dmy' ? null : fmt || null;
  }

  var api = { US: US, RS: RS, BLANK: BLANK, AGGS: AGGS, SHOWS: SHOWS, AGG_LABEL: AGG_LABEL, SHOW_LABEL: SHOW_LABEL, LIMITS: LIMITS,
    keyText: keyText, compareKey: compareKey, compareKeys: compareKeys, cellKey: cellKey, normalise: normalise, cube: cube,
    drill: drill, distinct: distinctValues, format: format, valueFormat: valueFormat, valueLabel: valueLabel, label: label };
  if (typeof module !== 'undefined' && module.exports) { module.exports = api; } else { root.drishtiPivotEngine = api; }
})(this);
