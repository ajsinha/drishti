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
/* The YAML assistant over a JSON Schema, without a page (moved from Sutra Studio): what completes the cursor's place (the keys valid
   there, an object's options by its kind when the schema says so with if/then and x-rachana-options, enum and boolean values, the
   grammar's functions with their signatures, field paths of a sample document after "$." and "@."), the light check of a document
   (unknown keys, missing required keys, options its kind does not take, values outside an enum, a whole number, a tab), and the help
   of the key under the cursor. The YamlPane hands these to drishti-inputs' CodeEditor as its completion and lint hooks. */
import { parseYamlTree, yamlContext, yamlChain, yamlGet } from './drishti_yaml-read.js';

/** The keys every object of a kind-switched schema has before its kind's options (Rachana's panels). */
const BASE_KEYS = ['id', 'kind', 'title', 'description', 'key', 'code', 'area', 'span', 'height', 'infer', 'columns', 'body'];

/** The argument names of Rachana's expression functions (their signatures in completion). */
export const RACHANA_FUNCTION_ARGS = Object.freeze( {
  abs: ['number'], coalesce: ['a', 'b', '…'], contains: ['textOrList', 'value'], first: ['list'], fmt: ['value', "'format'"],
  last: ['list'], link: ['id', "'kind'", 'label'], lower: ['text'], max: ['list'], min: ['list'], size: ['listOrText'],
  starts_with: ['text', "'prefix'"], ends_with: ['text', "'suffix'"], sum: ['list', "'field'"], upper: ['text'],
  least: ['a', 'b', '…'], greatest: ['a', 'b', '…'], round_up: ['number', 'places'], round_down: ['number', 'places'],
  'if': ['condition', 'then', 'else'], iferror: ['value', 'fallback'], left: ['text', 'n'], right: ['text', 'n'],
  concat: ['a', 'b', '…'], days_between: ['from', 'to'], yearfrac: ['from', 'to', "'basis'"], map: ['list', 'expr over @'],
  sort: ['list', 'key over @', 'desc'], reduce: ['list', 'init', 'expr over @ and @acc']
});
/** What each of Rachana's expression functions gives, in words. */
export const RACHANA_FUNCTION_HELP = Object.freeze( {
  abs: 'the absolute value', coalesce: 'the first argument that is not null', contains: 'true when the text or list holds the value',
  first: 'the first element of a list', fmt: 'the value formatted with a format (amount0, pct4, date, …)', last: 'the last element of a list',
  link: 'a link that opens the entity id (of a kind; with a label)', lower: 'lower-case text', max: 'the largest value', min: 'the smallest value',
  size: 'the number of elements (or characters)', starts_with: 'true when the text starts with the prefix', sum: 'the sum of a list (or of a field of its rows)',
  upper: 'upper-case text', ends_with: 'true when the text ends with the suffix', least: 'the smallest argument (numbers, or dates)',
  greatest: 'the largest argument (numbers, or dates)', round_up: 'rounded away from zero', round_down: 'rounded towards zero',
  'if': 'then when the condition holds, else else', iferror: 'the value, or the fallback when it fails', left: 'the first n characters',
  right: 'the last n characters', concat: 'the texts joined', days_between: 'calendar days from one date to another',
  yearfrac: 'the year fraction on a basis: ACT/360, ACT/365F, 30/360, 30E/360, ACT/ACT', map: 'each element transformed',
  sort: 'the elements ordered by a key', reduce: 'the list folded into one value'
});

/**
 * A JSON Schema read for the assistant: $refs resolved, the kinds of a kind-switched object ($defs.panel's allOf if/then) and the
 * grammar's functions (x-rachana-functions).
 */
export class YamlSchema {
  /**
   * @param {Object} raw the JSON Schema
   * @param {{switched: string, help: Object<string, string>, args: Object<string, string[]>}} [o] switched: the $defs entry whose
   *   options follow its kind (default 'panel'); help and args: words and argument names of the functions
   */
  constructor(raw, o) {
    this.raw = raw;
    this.o = Object.assign({ switched: 'panel', help: RACHANA_FUNCTION_HELP, args: RACHANA_FUNCTION_ARGS }, o);
    this.defs = raw.$defs || {};
    this.fns = raw['x-rachana-functions'] || {};
    const sw = this.defs[this.o.switched] || {}, byKind = {}, optionKinds = {};
    (sw.allOf || []).forEach((a) => {
      const k = a.if && a.if.properties && a.if.properties.kind && a.if.properties.kind.const;
      if (k && a.then) { byKind[k] = { required: a.then.required || [], options: a.then['x-rachana-options'] || [], props: a.then.properties || {} }; }
      if (a.then) { (a.then['x-rachana-options'] || []).forEach((x) => { optionKinds[x] = (optionKinds[x] || []).concat([k]); }); }
    });
    this.byKind = byKind;
    this.optionKinds = optionKinds;
    this.base = BASE_KEYS.filter((k) => sw.properties && sw.properties[k]);
  }

