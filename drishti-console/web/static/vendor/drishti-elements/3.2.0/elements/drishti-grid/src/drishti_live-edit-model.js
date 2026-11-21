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
/* The grid's live-mode edit, the logic (tested in Node; the screen is drishti_live-edit.js). What a live table lets the reader change comes
   from the table's schema, `write`: only its columns, each with its rules (type, required, min, max, pattern, options) and how a reason
   is asked (none, optional, required). No `write`: no edit control at all. The page checks the rules first (the host's words), makes a
   change with a ULID the retry reuses, and never changes the row itself: the change is pending (queued, held with its message, applied)
   until the table's patch arrives. The host's answers (409, 422, 403, 405, 404) are read here into what the screen shows. A masked cell
   ({"$masked": true}) is never editable. */
import { isMasked } from '../../drishti-core/src/drishti_index.js';

const CROCKFORD = '0123456789ABCDEFGHJKMNPQRSTVWXYZ';

/**
 * A ULID: 26 characters, a time (48 bits) then randomness (80 bits), sortable by creation.
 * @param {number} [now] milliseconds (default: now)
 * @param {function(): number} [rnd] a random number in [0, 1) (default: crypto, else Math.random)
 * @returns {string}
 */
export function ulid(now, rnd) {
  let t = now === undefined ? Date.now() : now, out = '';
  for (let i = 0; i < 10; i++) { out = CROCKFORD[t % 32] + out; t = Math.floor(t / 32); }
  const bytes = new Uint8Array(16);
  const c = typeof globalThis !== 'undefined' ? globalThis.crypto : null;
  if (!rnd && c && c.getRandomValues) { c.getRandomValues(bytes); } else { const r = rnd || Math.random; for (let i = 0; i < 16; i++) { bytes[i] = Math.floor(r() * 256); } }
  for (let i = 0; i < 16; i++) { out += CROCKFORD[bytes[i] % 32]; }
  return out;
}


/** @private an option's value and label */
function option(o) { return o !== null && typeof o === 'object' ? { value: o.value, label: String(o.label !== undefined ? o.label : o.value) } : { value: o, label: String(o) }; }

/**
 * @typedef {Object} WriteSpec
 * @property {Object<string, {type: string, required: boolean, min: ?number, max: ?number, pattern: ?string, options: ?Array<{value: *, label: string}>}>} columns
 * @property {('none'|'optional'|'required')} reason
 * @property {string[]} key the key columns
 * @property {?string} version the row version column (null: the held changes' tray, `basedOn` is "held")
 * @property {boolean} decides the tray: the column `state` takes released or rejected
 */

/**
 * What the reader may change, from the table's schema: null when the schema has no `write` (a read-only table: no edit control at all).
 * @param {?Object} schema the table's schema document
 * @returns {?WriteSpec}
 */
export function writeSpec(schema) {
  const w = schema && schema.write;
  if (!w || typeof w !== 'object' || !w.columns || typeof w.columns !== 'object') { return null; }
  const columns = {};
  Object.keys(w.columns).forEach((name) => {
    const r = w.columns[name] || {};
    columns[name] = { type: String(r.type || 'string'), required: r.required === true, min: typeof r.min === 'number' ? r.min : null, max: typeof r.max === 'number' ? r.max : null,
      pattern: typeof r.pattern === 'string' && r.pattern ? r.pattern : null, options: Array.isArray(r.options) && r.options.length ? r.options.map(option) : null };
  });
  if (!Object.keys(columns).length) { return null; }
  const st = columns.state, decides = !!(st && st.options && st.options.some((o) => o.value === 'released') && st.options.some((o) => o.value === 'rejected'));
  return { columns: columns, reason: w.reason === 'required' || w.reason === 'optional' ? w.reason : 'none', key: Array.isArray(schema.key) ? schema.key.slice() : [],
    version: typeof schema.rowVersion === 'string' ? schema.rowVersion : null, decides: decides };
}

/**
 * The editor a column's rule asks for: number, select, date, checkbox, else text.
 * @param {{type: string, options: ?Array}} rule
 * @returns {string}
 */
