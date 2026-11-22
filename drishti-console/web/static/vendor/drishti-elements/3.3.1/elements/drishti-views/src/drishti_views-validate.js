/*
 * Drishti Elements
 *
 * Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>.
 * All rights reserved.
 *
 * PROPRIETARY.
 *
 * This file is the proprietary property of Ashutosh Sinha.
 * Copying, use, modification or distribution of this file, via any medium,
 * is not permitted except with the express prior written permission of the
 * copyright holder.
 *
 * See the LICENSE file in the root of this repository.
 */
/* The ViewModel validator: the keywords the compact schema uses (drishti_views-schema.js, generated from the pinned copy of the
   specification) and nothing else, with no dependency, in the browser and in Node alike. It answers {ok, issues}; an issue is
   {level, code, path, message, fix?}. It runs when a view is loaded, never per patch.

   Normal mode follows the specification's rule for a consumer: an unknown field is ignored (a note: it is usually a misspelling) and an
   unknown word of a closed list is drawn as the painter's fallback (a warning). Strict mode, for a producer's own tests, makes both
   errors. Bounds the schema does not state (panels, strip cells, nesting) and the keys no object may carry are checked beside it. */
import { SCHEMA, CONTRACT } from './drishti_views-schema.js';

/** The bounds a view is held to whatever its schema says: panels, strip cells, nesting of tabs inside tabs. */
export const LIMITS = Object.freeze({ panels: 200, strip: 24, depth: 4, rows: 50000 });
/** Keys no object of a view or a frame may carry: they would reach an object's prototype. */
export const FORBIDDEN_KEYS = Object.freeze(['__proto__', 'constructor', 'prototype']);

const FORBIDDEN = new Set(FORBIDDEN_KEYS);
const DATE_TIME = /^\d{4}-\d{2}-\d{2}[Tt ]\d{2}:\d{2}(?::\d{2}(?:\.\d+)?)?(?:[Zz]|[+-]\d{2}:?\d{2})?$/;
const SOFT = { 'unknown-field': 'note', enum: 'warning', format: 'warning' };
const DEFS = SCHEMA.$defs;
const DEF_NODES = unionMembers(SCHEMA);
const MAJOR = String(CONTRACT).split('.')[0];
const patterns = new Map();

/**
 * @typedef {Object} ViewIssue
 * @property {('error'|'warning'|'note')} level an error makes the view invalid; a warning and a note are drawn past
 * @property {string} code what kind of problem: type, required, enum, const, unknown-field, any-of, one-of, pattern, minimum, maximum,
 *   max-items, max-length, format, view.version, key.forbidden, limit
 * @property {string} path where, as a path into the view ("panels[2].data.rows[0]"; "" is the view itself)
 * @property {string} message what is wrong, in words
 * @property {string} [fix] what to write instead
 */

/**
 * @typedef {Object} ValidateOptions
 * @property {boolean} [strict=false] unknown fields and unknown words of a closed list are errors (a producer's own tests)
 * @property {('view'|'frame')} [as='view'] what the value is: a ViewModel or a frame of its live stream
 * @property {{panels: number, strip: number, depth: number}} [limits] bounds other than LIMITS
 */

function typeOf(v) {
  if (v === null) { return 'null'; }
  if (Array.isArray(v)) { return 'array'; }
  const t = typeof v;
  if (t === 'number') { return Number.isInteger(v) ? 'integer' : (Number.isFinite(v) ? 'number' : 'not-a-number'); }
  return t;
}

function isType(v, t) {
  const have = typeOf(v);
  return have === t || (t === 'number' && have === 'integer');
}

function anyType(v, types) {
  for (let i = 0; i < types.length; i++) { if (isType(v, types[i])) { return true; } }
  return false;
}

function where(path) { return path || 'the view'; }

function nearest(word, list) {
  let best = null, score = 3;
  const w = String(word).toLowerCase();
  list.forEach((c) => {
    const d = distance(w, String(c).toLowerCase());
    if (d < score) { score = d; best = c; }
  });
  return best;
}