  /**
   * A node with its $refs followed.
   * @param {Object} n
   * @returns {?Object}
   */
  resolve(n) { let g = 0; while (n && n.$ref && g++ < 8) { n = this.defs[n.$ref.replace('#/$defs/', '')]; } return n || null; }

  /**
   * The schema at a key path ('[]' for a list's items).
   * @param {string[]} path
   * @returns {?Object}
   */
  at(path) { let n = this.resolve(this.raw); for (let i = 0; i < path.length && n; i++) { n = this.resolve(path[i] === '[]' ? n.items : (n.properties || {})[path[i]]); } return n; }

  /**
   * Whether a schema is the kind-switched object.
   * @param {Object} n
   * @returns {boolean} whether it is the kind-switched object
   */
  isSwitched(n) { return !!n && n === this.defs[this.o.switched]; }

  /**
   * The keys an object may hold (a kind-switched one: the common keys and its kind's options).
   * @param {Object} n
   * @param {string} [kind]
   * @returns {string[]}
   */
  keys(n, kind) {
    if (!n || !n.properties) { return []; }
    if (this.isSwitched(n) && kind && this.byKind[kind]) { return this.base.concat(this.byKind[kind].options.filter((x) => BASE_KEYS.indexOf(x) < 0)); }
    return Object.keys(n.properties);
  }

  /**
   * A key's schema inside an object's.
   * @param {Object} n
   * @param {string} key
   * @returns {?Object}
   */
  child(n, key) { return n && n.properties ? this.resolve(n.properties[key]) : null; }

  /**
   * Whether a value is an expression of the grammar.
   * @param {Object} n
   * @returns {boolean} whether its value is an expression of the grammar
   */
  isExpr(n) { return !!n && /Rachana-EL/.test(n.description || ''); }

  /**
   * A function's signature, optional arguments marked with ?.
   * @param {string} name
   * @returns {string}
   */
  signature(name) {
    const spec = this.fns[name] || { min: 1, max: 1 };
    let args = (this.o.args[name] || []).slice();
    if (args.indexOf('…') < 0) {
      args = args.slice(0, spec.max);
      while (args.length < Math.min(spec.max, 3)) { args.push('arg' + (args.length + 1)); }
      if (spec.max > args.length) { args.push('…'); }
    }
    return name + '(' + args.map((a, i) => (i >= spec.min && a !== '…' ? a + '?' : a)).join(', ') + ')';
  }

  /**
   * A key's meaning in words.
   * @param {Object} n
   * @param {string} key
   * @param {string} [kind]
   * @param {boolean} [inSwitched]
   * @returns {string}
   */
  describe(n, key, kind, inSwitched) {
    if (!n) { return ''; }
    const bits = [n.description || ''];
    if (n.enum) { bits.push('One of: ' + n.enum.slice(0, 16).join(', ') + (n.enum.length > 16 ? ', …' : '')); }
    if (n.type === 'boolean') { bits.push('true or false'); }
    if (n.type === 'integer' && n.minimum !== undefined) { bits.push('A whole number from ' + n.minimum + ' to ' + n.maximum); }
    if (inSwitched && this.optionKinds[key] && BASE_KEYS.indexOf(key) < 0) { bits.push('Option of: ' + this.optionKinds[key].join(', ') + (kind ? ' (this one is ' + kind + ')' : '')); }
    return bits.filter(Boolean).join(' · ');
  }
}