export function editorFor(rule) {
  if (rule.options) { return 'select'; }
  if (rule.type === 'number' || rule.type === 'integer' || rule.type === 'money') { return 'number'; }
  if (rule.type === 'date') { return 'date'; }
  if (rule.type === 'boolean') { return 'checkbox'; }
  return 'text';
}

/**
 * One value against one column's rules, in the host's words: null when fine.
 * @param {string} name the column
 * @param {{type: string, required: boolean, min: ?number, max: ?number, pattern: ?string, options: ?Array}} rule
 * @param {*} v
 * @returns {?string}
 */
export function checkRule(name, rule, v) {
  if (v === null || v === undefined || v === '') { return rule.required ? name + ' is required' : null; }
  if (rule.options && !rule.options.some((o) => o.value === v || String(o.value) === String(v))) { return name + ' is not one of ' + rule.options.map((o) => o.label).join(', '); }
  if (rule.type === 'number' || rule.type === 'integer' || rule.type === 'money') {
    if (typeof v !== 'number' || isNaN(v)) { return name + ' is not a number'; }
    if (rule.type === 'integer' && !Number.isInteger(v)) { return name + ' is not a whole number'; }
    if (rule.min !== null && v < rule.min) { return name + ' is at least ' + rule.min; }
    if (rule.max !== null && v > rule.max) { return name + ' is at most ' + rule.max; }
    return null;
  }
  if (rule.pattern && !new RegExp('^(?:' + rule.pattern + ')$').test(String(v))) { return name + ' does not match ' + rule.pattern; }
  return null;
}

/**
 * The reason a change needs: required by the table, or for a rejection in the tray (a decision to reject always says why).
 * @param {WriteSpec} spec
 * @param {Object} set
 * @returns {('none'|'optional'|'required')}
 */
export function reasonMode(spec, set) { return spec.decides && set && set.state === 'rejected' ? 'required' : spec.reason; }

/**
 * Every problem of a change before it is sent, by column: the rules of the columns set, and the reason. Nothing is sent when any.
 * @param {WriteSpec} spec
 * @param {Object} set the columns and their new values
 * @param {?string} reason
 * @returns {Object<string, string>} column to message ('reason' for the reason)
 */
export function checkChange(spec, set, reason) {
  const bad = {};
  Object.keys(set).forEach((name) => {
    const rule = spec.columns[name];
    if (!rule) { bad[name] = name + ' cannot be changed'; return; }
    const why = checkRule(name, rule, set[name]);
    if (why) { bad[name] = why; }
  });
  if (reasonMode(spec, set) === 'required' && !String(reason || '').trim()) { bad.reason = 'a reason is required'; }
  return bad;
}

/**
 * The change to send for one row: an update by key, on the version the row shows (the tray: "held").
 * @param {WriteSpec} spec
 * @param {Object} row
 * @param {Object} set
 * @param {?string} reason
 * @param {string} id the change's ULID (the same for a retry)
 * @returns {{id: string, op: string, key: Array, basedOn: *, set: Object, reason?: string}}
 */
export function buildChange(spec, row, set, reason, id) {
  const c = { id: id, op: 'update', key: spec.key.map((k) => row[k]), basedOn: spec.version ? row[spec.version] : 'held', set: Object.assign({}, set) };
  const r = String(reason || '').trim();
  if (r) { c.reason = r; }
  return c;
}

/**
 * Which columns of a row the reader may edit now: the write columns, none when the row is read only, never a masked cell.
 * @param {WriteSpec} spec
 * @param {Object} row
 * @param {string} column
 * @param {?string} lockedWhy the row's lock (403, 405), or null
 * @returns {boolean}
 */
export function mayEdit(spec, row, column, lockedWhy) {
  return !!spec && !lockedWhy && Object.prototype.hasOwnProperty.call(spec.columns, column) && !isMasked(row[column]) && !!row;
}

/** @private a refusal over a bridge has no HTTP status: its code says it */
const CODE_STATUS = { 'rdp.write.conflict': 409, 'rdp.write.invalid': 422, 'rdp.write.forbidden': 403, 'rdp.forbidden': 403, 'rdp.write.unsupported': 405, 'rdp.table.unknown': 404 };

