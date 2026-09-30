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
/* A CodeMirror 5 mode for Markdown Sutras: Markdown outside the fences (headings, emphasis, code, links,
   lists, tables, quotes) and Rachana highlighting (the rachana-yaml mode) inside the ```sutra block.
   Other fenced blocks show as code. Written for Drishti; no external mode is loaded. */
(function () {
  'use strict';
  var CM = window.CodeMirror;
  if (!CM) { return; }
  CM.defineMode('sutra-markdown', function (config) {
    var yaml = CM.getMode(config, 'rachana-yaml');
    var OPEN = /^\s*(```|~~~)\s*([\w-]*)\s*$/;
    function inline(stream, state) {
      if (stream.match(/^`[^`]*`/)) { return 'string-2'; }
      if (stream.match(/^\*\*[^*]+\*\*/) || stream.match(/^__[^_]+__/)) { return 'strong'; }
      if (stream.match(/^\*[^*\s][^*]*\*/) || stream.match(/^_[^_\s][^_]*_/)) { return 'em'; }
      if (stream.match(/^!?\[[^\]]*\]\([^)]*\)/)) { return 'link'; }
      if (stream.match(/^<https?:[^>]+>/)) { return 'link'; }
      if (stream.match(/^\|/)) { return 'bracket'; }
      stream.next();
      stream.eatWhile(/[^`*_![<|]/);
      return state.heading ? 'header' : state.quote ? 'quote' : null;
    }
    return {
      startState: function () { return { fence: null, lang: '', inner: null, heading: false, quote: false }; },
      copyState: function (s) {
        return { fence: s.fence, lang: s.lang, inner: s.inner ? CM.copyState(yaml, s.inner) : null, heading: s.heading, quote: s.quote };
      },
      token: function (stream, state) {
        if (stream.sol()) {
          state.heading = false; state.quote = false;
          var m = stream.string.match(OPEN);
          if (state.fence === null && m) {
            state.fence = m[1]; state.lang = m[2];
            state.inner = m[2] === 'sutra' ? CM.startState(yaml) : null;
            stream.skipToEnd();
            return 'meta sutra-fence';
          }
          if (state.fence !== null && stream.string.trim() === state.fence) {
            state.fence = null; state.inner = null;
            stream.skipToEnd();
            return 'meta sutra-fence';
          }
        }
        if (state.fence !== null) {
          if (state.inner) { return yaml.token(stream, state.inner); }
          stream.skipToEnd();
          return 'string-2';
        }
        if (stream.sol()) {
          if (stream.match(/^#{1,6}\s/)) { state.heading = true; stream.skipToEnd(); return 'header'; }
          if (stream.match(/^\s*>\s?/)) { state.quote = true; return 'quote'; }
          if (stream.match(/^\s*([-*+]|\d+\.)\s/)) { return 'variable-3'; }
          if (stream.match(/^\s*\|?[\s:-]+\|[\s|:-]*$/)) { return 'bracket'; }
          if (stream.match(/^\s*(---|\*\*\*)\s*$/)) { return 'hr'; }
        }
        return inline(stream, state);
      },
      innerMode: function (state) { return state.inner ? { state: state.inner, mode: yaml } : { state: state, mode: this }; },
      blankLine: function (state) { state.heading = false; state.quote = false; }
    };
  });
})();
