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
/* A small CodeMirror 5 mode for Rachana YAML: comments, keys, quoted strings, numbers, booleans and the
   Rachana-EL markers ($, @, #index). Written for Drishti; no external mode is loaded. */
(function () {
  'use strict';
  if (!window.CodeMirror) { return; }
  window.CodeMirror.defineMode('rachana-yaml', function () {
    return {
      startState: function () { return { inValue: false }; },
      token: function (stream, state) {
        if (stream.sol()) { state.inValue = false; }
        if (stream.eatSpace()) { return null; }
        if (stream.peek() === '#') { stream.skipToEnd(); return 'comment'; }
        if (stream.match(/^"(?:[^"\\]|\\.)*"?/) || stream.match(/^'(?:[^']|'')*'?/)) { return 'string'; }
        if (!state.inValue && stream.match(/^[\w.-]+(?=\s*:)/)) { return 'keyword'; }
        if (stream.match(/^:/)) { state.inValue = true; return 'operator'; }
        if (stream.match(/^[-[\]{},]/)) { return 'bracket'; }
        if (stream.match(/^(true|false|null)\b/)) { return 'atom'; }
        if (stream.match(/^-?\d+(\.\d+)?\b/)) { return 'number'; }
        if (stream.match(/^[$@][\w.[\]?-]*/) || stream.match(/^#index/)) { return 'variable-2'; }
        if (stream.match(/^F([1-9]|1[0-2])\b/)) { return 'def'; }
        if (stream.match(/^[A-Za-z_][\w-]*/)) { return null; }
        if (stream.match(/^[A-Za-z_][\w-]*/)) { return null; }
        stream.next();
        return null;
      },
      lineComment: '#'
    };
  });
})();