function distance(a, b) {
  if (Math.abs(a.length - b.length) > 2) { return 9; }
  let prev = Array.from({ length: b.length + 1 }, (_, i) => i);
  for (let i = 1; i <= a.length; i++) {
    const cur = [i];
    for (let j = 1; j <= b.length; j++) { cur[j] = Math.min(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + (a[i - 1] === b[j - 1] ? 0 : 1)); }
    prev = cur;
  }
  return prev[b.length];
}

function resolve(ref) {
  const name = ref.indexOf('#/$defs/') === 0 ? ref.slice(8) : null;
  const node = name !== null ? DEFS[name] : null;
  if (!node) { throw new Error('drishti-views: the schema refers to ' + ref + ', which it does not define'); }
  return node;
}

/** The JSON types a schema accepts at its own level (for one message when no branch of an anyOf fits). */
function typesOf(node, seen) {
  seen = seen || new Set();
  if (!node || seen.has(node)) { return []; }
  seen.add(node);
  const out = [];
  if (node.$ref) { out.push(...typesOf(resolve(node.$ref), seen)); }
  if (node.type) { out.push(...[].concat(node.type)); }
  if (node.const !== undefined) { out.push(typeOf(node.const)); }
  (node.anyOf || []).concat(node.oneOf || [], node.allOf || []).forEach((b) => out.push(...typesOf(b, seen)));
  if (node.properties && !node.type) { out.push('object'); }
  return [...new Set(out.map((t) => (t === 'integer' && out.indexOf('number') >= 0 ? 'number' : t)))];
}

const TAKES = new Map();

/** The definitions that are branches of a wide union (three or more): a value is often checked by the union and again by the one
    branch a rule picks (a panel's data by its kind), so their results are kept per object for the length of one validation. */
function unionMembers(schema) {
  const out = new Set();
  const walk = (n) => {
    if (Array.isArray(n)) { n.forEach(walk); return; }
    if (!n || typeof n !== 'object') { return; }
    if (Array.isArray(n.anyOf) && n.anyOf.length >= 3) {
      n.anyOf.forEach((b) => { if (b && b.$ref && b.$ref.indexOf('#/$defs/') === 0 && schema.$defs[b.$ref.slice(8)]) { out.add(schema.$defs[b.$ref.slice(8)]); } });
    }
    Object.keys(n).forEach((k) => walk(n[k]));
  };
  walk(schema);
  return out;
}

/** Whether a branch can take a value of a JSON type at all (its declared types; a branch that declares none takes anything). */
function takes(node, t) {
  let types = TAKES.get(node);
  if (types === undefined) { types = typesOf(node); TAKES.set(node, types); }
  return !types.length || types.indexOf(t) >= 0 || (t === 'integer' && types.indexOf('number') >= 0);
}

function words(types) {
  const a = types.map((t) => ({ object: 'an object', array: 'a list', string: 'text', integer: 'a whole number', number: 'a number', boolean: 'true or false', null: 'null' }[t] || t));
  return a.length > 1 ? a.slice(0, -1).join(', ') + ' or ' + a[a.length - 1] : (a[0] || 'nothing');
}

class Run {
  constructor(strict) { this.strict = !!strict; this.memo = new Map(); }

  push(out, code, path, message, fix) {
    const level = SOFT[code] && !this.strict ? SOFT[code] : 'error';
    const issue = { level: level, code: code, path: path, message: message };
    if (fix) { issue.fix = fix; }
    out.push(issue);
  }

  /** Checks a value against a schema node, appending issues to out. An object checked twice by one node at one place (a panel's
      data by the union of every record and by its kind's record) is checked once. */
  check(node, v, path, out) {
    if (node === true || !node) { return; }
    if (v !== null && typeof v === 'object' && DEF_NODES.has(node)) {
      let seen = this.memo.get(node);
      if (!seen) { seen = new WeakMap(); this.memo.set(node, seen); }
      const hit = seen.get(v);
      if (hit && hit.path === path) { out.push(...hit.issues); return; }
      const mine = [];
      this.checkNode(node, v, path, mine);
      seen.set(v, { path: path, issues: mine });
      out.push(...mine);
      return;
    }
    this.checkNode(node, v, path, out);
  }

