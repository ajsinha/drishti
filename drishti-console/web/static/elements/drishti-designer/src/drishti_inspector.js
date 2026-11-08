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
/* The inspector: the properties of what is selected, as a form drawn from a JSON schema by drishti-inputs (labels, validation in
   words, conditional fields, arrays, the code editor for a formula). The host says which schema fits the selection (schemaFor) and
   what its values are (valuesFor); each edit is the host's at once ("change" with the path and the value), which it keeps as one step of
   its undo. With several items selected the inspector shows the fields they share, a field whose values differ left blank and marked
   "mixed"; an edit sets it on every one. A host gives the form more of Form's options (its own widgets, a layout) and its own header
   (what the selection is, its actions) beside the title. */
import { EventBus, h } from '../../drishti-core/src/drishti_index.js';
import { Form } from '../../drishti-inputs/src/drishti_index.js';

/**
 * The values several objects share: a key with one value in all of them keeps it; a key whose values differ is left out and named.
 * @param {Object[]} objs
 * @returns {{values: Object, mixed: string[]}}
 */
export function commonValues(objs) {
  const out = {}, mixed = [];
  if (!objs.length) { return { values: out, mixed }; }
  const keys = new Set();
  objs.forEach((o) => Object.keys(o || {}).forEach((k) => keys.add(k)));
  keys.forEach((k) => {
    const vals = objs.map((o) => JSON.stringify((o || {})[k]));
    if (vals.every((v) => v === vals[0])) { if (objs[0][k] !== undefined) { out[k] = JSON.parse(vals[0]); } } else { mixed.push(k); }
  });
  return { values: out, mixed };
}

/**
 * The fields of a schema that every schema in a list has, with the same type (the schema of a mixed selection).
 * @param {Object[]} schemas object schemas
 * @returns {?Object} an object schema, or null when nothing is shared
 */
export function sharedSchema(schemas) {
  if (!schemas.length) { return null; }
  if (schemas.length === 1) { return schemas[0]; }
  const first = schemas[0], props = {};
  Object.keys(first.properties || {}).forEach((k) => {
    const f = first.properties[k];
    if (schemas.every((s) => s.properties && s.properties[k] && s.properties[k].type === f.type)) { props[k] = f; }
  });
  if (!Object.keys(props).length) { return null; }
  return { type: 'object', title: schemas.length + ' items', properties: props, required: (first.required || []).filter((k) => props[k]) };
}

/**
 * @typedef {Object} InspectorOptions
 * @property {function(string[]): ?Object} schemaFor the object schema of the selected ids (null: nothing to inspect)
 * @property {function(string[]): Object} valuesFor the values of the selected ids
 * @property {string} [empty='Select an item to see its properties.'] what an empty selection says
 * @property {boolean} [readOnly=false]
 * @property {string} [locale]
 * @property {Object} [form] more options for the Form (widgets, layout, pathAttr: drishti-inputs' FormOptions)
 * @property {function(string[], Object): (?Node|Node[])} [head] the host's header for the selection (what it is, its actions), drawn
 *   in place of the schema's title
 */

/**
 * The inspector. Events: change {ids, path, value, values}.
 */
export class Inspector {
  /**
   * @param {HTMLElement} el
   * @param {InspectorOptions} o
   */
  constructor(el, o) {
    if (!o || typeof o.schemaFor !== 'function' || typeof o.valuesFor !== 'function') { throw new Error('an inspector needs schemaFor and valuesFor'); }
    this.el = el;
    this.doc = el.ownerDocument;
    this.o = Object.assign({ empty: 'Select an item to see its properties.' }, o);
    this.events = new EventBus();
    this.ids = [];
    this.form = null;
    el.classList.add('dd-inspector');
    this.head = h(this.doc, 'div', { cls: 'dd-inspector-head', attrs: { 'aria-live': 'polite' } });
    this.body = h(this.doc, 'div', { cls: 'dd-inspector-body' });
    el.append(this.head, this.body);
    this.show([]);
  }

  /**
   * Listens to one of its events; answers the function that stops listening.
   * @param {string} name
   * @param {function(Object): void} fn
   * @returns {function(): void}
   */
  on(name, fn) { return this.events.on(name, fn); }

  /**
   * Follows a selection (the inspector redraws when it changes).
   * @param {import('./drishti_selection.js').Selection} selection
   * @returns {function(): void} stops following
   */
  follow(selection) {
    this.show(selection.list());
    return selection.on('change', (s) => this.show(s.ids));
  }

  /**
   * Shows the properties of these ids.
   * @param {string[]} ids
   * @returns {void}
   */
  show(ids) {
    this.ids = (ids || []).slice();
    if (this.form) { this.form.destroy(); this.form = null; }
    this.body.textContent = '';
    const schema = this.ids.length ? this.o.schemaFor(this.ids) : null;
    if (!schema) {
      this.head.textContent = '';
      this.body.appendChild(h(this.doc, 'p', { cls: 'dd-inspector-empty', text: this.ids.length ? 'Nothing to set on this selection.' : this.o.empty }));
      return;
    }
    const values = this.o.valuesFor(this.ids) || {};
    this.mixed = values.__mixed || [];
    delete values.__mixed;
    this.head.textContent = this.ids.length > 1 ? this.ids.length + ' items selected' + (this.mixed.length ? ' · mixed: ' + this.mixed.join(', ') : '') : (schema.title || '');
    const own = this.o.head ? this.o.head(this.ids.slice(), schema) : null;
    if (own) { this.head.textContent = ''; this.head.append(...[].concat(own).filter(Boolean)); }
    const host = h(this.doc, 'div', { cls: 'dd-inspector-form' });
    this.body.appendChild(host);
    this.form = new Form(host, Object.assign({}, this.o.form, { schema: Object.assign({}, schema, { title: '' }), value: values, live: true, readOnly: !!this.o.readOnly, locale: this.o.locale }));
    const at = (this.o.form && this.o.form.pathAttr) || 'data-path';
    this.mixed.forEach((k) => { const w = host.querySelector('[' + at + '="' + k + '"]'); if (w) { w.setAttribute('data-dd-mixed', ''); } });
    this.form.on('change', (c) => { if (this.problem(c.path) === null) { this.events.emit('change', { ids: this.ids.slice(), path: c.path, value: c.value, values: c.values }); } });
  }

  /** @private a field's problem in words, or null */
  problem(path) { return this.form && this.form.errors && this.form.errors[path] ? this.form.errors[path] : null; }

  /**
   * Draws the same selection again with fresh values (an undo, a YAML edit), keeping the focus on its field.
   * @returns {void}
   */
  refresh() {
    if (!this.form || !this.ids.length) { this.show(this.ids); return; }
    const values = this.o.valuesFor(this.ids) || {};
    delete values.__mixed;
    if (JSON.stringify(values) !== JSON.stringify(this.form.getValue())) { this.form.setValue(values); }
  }

  /**
   * The problems of the fields shown, in words, by path.
   * @returns {Object<string, string>}
   */
  problems() { return this.form ? this.form.validate().errors : {}; }
}
