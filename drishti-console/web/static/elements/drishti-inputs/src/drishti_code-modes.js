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
/* The code editor's languages: Rachana expressions (functions, fields in brackets, $paths and @names, strings, numbers, operators,
   and, or, not, if, then, else, true, false, null, comments after //), Rachana YAML (keys, the Rachana-EL markers, F1 to F12) and
   Python (CodeMirror's vendored mode, loaded when asked). A mode here is a CodeMirror 5 mode object (startState and token over a
   StringStream); MiniStream is a stream of one line with the same methods, so tokens() highlights text without CodeMirror (the diff
   view, a read-only snippet) and the modes are tested in Node. */

const RACHANA_WORDS = /^(and|or|not|if|then|else|in|is|between|case|when|end)\b/i;
const ATOMS = /^(true|false|null)\b/i;

/**
 * The Rachana expression mode.
 * @returns {{startState: function(): Object, token: function(Object, Object): ?string, lineComment: string}}
 */
export function rachanaMode() {
  return {
    startState: () => ({ inString: null }),
    token: (stream, state) => {
      if (state.inString) {
        while (!stream.eol()) { const ch = stream.next(); if (ch === '\\') { stream.next(); } else if (ch === state.inString) { state.inString = null; break; } }
        return 'string';
      }
      if (stream.eatSpace()) { return null; }
      if (stream.match('//')) { stream.skipToEnd(); return 'comment'; }
      const ch = stream.peek();
      if (ch === '"' || ch === '\'') { stream.next(); state.inString = ch; return rachanaMode().token(stream, state) || 'string'; }
      if (stream.match(/^\[[^\]]*\]?/)) { return 'variable-2'; }
      if (stream.match(/^[$@][\w.[\]?-]*/)) { return 'variable-2'; }
      if (stream.match(/^-?(\d+\.?\d*|\.\d+)(e[-+]?\d+)?(%|bp|k|m|bn)?\b/i)) { return 'number'; }
      if (stream.match(ATOMS)) { return 'atom'; }
      if (stream.match(/^[A-Za-z_][\w.]*(?=\s*\()/)) { return 'builtin'; }
      if (stream.match(RACHANA_WORDS)) { return 'keyword'; }
      if (stream.match(/^[A-Za-z_][\w.]*/)) { return 'variable'; }
      if (stream.match(/^(<=|>=|<>|!=|==|&&|\|\||[-+*/^%=<>!])/)) { return 'operator'; }
      if (stream.match(/^[()[\]{},]/)) { return 'bracket'; }
      stream.next();
      return 'error';
    },
    lineComment: '//'
  };
}

/**
 * The Rachana YAML mode: comments, keys, quoted strings, numbers, booleans, the Rachana-EL markers ($, @, #index), F1 to F12.
 * @returns {{startState: function(): Object, token: function(Object, Object): ?string, lineComment: string}}
 */
