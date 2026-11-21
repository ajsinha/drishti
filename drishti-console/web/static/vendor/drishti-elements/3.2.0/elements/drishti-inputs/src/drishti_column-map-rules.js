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
/* The rules of a column mapping, without a page (so a form's schema and the ColumnMap widget share them): which of the caller's columns
   a local column starts on (the same name, then the same name in any case or without separators), whether a column's type fits what a
   local column expects, and every problem of a mapping in words. A local column is {name, type, required, label}; a caller's column is
   {name, type} or a plain name. */

/**
 * @typedef {Object} LocalColumn
 * @property {string} name
 * @property {string} [type] string, number, integer, boolean, date, datetime or time
 * @property {boolean} [required]
 * @property {string} [label]
 */

/** @private reads a column given as a name */
export function asColumn(c) { return c !== null && typeof c === 'object' ? { name: String(c.name), type: c.type || '' } : { name: String(c), type: '' }; }

const loose = (s) => String(s).toLowerCase().replace(/[\s_\-.]+/g, '');

/**
 * Whether the type of a caller's column fits what a local column expects. Unknown on either side fits; a string accepts anything; a number
 * accepts an integer; a date-time accepts a date; nothing else crosses.
 * @param {string} want the local column's type
 * @param {string} have the caller's column's type
 * @returns {boolean}
 */
export function typesFit(want, have) {
  if (!want || !have || want === have || want === 'string') { return true; }
  if (want === 'number') { return have === 'integer'; }
  if (want === 'datetime') { return have === 'date'; }
  return false;
}

/**
 * The starting mapping: each local column on the caller's column of the same name; failing that of the same name in any case or without
 * spaces, underscores or hyphens (when exactly one column is such). Local columns with no match are left out.
 * @param {LocalColumn[]} targets
 * @param {Array<(string|{name: string, type: string})>} columns
 * @returns {Object<string, string>}
 */
export function identityMap(targets, columns) {
  const cols = (columns || []).map(asColumn), out = {};
  (targets || []).forEach((t) => {
    const exact = cols.find((c) => c.name === t.name);
    if (exact) { out[t.name] = exact.name; return; }
    const near = cols.filter((c) => loose(c.name) === loose(t.name));
    if (near.length === 1) { out[t.name] = near[0].name; }
  });
  return out;
}

/**
 * Every problem of a mapping, in words: a required local column not mapped, a column that is not the caller's, a type that does not
 * fit and (with unique) a column used twice.
 * @param {{targets: LocalColumn[], columns: Array, unique: boolean}} spec
 * @param {Object<string, string>} value local name to the caller's column
 * @returns {Array<{name: string, message: string}>}
 */
export function mapProblems(spec, value) {
  const cols = new Map((spec.columns || []).map(asColumn).map((c) => [c.name, c])), v = value || {}, out = [], used = new Map();
  (spec.targets || []).forEach((t) => {
    const name = t.label || t.name, got = v[t.name];
    if (got === undefined || got === null || got === '') {
      if (t.required) { out.push({ name: t.name, message: name + ' is required: pick the column that holds it' }); }
      return;
    }
    const c = cols.get(got);
    if (!c) { out.push({ name: t.name, message: name + ': "' + got + '" is not one of your columns' }); return; }
    if (!typesFit(t.type, c.type)) { out.push({ name: t.name, message: name + ' expects ' + (t.type === 'integer' ? 'a whole number' : 'a ' + t.type) + ', but ' + c.name + ' is ' + c.type }); }
    if (spec.unique) {
      if (used.has(got)) { out.push({ name: t.name, message: got + ' is already used for ' + used.get(got) }); } else { used.set(got, name); }
    }
  });
  return out;
}
