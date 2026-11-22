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
/* A table that maps a program's own columns onto the caller's: one row per local column (its name, its type and a mark when it is
   required), each with a select of the caller's columns. Local columns whose name matches a column start on it (the same name, then the
   same name in any case or without separators), and "Match by name" does that again; a row says in words what is wrong (a required
   column not mapped, a type that does not fit, a column used twice when options.unique). In a form: a field of type columnmap
   (options.targets, options.columns, options.unique); its value is {localName: callerColumn}. The rules are in
   drishti_column-map-rules.js. */
import { InputBase } from './drishti_inputs-dom.js';
import { Select } from './drishti_select.js';
import { identityMap, mapProblems, asColumn } from './drishti_column-map-rules.js';

/**
 * @typedef {Object} ColumnMapOptions
 * @property {Array<{name: string, type: string, required: boolean, label: string, description: string}>} targets the local columns, in order
 * @property {Array<(string|{name: string, type: string})>} columns the caller's columns
 * @property {Object<string, string>} [value] the mapping to start from; local columns left out start on a column of their name when there is one
 * @property {boolean} [autoMatch=true] start unmapped local columns on the column of the same name
 * @property {boolean} [unique=false] a caller's column may be used for one local column only
 * @property {boolean} [disabled=false]
 * @property {string} [label] names the table
 * @property {string} [emptyLabel='(not mapped)'] the select's words for no column
 */

/**
 * A column mapping. Events: change {value, problems}.
 */
export class ColumnMap extends InputBase {
  /**
   * @param {HTMLElement} host
   * @param {ColumnMapOptions} options
   */
  constructor(host, options) {
    super(host, Object.assign({ autoMatch: true, emptyLabel: '(not mapped)' }, options), 'di-colmap');
    const o = this.o;
    this.targets = (o.targets || []).map((t) => (typeof t === 'string' ? { name: t } : t));
    this.cols = (o.columns || []).map(asColumn);
    this.value = Object.assign({}, o.autoMatch ? identityMap(this.targets, this.cols) : {}, o.value || {});
    this.table = this.el('table', { cls: 'di-colmap-table', attrs: { 'aria-label': o.label || 'Column mapping' } });
    this.table.appendChild(this.el('thead', {}, [this.el('tr', {}, ['Column', 'Type', 'Your column'].map((t) => this.el('th', { text: t, attrs: { scope: 'col' } })))]));
    this.body = this.el('tbody');
    this.table.appendChild(this.body);
    this.summary = this.el('div', { cls: 'di-msg di-colmap-summary', attrs: { 'aria-live': 'polite' } });
    this.match = this.el('button', { cls: 'di-btn di-colmap-match', text: 'Match by name', attrs: { type: 'button' }, data: { act: 'match' }, on: { click: () => this.matchByName() } });
    host.append(this.el('div', { cls: 'di-colmap-tools' }, [this.match]), this.table, this.summary);
    this.selects = new Map();
    this.draw();
  }

  /** @private the options of one row's select: the empty choice and the columns */
  choices() {
    return [{ value: '', label: this.o.emptyLabel }].concat(this.cols.map((c) => ({ value: c.name, label: c.name, detail: c.type })));
  }

  /** @private draws every row */
  draw() {
    this.body.textContent = '';
    this.selects.clear();
    this.targets.forEach((t, i) => {
      const hostCell = this.el('div', { cls: 'di-colmap-select' });
      const sel = new Select(hostCell, { options: this.choices(), value: this.value[t.name] === undefined ? '' : this.value[t.name], label: (t.label || t.name) + ': your column', disabled: !!this.o.disabled });
      sel.control.id = this.uidOf(i);
      sel.on('change', (d) => { this.setOne(t.name, d.value); });
      this.selects.set(t.name, sel);
      const msg = this.el('div', { cls: 'di-msg', attrs: { id: this.uidOf(i) + '-msg', 'aria-live': 'polite' } });
      sel.control.setAttribute('aria-describedby', msg.id);
      const name = this.el('td', { cls: 'di-colmap-name' }, [this.el('label', { text: t.label || t.name, attrs: { for: sel.control.id } }),
        t.required ? this.el('span', { cls: 'di-req', text: ' *', attrs: { title: 'Required', 'aria-label': 'required' } }) : null]);
      if (t.description) { name.appendChild(this.el('div', { cls: 'di-help', text: t.description })); }
      this.body.appendChild(this.el('tr', { data: { local: t.name } }, [name, this.el('td', { cls: 'di-colmap-type', text: t.type || '' }), this.el('td', {}, [hostCell, msg])]));
    });
    this.check();
  }

  /** @private */
  uidOf(i) { return (this.idBase || (this.idBase = 'di-colmap-' + (++ColumnMap.n || (ColumnMap.n = 1)))) + '-' + i; }

  /** @private shows each row's problem and the count */
  check() {
    const bad = mapProblems(this.o, this.value), by = new Map(bad.map((b) => [b.name, b.message]));
    this.targets.forEach((t, i) => {
      const row = this.body.children[i], msg = row && row.querySelector('.di-msg'), sel = this.selects.get(t.name);
      if (!row) { return; }
      msg.textContent = by.get(t.name) || '';
      row.classList.toggle('di-invalid', by.has(t.name));
      if (sel) { sel.control.setAttribute('aria-invalid', by.has(t.name) ? 'true' : 'false'); }
    });
    const mapped = this.targets.filter((t) => this.value[t.name]).length;
    this.summary.textContent = bad.length ? bad.length + (bad.length === 1 ? ' problem' : ' problems') + ' in the mapping' : mapped + ' of ' + this.targets.length + ' mapped';
    this.summary.classList.toggle('di-colmap-ok', !bad.length);
    return bad;
  }

  /** @private */
  setOne(name, col) {
    if (col === '' || col === null || col === undefined) { delete this.value[name]; } else { this.value[name] = col; }
    this.emit('change', { value: this.getValue(), problems: this.check() });
  }

  /**
   * Puts every local column on the caller's column of its name, leaving the rest as they are.
   * @returns {void}
   */
  matchByName() {
    const m = identityMap(this.targets, this.cols);
    Object.keys(m).forEach((k) => { this.value[k] = m[k]; this.selects.get(k).setValue(m[k]); });
    this.emit('change', { value: this.getValue(), problems: this.check() });
  }

  /**
   * The mapping: {local column: caller's column}, without the unmapped.
   * @returns {Object<string, string>}
   */
  getValue() { return Object.assign({}, this.value); }

  /**
   * Sets the mapping (no event).
   * @param {Object<string, string>} v
   * @returns {void}
   */
  setValue(v) {
    this.value = Object.assign({}, v || {});
    this.targets.forEach((t) => this.selects.get(t.name).setValue(this.value[t.name] === undefined ? '' : this.value[t.name]));
    this.check();
  }

  /**
   * The problems of the mapping now, in words.
   * @returns {Array<{name: string, message: string}>}
   */
  problems() { return mapProblems(this.o, this.value); }

  /**
   * Replaces the caller's columns (a mapped column that is gone stays in the mapping and is reported).
   * @param {Array<(string|{name: string, type: string})>} columns
   * @returns {void}
   */
  setColumns(columns) { this.o.columns = columns; this.cols = (columns || []).map(asColumn); this.draw(); }
}
