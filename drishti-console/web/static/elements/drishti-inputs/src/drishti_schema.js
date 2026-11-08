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
/* Forms from a schema, the model (tested in Node): a JSON-schema-like description of the fields (string, number, integer, money, boolean,
   date, time, date and time, date range, enum, array, object), their defaults, the conditions that show a field (when: another field equals, is in, is more than,
   is true; all, any, not), and validation with messages in words ("Notional must be at least 1,000", "Pick at most 3 desks", "Maturity
   must be after the trade date"). A field that is hidden is neither validated nor part of the value. Changes between two values (the
   four-eyes proposal's diff) are listed by path. Paths read "legs[1].notional". */
import { formatNumber, formatDate, isoDate, parseNumberText } from '../../drishti-core/src/drishti_index.js';
import { parseTime, parseDateTime } from './drishti_time-picker.js';

/**
 * A field of a form schema.
 * @typedef {Object} FieldSchema
 * @property {('string'|'number'|'integer'|'money'|'boolean'|'date'|'time'|'datetime'|'daterange'|'array'|'object')} type money: a number
 *   shown grouped with its currency; time: "HH:MM"; datetime: "YYYY-MM-DDTHH:MM" in its timeZone
 * @property {string} [title] the label (default: the key in words)
 * @property {string} [description] help under the field
 * @property {*} [default]
 * @property {Array} [enum] the values allowed
 * @property {string[]} [enumLabels] words for each value
 * @property {number} [minimum]
 * @property {number} [maximum]
 * @property {number} [multipleOf]
 * @property {number} [minLength]
 * @property {number} [maxLength]
 * @property {string} [pattern] a regular expression the whole text must match
 * @property {string} [patternMessage] the words when it does not ("a book code such as RT-123")
 * @property {string} [format] number: a named display format; string: email, textarea, code
 * @property {string} [after] date: a field it must be after
 * @property {string} [before] date: a field it must be before
 * @property {Object} [when] the condition that shows it
 * @property {FieldSchema} [items] array: each item's schema
 * @property {number} [minItems]
 * @property {number} [maxItems]
 * @property {boolean} [uniqueItems]
 * @property {Object<string, FieldSchema>} [properties] object: its fields
 * @property {string[]} [required] object: the keys that must have a value
 * @property {string[]} [order] object: the order of its fields (default: as written)
 * @property {string} [widget] how it is drawn: text, textarea, select, radio, checkbox, switch, multiselect, combobox, tree, date, daterange, code
 * @property {Object} [options] the widget's options (a combobox's source, a tree's nodes, a code editor's mode)
 * @property {boolean} [readOnly]
 * @property {string} [placeholder]
 * @property {string} [currency] money: its currency code (USD), or the key of the field that holds it (a sibling, or "/path")
 * @property {string} [timeZone] datetime: the zone named beside it
 * @property {number} [stepMinutes] time, datetime: the minutes between the times offered (default 15)
 * @property {string} [name] the name a surrounding server form submits the value under (hidden inputs; a native control carries it)
 * @property {*} [nameValue] boolean with a name: what is submitted when it is on (default "true")
 * @property {(string|Object<string, string>)} [hook] data attributes on the field's control: "format-title" is data-format-title="";
 *   an object sets each attribute as given (the hooks tutorials and tests find a field by)
 * @property {string} [step] the step (of the form's steps) the field belongs to
 * @property {string} [dependsOn] enum lists: the field whose chosen values narrow the values offered (see parents)
 * @property {Object<string, string[]>} [parents] enum lists: for each value, the values of dependsOn it goes with
 * @property {string} [optionHook] checkboxes and radios: each option's control gets data-<optionHook>="<value>"
 * @property {boolean} [allowEmpty] select: false leaves out the empty choice
 * @property {string} [emptyLabel] select: the words of the empty choice
 * @property {boolean} [lazy] text and numbers: the value changes when the field is left or Enter is pressed, not at each key
 * @property {boolean} [noLabel] a custom widget that draws its own label
 * @property {string} [addLabel] an array of objects: the words of its Add button
 * @property {string} [parseMessage] the words when typed text does not read as the field's type (default: the form's own)
 */

/**
 * A key in words: tradeDate and TRADE_DATE read "Trade date".
 * @param {string} key
 * @returns {string}
 */
