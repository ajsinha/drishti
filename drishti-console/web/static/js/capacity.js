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
/* Admin -> Capacity's planner in the browser (docs/architecture/RESOURCES_AND_SCALE.md 9.7). The server serves
   capacity/planner.yaml as JSON; this evaluates its expressions as you type, exactly as tools/sizing_planner.py's
   evaluator does for the workbook (a small Excel subset: names, + - * / ^ &, comparisons, IF, MAX, MIN, CEILING, INT,
   ROUND, AND, OR, CHAR). The shared test vectors (drishti-server/src/test/resources/capacity/test-vectors.json) keep
   the two, and the workbook Excel recalculates, in agreement. No DOM here: capacity-page.js draws the page, and the
   tests run this file under Node. window.drishtiCapacity = { Evaluator, cells, evaluate, yamlBlockFormula,
   commandFormula, text, headroom, growth }. */
(function (root) {
  'use strict';

  var TOKEN = /\s*(?:(\d+(?:\.\d+)?(?:[eE][+-]?\d+)?)|("(?:[^"]|"")*")|([A-Za-z_][A-Za-z0-9_.]*)|(<=|>=|<>|[-+*/^&=<>(),]))/y;

  function tokens(f) {
    var out = [], i = 0, m;
    while (i < f.length) {
      TOKEN.lastIndex = i;
      m = TOKEN.exec(f);
      if (!m || TOKEN.lastIndex === i) {
        if (f.slice(i).trim() === '') { break; }
        throw new Error('cannot read ' + f.slice(i) + ' in ' + f);
      }
      i = TOKEN.lastIndex;
      if (m[1] !== undefined) { out.push(['num', m[1]]); }
      else if (m[2] !== undefined) { out.push(['str', m[2].slice(1, -1).replace(/""/g, '"')]); }
      else if (m[3] !== undefined) { out.push(['name', m[3]]); }
      else { out.push(['op', m[4]]); }
    }
    return out;
  }

  /** Python's repr of a float, as the workbook's text joins print a number (1e-05, not 1e-5). */
  function pyFloat(v) {
    var s = String(v);
    var e = /e([+-])(\d)$/.exec(s);
    return e ? s.replace(/e([+-])(\d)$/, 'e$1' + '0$2') : (s.indexOf('.') < 0 && s.indexOf('e') < 0 && isFinite(v) ? s + '.0' : s);
  }

  function text(v) {
    if (typeof v === 'boolean') { return v ? 'TRUE' : 'FALSE'; }
    if (typeof v === 'number') { return Number.isInteger(v) ? String(v) : pyFloat(v); }
    return String(v);
  }

  function num(v) {
    if (typeof v === 'string') { throw new Error('text ' + JSON.stringify(v) + ' used as a number'); }
    return typeof v === 'boolean' ? (v ? 1 : 0) : v;
  }

  function truthy(v) { return typeof v === 'string' ? v.length > 0 : !!v; }

  /** Python's round(x, n): half to even on the binary value, as the workbook's figures were computed. */
  function pyRound(x, n) {
    var p = Math.pow(10, n), y = x * p, r = Math.round(y);
    if (Math.abs(y % 1) === 0.5) { r = 2 * Math.round(y / 2); }
    return r / p;
  }

  /** The workbook's formulas by name: {name: number, text, or a formula string starting with '='}. */
  function Evaluator(cells) {
    this.cells = cells;
    this.memo = {};
    this.toks = null;
    this.pos = 0;
  }

  Evaluator.prototype.value = function (name) {
    if (!Object.prototype.hasOwnProperty.call(this.memo, name)) {
      if (!Object.prototype.hasOwnProperty.call(this.cells, name)) { throw new Error('no name ' + name); }
      var c = this.cells[name];
      this.memo[name] = (typeof c === 'string' && c.charAt(0) === '=') ? this.eval(c.slice(1)) : c;
    }
    return this.memo[name];
  };

  Evaluator.prototype.eval = function (formula) {
    var saved = [this.toks, this.pos];
    this.toks = tokens(formula);
    this.pos = 0;
    try {
      var v = this.compare();
      if (this.pos !== this.toks.length) { throw new Error('unexpected ' + this.toks[this.pos] + ' in ' + formula); }
      return v;
    } finally {
      this.toks = saved[0];
      this.pos = saved[1];
    }
  };

  Evaluator.prototype.peek = function () { return this.pos < this.toks.length ? this.toks[this.pos] : [null, null]; };
  Evaluator.prototype.isOp = function (op) { var t = this.peek(); return t[0] === 'op' && t[1] === op; };
  Evaluator.prototype.take = function (op) {
    var t = this.peek();
    if (op !== undefined && !(t[0] === 'op' && t[1] === op)) { throw new Error('expected ' + op + ', found ' + t); }
    this.pos++;
    return t;
  };

  Evaluator.prototype.compare = function () {
    var a = this.concat(), t, op, b, x, y;
    for (;;) {
      t = this.peek();
      if (t[0] !== 'op' || ['=', '<>', '<', '>', '<=', '>='].indexOf(t[1]) < 0) { return a; }
      op = this.take()[1];
      b = this.concat();
      if (typeof a === 'string' && typeof b === 'string') { x = a.toLowerCase(); y = b.toLowerCase(); }
      else if (typeof a === 'string' || typeof b === 'string') { x = a; y = b; if (op === '=' || op === '<>') { a = (op === '<>'); continue; } }
      else { x = num(a); y = num(b); }
      a = op === '=' ? x === y : op === '<>' ? x !== y : op === '<' ? x < y : op === '>' ? x > y : op === '<=' ? x <= y : x >= y;
    }
  };

  Evaluator.prototype.concat = function () {
    var a = this.add();
    while (this.isOp('&')) { this.take(); a = text(a) + text(this.add()); }
    return a;
  };

  Evaluator.prototype.add = function () {
    var a = this.mul(), op, b;
    while (this.isOp('+') || this.isOp('-')) { op = this.take()[1]; b = this.mul(); a = op === '+' ? num(a) + num(b) : num(a) - num(b); }
    return a;
  };

  Evaluator.prototype.mul = function () {
    var a = this.pow(), op, b;
    while (this.isOp('*') || this.isOp('/')) { op = this.take()[1]; b = this.pow(); a = op === '*' ? num(a) * num(b) : num(a) / num(b); }
    return a;
  };

  Evaluator.prototype.pow = function () {
    var a = this.unary();
    while (this.isOp('^')) { this.take(); a = Math.pow(num(a), num(this.unary())); }
    return a;
  };

  Evaluator.prototype.unary = function () {
    if (this.isOp('-')) { this.take(); return -num(this.unary()); }
    return this.atom();
  };

  Evaluator.prototype.atom = function () {
    var t = this.take(), x;
    if (t[0] === 'num') { return parseFloat(t[1]); }
    if (t[0] === 'str') { return t[1]; }
    if (t[0] === 'op' && t[1] === '(') { x = this.compare(); this.take(')'); return x; }
    if (t[0] === 'name') { return this.isOp('(') ? this.call(t[1].toUpperCase()) : this.value(t[1]); }
    throw new Error('unexpected ' + t[1]);
  };

  Evaluator.prototype.call = function (fn) {
    this.take('(');
    var args = [], c, a, b, x, s;
    if (fn === 'IF') {
      c = this.compare(); this.take(','); a = this.compare(); this.take(','); b = this.compare(); this.take(')');
      return truthy(c) ? a : b;
    }
    if (!this.isOp(')')) {
      args.push(this.compare());
      while (this.isOp(',')) { this.take(); args.push(this.compare()); }
    }
    this.take(')');
    switch (fn) {
      case 'MAX': return Math.max.apply(null, args.map(num));
      case 'MIN': return Math.min.apply(null, args.map(num));
      case 'CEILING': x = num(args[0]); s = num(args[1]); return Math.ceil(x / s - 1e-12) * s;
      case 'AND': return args.every(truthy);
      case 'OR': return args.some(truthy);
      case 'CHAR': return String.fromCharCode(Math.trunc(num(args[0])));
      case 'INT': return Math.floor(num(args[0]));
      case 'ROUND': return pyRound(num(args[0]), Math.trunc(num(args[1])));
      default: throw new Error('function ' + fn + ' is not supported');
    }
  };

  // ------------------------------------------------------------------------------------- the derived formulas
  function q(s) { return '"' + s.replace(/"/g, '""') + '"'; }

  /** One nested YAML block of every setting (tools/sizing_planner.py yaml_block_formula, line for line). */
  function yamlBlockFormula(plan) {
    var tree = {}, order = [];
    function child(node, k) {
      if (!node.keys) { node.keys = []; node.map = {}; }
      if (!Object.prototype.hasOwnProperty.call(node.map, k)) { node.keys.push(k); node.map[k] = { keys: [], map: {} }; }
      return node.map[k];
    }
    tree = { keys: [], map: {} };
    plan.settings.forEach(function (s) {
      var parts = s.key.split('.'), node = tree;
      parts.slice(0, -1).forEach(function (p) { node = child(node, p); });
      var leaf = parts[parts.length - 1];
      node.keys.push(leaf);
      node.map[leaf] = { leaf: [s.name, s.condition || null] };
    });
    function conds(node) {
      var out = [];
      node.keys.forEach(function (k) {
        var v = node.map[k];
        (v.leaf ? [v.leaf[1]] : conds(v)).forEach(function (c) { if (out.indexOf(c) < 0) { out.push(c); } });
      });
      return out;
    }
    var lines = [];
    function emit(node, depth, outer) {
      node.keys.forEach(function (k) {
        var v = node.map[k], pad = new Array(depth + 1).join('  ');
        if (!v.leaf) {
          var c = conds(v), cond = c.length === 1 ? c[0] : null;
          lines.push([q(pad + k + ':'), cond || outer]);
          emit(v, depth + 1, cond || outer);
        } else {
          lines.push([q(pad + k + ': ') + '&' + v.leaf[0], v.leaf[1] || outer]);
        }
      });
    }
    emit(tree, 0, null);
    void order;
    var out = [lines[0][0]];
    lines.slice(1).forEach(function (l) { out.push(l[1] ? 'IF(' + l[1] + ',CHAR(10)&' + l[0] + ',"")' : 'CHAR(10)&' + l[0]); });
    return out.join('&');
  }

  /** The whole command line (tools/sizing_planner.py _command_formula). */
  function commandFormula(plan) {
    var parts = ['JvmArenas&" java"'];
    plan.jvm.forEach(function (j) { if (j.name !== 'JvmArenas') { parts.push('IF(' + j.name + '="",""," "&' + j.name + ')'); } });
    return parts.join('&') + '&" -jar "&ServerJar';
  }

  /** Every named cell: the inputs (overrides in place), coefficients and every expression (tools/sizing_planner.py workbook_cells). */
  function cells(plan, overrides) {
    var c = {};
    plan.inputs.forEach(function (i) { c[i.name] = i['default']; });
    Object.keys(overrides || {}).forEach(function (k) { c[k] = overrides[k]; });
    plan.coefficients.forEach(function (k) { c[k.name] = k.value; });
    plan.results.forEach(function (b) { b.rows.forEach(function (r) { c[r.name] = '=' + r.expression; }); });
    plan.jvm.forEach(function (j) { c[j.name] = '=' + j.expression; });
    c.JvmCommand = '=' + commandFormula(plan);
    plan.settings.forEach(function (s) { c[s.name] = '=' + s.expression; });
    c.YamlBlock = '=' + yamlBlockFormula(plan);
    plan.warnings.forEach(function (w) { c[w.name] = '=' + w.expression; });
    (plan.calibration || []).forEach(function (k) { c[k.name] = '=' + k.expression; });
    return c;
  }

  function evaluate(plan, overrides) { return new Evaluator(cells(plan, overrides)); }

  // ------------------------------------------------------------------------------------- today against the plan
  var MB = 1024;

  function state(ratio) { return ratio == null ? 'none' : ratio > 1 ? 'short' : ratio > 0.8 ? 'tight' : 'room'; }

  /**
   * Each resource's need (the plan) against what this server has (live), with where it is now: heap (-Xmx needed against -Xmx set),
   * RAM (resident memory planned with the OS and console against the machine), disk (the plan's disk in use against the data folder's
   * disk, with the days left at the plan's daily growth) and cores (the plan's peak requests a second against what the cores answer).
   */
  function headroom(plan, ev, live) {
    var s = (live && live.server) || {}, d = (live && live.disk) || null, l = (live && live.load) || null, v = function (n) { return ev.value(n); };
    var out = [];
    var needHeap = v('XmxRawMB'), hasHeap = s.heapMaxMb || null;
    out.push({ id: 'heap', label: 'Heap', need: needHeap, has: hasHeap, now: s.heapAfterGcMb == null ? null : s.heapAfterGcMb, unit: 'MB',
      ratio: hasHeap ? needHeap / hasHeap : null, fix: '-Xmx' + v('XmxGB') + 'g -Xms' + v('XmxGB') + 'g and drishti.var.open-heap-mb: ' + v('SetOpenHeapMB') });
    var needRam = v('RssPlannedMB') + (v('OsReserveGB') + v('ConsoleGB')) * MB, hasRam = s.ramMb || null;
    out.push({ id: 'ram', label: 'RAM', need: needRam, has: hasRam, now: s.rssMb == null ? null : s.rssMb, unit: 'MB',
      ratio: hasRam ? needRam / hasRam : null, fix: 'a machine of ' + v('RamRecommendedGB') + ' GB, or fewer dates in memory (drishti.var.hot-dates)' });
    var needDisk = v('DiskUsedGB'), hasDisk = d && d.totalBytes ? d.totalBytes / 1073741824 : null;
    var perDay = (v('LakeVarDateMB') + v('IntradayUpdatesDay') * v('Scenarios') * v('LakeBytesPerValue') / v('BytesPerMB')
      + v('TradeRowsPerDay') * v('TradeLakeBytesPerRow') / v('BytesPerMB')) / MB;
    var room = d && d.freeBytes != null ? (d.freeBytes - (d.guardBytes || 0)) / 1073741824 : null;
    out.push({ id: 'disk', label: 'Disk', need: needDisk, has: hasDisk, now: d && d.totalBytes != null ? (d.totalBytes - d.freeBytes) / 1073741824 : null,
      unit: 'GB', ratio: hasDisk ? needDisk / hasDisk : null, perDayGB: perDay, daysLeft: room == null || perDay <= 0 ? null : Math.max(0, room / perDay),
      fix: v('DiskRecommendedGB') + ' GB, or the lakes on object storage and ' + v('DiskLocalRecommendedGB') + ' GB of local NVMe' });
    var cores = s.cpus ? Math.max(1, s.cpus / 2 - v('CoresReserve')) : null;
    var canRps = cores == null ? null : cores * v('MixRatePerCore') * v('CpuTarget');
    out.push({ id: 'cores', label: 'Cores', need: v('PeakRps'), has: canRps, now: l ? l.requestsPerSecondPeakToday : null, unit: 'requests/s',
      ratio: canRps ? v('PeakRps') / canRps : null, fix: v('CoresRecommended') + ' cores' });
    out.forEach(function (r) {
      r.state = state(r.ratio);
      if (r.id === 'disk' && r.state === 'room' && r.daysLeft != null && r.daysLeft < 365) { r.state = 'tight'; }
      if (r.id === 'disk' && r.daysLeft != null && r.daysLeft < 30) { r.state = 'short'; }
    });
    return out;
  }

  /** The plan grown ×m: the size inputs scaled, the rest held (intraday share, dates in memory, users). */
  var GROWS = ['Positions', 'Trades', 'Portfolios', 'BasketMembers', 'Nodes', 'BooksCells', 'TradeRowsPerDay', 'UpdatesPerBatch'];

  function growth(plan, inputs, factors) {
    return factors.map(function (m) {
      var o = {};
      Object.keys(inputs).forEach(function (k) { o[k] = inputs[k]; });
      GROWS.forEach(function (k) { o[k] = Math.round(num(o[k] != null ? o[k] : defaultOf(plan, k)) * m); });
      var ev = evaluate(plan, o);
      return { factor: m, trades: o.Trades, ev: ev };
    });
  }

  function defaultOf(plan, name) {
    for (var i = 0; i < plan.inputs.length; i++) { if (plan.inputs[i].name === name) { return plan.inputs[i]['default']; } }
    return null;
  }

  root.drishtiCapacity = { Evaluator: Evaluator, tokens: tokens, text: text, cells: cells, evaluate: evaluate, yamlBlockFormula: yamlBlockFormula,
    commandFormula: commandFormula, headroom: headroom, growth: growth, GROWS: GROWS, defaultOf: defaultOf };
})(typeof window !== 'undefined' ? window : this);
