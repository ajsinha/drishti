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
/* YAML written from plain values (block maps and lists, keys in their order, strings quoted only when they must be, text over several
   lines as a | block), and the lines each path of a YAML text spans, read from its tree (drishti_yaml-read.js), so a designer can
   highlight the selected item's lines and say which item a line belongs to. A path is written as drishti-inputs writes one:
   pages[0].visuals[2].title. A host whose server writes the YAML uses only the ranges; one without a server writes it here. */
import { parse } from './drishti_yaml-read.js';

const PLAIN = /^[A-Za-z_/$@(][^\n#{}[\],"'`]*$/;
const RESERVED = /^(true|false|null|yes|no|on|off|~|-?\d+(\.\d+)?([eE][-+]?\d+)?)$/i;

/**
 * A scalar as YAML.
 * @param {*} v
 * @returns {string}
 */
export function yamlScalar(v) {
  if (v === null || v === undefined) { return 'null'; }
  if (typeof v === 'boolean' || typeof v === 'number') { return String(v); }
  const s = String(v);
  if (s && PLAIN.test(s) && !RESERVED.test(s) && !/\s$/.test(s) && s.indexOf(': ') < 0 && !/:$/.test(s)) { return s; }
  return JSON.stringify(s);
}

/**
 * Plain values as block YAML.
 * @param {*} value an object, a list or a scalar
 * @param {{indent: number}} [o] spaces per level (default 2)
 * @returns {string} the text, ending with a new line
 */
export function dumpYaml(value, o) {
  const step = (o && o.indent) || 2, out = [];
  const pad = (n) => ' '.repeat(n);
  const isObj = (v) => v && typeof v === 'object' && !Array.isArray(v);
  const empty = (v) => (Array.isArray(v) && !v.length) || (isObj(v) && !Object.keys(v).length);
  const inline = (v) => (Array.isArray(v) ? '[]' : '{}');
  function block(s, ind) { s.replace(/\n$/, '').split('\n').forEach((l) => out.push(l ? pad(ind) + l : '')); }
  function map(obj, ind, firstPrefix) {
    Object.keys(obj).forEach((k, i) => {
      const lead = i === 0 && firstPrefix !== undefined ? firstPrefix : pad(ind), v = obj[k], key = yamlScalar(k);
      if (empty(v)) { out.push(lead + key + ': ' + inline(v)); }
      else if (isObj(v)) { out.push(lead + key + ':'); map(v, ind + step); }
      else if (Array.isArray(v)) { out.push(lead + key + ':'); seq(v, ind + step); }
      else if (typeof v === 'string' && v.indexOf('\n') >= 0) { out.push(lead + key + ': |' + (/\n$/.test(v) ? '' : '-')); block(v, ind + step); }
      else { out.push(lead + key + ': ' + yamlScalar(v)); }
    });
  }
  function seq(list, ind) {
    list.forEach((v) => {
      if (empty(v)) { out.push(pad(ind) + '- ' + inline(v)); }
      else if (isObj(v)) { map(v, ind + 2, pad(ind) + '- '); }
      else if (Array.isArray(v)) { out.push(pad(ind) + '-'); seq(v, ind + step); }
      else if (typeof v === 'string' && v.indexOf('\n') >= 0) { out.push(pad(ind) + '- |' + (/\n$/.test(v) ? '' : '-')); block(v, ind + step); }
      else { out.push(pad(ind) + '- ' + yamlScalar(v)); }
    });
  }
  if (isObj(value)) { if (empty(value)) { return '{}\n'; } map(value, 0); } else if (Array.isArray(value)) { if (!value.length) { return '[]\n'; } seq(value, 0); } else { out.push(yamlScalar(value)); }
  return out.join('\n') + '\n';
}

/**
 * The lines (0-based, first and last) each path of a YAML text spans, from its tree: every key of a map and every item of a list.
 * @param {string} text
 * @returns {Map<string, [number, number]>} path -> [first line, last line]
 */
export function yamlRanges(text) {
  const lines = String(text || '').split('\n'), root = parse(text), out = new Map();
  let last = lines.length - 1;
  while (last > 0 && !lines[last].trim()) { last--; }
  const startOf = (n) => (n && n.line !== undefined ? n.line : null);
  function walk(node, path, endLine) {
    if (!node || node.t === 'scalar') { return; }
    if (node.t === 'map') {
      node.entries.forEach((e, i) => {
        const next = node.entries[i + 1], to = next ? next.line - 1 : endLine, p = path ? path + '.' + e.key : String(e.key);
        out.set(p, [e.line, trimEnd(e.line, to)]);
        walk(e.value, p, to);
      });
    } else if (node.t === 'seq') {
      node.items.forEach((it, i) => {
        if (!it) { return; }
        const next = node.items.slice(i + 1).find((x) => x && startOf(x) !== null), to = next ? next.line - 1 : endLine, p = path + '[' + i + ']';
        out.set(p, [it.line, trimEnd(it.line, to)]);
        walk(it, p, to);
      });
    }
  }
  function trimEnd(from, to) { while (to > from && (!lines[to] || !lines[to].trim() || /^\s*#/.test(lines[to]))) { to--; } return to; }
  walk(root, '', last);
  return out;
}

/**
 * The deepest path whose lines hold a line (the item a click in the YAML belongs to), or ''.
 * @param {Map<string, [number, number]>} ranges from yamlRanges
 * @param {number} line 0-based
 * @param {function(string): boolean} [only] only paths this accepts (a designer's items: "pages[0].visuals[2]")
 * @returns {string}
 */
export function pathAtLine(ranges, line, only) {
  let best = '', size = Infinity;
  ranges.forEach((r, p) => { if (r[0] <= line && line <= r[1] && (!only || only(p)) && (r[1] - r[0] < size || (r[1] - r[0] === size && p.length > best.length))) { best = p; size = r[1] - r[0]; } });
  return best;
}
