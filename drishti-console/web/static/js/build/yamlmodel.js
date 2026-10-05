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
/* What the Sutra text says, as plain JavaScript values, read with Studio's forgiving YAML reader (studio-yaml.js): the panels
 * with their ids, kinds and options, and the title, strip, keys and match. The workbench never writes through this (every
 * change is an operation the server applies); it only reads, to fill the inspector and to know which panels exist.
 *
 *   WB.model(yamlText) -> {panels: [{id, kind, line, values}], title, strip, keys, match, ids}   values: option -> JS value */
(function () {
  'use strict';
  var WB = window.DrishtiWB = window.DrishtiWB || {};

  function js(n) {
    if (!n) { return null; }
    if (n.t === 'scalar') { return n.v; }
    if (n.t === 'seq') { return n.items.map(js); }
    var o = {};
    (n.entries || []).forEach(function (e) { o[e.key] = js(e.value); });
    return o;
  }
  function entry(map, key) {
    var list = (map && map.entries) || [];
    for (var i = 0; i < list.length; i++) { if (list[i].key === key) { return list[i]; } }
    return null;
  }

  /** Studio's reader takes a flow collection ({ a: 1, b: [x, y] }) only on one line; YAML lets it run over several. Each such run is joined
   *  onto its first line and the lines it used are left empty, so every other line keeps its number. */
  function unwrap(text) {
    var lines = text.split('\n'), out = [], skipBlockAt = -1;
    for (var i = 0; i < lines.length; i++) {
      var ln = lines[i], indent = ln.search(/\S/);
      if (skipBlockAt >= 0) {                                    // inside a | or > block: braces there are text
        if (ln.trim() === '' || indent > skipBlockAt) { out.push(ln); continue; }
        skipBlockAt = -1;
      }
      var d = depth(ln, 0);
      if (d > 0) {
        var joined = ln, j = i;
        while (d > 0 && j + 1 < lines.length) { j++; joined += ' ' + lines[j].trim(); d = depth(lines[j], d); }
        out.push(joined);
        for (var k = i + 1; k <= j; k++) { out.push(''); }
        i = j;
        continue;
      }
      if (/:\s*[|>][+-]?\d*\s*(#.*)?$/.test(ln)) { skipBlockAt = indent; }
      out.push(ln);
    }
    return out.join('\n');
  }
  /** The flow depth after a line, starting from `d` (quotes and comments are skipped). */
  function depth(ln, d) {
    var q = null;
    for (var i = 0; i < ln.length; i++) {
      var c = ln[i];
      if (q) { if (c === '\\' && q === '"') { i++; } else if (c === q) { q = null; } continue; }
      if (c === '"' || c === "'") { if (i === 0 || /[\s,[{:]/.test(ln[i - 1])) { q = c; } continue; }
      if (c === '#' && (i === 0 || /\s/.test(ln[i - 1]))) { break; }
      if ((c === '{' || c === '[') && (d > 0 || /(^|[\s:,-])$/.test(ln.slice(0, i)))) { d++; } else if ((c === '}' || c === ']') && d > 0) { d--; }
    }
    return d;
  }

  WB.model = function (text) {
    var root = window.drishtiYaml.parse(unwrap(text || ''));
    var out = { panels: [], title: {}, strip: [], keys: {}, match: {}, ids: [] };
    if (!root || root.t !== 'map') { return out; }
    var pick = function (k) { var e = entry(root, k); return e ? js(e.value) : null; };
    out.title = pick('title') || {};
    out.strip = pick('strip') || [];
    out.keys = pick('keys') || {};
    out.match = pick('match') || {};
    var ps = entry(root, 'panels');
    if (ps && ps.value && ps.value.t === 'seq') {
      ps.value.items.forEach(function (n) {
        var v = js(n) || {};
        if (typeof v.id !== 'string') { return; }
        out.panels.push({ id: v.id, kind: v.kind, line: n.line, values: v });
        out.ids.push(v.id);
      });
    }
    return out;
  };
})();