export function words(key) {
  const s = String(key).replace(/[_-]+/g, ' ').replace(/([a-z0-9])([A-Z])/g, '$1 $2').trim().toLowerCase();
  return s.charAt(0).toUpperCase() + s.slice(1);
}

/**
 * A path's parts: "legs[1].notional" is ['legs', 1, 'notional'].
 * @param {string} path
 * @returns {Array<(string|number)>}
 */
export function pathParts(path) {
  const out = [];
  String(path || '').replace(/([^.[\]]+)|\[(\d+)\]/g, (m, key, idx) => { out.push(idx !== undefined ? +idx : key); return m; });
  return out;
}

/**
 * The value at a path.
 * @param {Object} obj
 * @param {string} path
 * @returns {*}
 */
export function getPath(obj, path) { return pathParts(path).reduce((o, k) => (o === null || o === undefined ? undefined : o[k]), obj); }

/**
 * A copy of obj with the value at a path set (objects and arrays on the way are copied, never changed).
 * @param {Object} obj
 * @param {string} path
 * @param {*} value
 * @returns {Object}
 */
export function setPath(obj, path, value) {
  const parts = pathParts(path);
  const put = (o, i) => {
    const k = parts[i], base = Array.isArray(o) ? o.slice() : Object.assign({}, o);
    base[k] = i === parts.length - 1 ? value : put(o && o[k] !== undefined && o[k] !== null ? o[k] : (typeof parts[i + 1] === 'number' ? [] : {}), i + 1);
    return base;
  };
  return parts.length ? put(obj || {}, 0) : value;
}

/** @private the path of a sibling: a name relative to the field's object, or "/a.b" from the root */
function sibling(path, name) {
  if (name.charAt(0) === '/') { return name.slice(1); }
  const parts = String(path).split('.');
  parts.pop();
  return parts.concat([name]).join('.');
}

/**
 * Whether a condition holds for a field at a path: {field, equals | notEquals | in | notIn | gt | gte | lt | lte | truthy | falsy | empty},
 * {all: [...]}, {any: [...]}, {not: {...}}. field is a sibling's key, or "/path" from the root.
 * @param {Object} cond
 * @param {Object} values the whole form's value
 * @param {string} path the field the condition belongs to
 * @returns {boolean}
 */
export function conditionHolds(cond, values, path) {
  if (!cond) { return true; }
  if (cond.all) { return cond.all.every((c) => conditionHolds(c, values, path)); }
  if (cond.any) { return cond.any.some((c) => conditionHolds(c, values, path)); }
  if (cond.not) { return !conditionHolds(cond.not, values, path); }
  const v = getPath(values, sibling(path, cond.field));
  if ('equals' in cond) { return v === cond.equals; }
  if ('notEquals' in cond) { return v !== cond.notEquals; }
  if (cond.in) { return cond.in.indexOf(v) >= 0; }
  if (cond.notIn) { return cond.notIn.indexOf(v) < 0; }
  if ('gt' in cond) { return v > cond.gt; }
  if ('gte' in cond) { return v >= cond.gte; }
  if ('lt' in cond) { return v < cond.lt; }
  if ('lte' in cond) { return v <= cond.lte; }
  if (cond.truthy) { return !!v && !(Array.isArray(v) && !v.length); }
  if (cond.falsy) { return !v || (Array.isArray(v) && !v.length); }
  if (cond.empty) { return v === undefined || v === null || v === '' || (Array.isArray(v) && !v.length); }
  throw new Error('a condition needs equals, notEquals, in, notIn, gt, gte, lt, lte, truthy, falsy or empty: ' + JSON.stringify(cond));
}

/**
 * The fields of an object schema in order, each with its key, path, label and whether it is required.
 * @param {FieldSchema} schema an object schema
 * @param {string} [base] the object's path
 * @returns {Array<{key: string, path: string, field: FieldSchema, label: string, required: boolean}>}
 */
export function fieldsOf(schema, base) {
  const props = schema.properties || {}, req = schema.required || [];
  const keys = (schema.order || []).concat(Object.keys(props).filter((k) => (schema.order || []).indexOf(k) < 0));
  return keys.filter((k) => props[k]).map((k) => ({ key: k, path: base ? base + '.' + k : k, field: props[k], label: props[k].title || words(k), required: req.indexOf(k) >= 0 }));
}