  checkNode(node, v, path, out) {
    if (node.$ref) { this.check(resolve(node.$ref), v, path, out); }  // a definition: checked once per object and place
    if (node.type !== undefined) {
      const types = typeof node.type === 'string' ? null : node.type;
      if (types ? !anyType(v, types) : !isType(v, node.type)) {
        const want = types || [node.type];
        this.push(out, 'type', path, 'is ' + words([typeOf(v)]) + '; it should be ' + words(want), 'write ' + words(want));
        return;
      }
    }
    if (node.const !== undefined && v !== node.const) {
      this.push(out, 'const', path, 'is ' + JSON.stringify(v) + '; it must be ' + JSON.stringify(node.const), 'write ' + JSON.stringify(node.const));
    }
    if (node.enum && node.enum.indexOf(v) < 0) {
      const near = typeof v === 'string' ? nearest(v, node.enum) : null;
      this.push(out, 'enum', path, JSON.stringify(v) + ' is not one of ' + node.enum.join(', ') + (near ? '; did you mean "' + near + '"?' : ''),
        near ? 'write "' + near + '"' : 'write one of ' + node.enum.join(', '));
    }
    const t = typeof v;
    if (t === 'string') { if (node.maxLength !== undefined || node.pattern !== undefined || node.format !== undefined) { this.text(node, v, path, out); } } else if (t === 'number') {
      if (node.minimum !== undefined || node.maximum !== undefined) { this.number(node, v, path, out); }
    } else if (t === 'object' && v !== null) {
      if (Array.isArray(v)) { if (node.items !== undefined || node.maxItems !== undefined) { this.array(node, v, path, out); } } else if (node.properties || node.required || node.additionalProperties !== undefined || node.propertyNames) { this.object(node, v, path, out); }
    }
    if (node.allOf) { node.allOf.forEach((b) => this.check(b, v, path, out)); }
    if (node.anyOf) { this.branches(node.anyOf, v, path, out, false); }
    if (node.oneOf) { this.branches(node.oneOf, v, path, out, true); }
    if (node.if && node.then && this.passes(node.if, v, path)) { this.check(node.then, v, path, out); }
  }

  passes(node, v, path) {
    const probe = [];
    this.check(node, v, path, probe);
    return !probe.some((i) => i.level === 'error');
  }

  text(node, v, path, out) {
    if (node.maxLength !== undefined && [...v].length > node.maxLength) {
      this.push(out, 'max-length', path, 'has ' + [...v].length + ' characters; at most ' + node.maxLength + ' are allowed');
    }
    if (node.pattern !== undefined) {
      let re = patterns.get(node.pattern);
      if (!re) { re = new RegExp(node.pattern, 'u'); patterns.set(node.pattern, re); }
      if (!re.test(v)) { this.push(out, 'pattern', path, JSON.stringify(v) + ' does not have the form ' + node.pattern); }
    }
    if (node.format === 'date-time' && !DATE_TIME.test(v)) {
      this.push(out, 'format', path, JSON.stringify(v) + ' is not an ISO-8601 date and time', 'write it as 2026-11-19T09:30:00Z');
    }
  }

  number(node, v, path, out) {
    if (node.minimum !== undefined && v < node.minimum) { this.push(out, 'minimum', path, 'is ' + v + '; the least allowed is ' + node.minimum); }
    if (node.maximum !== undefined && v > node.maximum) { this.push(out, 'maximum', path, 'is ' + v + '; the most allowed is ' + node.maximum); }
  }

