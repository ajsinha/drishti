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
/* Chart any table, the engine (docs/architecture/TABLE_CHARTS.md sections 6 to 15): pure functions, no DOM. It classifies a
   table's columns (date, number, text; masked; identifiers), picks the smart defaults, normalises a settings document,
   maps the settings onto the pivot engine (X -> rows, split -> columns, series -> values), turns the cube into drawn
   series (date buckets, Top N with Others, split cap, sort, percent, gaps), refuses what cannot be drawn honestly (a pie
   with negative values, 100% of percentages), and builds the ECharts option from the theme tokens it is given (never a
   literal colour). Also the text summary (caption, aria-label) and the export grid. Masks hold: a masked column is never
   chartable, and the masked cells of a partly masked column are left out of every aggregate and counted.
   Loaded in the page after pivot-engine.js (window.drishtiTableChartEngine) and under node for tests (module.exports). */
(function (root) {
  'use strict';
  var P = typeof module !== 'undefined' && module.exports ? require('./pivot-engine.js') : null;
  function PE() { return P || root.drishtiPivotEngine || (root.drishtiModules || {}).pivotEngine; }

  var MASK = '•••', OTHERS = 'Others', ROWS = '·rows', DOT = '·';
  var TYPES = ['line', 'bar', 'area', 'pie', 'scatter', 'combo'];
  var AGGS = ['sum', 'avg', 'count', 'min', 'max'];
  var BUCKETS = ['auto', 'day', 'week', 'month', 'quarter', 'year'];
  var SERIES_TYPES = ['bar', 'line', 'area'];
  var LIMITS = { maxPoints: 500, categoryMax: 30, categoryTop: 20, splitMax: 12, pieSlices: 12, scatterPoints: 5000, seriesMax: 6,
    rowKeys: 2000, columnKeys: 200, longLabel: 18 };
  var AGG_WORD = { sum: 'sum', avg: 'average', count: 'count', min: 'min', max: 'max', none: 'points' };
  var BUCKET_WORD = { day: 'day', week: 'week', month: 'month', quarter: 'quarter', year: 'year' };

  function limits(opts) { var o = {}, k; for (k in LIMITS) { o[k] = LIMITS[k]; } for (k in (opts && opts.limits) || {}) { o[k] = opts.limits[k]; } return o; }
  function has(v) { return v !== null && v !== undefined && v !== ''; }
  function num(v) { return typeof v === 'number' && isFinite(v); }
  function masked(v) { return v === MASK; }
  var NUMERIC = /^[+-]?(\d{1,3}(,\d{3})+|\d+)(\.\d+)?([eE][+-]?\d+)?$/;
  /** A finite number from a number or a plain numeric text ("1,500.25"); otherwise null. */
  function toNumber(v) {
    if (num(v)) { return v; }
    if (typeof v === 'string' && NUMERIC.test(v.trim())) { var n = parseFloat(v.trim().replace(/,/g, '')); return isFinite(n) ? n : null; }
    return null;
  }
  var ISO = /^(\d{4})-(\d{2})-(\d{2})(?:[T ](\d{2}):(\d{2})(?::(\d{2})(?:\.\d+)?)?(Z|[+-]\d{2}:?\d{2})?)?$/;
  /** Whether a value is an ISO date or date-time (a real day of the calendar). */
  function isDate(v) {
    var m = typeof v === 'string' && ISO.exec(v.trim());
    if (!m) { return false; }
    var d = new Date(Date.UTC(+m[1], +m[2] - 1, +m[3]));
    return d.getUTCFullYear() === +m[1] && d.getUTCMonth() === +m[2] - 1 && d.getUTCDate() === +m[3];
  }
  /** The day ("YYYY-MM-DD") of a date or date-time; a date-time with a zone is moved to the console's zone (offsetMinutes). */
  function dayOf(v, offsetMinutes) {
    var m = ISO.exec(String(v).trim());
    if (!m) { return null; }
    if (!m[7]) { return m[1] + '-' + m[2] + '-' + m[3]; }
    var t = Date.parse(String(v).trim().replace(' ', 'T'));
    var off = offsetMinutes == null ? -new Date(t).getTimezoneOffset() : offsetMinutes;
    return new Date(t + off * 60000).toISOString().slice(0, 10);
  }
  function utc(day) { return Date.UTC(+day.slice(0, 4), +day.slice(5, 7) - 1, +day.slice(8, 10)); }
  function iso(ms) { return new Date(ms).toISOString().slice(0, 10); }
  /** The bucket key of a day: day itself, the Monday of its week, "YYYY-MM", "YYYY-Qn", "YYYY". */
  function bucketOf(day, bucket) {
    switch (bucket) {
      case 'week': { var ms = utc(day), dow = (new Date(ms).getUTCDay() + 6) % 7; return iso(ms - dow * 86400000); }
      case 'month': return day.slice(0, 7);
      case 'quarter': return day.slice(0, 4) + '-Q' + (Math.floor((+day.slice(5, 7) - 1) / 3) + 1);
      case 'year': return day.slice(0, 4);
      default: return day;
    }
  }
  /** "auto": days while they fit maxPoints, else the smallest period that fits (as a BI time series does). */
  function autoBucket(days, maxPoints) {
    var order = ['day', 'week', 'month', 'quarter', 'year'];
    for (var i = 0; i < order.length; i++) {
      var seen = {}, n = 0;
      for (var j = 0; j < days.length && n <= maxPoints; j++) { var k = bucketOf(days[j], order[i]); if (!seen[k]) { seen[k] = true; n++; } }
      if (n <= maxPoints) { return order[i]; }
    }
    return 'year';
  }

  // ---- columns ------------------------------------------------------------------------------------------------------
  var NUM_FMT = /^((pct|signed|amount|price|rate|df|pips)\d|compact|int|number)$/;
  function declaredRole(f) {
    var t = String(f.type || '').toLowerCase(), fmt = String(f.fmt || '').toLowerCase();
    if (/^(date|datetime|timestamp|instant)$/.test(t) || /^(date|dmy|datetime)$/.test(fmt)) { return 'date'; }
    if (/^(number|numeric|integer|int|long|decimal|double|float)$/.test(t) || NUM_FMT.test(fmt)) { return 'number'; }
    if (/^(text|string|boolean|bool)$/.test(t) || fmt === 'text') { return 'text'; }
    return null;
  }
  function index(fields) { var at = {}; (fields || []).forEach(function (f, i) { at[f.name] = i; }); return at; }
  /**
   * Each column's role and what the chart may do with it. Declared types win (records fmt, BI type, history kinds);
   * otherwise a column is a date when at least 90% of its non-blank values are ISO dates, a number when at least 90% are
   * numbers, text otherwise. A masked column (declared, or every non-blank cell masked) is not chartable; a partly masked
   * one is, and its masked cells are counted.
   */
  function classify(fields, records) {
    var rows = (records && records.rows) || [], at = index((records && records.fields) || fields);
    return (fields || []).map(function (f) {
      var i = at[f.name], n = 0, blanks = 0, cells = 0, dates = 0, nums = 0, seen = {}, distinct = 0, len = 0;
      for (var r = 0; r < rows.length; r++) {
        var v = i == null ? null : rows[r][i];
        if (!has(v)) { blanks++; continue; }
        if (masked(v)) { cells++; continue; }
        n++;
        if (isDate(v)) { dates++; }
        if (toNumber(v) !== null) { nums++; }
        var k = String(v);
        if (!seen[k] && distinct < 100000) { seen[k] = true; distinct++; len += k.length; }
      }
      var role = declaredRole(f) || (n && dates / n >= 0.9 ? 'date' : n && nums / n >= 0.9 ? 'number' : 'text');
      var hidden = f.masked === true || (n === 0 && cells > 0);
      var misfit = role === 'number' ? n - nums : role === 'date' ? n - dates : 0;
      return { name: f.name, label: f.label || f.name, role: role, fmt: f.fmt || null, id: !!(f.id || f.kind), masked: hidden,
        maskedCells: hidden ? 0 : cells, blanks: blanks, values: n, distinct: distinct, misfit: misfit,
        avgLabel: distinct ? len / distinct : 0, chartable: !hidden, reason: hidden ? 'hidden for your role' : null };
    });
  }
  function find(cols, name) { for (var i = 0; i < cols.length; i++) { if (cols[i].name === name) { return cols[i]; } } return null; }
  /** Whether the table can be charted at all, and the reason when not (the icon is then disabled with it as its title). */
  function chartable(cols) {
    var ok = cols.filter(function (c) { return c.chartable; });
    if (!cols.length || !ok.length) { return { ok: false, reason: 'Nothing to chart: every column is hidden for your role' }; }
    if (ok.every(function (c) { return c.id; })) { return { ok: false, reason: 'Nothing to chart: only identifiers' }; }
    return { ok: true, reason: null };
  }
  /** Which chart types the columns allow, with the reason for those they do not. */
  function types(cols) {
    var nums = cols.filter(function (c) { return c.chartable && c.role === 'number' && !c.id; });
    var out = {};
    TYPES.forEach(function (t) { out[t] = { ok: true, reason: null }; });
    if (nums.length < 2) { out.scatter = { ok: false, reason: 'A scatter needs two numeric columns' }; }
    if (!cols.some(function (c) { return c.chartable && c.role === 'text'; })) { out.pie = { ok: false, reason: 'A pie needs a text column for its slices' }; }
    return out;
  }
  function defaultAgg(col) { return col && /^(pct|price|rate)/.test(String(col.fmt || '')) ? 'avg' : 'sum'; }

  // ---- settings -----------------------------------------------------------------------------------------------------
  function blank() {
    return { v: 1, type: 'bar', x: { field: null, bucket: 'auto', gaps: 'connect' }, series: [], split: null, sort: { by: 'x', dir: 'asc', series: 0 },
      top: { n: 0, others: true }, bars: 'grouped', percent: false, donut: false, labels: false, scope: 'shown' };
  }
  function pick(v, list, dflt) { return list.indexOf(v) >= 0 ? v : dflt; }
  function int(v, lo, hi, dflt) { var n = Math.floor(Number(v)); return isFinite(n) ? Math.max(lo, Math.min(hi, n)) : dflt; }
  /**
   * The settings as the engine reads them: unknown keys dropped, numbers clamped, enums replaced, at most seriesMax series,
   * the type's own rules (a pie: one series, no split; a combo: no split). Fields no longer offered (gone, or masked) are
   * PROBLEMS: a saved document with problems is not applied (section 7, rule 7). Replaced values are FIXES only.
   */
  function normalise(input, cols, opts) {
    var L = limits(opts), s = input || {}, out = blank(), problems = [], fixes = [];
    if (s.v !== undefined && s.v !== 1) { problems.push({ key: 'v', why: 'version', text: 'a settings document of version ' + s.v }); }
    var offered = function (name, key) {
      if (!has(name)) { return false; }
      var c = find(cols, name);
      if (!c) { problems.push({ key: key, field: name, why: 'missing', text: 'Your saved chart names ' + name + ', which this table no longer has' }); return false; }
      if (!c.chartable) { problems.push({ key: key, field: name, why: 'masked', text: name + ' is hidden for your role' }); return false; }
      return true;
    };
    out.type = pick(s.type, TYPES, 'bar');
    if (has(s.type) && out.type !== s.type) { fixes.push('type'); }
    var x = s.x && typeof s.x === 'object' ? s.x : { field: s.x };
    if (offered(x.field, 'x')) { out.x.field = x.field; }
    out.x.bucket = pick(x.bucket, BUCKETS, 'auto');
    out.x.gaps = pick(x.gaps, ['connect', 'break'], 'connect');
    var list = Array.isArray(s.series) ? s.series : [];
    if (list.length > L.seriesMax) { fixes.push('series'); list = list.slice(0, L.seriesMax); }
    list.forEach(function (x, i) {
      var e = typeof x === 'string' ? { field: x } : x || {}, agg = pick(e.agg, AGGS.concat(out.type === 'scatter' ? ['none'] : []), null);
      if (!agg) { if (has(e.agg)) { fixes.push('series[' + i + '].agg'); } agg = has(e.field) ? defaultAgg(find(cols, e.field)) : 'count'; }
      if (agg !== 'count' || has(e.field)) {
        if (!offered(e.field, 'series[' + i + ']')) { if (agg !== 'count') { return; } e = { agg: 'count' }; }
      }
      var o = { field: agg === 'count' && !has(e.field) ? null : e.field, agg: agg, axis: pick(e.axis, ['left', 'right'], 'left'),
        type: pick(e.type, SERIES_TYPES, i === 0 ? 'bar' : 'line'), label: typeof e.label === 'string' && e.label ? e.label.slice(0, 80) : null, abs: !!e.abs };
      if (e.size && offered(e.size, 'series[' + i + '].size')) { o.size = e.size; }
      out.series.push(o);
    });
    var sp = s.split && typeof s.split === 'object' ? s.split.field : s.split;
    if (has(sp) && offered(sp, 'split') && sp !== out.x.field) { out.split = sp; }
    var so = s.sort || {};
    out.sort = { by: pick(so.by, ['x', 'value'], 'x'), dir: pick(so.dir, ['asc', 'desc'], 'asc'), series: int(so.series, 0, Math.max(0, out.series.length - 1), 0) };
    var tp = s.top || {};
    out.top = { n: int(tp.n, 0, 1000, 0), others: tp.others !== false };
    out.bars = pick(s.bars, ['grouped', 'stacked'], 'grouped');
    out.percent = !!s.percent && out.bars === 'stacked';
    out.donut = !!s.donut; out.labels = !!s.labels;
    out.scope = pick(s.scope, ['shown', 'all'], 'shown');
    if (out.type === 'pie') {
      if (out.series.length > 1 || out.split) { fixes.push('pie'); }
      out.series = out.series.slice(0, 1); out.split = null;
      out.series.forEach(function (e) { if (e.agg !== 'sum' && e.agg !== 'count') { e.agg = 'sum'; fixes.push('pie.agg'); } e.axis = 'left'; });
    }
    if (out.type === 'combo' && out.split) { out.split = null; fixes.push('combo.split'); }
    if (out.type !== 'scatter') { out.series.forEach(function (e) { if (e.agg === 'none') { e.agg = 'sum'; } }); }
    return { settings: out, problems: problems, fixes: fixes };
  }
  /**
   * The smart defaults (section 7): X the first date column, else the first text column with 2..categoryMax values, else
   * the first text column, else the first number; Y the first number that is not X and not an identifier, else a count of
   * rows; the type from X; sum (avg for percentages, prices, rates). A saved document wins when it has no problems;
   * otherwise it is set aside with a note.
   */
  function defaults(cols, opts) {
    var L = limits(opts), ok = cols.filter(function (c) { return c.chartable; }), notes = [];
    var role = function (r) { return ok.filter(function (c) { return c.role === r; }); };
    var dates = role('date'), texts = role('text'), nums = role('number');
    var x = dates[0] || texts.filter(function (c) { return c.distinct >= 2 && c.distinct <= L.categoryMax; })[0] || texts[0] || nums[0] || null;
    var s = blank();
    if (!x) { return { settings: null, notes: [chartable(cols).reason] }; }
    var y = nums.filter(function (c) { return c.name !== x.name && !c.id; })[0] || null;
    s.x.field = x.name;
    s.type = x.role === 'date' ? 'line' : x.role === 'text' ? 'bar' : y ? 'scatter' : 'bar';
    s.series = [y ? { field: y.name, agg: s.type === 'scatter' ? 'none' : defaultAgg(y), axis: 'left', type: 'bar', label: null, abs: false }
      : { field: null, agg: 'count', axis: 'left', type: 'bar', label: null, abs: false }];
    s.sort = x.role === 'text' ? { by: 'value', dir: 'desc', series: 0 } : { by: 'x', dir: 'asc', series: 0 };
    if (x.role === 'text' && x.distinct > L.categoryMax) {
      s.top = { n: L.categoryTop, others: true };
      notes.push(x.distinct + ' values of ' + x.name + ': showing the top ' + L.categoryTop + ' and Others');
    }
    if (opts && opts.saved) {
      var n = normalise(opts.saved, cols, opts);
      if (!n.problems.length && n.settings.x.field && n.settings.series.length) { return { settings: n.settings, notes: [], saved: true }; }
      notes.push((n.problems[0] ? n.problems[0].text : 'Your saved chart cannot be drawn') + '; using the defaults');
    }
    return { settings: s, notes: notes, saved: false };
  }

  // ---- settings -> rows -> cube -------------------------------------------------------------------------------------
  function seriesField(e, i) { return e.agg === 'count' && !e.field ? ROWS : e.field; }
  /** The bucket the X will use: "day" for a date unless asked otherwise; auto resolved over the rows' days. */
  function resolveBucket(settings, xcol, days, L) {
    if (!xcol || xcol.role !== 'date') { return null; }
    var b = settings.x.bucket || 'auto';
    return b === 'auto' ? autoBucket(days, L.maxPoints) : b;
  }
  /**
   * The rows ready for the cube: X bucketed into a derived key column ("PAY_DATE·month"); masked cells left out of every
   * aggregate (a masked X or split cell drops its row); values that do not fit a numeric series left out; blank X left out
   * on a time axis; a count-of-rows column. Every exclusion is counted for the status line.
   */
  function prepare(records, settings, cols, opts) {
    var L = limits(opts), fields = (records && records.fields) || [], at = index(fields), rows = (records && records.rows) || [];
    var xcol = find(cols, settings.x.field), xi = at[settings.x.field], si = settings.split ? at[settings.split] : null;
    var time = !!xcol && xcol.role === 'date', notes = { masked: {}, misfit: {}, blankX: 0, rows: rows.length, kept: 0 };
    var days = [];
    if (time) { rows.forEach(function (r) { var v = r[xi]; if (has(v) && !masked(v) && isDate(v)) { days.push(dayOf(v, opts && opts.offsetMinutes)); } }); }
    var bucket = resolveBucket(settings, xcol, days, L);
    var xKey = time && bucket !== 'day' ? settings.x.field + DOT + bucket : settings.x.field;
    var used = {};
    settings.series.forEach(function (e) { if (e.field) { used[e.field] = at[e.field]; } if (e.size) { used[e.size] = at[e.size]; } });
    var abs = {};
    settings.series.forEach(function (e) { if (e.abs && e.field) { abs[e.field] = true; } });
    var outFields = fields.slice();
    if (xKey !== settings.x.field) { outFields.push({ name: xKey, label: (xcol.label || xcol.name) + ' (' + bucket + ')' }); }
    outFields.push({ name: ROWS, label: 'Rows' });
    var out = [], count = function (map, k) { map[k] = (map[k] || 0) + 1; };
    for (var r = 0; r < rows.length; r++) {
      var row = rows[r].slice(), xv = xi == null ? null : row[xi];
      if (masked(xv)) { count(notes.masked, settings.x.field); continue; }
      if (si != null && masked(row[si])) { count(notes.masked, settings.split); continue; }
      if (time) {
        if (!has(xv) || !isDate(xv)) { if (has(xv)) { count(notes.misfit, settings.x.field); } else { notes.blankX++; } continue; }
        row[xi] = dayOf(xv, opts && opts.offsetMinutes);
      }
      for (var f in used) {
        var i = used[f];
        if (i == null) { continue; }
        var v = row[i];
        if (masked(v)) { count(notes.masked, f); row[i] = null; continue; }
        if (!has(v)) { row[i] = null; continue; }
        var n = toNumber(v);
        if (n === null) { count(notes.misfit, f); row[i] = null; continue; }
        row[i] = abs[f] ? Math.abs(n) : n;
      }
      if (xKey !== settings.x.field) { row.push(bucketOf(row[xi], bucket)); }
      row.push(1);
      out.push(row);
    }
    notes.kept = out.length;
    return { records: { fields: outFields, rows: out, truncated: !!(records && records.truncated) }, xKey: xKey, bucket: bucket, time: time, notes: notes };
  }
  /**
   * The settings as a pivot arrangement: X -> rows, split -> columns, each series -> values. An average also asks its sum
   * and count, so Others (and a merged split) stays exact. Returns the arrangement and, per series, where its values are.
   */
  function toArrangement(settings, prep) {
    var values = [], at = [];
    settings.series.forEach(function (e, i) {
      var f = seriesField(e, i), agg = e.agg === 'none' ? 'sum' : e.agg, slot = { main: values.length, agg: agg };
      values.push({ field: f, agg: agg });
      if (agg === 'avg') { slot.sum = values.length; values.push({ field: f, agg: 'sum' }); slot.count = values.length; values.push({ field: f, agg: 'count' }); }
      at.push(slot);
    });
    return { arrangement: { rows: [prep ? prep.xKey : settings.x.field], columns: settings.split ? [settings.split] : [], values: values }, slots: at };
  }
  /** The cube of the prepared rows (the pivot engine's own aggregation; the chart never aggregates on its own). */
  function cube(prep, settings, opts) {
    var L = limits(opts), a = toArrangement(settings, prep);
    var c = PE().cube(prep.records, a.arrangement, { maxRowKeys: L.rowKeys, maxColumnKeys: L.columnKeys });
    c.slots = a.slots;
    return c;
  }

  // ---- cube -> drawn series -----------------------------------------------------------------------------------------
  /** Several cells of one series combined: sums and counts add, min and max fold, an average from its sum and count. */
  function combine(slot, cells) {
    var acc = null, s = 0, k = 0, any = false;
    cells.forEach(function (c) {
      if (!c) { return; }
      if (slot.agg === 'avg') { if (num(c[slot.sum])) { s += c[slot.sum]; any = true; } if (num(c[slot.count])) { k += c[slot.count]; } return; }
      var v = c[slot.main];
      if (!num(v)) { return; }
      if (acc === null) { acc = v; } else if (slot.agg === 'min') { acc = Math.min(acc, v); } else if (slot.agg === 'max') { acc = Math.max(acc, v); } else { acc += v; }
    });
    if (slot.agg === 'avg') { return any && k ? s / k : null; }
    return acc;
  }
  function seriesLabel(e, cols) {
    if (e.label) { return e.label; }
    if (!e.field) { return 'Rows'; }
    var c = find(cols, e.field);
    return (c ? c.name : e.field) + (e.agg === 'none' ? '' : ' (' + e.agg + ')') + (e.abs ? ' absolute' : '');
  }
  function businessDays(from, to, holidays) {
    var out = [], off = {};
    (holidays || []).forEach(function (h) { off[h] = true; });
    for (var ms = utc(from); ms <= utc(to); ms += 86400000) {
      var d = new Date(ms).getUTCDay(), k = iso(ms);
      if (d !== 0 && d !== 6 && !off[k]) { out.push(k); }
    }
    return out;
  }
  function byValue(dir) { return function (a, b) { var x = num(a.v) ? a.v : -Infinity, y = num(b.v) ? b.v : -Infinity; return (dir === 'asc' ? x - y : y - x) || a.i - b.i; }; }
  /**
   * The drawn model: the X values, one series per (series, split value), Top N and Others on a category X, at most
   * splitMax split series (the largest by the first series, then Others), the sort, percent, a pie's checks and gaps on a
   * time axis. Returns {axis, x, labels, series[], notes[], refusal, empty, single, ...}.
   */
  function series(c, settings, cols, prep, opts) {
    var L = limits(opts), E = PE(), slots = c.slots, notes = [], refusal = null;
    var xcol = find(cols, settings.x.field), time = !!(prep && prep.time), bucket = prep ? prep.bucket : null;
    var cell = function (xk, ck) { return c.cells[E.cellKey(xk == null ? [] : [xk], ck == null ? [] : [ck])]; };
    var xs = c.rowKeys.map(function (k) { return k[0]; });
    var value = function (s, xk, cks) { return combine(slots[s], (cks || [null]).map(function (ck) { return cell(xk, ck); })); };
    // split: one drawn series per value, the largest splitMax - 1 and Others
    var groups = [null];
    if (settings.split) {
      var ks = c.columnKeys.map(function (k, i) { return { k: k[0], v: value(0, null, [k[0]]), i: i }; });
      if (ks.length > L.splitMax) {
        var kept = ks.slice().sort(byValue('desc')).slice(0, L.splitMax - 1).sort(function (a, b) { return a.i - b.i; });
        var keep = {}; kept.forEach(function (x) { keep[x.k] = true; });
        groups = kept.map(function (x) { return [x.k]; });
        groups.push(ks.filter(function (x) { return !keep[x.k]; }).map(function (x) { return x.k; }));
        groups[groups.length - 1].others = true;
        notes.push(ks.length + ' values of ' + settings.split + ': the ' + (L.splitMax - 1) + ' largest and Others');
      } else { groups = ks.map(function (x) { return [x.k]; }); }
    }
    // X: Top N with Others (category axes), a pie's slice cap, the sort
    var items = xs.map(function (k, i) { return { k: k, keys: [k], i: i, v: value(settings.sort.series || 0, k, null) }; });
    var others = null, n = settings.top.n, forceOthers = false;
    if (settings.type === 'pie' && items.length > L.pieSlices && (!n || n > L.pieSlices - 1)) { n = L.pieSlices - 1; forceOthers = true; notes.push(items.length + ' slices: the ' + n + ' largest and Others'); }
    if (n && !time && items.length > n) {
      var ranked = items.slice().sort(byValue('desc')), top = ranked.slice(0, n), rest = ranked.slice(n);
      var tk = {}; top.forEach(function (x) { tk[x.k] = true; });
      items = items.filter(function (x) { return tk[x.k]; });
      if (settings.top.others || forceOthers) { others = { k: OTHERS, keys: rest.map(function (x) { return x.k; }), others: true, i: Infinity }; }
      else { notes.push(rest.length + ' smaller values left out (Top ' + n + ')'); }
    } else if (n && time && settings.top.n) { notes.push('Top N applies to categories, not to a time axis'); }
    if (settings.sort.by === 'value') { items.sort(byValue(settings.sort.dir)); }
    else if (settings.sort.dir === 'desc') { items.reverse(); }
    if (others) { others.v = combine(slots[0], others.keys.map(function (k) { return cell(k, null); })); items.push(others); }
    // gaps: a business day the calendar expects but the data lacks is drawn as a gap ("break")
    if (time && bucket === 'day' && settings.x.gaps === 'break' && items.length > 1 && settings.type !== 'pie') {
      var have = {}; items.forEach(function (x) { have[x.k] = true; });
      var dd = items.map(function (x) { return x.k; }).filter(function (k) { return k !== E.BLANK; }).sort();
      var missing = businessDays(dd[0], dd[dd.length - 1], opts && opts.holidays).filter(function (k) { return !have[k]; });
      missing.forEach(function (k) { items.push({ k: k, keys: [], gap: true, i: -1 }); });
      items.sort(function (a, b) { return (a.k < b.k ? -1 : a.k > b.k ? 1 : 0) * (settings.sort.dir === 'desc' ? -1 : 1); });
      if (missing.length) { notes.push(missing.length + ' business day' + (missing.length === 1 ? '' : 's') + ' without data drawn as gaps'); }
    }
    var drawn = [];
    settings.series.forEach(function (e, s) {
      groups.forEach(function (g, gi) {
        var data = items.map(function (it) { return it.gap ? null : combine(slots[s], it.keys.reduce(function (acc, xk) { return acc.concat((g || [null]).map(function (ck) { return cell(xk, ck); })); }, [])); });
        var base = seriesLabel(e, cols), split = g ? (g.others ? OTHERS : g[0]) : null;
        drawn.push({ key: s + ':' + gi, index: s, split: split, others: !!(g && g.others), name: split == null ? base : settings.series.length > 1 ? base + ' · ' + split : split,
          label: base, field: e.field, agg: e.agg, axis: e.axis, type: settings.type === 'combo' ? e.type : settings.type, data: data,
          fmt: e.agg === 'count' ? 'int' : (find(cols, e.field) || {}).fmt || null });
      });
    });
    // percent: each X's share of its stack (per axis); refused over percentages
    if (settings.percent && settings.type === 'bar') {
      if (drawn.some(function (d) { return /^pct/.test(String(d.fmt || '')); })) { notes.push('100% stacking is off: the values are already percentages'); settings = Object.assign({}, settings, { percent: false }); }
      else {
        ['left', 'right'].forEach(function (ax) {
          var on = drawn.filter(function (d) { return d.axis === ax; });
          items.forEach(function (_, j) {
            var t = on.reduce(function (a, d) { return a + (num(d.data[j]) ? Math.abs(d.data[j]) : 0); }, 0);
            on.forEach(function (d) { d.data[j] = num(d.data[j]) && t ? d.data[j] / t : null; });
          });
          on.forEach(function (d) { d.fmt = 'pct'; });
        });
      }
    }
    // a pie: shares of a whole, so no negative slice; zero slices left out
    if (settings.type === 'pie' && drawn.length) {
      var d0 = drawn[0], neg = d0.data.filter(function (v) { return num(v) && v < 0; }).length;
      if (neg) {
        refusal = { code: 'pie-negative', actions: ['bar', 'abs'],
          message: 'A pie shows shares of a whole, and ' + (d0.field || 'the value') + ' has negative values (' + neg + ' of ' + d0.data.length +
            ' slices). Use a bar chart, or chart the absolute values.' };
      }
      var zero = 0, keepIdx = [];
      d0.data.forEach(function (v, j) { if (num(v) && v !== 0) { keepIdx.push(j); } else { zero++; } });
      if (zero && !refusal) {
        items = keepIdx.map(function (j) { return items[j]; }); d0.data = keepIdx.map(function (j) { return d0.data[j]; });
        notes.push(zero + ' empty or zero slice' + (zero === 1 ? '' : 's') + ' left out');
      }
    }
    var labels = items.map(function (it) { return it.k; });
    var avg = labels.reduce(function (a, l) { return a + String(l).length; }, 0) / (labels.length || 1);
    var axis = time ? (bucket === 'day' || bucket === 'week' ? 'time' : 'category') : (xcol && xcol.role === 'number' && settings.type === 'scatter' ? 'value' : 'category');
    return { axis: axis, time: time, bucket: bucket, xField: settings.x.field, xLabel: xcol ? xcol.label : settings.x.field, x: labels,
      others: items.map(function (it) { return !!it.others; }), series: drawn, notes: notes.concat(exclusions(prep, cols)), refusal: refusal,
      empty: !c.count, single: c.count === 1, rows: c.count, total: c.total, moreRows: !!c.moreRows, partial: !!c.partial,
      horizontal: settings.type === 'bar' && !time && axis === 'category' && avg > L.longLabel, settings: settings };
  }
  /** The status line's notes about what was left out (masked cells, misfits, blank dates). */
  function exclusions(prep, cols) {
    if (!prep) { return []; }
    var n = prep.notes, out = [], total = 0, k;
    for (k in n.masked) { total += n.masked[k]; }
    if (total) { out.push(total + ' value' + (total === 1 ? '' : 's') + ' hidden for your role ' + (total === 1 ? 'was' : 'were') + ' left out'); }
    for (k in n.misfit) {
      var c = find(cols, k), what = c && c.role === 'date' ? 'non-date' : 'non-numeric';
      out.push(n.misfit[k] + ' ' + what + ' ' + k + ' value' + (n.misfit[k] === 1 ? '' : 's') + ' left out');
    }
    if (n.blankX) { out.push(n.blankX + ' row' + (n.blankX === 1 ? '' : 's') + ' without a ' + prep.xKey.split(DOT)[0] + ' left out'); }
    return out;
  }
  /** Scatter without aggregation: the points themselves (at most scatterPoints, the first by X), grouped by split. */
  function points(prep, settings, cols, opts) {
    var L = limits(opts), at = index(prep.records.fields), e = settings.series[0] || {}, xi = at[settings.x.field], yi = at[e.field];
    var zi = e.size ? at[e.size] : null, si = settings.split ? at[settings.split] : null, groups = {}, order = [], n = 0, notes = [];
    var rows = prep.records.rows.filter(function (r) { return num(toNumber(r[xi])) && num(r[yi]); });
    rows.sort(function (a, b) { return (toNumber(a[xi]) - toNumber(b[xi])) * (settings.sort.dir === 'desc' ? -1 : 1); });
    if (rows.length > L.scatterPoints) { notes.push(rows.length + ' points: the first ' + L.scatterPoints + ' by ' + settings.x.field); rows = rows.slice(0, L.scatterPoints); }
    rows.forEach(function (r) {
      var g = si == null ? '' : PE().keyText(r[si]);
      if (!groups[g]) { groups[g] = []; order.push(g); }
      groups[g].push(zi == null ? [toNumber(r[xi]), r[yi]] : [toNumber(r[xi]), r[yi], num(r[zi]) ? r[zi] : null]);
      n++;
    });
    var label = seriesLabel(e, cols);
    return { axis: 'value', time: false, xField: settings.x.field, xLabel: (find(cols, settings.x.field) || {}).label || settings.x.field, x: [],
      series: order.map(function (g, i) { return { key: '0:' + i, index: 0, split: si == null ? null : g, others: false, name: si == null ? label : g, label: label,
        field: e.field, agg: 'none', axis: 'left', type: 'scatter', data: groups[g], fmt: (find(cols, e.field) || {}).fmt || null, sized: zi != null }; }),
      notes: notes.concat(exclusions(prep, cols)), refusal: null, empty: !n, single: n === 1, rows: n, total: prep.notes.rows, settings: settings };
  }
  /** One call: rows -> prepared -> cube (or points) -> drawn model. */
  function build(records, cols, settings, opts) {
    var prep = prepare(records, settings, cols, opts);
    if (settings.type === 'scatter' && (settings.series[0] || {}).agg === 'none') { return points(prep, settings, cols, opts); }
    return series(cube(prep, settings, opts), settings, cols, prep, opts);
  }
  /** A server-aggregated cube (search pivot) drawn the same way; its values must be laid out as toArrangement asks. */
  function fromCube(serverCube, settings, cols, opts) {
    var c = Object.assign({}, serverCube, { slots: toArrangement(settings, null).slots });
    return series(c, settings, cols, null, opts);
  }

  // ---- the ECharts option -------------------------------------------------------------------------------------------
  function palette(t) { return t.palette && t.palette.length ? t.palette.slice() : [t.link, t.accent, t.ok, t.neg, t.warn, t.bad, t.muted, t.ink].filter(Boolean); }
  function fmtValue(v, f) { return PE().format(v, f === 'int' ? 'int' : f === 'pct' ? 'pct' : 'compact'); }
  /**
   * The ECharts option of a drawn model, every colour taken from the theme tokens {@code t} (charts.js tokens(), or
   * {palette: [...], faint, border, muted, ink, surface, neg}). No literal colour, no animation; colour is never the only
   * difference past the palette's length (dashes on lines, decals on bars).
   */
  function option(model, settings, t) {
    var pal = palette(t), many = model.series.length > pal.length, stacked = settings.bars === 'stacked' || (settings.type === 'area' && !!settings.split);
    var colorOf = function (d, i) { return d.others ? t.faint : pal[i % pal.length]; };
    var dashOf = function (i) { return i < pal.length ? 'solid' : ['dashed', 'dotted'][Math.floor(i / pal.length - 1) % 2]; };
    var ax = { axisLine: { lineStyle: { color: t.border } }, axisLabel: { color: t.muted, fontFamily: t.mono, fontSize: 10 },
      splitLine: { lineStyle: { color: t.border, opacity: 0.5 } }, nameTextStyle: { color: t.muted, fontSize: 10 } };
    var tip = { backgroundColor: t.surface, borderColor: t.border, textStyle: { color: t.ink, fontFamily: t.mono, fontSize: 11 } };
    var base = { animation: false, aria: { enabled: true, label: { description: summary(model, settings) } }, tooltip: Object.assign({ trigger: model.axis === 'value' ? 'item' : 'axis' }, tip),
      legend: { show: model.series.length > 1, type: 'scroll', top: 0, textStyle: { color: t.muted, fontSize: 11 }, pageTextStyle: { color: t.muted } } };
    if (many) { base.aria.decal = { show: true }; }
    if (settings.type === 'pie') {
      var d0 = model.series[0] || { data: [] };
      base.tooltip = Object.assign({ trigger: 'item' }, tip);
      base.legend.show = true;
      base.series = [{ type: 'pie', name: d0.label, radius: settings.donut ? ['45%', '70%'] : '70%', avoidLabelOverlap: true,
        label: { show: settings.labels || model.x.length <= 8, color: t.ink }, labelLine: { lineStyle: { color: t.border } },
        itemStyle: { borderColor: t.surface, borderWidth: 1 },
        data: model.x.map(function (k, j) { return { name: k, value: d0.data[j], itemStyle: { color: model.others[j] ? t.faint : pal[j % pal.length] } }; }) }];
      return base;
    }
    var right = model.series.some(function (d) { return d.axis === 'right'; });
    var yname = function (side) { return model.series.filter(function (d) { return d.axis === side; }).map(function (d) { return d.label; }).filter(function (v, i, a) { return a.indexOf(v) === i; }).join(', '); };
    var fmtOf = function (side) { var d = model.series.filter(function (x) { return x.axis === side; })[0]; return d ? d.fmt : null; };
    var valueAxis = function (side) {
      var f = fmtOf(side);
      return Object.assign({ type: 'value', name: yname(side), position: side, scale: settings.type === 'scatter' }, ax,
        { axisLabel: Object.assign({}, ax.axisLabel, { formatter: function (v) { return fmtValue(v, f); } }) }, side === 'right' ? { splitLine: { show: false } } : {},
        settings.percent ? { max: 1 } : {});
    };
    var xAxis = model.axis === 'time' ? Object.assign({ type: 'time', name: model.xLabel }, ax)
      : model.axis === 'value' ? Object.assign({ type: 'value', name: model.xLabel, scale: true }, ax)
        : Object.assign({ type: 'category', name: model.xLabel, data: model.x }, ax, { axisLabel: Object.assign({}, ax.axisLabel, { hideOverlap: true }) });
    var yAxis = right ? [valueAxis('left'), valueAxis('right')] : [valueAxis('left')];
    if (model.horizontal) { base.xAxis = yAxis[0]; base.yAxis = Object.assign(xAxis, { inverse: true }); } else { base.xAxis = xAxis; base.yAxis = yAxis; }
    base.grid = { left: model.horizontal ? 140 : 56, right: right ? 56 : 20, top: model.series.length > 1 ? 32 : 16, bottom: 40, containLabel: true };
    var signTone = model.series.length === 1 && model.series[0].type === 'bar' && model.series[0].data.some(function (v) { return num(v) && v < 0; });
    base.series = model.series.map(function (d, i) {
      var color = colorOf(d, i), type = d.type === 'area' ? 'line' : d.type;
      var data = model.axis === 'time' ? d.data.map(function (v, j) { return [model.x[j], v]; }) : model.axis === 'value' ? d.data : d.data.slice();
      if (signTone && !model.horizontal) { data = data.map(function (v, j) { return num(v) && v < 0 ? { value: model.axis === 'time' ? [model.x[j], v] : v, itemStyle: { color: t.neg } } : v; }); }
      var o = { type: type, name: d.name, data: data, yAxisIndex: model.horizontal ? undefined : (d.axis === 'right' && right ? 1 : 0), itemStyle: { color: color },
        label: { show: !!settings.labels, color: t.ink, fontSize: 10, formatter: function (p) { var v = Array.isArray(p.value) ? p.value[1] : p.value; return fmtValue(v, d.fmt); } } };
      if (model.horizontal) { delete o.yAxisIndex; }
      if (type === 'line') {
        o.lineStyle = { color: color, type: dashOf(i), width: 2 };
        o.smooth = false; o.step = false; o.connectNulls = settings.x.gaps !== 'break';
        o.showSymbol = d.data.filter(num).length <= 1 || d.data.length <= 60; o.symbolSize = d.data.filter(num).length <= 1 ? 8 : 4;
      }
      if (d.type === 'area') { o.areaStyle = { color: color, opacity: 0.25 }; if (stacked) { o.stack = 'area-' + d.axis; } }
      if (type === 'bar' && settings.bars === 'stacked') { o.stack = 'bar-' + d.axis; }
      if (type === 'scatter') {
        o.symbolSize = d.sized ? sizer(d.data) : 6;
        o.tooltip = { formatter: function (p) { return d.name + ': ' + fmtValue(p.value[0], null) + ', ' + fmtValue(p.value[1], d.fmt); } };
      }
      return o;
    });
    return base;
  }
  function sizer(data) {
    var zs = data.map(function (p) { return p[2]; }).filter(num), lo = Math.min.apply(null, zs.concat([0])), hi = Math.max.apply(null, zs.concat([1]));
    return function (p) { return num(p[2]) && hi > lo ? 4 + 20 * (p[2] - lo) / (hi - lo) : 6; };
  }

  // ---- words, export ------------------------------------------------------------------------------------------------
  var TYPE_WORD = { line: 'Line', bar: 'Bar', area: 'Area', pie: 'Pie', scatter: 'Scatter', combo: 'Combo' };
  function group(n) { return String(n).replace(/\B(?=(\d{3})+(?!\d))/g, ','); }
  /** The trend of a series by its least-squares slope over the range: up, down or flat (within 5% of the largest value). */
  function trend(values) {
    var pts = []; values.forEach(function (v, i) { if (num(v)) { pts.push([i, v]); } });
    if (pts.length < 2) { return null; }
    var n = pts.length, sx = 0, sy = 0, sxy = 0, sxx = 0, big = 0;
    pts.forEach(function (p) { sx += p[0]; sy += p[1]; sxy += p[0] * p[1]; sxx += p[0] * p[0]; big = Math.max(big, Math.abs(p[1])); });
    var slope = (n * sxy - sx * sy) / (n * sxx - sx * sx || 1), change = slope * (pts[n - 1][0] - pts[0][0]);
    return Math.abs(change) <= 0.05 * (big || 1) ? 'flat' : change > 0 ? 'up' : 'down';
  }
  /**
   * The text summary: the visible caption and the chart's aria-label. "Bar chart of AMOUNT (sum) by CURRENCY, 7
   * categories, highest USD 4.1m, lowest NOK −0.3m; 25 of 1,340 rows."
   */
  function summary(model, settings, info) {
    var i = info || {}, rows = i.shown != null ? i.shown : model.total, total = i.total;
    var tail = rows == null ? '' : (total != null && total > rows ? group(rows) + ' of ' + group(total) + ' rows' : rows === 1 ? 'one row' : group(rows) + ' rows');
    if (model.empty) { return 'No rows to chart' + (i.filters ? ' (the table\'s filters leave none)' : '') + '.'; }
    var names = model.series.map(function (d) { return d.label; }).filter(function (v, k, a) { return a.indexOf(v) === k; });
    var head = TYPE_WORD[settings.type] + ' chart of ' + names.join(' and ') + ' by ' + model.xField + (model.bucket && model.bucket !== 'day' ? ' (' + model.bucket + ')' : '');
    var parts = [head];
    if (settings.type === 'scatter' && model.axis === 'value') { parts.push(group(model.rows) + ' point' + (model.rows === 1 ? '' : 's')); }
    else {
      var n = model.x.length;
      parts.push(model.time ? n + ' ' + (BUCKET_WORD[model.bucket] || 'day') + (n === 1 ? '' : 's') : n + ' categor' + (n === 1 ? 'y' : 'ies'));
      if (settings.split) { parts.push('split by ' + settings.split + ' (' + model.series.length + ')'); }
      var d = model.series[0], hi = null, lo = null;
      if (d) {
        d.data.forEach(function (v, j) { if (num(v)) { if (hi === null || v > d.data[hi]) { hi = j; } if (lo === null || v < d.data[lo]) { lo = j; } } });
        if (model.time && n > 1) { parts.push('from ' + model.x[0] + ' to ' + model.x[n - 1]); }
        if (hi !== null && n > 1) {
          parts.push('highest ' + model.x[hi] + ' ' + fmtValue(d.data[hi], d.fmt));
          parts.push('lowest ' + model.x[lo] + ' ' + fmtValue(d.data[lo], d.fmt));
        } else if (hi !== null) { parts.push(model.x[hi] + ' ' + fmtValue(d.data[hi], d.fmt)); }
        var tr = model.time ? trend(d.data) : null;
        if (tr) { parts.push('trend ' + tr); }
      }
    }
    return parts.join(', ') + (tail ? '; ' + tail : '') + '.';
  }
  /** The chart's data as drawn (Others included, masked values left out): X then one column per drawn series. */
  function exportGrid(model, settings, name) {
    if (model.axis === 'value' && settings.type === 'scatter') {
      var withSize = model.series.some(function (d) { return d.sized; }), split = model.series.some(function (d) { return d.split != null; });
      var header = [model.xLabel, model.series[0] ? model.series[0].label : 'Y'].concat(withSize ? [settings.series[0].size] : []).concat(split ? [settings.split] : []);
      var rows = [];
      model.series.forEach(function (d) { d.data.forEach(function (p) { rows.push(p.slice(0, withSize ? 3 : 2).concat(split ? [d.split] : [])); }); });
      return { name: name || 'chart', header: header, rows: rows };
    }
    return { name: name || 'chart', header: [model.xLabel].concat(model.series.map(function (d) { return d.name; })),
      rows: model.x.map(function (k, j) { return [k].concat(model.series.map(function (d) { var v = d.data[j]; return num(v) ? v : null; })); }) };
  }

  var api = { MASK: MASK, OTHERS: OTHERS, TYPES: TYPES, AGGS: AGGS, BUCKETS: BUCKETS, LIMITS: LIMITS,
    toNumber: toNumber, isDate: isDate, dayOf: dayOf, bucketOf: bucketOf, autoBucket: autoBucket, businessDays: businessDays,
    classify: classify, chartable: chartable, types: types, normalise: normalise, defaults: defaults,
    prepare: prepare, toArrangement: toArrangement, cube: cube, combine: combine, series: series, points: points, build: build, fromCube: fromCube,
    option: option, palette: palette, summary: summary, trend: trend, exportGrid: exportGrid };
  if (typeof module !== 'undefined' && module.exports) { module.exports = api; } else {
    (root.drishtiModules = root.drishtiModules || {}).tableChartEngine = api;
    root.drishtiTableChartEngine = api;
  }
})(this);