/**
 * A schema's starting value: every default, objects filled in, arrays at their minItems.
 * @param {FieldSchema} schema
 * @returns {*}
 */
export function defaults(schema) {
  if (schema.default !== undefined) { return JSON.parse(JSON.stringify(schema.default)); }
  switch (schema.type) {
    case 'object': { const o = {}; fieldsOf(schema).forEach((f) => { const d = defaults(f.field); if (d !== undefined) { o[f.key] = d; } }); return o; }
    case 'array': return Array.from({ length: schema.minItems || 0 }, () => defaults(schema.items || { type: 'string' }));
    case 'boolean': return false;
    default: return undefined;
  }
}

function blank(v) { return v === undefined || v === null || v === '' || (Array.isArray(v) && !v.length) || (typeof v === 'number' && isNaN(v)); }
function num(v, field) { return field && field.format ? formatNumber(v, { decimals: /\d/.test(field.format) ? +field.format.replace(/\D/g, '') : undefined }) : formatNumber(v, { maxDecimals: 6 }); }
function listWords(xs) { return xs.length <= 1 ? xs.join('') : xs.slice(0, -1).join(', ') + ' or ' + xs[xs.length - 1]; }
function enumLabel(field, v) { const i = (field.enum || []).indexOf(v); return field.enumLabels && i >= 0 ? field.enumLabels[i] : String(v); }

/**
 * One field's problem in words, or null (its own rules; not the fields inside it).
 * @param {FieldSchema} field
 * @param {*} v
 * @param {{label: string, required: boolean, values: Object, path: string, labelOf: function(string): string}} c
 * @returns {?string}
 */
