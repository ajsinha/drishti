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
/* A forgiving reader for the YAML a Sutra is written in (block maps and lists, one-line flow maps and lists,
   quoted and plain scalars, block scalars, comments). It never throws: half-typed lines are skipped, so the
   editor can ask it about the document while the author types. It answers two questions:
     parse(text)        -> a tree of {t: 'map'|'seq'|'scalar', line, entries|items|v}, keys with their line/ch;
     context(lines, line, ch) -> where the cursor is: the key path ('[]' for a list item), whether a key or a
                           value is being typed, the key, the typed prefix and where it starts.
   Exposes window.drishtiYaml. */
(function () {
  'use strict';
  var KEY = /^("(?:[^"\\]|\\.)*"|'(?:[^']|'')*'|[^\s:#{}[\],"'][^:#{}[\],]*?)\s*:(?=\s|$)/;

  function unquote(s) {
    s = s.trim();
    if (s.length > 1 && s[0] === '"' && s[s.length - 1] === '"') { try { return JSON.parse(s); } catch (e) { return s.slice(1, -1); } }
    if (s.length > 1 && s[0] === "'" && s[s.length - 1] === "'") { return s.slice(1, -1).replace(/''/g, "'"); }
    return s;
  }
  function stripComment(s) {
    var q = null;
    for (var i = 0; i < s.length; i++) {
      var c = s[i];
      if (q) { if (c === '\\' && q === '"') { i++; } else if (c === q) { q = null; } continue; }
      if (c === '"' || c === "'") { if (i === 0 || /[\s,[{:]/.test(s[i - 1])) { q = c; } continue; }
      if (c === '#' && (i === 0 || /\s/.test(s[i - 1]))) { return s.slice(0, i); }
    }
    return s;
  }
  function scalar(text, line, ch) {
    var raw = text.trim(), v = unquote(raw);
    if (raw === v) {
      if (/^(true|false)$/.test(raw)) { v = raw === 'true'; }
      else if (/^-?\d+(\.\d+)?$/.test(raw)) { v = +raw; }
      else if (raw === 'null' || raw === '~') { v = null; }
    }
    return { t: 'scalar', v: v, raw: raw, line: line, ch: ch };
  }

  // ---- flow collections on one line: { a: 1, b: [x, y] } -------------------------------------------------
  function flow(s, pos, line, base) {
    var i = pos;
    function ws() { while (i < s.length && /\s/.test(s[i])) { i++; } }
    function quoted() { var q = s[i], j = i + 1; while (j < s.length && s[j] !== q) { if (s[j] === '\\' && q === '"') { j++; } j++; } return j + 1; }
    function plainEnd(stops) { var j = i; while (j < s.length && stops.indexOf(s[j]) < 0) { if (s[j] === ':' && /[\s,}]/.test(s[j + 1] || ' ') && stops.indexOf(':') >= 0) { break; } j++; } return j; }
    function value(stops) {
      ws();
      var c = s[i], start = i;
      if (c === '{') { return map(); }
      if (c === '[') { return seq(); }
      var end = (c === '"' || c === "'") ? quoted() : plainEnd(stops);
      i = Math.min(end, s.length);
      return scalar(s.slice(start, i), line, base + start);
    }
    function map() {
      var node = { t: 'map', line: line, ch: base + i, entries: [], flow: true };
      i++;
      for (;;) {
        ws();
        if (i >= s.length) { node.open = true; return node; }
        if (s[i] === '}') { i++; return node; }
        if (s[i] === ',') { i++; continue; }
        var ks = i, kend = (s[i] === '"' || s[i] === "'") ? quoted() : plainEnd(':,}');
        i = Math.min(kend, s.length);
        var key = unquote(s.slice(ks, i));
        ws();
        var entry = { key: key, line: line, ch: base + ks, value: null };
        node.entries.push(entry);
        if (s[i] === ':') { i++; entry.value = value(',}'); }
      }
    }
    function seq() {
      var node = { t: 'seq', line: line, ch: base + i, items: [], flow: true };
      i++;
      for (;;) {
        ws();
        if (i >= s.length) { node.open = true; return node; }
        if (s[i] === ']') { i++; return node; }
        if (s[i] === ',') { i++; continue; }
        var before = i;
        node.items.push(value(',]'));
        if (i === before) { i++; }
      }
    }
    var out = s[i] === '{' ? map() : seq();
    return out;
  }

  // ---- the block structure, by indentation ---------------------------------------------------------------
  function parse(text) {
    var src = text.split('\n'), lines = [];
    for (var n = 0; n < src.length; n++) {
      var body = stripComment(src[n]).replace(/\s+$/, '');
      if (!body.trim() || /^(---|\.\.\.)\s*$/.test(body)) { continue; }
      lines.push({ n: n, ind: body.length - body.replace(/^ +/, '').length, text: body });
    }
    var p = 0;
    function inlineValue(s, line, ch) {
      var t = s.trim();
      if (!t) { return null; }
      if (t[0] === '{' || t[0] === '[') { return flow(t, 0, line, ch + s.indexOf(t[0])); }
      return scalar(t, line, ch + s.indexOf(t[0]));
    }
    function blockScalar(ind) { var parts = []; while (p < lines.length && lines[p].ind > ind) { parts.push(src[lines[p].n].trim()); p++; } return parts.join('\n'); }
    // a block node starting at lines[p] with indentation exactly ind
    function node(ind) {
      if (p >= lines.length) { return null; }
      var l = lines[p], t = l.text.slice(l.ind);
      if (t[0] === '-' && (t.length === 1 || t[1] === ' ')) { return list(l.ind); }
      if (KEY.test(t)) { return map(l.ind); }
      p++;
      return inlineValue(t, l.n, l.ind);
    }
    function entryValue(rest, l, col, ind) {
      var t = rest.trim();
      if (/^[|>][-+0-9]*$/.test(t)) { var v = blockScalar(ind); return { t: 'scalar', v: v, raw: v, line: l.n, ch: col, block: true }; }
      if (t) {
        var v1 = inlineValue(rest, l.n, col);
        while (p < lines.length && lines[p].ind > ind && v1 && v1.t === 'scalar') { v1.v += ' ' + lines[p].text.trim(); p++; } // folded plain
        return v1;
      }
      if (p < lines.length && (lines[p].ind > ind || (lines[p].ind === ind && /^-( |$)/.test(lines[p].text.slice(ind))))) { return node(lines[p].ind); }
      return null;
    }
    function map(ind) {
      var m = { t: 'map', line: lines[p].n, ch: ind, entries: [] };
      while (p < lines.length && lines[p].ind === ind) {
        var l = lines[p], t = l.text.slice(ind), k = t.match(KEY);
        if (!k) {
          if (/^-( |$)/.test(t)) { break; }
          m.entries.push({ key: t.trim(), line: l.n, ch: ind, value: null, partial: true }); p++; continue;
        }
        p++;
        var entry = { key: unquote(k[1]), line: l.n, ch: ind, value: null };
        m.entries.push(entry);
        entry.value = entryValue(t.slice(k[0].length), l, ind + k[0].length, ind);
      }
      while (p < lines.length && lines[p].ind > ind) { p++; } // stray deeper lines
      return m;
    }
    function list(ind) {
      var s = { t: 'seq', line: lines[p].n, ch: ind, items: [] };
      while (p < lines.length && lines[p].ind === ind && /^-( |$)/.test(lines[p].text.slice(ind))) {
        var l = lines[p], rest = l.text.slice(ind + 1), off = rest.length - rest.replace(/^ +/, '').length, col = ind + 1 + off;
        rest = rest.slice(off);
        if (!rest) { p++; s.items.push(p < lines.length && lines[p].ind > ind ? node(lines[p].ind) : null); continue; }
        if (/^-( |$)/.test(rest) || (KEY.test(rest) && rest[0] !== '{' && rest[0] !== '[')) {
          // "- key: v" opens a map whose further keys sit at the column of "key"
          lines[p] = { n: l.n, ind: col, text: l.text.slice(0, ind) + ' ' + l.text.slice(ind + 1) };
          s.items.push(node(col));
          continue;
        }
        p++;
        s.items.push(inlineValue(rest, l.n, col));
      }
      return s;
    }
    var root = null;
    while (p < lines.length) {
      var before = p, r = node(lines[p].ind);
      if (!root && r && r.t === 'map') { root = r; } else if (root && r && r.t === 'map') { root.entries = root.entries.concat(r.entries); }
      if (p === before) { p++; }
    }
    return root || { t: 'map', line: 0, ch: 0, entries: [] };
  }

  function get(m, key) {
    if (!m || m.t !== 'map') { return undefined; }
    for (var i = 0; i < m.entries.length; i++) { if (m.entries[i].key === key) { return m.entries[i].value; } }
    return undefined;
  }
  /** Plain JavaScript values of a node (for previews and checks). */
  function plain(n) {
    if (!n) { return null; }
    if (n.t === 'scalar') { return n.v; }
    if (n.t === 'seq') { return n.items.map(plain); }
    var o = {};
    n.entries.forEach(function (e) { o[e.key] = plain(e.value); });
    return o;
  }
  /** The nodes along a path ('[]' picks the list item at or before the line), outermost first. */
  function chain(root, path, line) {
    var out = [root], cur = root;
    for (var i = 0; i < path.length && cur; i++) {
      var seg = path[i];
      if (seg === '[]') {
        if (cur.t !== 'seq') { break; }
        var pick = null;
        cur.items.forEach(function (it) { if (it && it.line <= line) { pick = it; } });
        cur = pick;
      } else {
        cur = get(cur, seg);
      }
      if (cur) { out.push(cur); }
    }
    return out;
  }

  // ---- where the cursor is ------------------------------------------------------------------------------
  function shape(text) {
    var body = stripComment(text).replace(/\s+$/, ''), ind = body.length - body.replace(/^ +/, '').length, dashes = [], i = ind;
    while (body[i] === '-' && (body[i + 1] === ' ' || i + 1 === body.length)) { dashes.push(i); i++; while (body[i] === ' ') { i++; } }
    var rest = body.slice(i), k = rest.match(KEY);
    return { blank: !body.trim(), ind: ind, dashes: dashes, col: i, key: k ? unquote(k[1]) : null, value: k ? rest.slice(k[0].length).trim() : null };
  }

  function context(lines, line, ch) {
    var cur = lines[line] || '', before = cur.slice(0, ch), path = [];
    // the current line: its list markers, then any flow collections up to the cursor
    var ind = before.length - before.replace(/^ +/, '').length, i = ind, need;
    var dashes = [];
    while (before[i] === '-' && (before[i + 1] === ' ' || i + 1 === before.length)) { dashes.push(i); i++; while (before[i] === ' ') { i++; } }
    need = dashes.length ? dashes[0] : i;
    var needIsDash = dashes.length > 0;
    if (!before.trim()) { need = ch; }
    // walk up for the block parents
    var block = [];
    for (var n = line - 1; n >= 0 && need > 0; n--) {
      var s = shape(lines[n]);
      if (s.blank) { continue; }
      var first = s.dashes.length ? s.dashes[0] : s.col;
      if (first > need || (first === need && !(needIsDash && !s.dashes.length && s.key && !s.value))) { continue; }
      if (s.dashes.length) {
        if (need > s.col && s.key) { block.unshift(s.key); }
        if (need > s.col && !s.key) { continue; }
        for (var d = s.dashes.length - 1; d >= 0; d--) { if (s.dashes[d] < need) { block.unshift('[]'); } }
        need = s.dashes[0]; needIsDash = true;
      } else if (s.key) {
        if (s.value && /^[|>][-+0-9]*$/.test(s.value)) { return { scalar: true, path: block }; }
        if (s.value && !/^[{[]/.test(s.value) && need > s.col) { return { scalar: true, path: block }; }
        block.unshift(s.key);
        need = s.col; needIsDash = false;
      } else {
        need = first; needIsDash = false;
      }
    }
    path = block.concat(dashes.map(function () { return '[]'; }));
    // the rest of the line: a small scanner over keys, values and flow collections
    var frames = [{ map: true, flow: false, key: null, inValue: false, keyStart: i, valStart: i, seg: null }], q = null, qStart = -1;
    for (var j = i; j < before.length; j++) {
      var c = before[j], f = frames[frames.length - 1];
      if (q) { if (c === '\\' && q === '"') { j++; } else if (c === q) { q = null; } continue; }
      if ((c === '"' || c === "'") && (f.inValue || !f.map) && !before.slice(f.map ? f.valStart : f.valStart, j).trim()) { q = c; qStart = j; continue; }
      if (c === '#' && (j === 0 || /\s/.test(before[j - 1]))) { return { comment: true, path: path }; }
      var valueEmpty = !before.slice(f.valStart, j).trim();
      if ((c === '{' || c === '[') && ((f.map && (f.inValue || !f.flow && j === i)) || !f.map) && valueEmpty) {
        var seg = f.map ? (f.inValue ? f.key : null) : '[]';
        frames.push({ map: c === '{', flow: true, key: null, inValue: false, keyStart: j + 1, valStart: j + 1, seg: seg });
        continue;
      }
      if ((c === '}' || c === ']') && frames.length > 1) { frames.pop(); frames[frames.length - 1].closed = true; continue; }
      if (c === ',' && f.flow) { f.inValue = false; f.keyStart = j + 1; f.valStart = j + 1; continue; }
      if (c === ':' && f.map && !f.inValue && (j + 1 === before.length || /\s/.test(before[j + 1]))) {
        f.key = unquote(before.slice(f.keyStart, j)); f.inValue = true; f.valStart = j + 1;
      }
    }
    for (var k = 1; k < frames.length; k++) { if (frames[k].seg) { path.push(frames[k].seg); } }
    var top = frames[frames.length - 1], res = { path: path, flow: top.flow, quoted: q, line: line };
    if (top.map && !top.inValue) {
      var raw = before.slice(top.keyStart), lead = raw.length - raw.replace(/^\s+/, '').length;
      res.mode = 'key'; res.prefix = raw.slice(lead); res.from = top.keyStart + lead;
    } else {
      var v = before.slice(top.valStart), lv = v.length - v.replace(/^\s+/, '').length;
      res.mode = top.map ? 'value' : 'item'; res.key = top.map ? top.key : null;
      res.from = top.valStart + lv + (q && qStart === top.valStart + lv ? 1 : 0);
      res.prefix = before.slice(res.from);
    }
    return res;
  }

  window.drishtiYaml = { parse: parse, context: context, chain: chain, get: get, plain: plain, unquote: unquote };
})();