function walkPath(v, expr) {
  const re = /\.([A-Za-z_$][\w$-]*)|\[(\d+)\]/g;
  let m;
  while ((m = re.exec(expr)) && v !== null && v !== undefined) {
    if (m[2] !== undefined) { v = Array.isArray(v) ? v[+m[2]] : undefined; } else { if (Array.isArray(v)) { v = v[0]; } v = v !== null && typeof v === 'object' ? v[m[1]] : undefined; }
  }
  return v;
}
function sampleOf(v) {
  if (!Array.isArray(v)) { return v; }
  const o = {};
  v.slice(0, 25).forEach((e) => { if (e && typeof e === 'object' && !Array.isArray(e)) { Object.keys(e).forEach((k) => { if (!(k in o)) { o[k] = e[k]; } }); } });
  return Object.keys(o).length ? o : v[0];
}
function rowOf(nodes, doc, skipLast) {
  let maps = nodes.filter((n) => n && n.t === 'map');
  if (skipLast) { maps = maps.slice(0, -1); }
  for (let i = maps.length - 1; i >= 0; i--) {
    const src = yamlGet(maps[i], 'rows') || yamlGet(maps[i], 'each');
    if (src && src.t === 'scalar' && typeof src.v === 'string') {
      const m = src.v.trim().match(/^([$@])((?:\.[A-Za-z_$][\w$-]*|\[\d+\])*)$/);
      if (!m) { return undefined; }
      return sampleOf(walkPath(m[1] === '$' ? doc : rowOf(maps.slice(0, i), doc, false), m[2]));
    }
  }
  return doc;
}
function preview(v) {
  if (v === null) { return 'null'; }
  if (Array.isArray(v)) { return 'list [' + v.length + ']'; }
  if (typeof v === 'object') { return '{ ' + Object.keys(v).slice(0, 4).join(', ') + (Object.keys(v).length > 4 ? ', …' : '') + ' }'; }
  const s = JSON.stringify(v);
  return s.length > 40 ? s.slice(0, 39) + '…' : s;
}

/**
 * Where the cursor is, with its schema: the YAML context, the parsed tree, the chain of nodes, the map it is in and its kind.
 * @param {YamlSchema} schema
 * @param {string} text
 * @param {number} line 0-based
 * @param {number} ch
 * @returns {Object}
 */