export function fieldProblem(field, v, c) {
  const L = c.label;
  if ((blank(v) && !(Array.isArray(v) && field.minItems)) || (field.type === 'boolean' && v === false && c.required && field.widget === 'checkbox')) {
    if (!c.required) { return null; }
    return field.type === 'array' || field.widget === 'multiselect' ? 'Pick at least one for ' + L.toLowerCase() : field.enum || field.widget === 'combobox' || field.widget === 'tree' ? 'Pick ' + L.toLowerCase() : L + ' is required';
  }
  if (field.enum && field.type !== 'array' && field.enum.indexOf(v) < 0) { return L + ' must be one of ' + listWords(field.enum.map((x) => enumLabel(field, x))); }
  switch (field.type) {
    case 'integer':
    case 'money':
    case 'number': {
      if (typeof v !== 'number' || isNaN(v)) { return L + ' must be a number'; }
      if (field.type === 'integer' && !Number.isInteger(v)) { return L + ' must be a whole number'; }
      if (field.minimum !== undefined && v < field.minimum) { return L + ' must be at least ' + num(field.minimum, field); }
      if (field.maximum !== undefined && v > field.maximum) { return L + ' must be at most ' + num(field.maximum, field); }
      if (field.exclusiveMinimum !== undefined && v <= field.exclusiveMinimum) { return L + ' must be more than ' + num(field.exclusiveMinimum, field); }
      if (field.multipleOf && Math.abs(v / field.multipleOf - Math.round(v / field.multipleOf)) > 1e-9) { return L + ' must be a multiple of ' + num(field.multipleOf, field); }
      return null;
    }
    case 'string': {
      const s = String(v);
      if (field.minLength && s.length < field.minLength) { return L + ' needs at least ' + field.minLength + ' characters'; }
      if (field.maxLength && s.length > field.maxLength) { return L + ' may have at most ' + field.maxLength + ' characters (it has ' + s.length + ')'; }
      if (field.pattern && !new RegExp('^(?:' + field.pattern + ')$').test(s)) { return L + ' must look like ' + (field.patternMessage || 'the pattern ' + field.pattern); }
      if (field.format === 'email' && !/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(s)) { return L + ' must be an email address'; }
      return null;
    }
    case 'date': {
      const d = isoDate(v);
      if (!d) { return L + ' must be a date'; }
      const cmp = (other, after) => {
        const p = sibling(c.path, other), o = isoDate(getPath(c.values, p));
        if (!o) { return null; }
        const ok = after ? d > o : d < o;
        return ok ? null : L + ' must be ' + (after ? 'after ' : 'before ') + (c.labelOf ? c.labelOf(p) : words(other)).toLowerCase() + ' (' + formatDate(o, 'd MMM yyyy') + ')';
      };
      if (field.minimum && d < field.minimum) { return L + ' must be on or after ' + formatDate(field.minimum, 'd MMM yyyy'); }
      if (field.maximum && d > field.maximum) { return L + ' must be on or before ' + formatDate(field.maximum, 'd MMM yyyy'); }
      return (field.after && cmp(field.after, true)) || (field.before && cmp(field.before, false)) || null;
    }
    case 'time': {
      const t = parseTime(v);
      if (!t) { return L + ' must be a time such as 14:05'; }
      if (field.minimum && t < field.minimum) { return L + ' must be at or after ' + field.minimum; }
      if (field.maximum && t > field.maximum) { return L + ' must be at or before ' + field.maximum; }
      return null;
    }
    case 'datetime': {
      const t = parseDateTime(v);
      if (!t) { return L + ' must be a date and a time such as 2026-12-31 17:00'; }
      if (field.minimum && t < field.minimum) { return L + ' must be on or after ' + field.minimum.replace('T', ' '); }
      if (field.maximum && t > field.maximum) { return L + ' must be on or before ' + field.maximum.replace('T', ' '); }
      for (const [other, after] of [[field.after, true], [field.before, false]]) {
        if (!other) { continue; }
        const p = sibling(c.path, other), o = parseDateTime(getPath(c.values, p)) || (isoDate(getPath(c.values, p)) ? isoDate(getPath(c.values, p)) + 'T00:00' : null);
        if (o && (after ? t <= o : t >= o)) { return L + ' must be ' + (after ? 'after ' : 'before ') + (c.labelOf ? c.labelOf(p) : words(other)).toLowerCase() + ' (' + o.replace('T', ' ') + ')'; }
      }
      return null;
    }
    case 'daterange': return v.from && v.to && v.from > v.to ? L + ': the start must not be after the end' : null;
    case 'array': {
      const n = v.length, what = L.toLowerCase();
      if (field.minItems && n < field.minItems) { return 'Pick at least ' + field.minItems + ' for ' + what + ' (' + n + ' now)'; }
      if (field.maxItems && n > field.maxItems) { return 'Pick at most ' + field.maxItems + ' for ' + what + ' (' + n + ' now)'; }
      if (field.uniqueItems && new Set(v.map((x) => JSON.stringify(x))).size !== n) { return L + ' has the same entry twice'; }
      if (field.items && field.items.enum) { const bad = v.filter((x) => field.items.enum.indexOf(x) < 0); if (bad.length) { return L + ': ' + bad.join(', ') + ' is not allowed'; } }
      return null;
    }
    default: return null;
  }
}

/**
 * A money field's currency: its fixed code, or what the field it names holds.
 * @param {FieldSchema} field
 * @param {Object} values the whole form's value
 * @param {string} path the money field's path
 * @returns {string}
 */
export function currencyOf(field, values, path) {
  const c = field.currency || '';
  if (/^[A-Z]{3}$/.test(c)) { return c; }
  const v = c ? getPath(values, sibling(path, c)) : null;
  return v ? String(v) : '';
}

/**
 * The values an enum list offers now: those whose parents include a value chosen in the field it depends on (all when none is chosen).
 * @param {FieldSchema} field
 * @param {Object} values
 * @param {string} path
 * @returns {Array}
 */
export function offeredValues(field, values, path) {
  const list = field.enum || (field.items && field.items.enum) || [];
  if (!field.dependsOn || !field.parents) { return list; }
  const chosen = getPath(values, sibling(path, field.dependsOn));
  const picked = Array.isArray(chosen) ? chosen.map(String) : chosen === undefined || chosen === null || chosen === '' ? [] : [String(chosen)];
  if (!picked.length) { return list; }
  return list.filter((v) => (field.parents[v] || []).some((p) => picked.indexOf(String(p)) >= 0));
}

/**
 * Validates a value against a schema: every shown field's problem, by path.
 * @param {FieldSchema} schema the form's object schema
 * @param {Object} values
 * @returns {{ok: boolean, errors: Object<string, string>}}
 */