  array(node, v, path, out) {
    if (node.maxItems !== undefined && v.length > node.maxItems) {
      this.push(out, 'max-items', path, 'has ' + v.length + ' items; at most ' + node.maxItems + ' are allowed');
    }
    if (node.items && node.items !== true) {
      for (let i = 0; i < v.length; i++) { this.check(node.items, v[i], path + '[' + i + ']', out); }
    }
  }

  object(node, v, path, out) {
    const props = node.properties;
    if (node.required) {
      node.required.forEach((k) => {
        if (!Object.prototype.hasOwnProperty.call(v, k)) {
          this.push(out, 'required', path, 'has no "' + k + '"; it is required', 'add "' + k + '"');
        }
      });
    }
    const keys = Object.keys(v);
    for (let i = 0; i < keys.length; i++) {
      const k = keys[i], sub = path ? path + '.' + k : k;
      if (node.propertyNames) { this.check(node.propertyNames, k, sub, out); }
      if (props && Object.prototype.hasOwnProperty.call(props, k)) {
        this.check(props[k], v[k], sub, out);
      } else if (node.additionalProperties === false) {
        const near = props ? nearest(k, Object.keys(props)) : null;
        this.push(out, 'unknown-field', sub, '"' + k + '" is not a field the specification names here' + (near ? '; did you mean "' + near + '"?' : '') + ' (it is ignored)',
          near ? 'write "' + near + '"' : 'remove "' + k + '"');
      } else if (node.additionalProperties && node.additionalProperties !== true) {
        this.check(node.additionalProperties, v[k], sub, out);
      }
    }
  }

  /** anyOf and oneOf: the issues of the branch the value most plausibly meant. A branch is ranked by its unknown fields first (a
      record most of whose fields it does not name is the wrong record, even when its rules let them pass), then its errors, then the
      rest; a value of a type no branch takes is one message. oneOf also refuses a value that fits more than one branch exactly. */
  branches(list, v, path, out, one) {
    const t = typeOf(v), cands = list.filter((b) => takes(b, t));
    if (!cands.length) {
      const types = [...new Set([].concat(...list.map((b) => typesOf(b))))];
      this.push(out, 'type', path, 'is ' + words([t]) + '; it should be ' + words(types), 'write ' + words(types));
      return;
    }
    if (cands.length === 1 && !one) { this.check(cands[0], v, path, out); return; }
    const runs = cands.map((b) => { const o = []; this.check(b, v, path, o); return o; });
    const typed = runs.map((o, i) => (o.some((x) => x.path === path && x.code === 'type') ? -1 : i)).filter((i) => i >= 0);
    if (!typed.length) {
      const types = [...new Set([].concat(...list.map((b) => typesOf(b))))];
      this.push(out, 'type', path, 'is ' + words([t]) + '; it should be ' + words(types), 'write ' + words(types));
      return;
    }
    const score = (o) => o.reduce((n, x) => n + (x.code === 'unknown-field' ? 100 : (x.level === 'error' ? 10 : 1)), 0);
    if (one) {
      const exact = runs.filter((o) => o.length === 0).length;
      if (exact > 1) { this.push(out, 'one-of', path, 'fits ' + exact + ' of its ' + list.length + ' forms; it must fit exactly one'); return; }
    }
    let best = typed[0];
    typed.forEach((i) => { if (score(runs[i]) < score(runs[best])) { best = i; } });
    out.push(...runs[best]);
  }
}

function walkKeys(v, path, out, run) {
  if (Array.isArray(v)) { for (let i = 0; i < v.length; i++) { walkKeys(v[i], path + '[' + i + ']', out, run); } return; }
  if (v === null || typeof v !== 'object') { return; }
  const keys = Object.keys(v);
  if (Object.prototype.hasOwnProperty.call(v, '__proto__') && keys.indexOf('__proto__') < 0) { keys.push('__proto__'); }
  for (let i = 0; i < keys.length; i++) {
    const k = keys[i], sub = path ? path + '.' + k : k;
    if (FORBIDDEN.has(k)) {
      run.push(out, 'key.forbidden', sub, 'the key "' + k + '" is refused in a view (it would reach an object\'s prototype)', 'rename "' + k + '"');
      continue;
    }
    walkKeys(v[k], sub, out, run);
  }
}