export function yamlPlace(schema, text, line, ch) {
  const ls = text.split('\n'), c = yamlContext(ls, line, ch);
  c.tree = parseYamlTree(text);
  c.chain = yamlChain(c.tree, c.path || [], line);
  c.resolved = c.chain.length === (c.path || []).length + 1;
  c.map = c.resolved && c.chain[c.chain.length - 1].t === 'map' ? c.chain[c.chain.length - 1] : null;
  c.node = schema ? schema.at(c.path || []) : null;
  let kind = c.map && yamlGet(c.map, 'kind');
  if (c.flow && !kind) { const mk = (ls[line] || '').match(/[{,]\s*kind:\s*([\w-]+)/); kind = mk ? { v: mk[1] } : null; }
  c.kind = kind && typeof kind.v === 'string' ? kind.v : '';
  c.lines = ls;
  return c;
}

/**
 * What completes the cursor's place, as drishti-inputs' completion entries (each with from and to: the columns it replaces).
 * @param {YamlSchema} schema
 * @param {string} text
 * @param {number} line
 * @param {number} ch
 * @param {{doc: *, explicit: boolean}} [o] doc: the sample document "$." and "@." walk
 * @returns {Array<Object>}
 */
export function yamlCompletions(schema, text, line, ch, o) {
  o = o || {};
  if (!schema) { return []; }
  const c = yamlPlace(schema, text, line, ch);
  if (c.scalar || c.comment || !c.mode) { return []; }
  const cur = c.lines[line] || '';
  let to = ch;
  while (to < cur.length && /[\w-]/.test(cur[to])) { to++; }
  const p = (c.prefix || '').toLowerCase();
  if (c.mode === 'key') {
    if (!/^[\w-]*$/.test(c.prefix)) { return []; }
    const have = {};
    if (c.map) { c.map.entries.forEach((e) => { if (e.line !== line) { have[e.key] = true; } }); }
    if (c.flow) { (cur.match(/[{,]\s*([\w-]+)\s*:/g) || []).forEach((k) => { have[k.replace(/[{,:\s]/g, '')] = true; }); }
    const rest = cur.slice(to), colon = /^\s*:/.test(rest) ? rest.match(/^\s*:\s?/)[0].length : 0;
    return schema.keys(c.node, c.kind).filter((k) => !have[k] && k.toLowerCase().indexOf(p) === 0).map((k) => {
      const n = schema.child(c.node, k);
      return { name: k, insert: k + ': ', kind: 'key', description: n ? schema.describe(n, k, c.kind, schema.isSwitched(c.node)) : '', from: c.from, to: to + colon };
    });
  }
  const vs = c.mode === 'item' ? schema.resolve(c.node && c.node.items) : schema.child(c.node, c.key);
  if (!vs) { return []; }
  const values = vs.enum || (vs.const !== undefined ? [vs.const] : null) || (vs.type === 'boolean' ? [true, false] : null);
  if (values) {
    const vp = p.replace(/^["']/, '');
    return values.map(String).filter((v) => v.toLowerCase().indexOf(vp) === 0).map((v) => ({ name: v, insert: v, kind: 'value',
      description: c.key === 'kind' && schema.byKind[v] ? 'options: ' + (schema.byKind[v].options.join(', ') || 'none') : '', from: c.from, to }));
  }
  if (!schema.isExpr(vs)) { return []; }
  const tail = cur.slice(0, ch).slice(c.from), m = tail.match(/([$@])((?:\.[A-Za-z_$][\w$-]*|\[\d+\])*)\.([\w$-]*)$/);
  if (m) {
    if (o.doc === undefined) { return []; }
    const base = m[1] === '$' ? o.doc : rowOf(c.chain, o.doc, c.mode === 'value' && (c.key === 'rows' || c.key === 'each'));
    const v = walkPath(base, m[2]), start = ch - m[3].length, q = m[3].toLowerCase(), out = [];
    if (Array.isArray(v)) { const el = sampleOf(v); if (el && typeof el === 'object') { Object.keys(el).forEach((k) => out.push({ name: '[0].' + k, insert: '[0].' + k, kind: 'field', description: preview(el[k]), from: start - 1, to })); } }
    else if (v && typeof v === 'object') { Object.keys(v).forEach((k) => out.push({ name: k, insert: k, kind: 'field', description: preview(v[k]), from: start, to })); }
    return out.filter((it) => it.name.replace('[0].', '').toLowerCase().indexOf(q) === 0);
  }
  const w = tail.match(/(^|[^\w$@.])([A-Za-z_]\w*)$/), word = w ? w[2] : '';
  if (!word && !o.explicit && tail.trim()) { return []; }
  const items = Object.keys(schema.fns).filter((f) => !schema.fns[f].oldSpellingOf && f.toLowerCase().indexOf(word.toLowerCase()) === 0)
    .map((f) => ({ name: f, signature: schema.signature(f), description: schema.o.help[f] || '', kind: 'function', insert: f + '(|)', from: ch - word.length, to }));
  if (!word) { items.unshift({ name: '@.', insert: '@.', kind: 'field', description: 'the current row', from: ch, to }, { name: '$.', insert: '$.', kind: 'field', description: 'the document', from: ch, to }); }
  return items;
}

/**
 * The light check: a document's problems against the schema, as lint problems (1-based line; from and to columns).
 * @param {YamlSchema} schema
 * @param {string} text
 * @param {string[]} [validKinds] kinds a match may name besides the schema's
 * @returns {Array<{line: number, from: number, to: number, severity: string, message: string}>}
 */
export function yamlCheck(schema, text, validKinds) {
  const tree = parseYamlTree(text), out = [], tabbed = {};
  (tree.tabs || []).forEach((n) => { tabbed[n] = true; });
  const add = (node, len, msg) => out.push({ line: node.line + 1, from: node.ch || 0, to: (node.ch || 0) + Math.max(1, len), severity: 'error', message: msg });
  function visit(node, s, label, key) {
    s = schema.resolve(s);
    if (!s || !node) { return; }
    if (schema.isSwitched(s) && node.t === 'map' && yamlGet(node, 'use') && s['x-rachana-use']) { s = schema.resolve(s['x-rachana-use']); }
    if (s.const !== undefined && node.t === 'scalar' && node.v !== s.const) { add(node, String(node.raw).length, label + ' must be ' + s.const); }
    if (s.enum && node.t === 'scalar' && node.v !== '' && node.v !== null && s.enum.indexOf(node.v) < 0 && !(label === 'match.kind' && (validKinds || []).indexOf(node.v) >= 0) && !/\$\{/.test(String(node.v))) {
      add(node, String(node.raw).length, '"' + node.v + '" is not a ' + (key === 'kind' && label.indexOf('match') < 0 ? 'panel kind' : 'valid ' + key) + (s.enum.length <= 16 ? ' (' + s.enum.join(', ') + ')' : ''));
    }
    if (s.type === 'boolean' && node.t === 'scalar' && typeof node.v !== 'boolean') { add(node, String(node.raw).length, label + ' is true or false'); }
    if (s.type === 'integer' && node.t === 'scalar' && typeof node.v !== 'number') { add(node, String(node.raw).length, label + ' is a whole number'); }
    else if (s.type === 'integer' && node.t === 'scalar' && (node.v % 1 !== 0 || (s.minimum !== undefined && node.v < s.minimum) || (s.maximum !== undefined && node.v > s.maximum))) {
      add(node, String(node.raw).length, label + ' is a whole number' + (s.minimum !== undefined ? ' from ' + s.minimum + ' to ' + s.maximum : ''));
    }
    if (s.type === 'array' && node.t !== 'seq') { if (node.t !== 'scalar' || node.v !== '') { add(node, 1, label + ' is a list'); } return; }
    if (s.type === 'array' && s.maxItems && node.items.length > s.maxItems) { add(node, 1, label + ' holds at most ' + s.maxItems); }
    if (node.t === 'seq') { node.items.forEach((it, i) => visit(it, s.items, label + '[' + i + ']', key)); return; }
    if (!s.properties) { return; }
    if (node.t !== 'map') { if (s.type === 'object') { add(node, String(node.raw || '').length || 1, label + ' is a map of keys'); } return; }
    const kn = yamlGet(node, 'kind'), kind = schema.isSwitched(s) && kn && kn.t === 'scalar' ? kn.v : '';
    const allowed = schema.keys(s, schema.byKind[kind] ? kind : ''), present = {};
    node.entries.forEach((e) => {
      if (present[e.key] && !e.partial) { add(e, e.key.length, 'duplicate key "' + e.key + '"' + (label ? ' in ' + label : '')); }
      present[e.key] = true;
      if (e.partial && tabbed[e.line]) { return; }
      if (e.partial) { add(e, e.key.length, '"' + e.key + '" needs a colon: key: value'); return; }
      if ((s.additionalProperties === false || schema.isSwitched(s)) && allowed.indexOf(e.key) < 0) {
        add(e, e.key.length, s.properties[e.key] && kind ? '"' + e.key + '" is not an option of ' + kind + ' panels (it is of ' + (schema.optionKinds[e.key] || []).join(', ') + ')' : 'unknown key "' + e.key + '" in ' + (label || 'the document'));
        return;
      }
      visit(e.value, s.properties[e.key], (label ? label + '.' : '') + e.key, e.key);
    });
    let req = (s.required || []).slice();
    if (kind && schema.byKind[kind]) { req = req.concat(schema.byKind[kind].required); }
    if (key === 'body') { req = req.filter((r) => r !== 'id'); }
    req.forEach((r) => { if (!present[r]) { add(node, 1, (label || 'the document') + ' needs "' + r + '"' + (kind && schema.byKind[kind].required.indexOf(r) >= 0 ? ' (' + kind + ' panels)' : '')); } });
  }
  (tree.tabs || []).forEach((n) => out.push({ line: n + 1, from: 0, to: 1, severity: 'error', message: 'line ' + (n + 1) + ' is indented with a tab: YAML allows only spaces' }));
  visit(tree, schema.raw, '', '');
  return out;
}

/**
 * The help of the key (or function) under the cursor: {title, text}, or null.
 * @param {YamlSchema} schema
 * @param {string} text
 * @param {number} line
 * @param {number} ch
 * @returns {?{title: string, text: string}}
 */
export function yamlHelp(schema, text, line, ch) {
  if (!schema) { return null; }
  const c = yamlPlace(schema, text, line, ch), t = c.lines[line] || '';
  let a = ch, b = ch;
  while (a > 0 && /[\w-]/.test(t[a - 1])) { a--; }
  while (b < t.length && /[\w-]/.test(t[b])) { b++; }
  const word = t.slice(a, b), name = (k) => (c.path || []).concat([k]).join('.').replace(/\.\[\]/g, '[]');
  if (c.mode === 'key' || (c.mode === 'value' && /^\s*:/.test(t.slice(b)))) {
    const holder = c.mode === 'key' ? c.node : schema.child(c.node, c.key), kn = schema.child(holder, word);
    return kn ? { title: name(word), text: schema.describe(kn, word, c.kind, schema.isSwitched(holder)) || 'No description.' } : null;
  }
  if (c.mode === 'value' && schema.fns[word] && t[b] === '(') { return { title: schema.signature(word), text: schema.o.help[word] || '' }; }
  if (c.mode === 'value' && c.key) { const vn = schema.child(c.node, c.key); return vn ? { title: name(c.key), text: schema.describe(vn, c.key, c.kind, schema.isSwitched(c.node)) || 'No description.' } : null; }
  return null;
}