export function validate(schema, values) {
  const errors = {}, labels = {};
  const label = (p) => labels[p] || words(String(p).split('.').pop());
  const visit = (sch, base) => {
    fieldsOf(sch, base).forEach((f) => {
      labels[f.path] = f.label;
      if (!conditionHolds(f.field.when, values, f.path)) { return; }
      const v = getPath(values, f.path);
      const why = fieldProblem(f.field, v, { label: f.label, required: f.required, values: values, path: f.path, labelOf: label });
      if (why) { errors[f.path] = why; }
      if (f.field.type === 'object' && v) { visit(f.field, f.path); }
      if (f.field.type === 'array' && f.field.items && f.field.items.type === 'object' && Array.isArray(v)) {
        v.forEach((item, i) => visit(f.field.items, f.path + '[' + i + ']'));
      }
    });
  };
  fieldsOf(schema).forEach((f) => { labels[f.path] = f.label; });
  visit(schema, '');
  return { ok: !Object.keys(errors).length, errors: errors };
}

/**
 * The value without the fields that are hidden (what a form submits).
 * @param {FieldSchema} schema
 * @param {Object} values
 * @returns {Object}
 */
export function visibleValue(schema, values) {
  const strip = (sch, v, base) => {
    if (!v || typeof v !== 'object') { return v; }
    const out = {};
    fieldsOf(sch, base).forEach((f) => {
      if (!conditionHolds(f.field.when, values, f.path) || v[f.key] === undefined) { return; }
      const x = v[f.key];
      if (f.field.type === 'object') { out[f.key] = strip(f.field, x, f.path); }
      else if (f.field.type === 'array' && f.field.items && f.field.items.type === 'object' && Array.isArray(x)) { out[f.key] = x.map((it, i) => strip(f.field.items, it, f.path + '[' + i + ']')); }
      else { out[f.key] = x; }
    });
    return out;
  };
  return strip(schema, values, '');
}

/**
 * What changed between two values, leaf by leaf (a four-eyes proposal's diff).
 * @param {*} before
 * @param {*} after
 * @param {string} [base]
 * @returns {Array<{path: string, old: *, value: *}>}
 */
export function changes(before, after, base) {
  const out = [], p = base || '';
  const isObj = (x) => x && typeof x === 'object' && !Array.isArray(x);
  if (isObj(before) || isObj(after)) {
    const keys = new Set(Object.keys(before || {}).concat(Object.keys(after || {})));
    keys.forEach((k) => out.push.apply(out, changes((before || {})[k], (after || {})[k], p ? p + '.' + k : k)));
    return out;
  }
  if (JSON.stringify(before) !== JSON.stringify(after)) { out.push({ path: p, old: before, value: after }); }
  return out;
}

/**
 * Reads typed text as a field's value: numbers with grouping, a true minus, k, m and bn, percentages; integers; booleans; dates (ISO).
 * Returns {value} or {error} in words.
 * @param {FieldSchema} field
 * @param {string} text
 * @param {string} [label]
 * @returns {{value: *, error: ?string}}
 */
export function parseField(field, text, label) {
  const t = String(text === undefined || text === null ? '' : text).trim(), L = label || field.title || 'The value';
  if (!t) { return { value: undefined, error: null }; }
  if (field.type === 'number' || field.type === 'integer' || field.type === 'money') {
    const v = parseNumberText(t);
    if (v === null) { return { value: undefined, error: L + ' must be a number, such as 1,250,000 or 1.25m' }; }
    return { value: v, error: null };
  }
  if (field.type === 'boolean') { return { value: /^(true|yes|y|1|on)$/i.test(t), error: null }; }
  if (field.type === 'date') { const d = isoDate(t); return d ? { value: d, error: null } : { value: undefined, error: L + ' must be a date such as 2026-11-06' }; }
  if (field.type === 'time') { const x = parseTime(t); return x ? { value: x, error: null } : { value: undefined, error: L + ' must be a time such as 14:05' }; }
  if (field.type === 'datetime') { const x = parseDateTime(t); return x ? { value: x, error: null } : { value: undefined, error: L + ' must be a date and a time such as 2026-12-31 17:00' }; }
  return { value: t, error: null };
}