/**
 * What a host's answer or refusal means for the screen.
 * @param {*} answer the write's answer ({change, state, message, version?}) or null
 * @param {*} error the DataError (status, error {code, message, fields, current}) or any error
 * @returns {{kind: string, message: string, fields: Array<{column: ?string, message: string}>, current: ?{version: *, by: *, at: *}, retry: boolean}}
 *   kind: queued, held, applied (the answer's states), refused, conflict (409), invalid (422), forbidden (403), unsupported (405), gone (404), failed;
 *   retry: the same change id may be sent again (the network failed, no answer was seen)
 */
export function outcome(answer, error) {
  if (!error) {
    const st = String((answer && answer.state) || 'queued'), kind = ['queued', 'held', 'applied', 'refused'].indexOf(st) >= 0 ? st : 'queued';
    return { kind: kind, message: String((answer && answer.message) || ''), fields: [], current: null, retry: false };
  }
  const e = error.error || {}, s = error.status || CODE_STATUS[e.code] || 0, msg = String(e.message || error.message || 'The change was not sent');
  const base = { message: msg, fields: Array.isArray(e.fields) ? e.fields.map((f) => ({ column: f.column || null, message: String(f.message || '') })) : [], current: e.current || null, retry: false };
  if (s === 409) { return Object.assign(base, { kind: 'conflict' }); }
  if (s === 422) { return Object.assign(base, { kind: 'invalid' }); }
  if (s === 403) { return Object.assign(base, { kind: 'forbidden' }); }
  if (s === 405) { return Object.assign(base, { kind: 'unsupported' }); }
  if (s === 404) { return Object.assign(base, { kind: 'gone' }); }
  return Object.assign(base, { kind: 'failed', retry: !s || s >= 500 });
}

/**
 * Who changed a row and when, in a sentence: the words of a 409.
 * @param {?{version: *, by: *, at: *}} current
 * @returns {string}
 */
export function changedWords(current) {
  if (!current) { return 'The row was changed by someone else'; }
  const at = String(current.at || '').replace('T', ' ').replace(/Z$/, '').slice(0, 19);
  return 'Changed by ' + (current.by || 'someone') + (at ? ' at ' + at : '');
}

/**
 * The state of the changes a reader sent that wait for the table's patch: by row, until a patch names the row (or the row goes).
 */
export class PendingChanges {
  /** Starts with nothing pending. */
  constructor() {
    this.rows = new Map();                  // row id -> {id, state, message, columns, change}
    this.attempts = new Map();              // the signature of a change -> the id it was sent with (a retry reuses it)
  }

  /**
   * The id to send a change with: the one a failed try used when the same change is sent again, else a new one.
   * @param {Array} key
   * @param {*} basedOn
   * @param {Object} set
   * @param {?string} reason
   * @returns {string}
   */
  idFor(key, basedOn, set, reason) {
    const sig = JSON.stringify([key, basedOn, set, reason || '']);
    let id = this.attempts.get(sig);
    if (!id) { id = ulid(); this.attempts.set(sig, id); }
    return id;
  }

  /**
   * A definitive answer was seen (not a network failure): the signature's id is not reused.
   * @param {Array} key
   * @param {*} basedOn
   * @param {Object} set
   * @param {?string} reason
   * @returns {void}
   */
  settled(key, basedOn, set, reason) { this.attempts.delete(JSON.stringify([key, basedOn, set, reason || ''])); }

  /**
   * Marks a row pending.
   * @param {string} rowId
   * @param {{id: string, state: string, message: string, columns: string[]}} p
   * @returns {void}
   */
  mark(rowId, p) { this.rows.set(rowId, p); }

  /**
   * The row's pending change, if any.
   * @param {string} rowId
   * @returns {?{id: string, state: string, message: string, columns: string[]}}
   */
  get(rowId) { return this.rows.get(rowId) || null; }

  /**
   * The row's patch arrived (or the row left): the mark goes.
   * @param {string} rowId
   * @returns {boolean} whether there was a mark
   */
  arrived(rowId) { return this.rows.delete(rowId); }

  /** @returns {number} the rows pending */
  get size() { return this.rows.size; }
}
