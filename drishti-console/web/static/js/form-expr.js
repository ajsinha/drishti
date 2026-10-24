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
/* A form's rules and hints (SUTRA_NEXT.md 10), evaluated in the browser as people type: the same small language the server checks drafts
   with (drishti-rachana FormExpr.java), so a message shown here is the message the server gives. Fields by id, a lookup's carried value
   (counterparty.LIMIT), numbers, 'text', true, false, null, today; + - * /, == != < <= > >=, and or not, brackets; empty len abs min max.
   An empty value makes a sum or a comparison empty, and a rule that comes out empty holds. Also the field checks (type, required, min,
   max, pattern, length, options) the server runs, with the same words. window.drishtiFormExpr. */
(function () {
  'use strict';
  var FN = { empty: 1, len: 1, abs: 1, min: 2, max: 2 };

  function tokens(s) {
    var out = [], i = 0, m;
    while (i < s.length) {
      var c = s[i], rest = s.slice(i);
      if (/\s/.test(c)) { i++; continue; }
      if ((m = /^[0-9][0-9._]*([eE][+-]?[0-9]+)?/.exec(rest))) { out.push({ t: 'n', v: m[0].replace(/_/g, '') }); i += m[0].length; continue; }
      if (c === '"' || c === "'") {
        var j = s.indexOf(c, i + 1);
        if (j < 0) { throw new Error('a text is not closed'); }
        out.push({ t: 's', v: s.slice(i + 1, j) }); i = j + 1; continue;
      }
      if ((m = /^[A-Za-z_][A-Za-z0-9_.]*/.exec(rest))) { out.push({ t: 'i', v: m[0] }); i += m[0].length; continue; }
      if ((m = /^(==|!=|<=|>=)/.exec(rest))) { out.push({ t: 'o', v: m[0] }); i += 2; continue; }
      if ('+-*/()<>,'.indexOf(c) >= 0) { out.push({ t: 'o', v: c }); i++; continue; }
      throw new Error("'" + c + "' is not part of the form expressions");
    }
    return out;
  }

  function parse(text) {
    var toks = tokens(text), i = 0;
    function at(w) { return i < toks.length && toks[i].v === w && toks[i].t !== 's'; }
    function close() { if (!at(')')) { throw new Error("a ')' is missing"); } i++; }
    function or() { var n = and(); while (at('or')) { i++; n = ['bin', 'or', n, and()]; } return n; }
    function and() { var n = not(); while (at('and')) { i++; n = ['bin', 'and', n, not()]; } return n; }
    function not() { if (at('not')) { i++; return ['not', not()]; } return cmp(); }
    function cmp() {
      var n = sum(), ops = ['==', '!=', '<=', '>=', '<', '>'];
      for (var k = 0; k < ops.length; k++) { if (at(ops[k])) { i++; return ['bin', ops[k], n, sum()]; } }
      return n;
    }
    function sum() { var n = prod(); while (at('+') || at('-')) { var op = toks[i++].v; n = ['bin', op, n, prod()]; } return n; }
    function prod() { var n = unary(); while (at('*') || at('/')) { var op = toks[i++].v; n = ['bin', op, n, unary()]; } return n; }
    function unary() { if (at('-')) { i++; return ['neg', unary()]; } return atom(); }
    function atom() {
      if (i >= toks.length) { throw new Error('the expression ends too soon'); }
      var t = toks[i++];
      if (t.t === 'n') { return ['lit', Number(t.v)]; }
      if (t.t === 's') { return ['lit', t.v]; }
      if (t.t === 'o') { if (t.v === '(') { var n = or(); close(); return n; } throw new Error("'" + t.v + "' is not expected here"); }
      if (t.v === 'true') { return ['lit', true]; }
      if (t.v === 'false') { return ['lit', false]; }
      if (t.v === 'null') { return ['lit', null]; }
      if (t.v === 'today') { return ['today']; }
      if (at('(')) {
        if (!FN[t.v]) { throw new Error("'" + t.v + "' is not a function here"); }
        i++;
        var args = [];
        if (!at(')')) { args.push(or()); while (at(',')) { i++; args.push(or()); } }
        close();
        if (args.length !== FN[t.v]) { throw new Error(t.v + '() takes ' + FN[t.v] + ' values'); }
        return ['call', t.v, args];
      }
      return ['name', t.v];
    }
    var root = or();
    if (i < toks.length) { throw new Error("'" + toks[i].v + "' is not expected"); }
    return root;
  }

  function empty(v) { return v === null || v === undefined || v === '' || (Array.isArray(v) && !v.length); }
  function num(v) {
    if (typeof v === 'number') { return isFinite(v) ? v : null; }
    if (typeof v === 'string' && v.trim() !== '') { var n = Number(v.replace(/,/g, '').trim()); return isNaN(n) ? null : n; }
    return null;
  }
  function truthy(v) { return v !== null && v !== undefined && v !== false && v !== 0 && v !== ''; }
  function today() { var d = new Date(); return d.toISOString().slice(0, 10); }

  function ev(n, vals) {
    switch (n[0]) {
      case 'lit': return n[1];
      case 'today': return today();
      case 'name': {
        var dot = n[1].indexOf('.');
        if (dot < 0) { return vals[n[1]] === undefined ? null : vals[n[1]]; }
        var hn = n[1].slice(0, dot), head = (vals.__carry && vals.__carry[hn]) || vals[hn];   // a lookup's carried values
        return head && typeof head === 'object' ? (head[n[1].slice(dot + 1)] === undefined ? null : head[n[1].slice(dot + 1)]) : null;
      }
      case 'not': { var x = ev(n[1], vals); return x === null ? null : !truthy(x); }
      case 'neg': { var y = num(ev(n[1], vals)); return y === null ? null : -y; }
      case 'call': {
        var a = n[2].map(function (k) { return ev(k, vals); });
        if (n[1] === 'empty') { return empty(a[0]); }
        if (n[1] === 'len') { return a[0] === null || a[0] === undefined ? 0 : (Array.isArray(a[0]) ? a[0].length : String(a[0]).length); }
        if (n[1] === 'abs') { var b = num(a[0]); return b === null ? null : Math.abs(b); }
        var p = num(a[0]), q = num(a[1]);
        return p === null || q === null ? null : (n[1] === 'min' ? Math.min(p, q) : Math.max(p, q));
      }
      default: {
        var op = n[1], l = ev(n[2], vals), r = ev(n[3], vals);
        if (op === 'and' || op === 'or') {
          var isAnd = op === 'and';
          if (l === null || r === null) {
            var decides = (l !== null && truthy(l) !== isAnd) || (r !== null && truthy(r) !== isAnd);
            return decides ? !isAnd : null;
          }
          return isAnd ? truthy(l) && truthy(r) : truthy(l) || truthy(r);
        }
        if (empty(l) || empty(r)) {
          if (op === '==') { return empty(l) && empty(r); }
          if (op === '!=') { return empty(l) !== empty(r); }
          return null;
        }
        var nl = num(l), nr = num(r);
        if ('+-*/'.indexOf(op) >= 0) {
          if (nl === null || nr === null) { return op === '+' ? String(l) + String(r) : null; }
          return op === '+' ? nl + nr : op === '-' ? nl - nr : op === '*' ? nl * nr : (nr === 0 ? null : nl / nr);
        }
        var c = nl !== null && nr !== null ? (nl < nr ? -1 : nl > nr ? 1 : 0) : (String(l) < String(r) ? -1 : String(l) > String(r) ? 1 : 0);
        return op === '==' ? c === 0 : op === '!=' ? c !== 0 : op === '<' ? c < 0 : op === '<=' ? c <= 0 : op === '>' ? c > 0 : c >= 0;
      }
    }
  }

  var cache = {};
  function compiled(text) { return cache[text] || (cache[text] = parse(text)); }
  function evaluate(text, vals) { try { return ev(compiled(text), vals || {}); } catch (e) { return null; } }
  function holds(text, vals) { var v = evaluate(text, vals); return v === null || truthy(v); }

  /* A typed value as the server reads it: a number for number fields (25,000,000 or 25m or 1.5bn), true/false for bool, else text. */
  function typed(f, v) {
    if (empty(v)) { return null; }
    if (f.type === 'number') {
      if (typeof v === 'number') { return v; }
      var m = /^\s*(-?[0-9][0-9,]*(?:\.[0-9]+)?)\s*(k|m|mm|bn|b)?\s*$/i.exec(String(v));
      if (!m) { return NaN; }
      var x = Number(m[1].replace(/,/g, '')), u = (m[2] || '').toLowerCase();
      return x * (u === 'k' ? 1e3 : u === 'm' || u === 'mm' ? 1e6 : u === 'bn' || u === 'b' ? 1e9 : 1);
    }
    if (f.type === 'bool') { return v === true || String(v).toLowerCase() === 'true'; }
    return String(v);
  }

  function plain(n) { return Number(n).toLocaleString('en-US', { maximumFractionDigits: 6 }); }
  function isDate(s) { return /^\d{4}-\d{2}-\d{2}$/.test(s) && !isNaN(Date.parse(s + 'T00:00:00Z')); }

  function valueProblem(f, raw, t) {
    var s = String(raw);
    if (f.type === 'number') {
      if (typeof t !== 'number' || isNaN(t)) { return f.label + ' is a number'; }
      if (f.min !== undefined && num(f.min) !== null && t < num(f.min)) { return f.label + ' is at least ' + plain(f.min); }
      if (f.max !== undefined && num(f.max) !== null && t > num(f.max)) { return f.label + ' is at most ' + plain(f.max); }
    } else if (f.type === 'date') {
      if (!isDate(s)) { return f.label + ' is a date (2026-12-31)'; }
      var lo = f.min === 'today' ? today() : f.min, hi = f.max === 'today' ? today() : f.max;
      if (lo && s < lo) { return f.label + ' is on or after ' + lo; }
      if (hi && s > hi) { return f.label + ' is on or before ' + hi; }
    } else if (f.type === 'enum') {
      if (!(f.options || []).some(function (o) { return String(o.value) === s; })) {
        return f.label + ' is one of ' + (f.options || []).map(function (o) { return o.label; }).join(', ');
      }
    }
    var max = f.maxLength || 4000;
    if (s.length > max) { return f.label + ' is at most ' + max + ' characters'; }
    if (f.pattern && !new RegExp('^(?:' + f.pattern + ')$').test(s)) { return f.label + ' is not in the expected form'; }
    return null;
  }

  /* The issues of one record, as the server finds them: checked = the ids whose own checks run; lookups = {fieldId: carried values}. */
  function check(form, values, checked, lookups) {
    var out = [], vals = {};
    (form.fields || []).forEach(function (f) {
      var v = values[f.id], blank = empty(v) || (typeof v === 'string' && !v.trim());
      var t = blank ? null : typed(f, v);
      vals[f.id] = t;
      if (!checked[f.id]) { return; }
      if (blank) { if (f.required) { out.push({ field: f.id, message: f.label + ' is required', severity: 'error' }); } return; }
      var why = valueProblem(f, v, t);
      if (why) { out.push({ field: f.id, message: why, severity: 'error' }); }
    });
    vals.__carry = lookups || {};
    (form.rules || []).forEach(function (r) {
      if (!holds(r.rule, vals)) { out.push({ field: (r.fields || [])[0] || null, message: r.message, severity: r.severity || 'error' }); }
    });
    return out;
  }

  window.drishtiFormExpr = { parse: parse, evaluate: evaluate, holds: holds, check: check, typed: typed, num: num, empty: empty };
}());