export function yamlMode() {
  return {
    startState: () => ({ inValue: false }),
    token: (stream, state) => {
      if (stream.sol()) { state.inValue = false; }
      if (stream.eatSpace()) { return null; }
      if (stream.peek() === '#' && !stream.match(/^#index/, false)) { stream.skipToEnd(); return 'comment'; }
      if (stream.match(/^"(?:[^"\\]|\\.)*"?/) || stream.match(/^'(?:[^']|'')*'?/)) { return 'string'; }
      if (!state.inValue && stream.match(/^[\w.-]+(?=\s*:)/)) { return 'keyword'; }
      if (stream.match(/^:/)) { state.inValue = true; return 'operator'; }
      if (stream.match(/^[-[\]{},|>]/)) { return 'bracket'; }
      if (stream.match(/^(true|false|null|yes|no)\b/)) { return 'atom'; }
      if (stream.match(/^-?\d+(\.\d+)?\b/)) { return 'number'; }
      if (stream.match(/^[$@][\w.[\]?-]*/) || stream.match(/^#index/)) { return 'variable-2'; }
      if (stream.match(/^F([1-9]|1[0-2])\b/)) { return 'def'; }
      if (stream.match(/^[A-Za-z_][\w-]*/)) { return null; }
      stream.next();
      return null;
    },
    lineComment: '#'
  };
}

/** The languages by name: the CodeMirror mode each is, and whether it is ours (defined here) or vendored (loaded). */
export const MODES = Object.freeze({
  rachana: { cm: 'drishti-rachana', ours: rachanaMode },
  yaml: { cm: 'rachana-yaml', ours: yamlMode },
  python: { cm: 'python', vendor: 'vendor/codemirror/mode/python/python.js' },
  text: { cm: null }
});

/**
 * Defines our modes on a CodeMirror (once each).
 * @param {Object} CM the CodeMirror global
 * @returns {void}
 */
export function defineModes(CM) {
  Object.keys(MODES).forEach((k) => { const m = MODES[k]; if (m.ours && !(CM.modes && CM.modes[m.cm] && CM.modes[m.cm].drishti)) { const f = () => m.ours(); f.drishti = true; CM.defineMode(m.cm, f); CM.modes[m.cm].drishti = true; } });
}

/**
 * A stream over one line with CodeMirror's StringStream methods (what a mode's token reads).
 */
export class MiniStream {
  /**
   * @param {string} line
   */
  constructor(line) { this.string = line; this.pos = 0; this.start = 0; }

  /**
   * At the line's start.
   * @returns {boolean}
   */
  sol() { return this.pos === 0; }

  /**
   * At the line's end.
   * @returns {boolean}
   */
  eol() { return this.pos >= this.string.length; }

  /**
   * The next character, not taken.
   * @returns {?string}
   */
  peek() { return this.string.charAt(this.pos) || undefined; }

  /**
   * Takes the next character.
   * @returns {?string}
   */
  next() { return this.pos < this.string.length ? this.string.charAt(this.pos++) : undefined; }

  /**
   * Takes white space.
   * @returns {boolean} whether any was taken
   */
  eatSpace() { const s = this.pos; while (/[\s ]/.test(this.string.charAt(this.pos)) && this.pos < this.string.length) { this.pos++; } return this.pos > s; }

  /**
   * Takes the rest of the line.
   * @returns {void}
   */
  skipToEnd() { this.pos = this.string.length; }

  /**
   * Takes text that matches (a string or a regular expression anchored with ^), unless consume is false.
   * @param {(string|RegExp)} pattern
   * @param {boolean} [consume=true]
   * @returns {*} the match, or false/null
   */
  match(pattern, consume) {
    if (typeof pattern === 'string') {
      const ok = this.string.substr(this.pos, pattern.length) === pattern;
      if (ok && consume !== false) { this.pos += pattern.length; }
      return ok;
    }
    const m = this.string.slice(this.pos).match(pattern);
    if (m && m.index > 0) { return null; }
    if (m && consume !== false) { this.pos += m[0].length; }
    return m;
  }

  /**
   * The text taken since the token started.
   * @returns {string}
   */
  current() { return this.string.slice(this.start, this.pos); }
}

/**
 * Text as tokens by one of our modes, line by line: [{text, style}] per line (style null for plain text).
 * @param {string} lang rachana or yaml (python and text come back plain)
 * @param {string} text
 * @returns {Array<Array<{text: string, style: ?string}>>}
 */
export function tokens(lang, text) {
  const m = MODES[lang] && MODES[lang].ours ? MODES[lang].ours() : null;
  const state = m ? m.startState() : null;
  return String(text).split('\n').map((line) => {
    if (!m) { return [{ text: line, style: null }]; }
    const s = new MiniStream(line), out = [];
    let guard = 0;
    while (!s.eol() && guard++ < 10000) {
      s.start = s.pos;
      const style = m.token(s, state);
      if (s.pos === s.start) { s.next(); }
      const t = s.current(), last = out[out.length - 1];
      if (last && last.style === (style || null)) { last.text += t; } else { out.push({ text: t, style: style || null }); }
    }
    return out;
  });
}