function depthOf(panels) {
  let deepest = 0;
  (panels || []).forEach((p) => {
    if (!p || typeof p !== 'object' || p.kind !== 'tabs' || !p.data || !Array.isArray(p.data.tabs)) { return; }
    p.data.tabs.forEach((t) => { if (t && Array.isArray(t.panels)) { deepest = Math.max(deepest, 1 + depthOf(t.panels)); } });
  });
  return deepest;
}

function bounds(vm, limits, out, run) {
  if (Array.isArray(vm.panels) && vm.panels.length > limits.panels) {
    run.push(out, 'limit', 'panels', 'the view has ' + vm.panels.length + ' panels; at most ' + limits.panels + ' are drawn', 'split it into views linked to each other');
  }
  if (Array.isArray(vm.strip) && vm.strip.length > limits.strip) {
    run.push(out, 'limit', 'strip', 'the strip has ' + vm.strip.length + ' cells; at most ' + limits.strip + ' are drawn', 'move figures into a kv or metric panel');
  }
  if (vm.datasets && typeof vm.datasets === 'object' && !Array.isArray(vm.datasets)) {          // contract 1.1: inline rows are bounded
    Object.keys(vm.datasets).forEach((name) => {
      const d = vm.datasets[name], n = d && (Array.isArray(d.values) ? d.values.length : Array.isArray(d.rows) ? d.rows.length : 0);
      if (n > limits.rows) {
        run.push(out, 'limit', 'datasets.' + name, 'the dataset has ' + n + ' inline rows; at most ' + limits.rows + ' are drawn', 'send them as Arrow, pages or a server source');
      }
    });
  }
  const depth = depthOf(vm.panels);
  if (depth > limits.depth) {
    run.push(out, 'limit', 'panels', 'tabs are nested ' + depth + ' deep; at most ' + limits.depth + ' levels are drawn', 'flatten the inner tabs');
  }
  if (typeof vm.contract === 'string' && vm.contract.split('.')[0] !== MAJOR) {
    run.push(out, 'view.version', 'contract', 'this view speaks contract ' + vm.contract + '; this painter draws contract ' + MAJOR + '.x',
      'use a drishti-views that draws contract ' + vm.contract.split('.')[0] + '.x');
  }
}

/**
 * Validates a ViewModel (or, with as: 'frame', a frame of its live stream) against the specification: {ok, issues}. ok is false when
 * any issue is an error. Fast enough to run on every load of a view; never run per patch.
 * @param {*} value the parsed JSON
 * @param {ValidateOptions} [o]
 * @returns {{ok: boolean, issues: ViewIssue[]}}
 * @example validate(vm).issues.forEach((i) => console.warn(formatIssue(i)))
 */
export function validate(value, o) {
  o = o || {};
  const run = new Run(o.strict), out = [];
  const limits = Object.assign({}, LIMITS, o.limits || {});
  walkKeys(value, '', out, run);
  if (o.as === 'frame') {
    run.check(DEFS.Frame, value, '', out);
  } else {
    run.check(SCHEMA, value, '', out);
    if (value && typeof value === 'object' && !Array.isArray(value)) { bounds(value, limits, out, run); }
  }
  const seen = new Set(), issues = out.filter((i) => { const k = i.level + ' ' + i.code + ' ' + i.path; if (seen.has(k)) { return false; } seen.add(k); return true; });
  return { ok: !issues.some((i) => i.level === 'error'), issues: issues };
}

/**
 * One issue as a line: its path, what is wrong and what to write instead.
 * @param {ViewIssue} issue
 * @returns {string}
 */
export function formatIssue(issue) {
  return where(issue.path) + ': ' + issue.message + (issue.fix ? ' (' + issue.fix + ')' : '') + ' [' + issue.level + ' ' + issue.code + ']';
}
