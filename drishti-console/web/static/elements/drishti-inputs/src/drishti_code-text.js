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
/* The code editor's text work, without a page (tested in Node): the word at the cursor and what completes it from the function
   catalogue (the name starting with it, then a part of a dotted or camel-case name, then containing it), and the line diff the diff view
   draws (Myers' O(ND) algorithm: the fewest lines deleted and inserted), paired side by side with line numbers. */

/**
 * The word at a cursor: letters, digits, _, . and a leading $ or @ (a Rachana path); from and to are its columns.
 * @param {string} line
 * @param {number} ch the cursor's column
 * @returns {{word: string, from: number, to: number}}
 */
export function wordAt(line, ch) {
  let a = ch, b = ch;
  while (a > 0 && /[\w.$@]/.test(line.charAt(a - 1))) { a--; }
  while (b < line.length && /[\w]/.test(line.charAt(b))) { b++; }
  return { word: line.slice(a, ch), from: a, to: b };
}

/**
 * An entry of the function catalogue (the server's: GET /api/v1/functions, or a page's own).
 * @typedef {Object} CatalogueEntry
 * @property {string} name
 * @property {string} [signature] "var(confidence, horizon)"
 * @property {string} [description]
 * @property {string} [kind] function, field, keyword, measure
 * @property {string} [insert] the text put in (default: the name, with "(" for a function)
 */

/**
 * What completes a word, best first (at most limit): the name starting with it, then a dotted or camel-case part starting with it,
 * then the name containing it; ties by name. The word is matched without case.
 * @param {CatalogueEntry[]} catalogue
 * @param {string} word
 * @param {number} [limit=50]
 * @returns {CatalogueEntry[]}
 */
export function completions(catalogue, word, limit) {
  const w = String(word || '').toLowerCase().replace(/^[$@]/, '');
  const scored = [];
  (catalogue || []).forEach((e) => {
    const n = e.name.toLowerCase().replace(/^[$@]/, '');
    let s = 0;
    if (!w) { s = 1; }
    else if (n === w) { s = 5; }
    else if (n.indexOf(w) === 0) { s = 4; }
    else if (e.name.split(/[._]|(?=[A-Z])/).some((p) => p.toLowerCase().indexOf(w) === 0)) { s = 3; }
    else if (n.indexOf(w) >= 0) { s = 2; }
    if (s) { scored.push({ e: e, s: s }); }
  });
  return scored.sort((a, b) => b.s - a.s || a.e.name.localeCompare(b.e.name)).slice(0, limit || 50).map((x) => x.e);
}

/**
 * The text an entry puts in: its insert, or its name with "()" for a function (the cursor goes between them: caret is its place).
 * @param {CatalogueEntry} e
 * @returns {{text: string, caret: number}}
 */
export function insertText(e) {
  if (e.insert) { const i = e.insert.indexOf('|'); return i >= 0 ? { text: e.insert.replace('|', ''), caret: i } : { text: e.insert, caret: e.insert.length }; }
  if ((e.kind || 'function') === 'function') { return { text: e.name + '()', caret: e.name.length + 1 }; }
  return { text: e.name, caret: e.name.length };
}

/**
 * The lines that changed from a to b (Myers): equal, delete (a line of a only) and insert (a line of b only), in order.
 * @param {string} a the original text
 * @param {string} b the new text
 * @returns {Array<{op: ('equal'|'delete'|'insert'), a: ?number, b: ?number, text: string}>} a and b are 1-based line numbers
 */
export function lineDiff(a, b) {
  const A = String(a).split('\n'), B = String(b).split('\n'), N = A.length, M = B.length, MAX = N + M;
  const V = new Map([[1, 0]]), trace = [];
  let found = false;
  for (let d = 0; d <= MAX && !found; d++) {
    trace.push(new Map(V));
    for (let k = -d; k <= d; k += 2) {
      let x = (k === -d || (k !== d && (V.get(k - 1) || 0) < (V.get(k + 1) || 0))) ? (V.get(k + 1) || 0) : (V.get(k - 1) || 0) + 1;
      let y = x - k;
      while (x < N && y < M && A[x] === B[y]) { x++; y++; }
      V.set(k, x);
      if (x >= N && y >= M) { found = true; break; }
    }
  }
  const out = [];
  let x = N, y = M;
  for (let d = trace.length - 1; d >= 0; d--) {
    const v = trace[d], k = x - y;
    const prevK = (k === -d || (k !== d && (v.get(k - 1) || 0) < (v.get(k + 1) || 0))) ? k + 1 : k - 1;
    const px = v.get(prevK) || 0, py = px - prevK;
    while (x > px && y > py) { out.push({ op: 'equal', a: x, b: y, text: A[x - 1] }); x--; y--; }
    if (d > 0) {
      if (x === px) { out.push({ op: 'insert', a: null, b: y, text: B[y - 1] }); y--; }
      else { out.push({ op: 'delete', a: x, b: null, text: A[x - 1] }); x--; }
    }
  }
  return out.reverse();
}

/**
 * A diff as side-by-side rows: equal lines on both sides, a run of deletions paired with the insertions after it (a changed line),
 * the rest alone. Each side {n, text, kind} or null.
 * @param {Array<{op: string, a: ?number, b: ?number, text: string}>} ops lineDiff's answer
 * @returns {Array<{left: ?{n: number, text: string, kind: string}, right: ?{n: number, text: string, kind: string}}>}
 */
export function sideBySide(ops) {
  const rows = [];
  for (let i = 0; i < ops.length;) {
    if (ops[i].op === 'equal') { rows.push({ left: { n: ops[i].a, text: ops[i].text, kind: 'equal' }, right: { n: ops[i].b, text: ops[i].text, kind: 'equal' } }); i++; continue; }
    const del = [], ins = [];
    while (i < ops.length && ops[i].op === 'delete') { del.push(ops[i++]); }
    while (i < ops.length && ops[i].op === 'insert') { ins.push(ops[i++]); }
    for (let k = 0; k < Math.max(del.length, ins.length); k++) {
      const l = del[k], r = ins[k], changed = l && r;
      rows.push({ left: l ? { n: l.a, text: l.text, kind: changed ? 'changed' : 'delete' } : null, right: r ? { n: r.b, text: r.text, kind: changed ? 'changed' : 'insert' } : null });
    }
  }
  return rows;
}

/**
 * How many lines a diff adds, removes and keeps.
 * @param {Array<{op: string}>} ops
 * @returns {{added: number, removed: number, same: number}}
 */
export function diffStats(ops) {
  const s = { added: 0, removed: 0, same: 0 };
  ops.forEach((o) => { if (o.op === 'insert') { s.added++; } else if (o.op === 'delete') { s.removed++; } else { s.same++; } });
  return s;
}
