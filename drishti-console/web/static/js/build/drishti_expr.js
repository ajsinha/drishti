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
/* What completes a Rachana-EL expression in the inspector (its fields are drishti-inputs' one-line code editors, the Form's formula
 * widget): after "$" the paths of the shape ("$.LEGS", "$.BOOK.NAME"), after "@" the fields of the rows of the panel (when the panel's
 * rows are "$.LEGS", "@.NOTIONAL"), and a word becomes a function name with its argument count; a prefix matches in any case ("$.legs"
 * offers "$.LEGS") and the path is written in its own case. The code editor draws the list and takes the keys (Up, Down, Enter, Tab,
 * Escape). The server's parser is the judge of an expression; this only saves typing and typos.
 *
 *   WB.expr.candidates({text}, src) -> [{text, hint}]        src: {paths(): [{path, type}], rows(): '$.legs' | '', functions: {name: {min, max}}}
 *   WB.expr.complete(src) -> the code editor's completion hook */
(function () {
  'use strict';
  var WB = window.Drishti.WB = window.Drishti.WB || {};
  function candidates(t, src) {
    var out = [], q = t.text, lq = q.toLowerCase();                 // paths match in any case: $.sch offers $.SCHEDULE (UPPER_SNAKE since 2.3.0)
    if (q[0] === '$') {
      var seen = {};
      src.paths().forEach(function (p) {
        var plain = p.path.replace(/\[\]/g, '');
        if (plain !== '$' && plain.toLowerCase().indexOf(lq) === 0 && !seen[plain]) { seen[plain] = 1; out.push({ text: plain, hint: p.type }); }
      });
    } else if (q[0] === '@') {
      var rows = (src.rows() || '').replace(/\[\]$/, '');
      if (rows) {
        var prefix = rows + '[].';
        src.paths().forEach(function (p) {
          if (p.path.indexOf(prefix) === 0) { var rest = '@.' + p.path.slice(prefix.length); if (rest.toLowerCase().indexOf(lq) === 0) { out.push({ text: rest.replace(/\[\]/g, ''), hint: p.type }); } }
        });
      }
    } else if (/^[a-z]/i.test(q)) {
      Object.keys(src.functions || {}).sort().forEach(function (f) {
        if (f.toLowerCase().indexOf(lq) === 0 && f !== q) {
          var s = src.functions[f];
          out.push({ text: f + '(', hint: s.min + (s.max !== s.min ? '-' + s.max : '') + ' argument' + (s.max === 1 ? '' : 's') });
        }
      });
    }
    return out.slice(0, 40);
  }

  /** The code editor's completion hook over the same candidates: paths as names, functions as functions (Enter writes "name()"). */
  function complete(src) {
    return function (c) {
      return candidates({ text: c.word }, src).map(function (x) {
        var fn = /\($/.test(x.text);
        return { name: fn ? x.text.slice(0, -1) : x.text, kind: fn ? 'function' : 'field', description: x.hint || '' };
      });
    };
  }
  WB.expr = { candidates: candidates, complete: complete };
})();
