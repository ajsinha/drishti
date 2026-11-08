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
/* A form drawn from a schema (drishti_schema.js): each field by its type or widget (text, a number read loosely and shown formatted,
   a select or radios for an enum, a checkbox or switch, the business-date picker, the range picker, the multi-select, the combobox, the
   tree picker, the code editor, a fieldset for an object, repeatable groups for an array of objects with add, remove and move), the
   conditions that show and hide fields as people type, validation in words on each field once it was touched (and on every field at
   submit, the first problem focused and a summary at the top), the server's own checks (check hook) and a submit. With four eyes, the
   submit becomes a proposal: what changed, a comment and an approver; the hook files it and the form waits, read-only, with the
   proposal's number. Labels are real <label>s, problems are tied to their fields (aria-describedby, aria-invalid).
   Live (no <form> of its own, no buttons: a change per field, for property panes and parts of a server form), steps (a field's step;
   Back and Next check the step's fields), named fields a surrounding server form submits, dependent lists, and custom widgets: the
   drawing is drishti_form-fields.js. */
import { announce } from '../../drishti-core/src/drishti_index.js';
import { fieldsOf, defaults, getPath, setPath, conditionHolds, validate, visibleValue, changes, currencyOf, offeredValues } from './drishti_schema.js';
import { InputBase, uid } from './drishti_inputs-dom.js';
import { Combobox } from './drishti_combobox.js';
import { drawField, defaultWidget, writeNames } from './drishti_form-fields.js';

/**
 * @typedef {Object} FourEyes
 * @property {function({values: Object, changes: Array, comment: string, approver: *}): Promise<{id: string, status: string}>} submit files
 *   the proposal (the server's approvals); its answer is shown
 * @property {(Array|function(string, AbortSignal): Promise<Array>)} [approvers] who may approve (a list or a server search); none: any approver
 * @property {boolean} [requireComment=true]
 */

/**
 * @typedef {Object} FormOptions
 * @property {import('./drishti_schema.js').FieldSchema} schema an object schema
 * @property {Object} [value] the starting value (default: the schema's defaults)
 * @property {function(Object): (Promise<*>|*)} [onSubmit] the value, when it is valid (and there are no four eyes)
 * @property {function(Object): Promise<Object<string, string>>} [check] the server's checks: problems by path
 * @property {FourEyes} [fourEyes]
 * @property {string} [submitLabel='Save']
 * @property {boolean} [readOnly=false]
 * @property {('stacked'|'columns')} [layout='stacked']
 * @property {string} [locale] how numbers are read and shown
 * @property {boolean} [live=false] no Save or Reset: each change is the host's at once (a designer's inspector, a property pane)
 * @property {boolean} [embedded=false] no <form> element of its own (the fields sit inside a page's server form, which submits the fields
 *   that have a name); implies live
 * @property {string} [pathAttr='data-path'] the attribute each field's block carries its path in (a page whose own controls use
 *   data-path names another)
 * @property {boolean} [actions=true] the Save and Reset buttons (false: none, the page has its own)
 * @property {Array<{id: string, title: string, description: string}>} [steps] the form in steps: a field's step names one; Back and Next,
 *   Next checks the step's fields; the last step submits
 * @property {Object<string, import('./drishti_form-fields.js').WidgetFactory>} [widgets] custom widgets by name (a field's widget)
 * @property {string} [label] the form's name for a reader (default: the schema's title)
 * @property {string} [submitHook] a data attribute on the submit button ("lookup-go" is data-lookup-go), for tutorials and tests
 * @property {boolean} [resetButton=true] the Reset button
 */

/**
 * A form from a schema. Events: change {path, value, values}, invalid {errors}, submitted {values, result}, proposed {proposal, result},
 * reset, step {index, id}.
 */
export class Form extends InputBase {
  /**
   * @param {HTMLElement} host
   * @param {FormOptions} options
   */
  constructor(host, options) {
    super(host, options, 'di-form');
    if (!this.o.schema || this.o.schema.type !== 'object') { throw new Error('a form needs an object schema'); }
    this.schema = this.o.schema;
    this.initial = this.o.value ? JSON.parse(JSON.stringify(this.o.value)) : defaults(this.schema);
    this.values = JSON.parse(JSON.stringify(this.initial));
    this.touched = new Set();
    this.errors = {};
    this.server = {};
    this.notes = {};
    this.parts = new Map();
    this.state = this.o.readOnly ? 'readonly' : 'editing';
    this.id = uid('form');
    this.at = 0;
    if (this.o.embedded) { this.o.live = true; }
    this.pathAttr = this.o.pathAttr || 'data-path';
    this.form = this.el(this.o.embedded ? 'div' : 'form', { cls: 'di-form-el' + (this.o.layout === 'columns' ? ' di-columns' : '') + (this.o.live ? ' di-live' : ''),
      attrs: { novalidate: this.o.embedded ? null : true, role: this.o.embedded ? 'group' : null, 'aria-label': this.o.label || this.schema.title || 'Form' } });
    if (!this.o.embedded) { this.form.addEventListener('submit', (e) => { e.preventDefault(); this.submit(); }); }
    host.appendChild(this.form);
    this.render();
  }

  // ---- drawing ---------------------------------------------------------------------------------------------------------------------------
  /**
   * Draws the form again (after an array changed shape); the focus goes back to the field that had it.
   * @returns {void}
   */
  render() {
    const had = this.doc.activeElement && this.form.contains(this.doc.activeElement) ? this.doc.activeElement.closest('[' + this.pathAttr + ']') : null;
    const path = had ? had.getAttribute(this.pathAttr) : null;
    this.parts.forEach((p) => { if (p.widget && p.widget.destroy) { p.widget.destroy(); } });
    this.parts.clear();
    this.form.textContent = '';
    if (this.schema.title) { this.form.appendChild(this.el('h3', { cls: 'di-form-title', text: this.schema.title })); }
    if (this.schema.description) { this.form.appendChild(this.el('p', { cls: 'di-help', text: this.schema.description })); }
    this.summary = this.el('div', { cls: 'di-form-summary', attrs: { role: 'alert', hidden: true, tabindex: '-1' } });
    this.banner = this.el('div', { cls: 'di-form-banner', attrs: { role: 'status', hidden: true } });
    this.form.append(this.summary, this.banner);
    if (this.o.steps && this.o.steps.length) { this.form.appendChild(this.stepBar()); }
    const body = this.el('div', { cls: 'di-form-body' });
    fieldsOf(this.schema, '').forEach((f) => body.appendChild(this.field(f)));
    this.form.appendChild(body);
    this.form.appendChild(this.actions());
    this.refresh();
    if (path) { const at = '[' + this.pathAttr + '="' + path + '"] ', el = this.form.querySelector(at + 'input, ' + at + 'button, ' + at + 'select, ' + at + 'textarea'); if (el) { el.focus(); } }   // the focus stays where it was
  }

  /** @private the buttons (Back and Next in a form with steps) */
  actions() {
    const bar = this.el('div', { cls: 'di-form-actions' });
    this.submitBtn = null;
    if (this.state !== 'editing' || this.o.live || this.o.actions === false) { bar.hidden = true; return bar; }
    const steps = this.o.steps || [], last = !steps.length || this.at >= steps.length - 1;
    if (steps.length && this.at > 0) { bar.appendChild(this.el('button', { cls: 'di-btn', text: 'Back', attrs: { type: 'button' }, data: { act: 'back' }, on: { click: () => this.goTo(this.at - 1) } })); }
    if (!last) { bar.appendChild(this.el('button', { cls: 'di-btn di-primary', text: 'Next', attrs: { type: 'button' }, data: { act: 'next' }, on: { click: () => this.next() } })); }
    else {
      this.submitBtn = this.el('button', { cls: 'di-btn di-primary', text: this.o.fourEyes ? 'Submit for approval' : (this.o.submitLabel || 'Save'), attrs: { type: 'submit' }, data: { act: 'submit' } });
      if (this.o.submitHook) { this.submitBtn.setAttribute('data-' + this.o.submitHook, ''); }
      bar.appendChild(this.submitBtn);
    }
    if (this.o.resetButton !== false) { bar.appendChild(this.el('button', { cls: 'di-btn', text: 'Reset', attrs: { type: 'button' }, data: { act: 'reset' }, on: { click: () => this.reset() } })); }
    return bar;
  }

  /** @private the steps, the current one marked */
  stepBar() {
    const ol = this.el('ol', { cls: 'di-steps', attrs: { 'aria-label': 'Steps' } });
    this.o.steps.forEach((st, i) => ol.appendChild(this.el('li', { cls: 'di-step' + (i === this.at ? ' di-current' : i < this.at ? ' di-done' : ''), text: (i < this.at ? '✓ ' : '') + st.title,
      attrs: { 'aria-current': i === this.at ? 'step' : null }, data: { step: st.id } })));
    const cur = this.o.steps[this.at];
    return this.el('div', { cls: 'di-steps-box' }, [ol, cur && cur.description ? this.el('p', { cls: 'di-help', text: cur.description }) : null]);
  }

  /** @private the step a top-level path belongs to ('' when the form has no steps or the field names none) */
  stepOf(path) {
    if (!this.o.steps || !this.o.steps.length) { return ''; }
    const top = String(path).split(/[.[]/)[0], f = fieldsOf(this.schema, '').find((x) => x.key === top);
    return (f && f.field.step) || this.o.steps[0].id;
  }

  /**
   * The current step (its index), or 0.
   * @returns {number}
   */
  step() { return this.at; }

  /**
   * Goes to a step (Back always; forward only through Next, which checks the step's fields).
   * @param {number} i
   * @returns {void}
   */
  goTo(i) {
    const n = (this.o.steps || []).length;
    if (!n) { return; }
    this.at = Math.max(0, Math.min(n - 1, i));
    this.render();
    const first = this.form.querySelector('.di-form-field:not([hidden]) input, .di-form-field:not([hidden]) select, .di-form-field:not([hidden]) textarea');
    if (first) { first.focus(); }
    this.emit('step', { index: this.at, id: this.o.steps[this.at].id });
    announce('Step ' + (this.at + 1) + ' of ' + n + ': ' + this.o.steps[this.at].title);
  }

  /**
   * Checks the current step's fields and, when they hold, goes on.
   * @returns {boolean} whether it went on
   */
  next() {
    const id = this.o.steps[this.at].id;
    this.parts.forEach((p, path) => { if (this.stepOf(path) === id) { this.touched.add(path); } });
    this.refresh();
    const bad = Object.keys(this.errors).filter((p) => this.parts.has(p) && this.shown(p) && this.stepOf(p) === id);
    if (bad.length) { this.focusField(bad[0]); announce(bad.length + (bad.length === 1 ? ' problem: ' : ' problems: ') + this.errors[bad[0]]); return false; }
    this.goTo(this.at + 1);
    return true;
  }

  /** @private one field's block (drishti_form-fields.js draws it) */
  field(f) { return drawField(this, f); }

  /** @private a field's widget when the schema names none */
  defaultWidget(fs) { return defaultWidget(fs); }

  /** @private */
  addItem(path) {
    const fs = this.parts.get(path).f.field, list = (getPath(this.values, path) || []).concat([defaults(fs.items)]);
    this.change(path, list, true);
    announce('Added; ' + list.length + ' now');
  }

  /** @private */
  removeItem(path, i) { const list = (getPath(this.values, path) || []).slice(); list.splice(i, 1); this.change(path, list, true); announce('Removed; ' + list.length + ' left'); }

  /** @private */
  moveItem(path, i, d) { const list = (getPath(this.values, path) || []).slice(), x = list[i]; list.splice(i, 1); list.splice(i + d, 0, x); this.change(path, list, true); }

  // ---- state ----------------------------------------------------------------------------------------------------------------------------
  /** @private a field's value changed */
  change(path, value, redraw) {
    this.values = setPath(this.values, path, value);
    delete this.server[path];
    const dependents = this.narrow(path);                   // a dependent list follows what was chosen here: only it is drawn again
    if (redraw) { this.touched.add(path); this.render(); } else { dependents.forEach((q) => this.redrawField(q)); this.refresh(); }
    this.emit('change', { path: path, value: value, values: this.getValue() });
  }

  /** @private one field drawn again in its place (its widget taken out first) */
  redrawField(path) {
    const p = this.parts.get(path);
    if (!p || !p.wrap.parentNode) { return; }
    if (p.widget && p.widget.destroy) { p.widget.destroy(); }
    if (p.custom && p.custom.destroy) { p.custom.destroy(); }
    p.wrap.replaceWith(this.field(p.f));
  }

  /** @private the lists that depend on a path: their values narrowed to what they offer now; their paths */
  narrow(path) {
    const any = [];
    this.parts.forEach((p, q) => {
      const fs = p.f.field;
      if (!fs.dependsOn) { return; }
      const target = fs.dependsOn.charAt(0) === '/' ? fs.dependsOn.slice(1) : q.split('.').slice(0, -1).concat([fs.dependsOn]).join('.');
      if (target !== path) { return; }
      any.push(q);
      const offered = offeredValues(fs.items && fs.items.enum ? Object.assign({}, fs.items, { dependsOn: fs.dependsOn, parents: fs.parents }) : fs, this.values, q).map(String), cur = getPath(this.values, q);
      if (Array.isArray(cur)) { this.values = setPath(this.values, q, cur.filter((x) => offered.indexOf(String(x)) >= 0)); }
      else if (cur !== undefined && offered.indexOf(String(cur)) < 0) { this.values = setPath(this.values, q, undefined); }
    });
    return any;
  }

  /**
   * Shows and hides fields by their conditions, and shows each touched field's problem.
   * @returns {void}
   */
  refresh() {
    const res = validate(this.schema, this.values);
    this.errors = Object.assign({}, res.errors, this.server);
    this.parts.forEach((p, path) => {
      if (p.parseError) { this.errors[path] = p.parseError; }
      const shown = this.shown(path), inStep = !this.o.steps || !this.o.steps.length || this.stepOf(path) === this.o.steps[this.at].id;
      p.wrap.hidden = !shown || !inStep;
      writeNames(p, getPath(this.values, path), shown);
      if (p.ccy) { p.ccy.textContent = currencyOf(p.f.field, this.values, path) || '—'; }
      const why = shown && (this.touched.has(path) || this.tried) ? this.errors[path] : null;
      const msg = p.wrap.querySelector('#' + CSS_ESCAPE(p.id) + '-msg');
      const note = shown && !why ? this.notes[path] : null;
      if (msg) { msg.textContent = why || note || ''; msg.classList.toggle('di-note', !!note); }
      p.wrap.classList.toggle('di-invalid', !!why);
      p.wrap.classList.toggle('di-warned', !!note);
      const target = p.input || (p.widget && p.widget.input);
      if (target) { if (why) { target.setAttribute('aria-invalid', 'true'); } else { target.removeAttribute('aria-invalid'); } }
    });
    if (this.tried) { this.drawSummary(); }
  }

  /** @private whether a field and every field around it are shown */
  shown(path) {
    for (const [p, part] of this.parts) {
      if ((path === p || path.indexOf(p + '.') === 0 || path.indexOf(p + '[') === 0) && !conditionHolds(part.f.field.when, this.values, p)) { return false; }
    }
    return true;
  }

  /** @private the problems at the top, each a link to its field */
  drawSummary() {
    const shown = Object.keys(this.errors).filter((p) => this.parts.has(p) && this.shown(p));
    this.summary.textContent = '';
    this.summary.hidden = !shown.length;
    if (!shown.length) { return; }
    this.summary.appendChild(this.el('b', { text: shown.length === 1 ? 'One thing to fix:' : shown.length + ' things to fix:' }));
    const ul = this.el('ul');
    shown.forEach((p) => ul.appendChild(this.el('li', {}, [this.el('a', { text: this.errors[p], attrs: { href: '#' + this.parts.get(p).id }, on: { click: (e) => { e.preventDefault(); this.focusField(p); } } })])));
    this.summary.appendChild(ul);
  }

  /**
   * Puts the focus on a field.
   * @param {string} path
   * @returns {void}
   */
  focusField(path) {
    const p = this.parts.get(path);
    if (!p) { return; }
    const t = p.input || (p.widget && (p.widget.input || p.widget.button)) || p.wrap.querySelector('input, select, textarea, button');
    if (t) { t.focus(); }
  }

  /**
   * Validates every shown field (and shows every problem).
   * @returns {{ok: boolean, errors: Object<string, string>}}
   */
  validate() {
    this.tried = true;
    this.refresh();
    const errors = {};
    Object.keys(this.errors).forEach((p) => { if (!this.parts.has(p) || this.shown(p)) { errors[p] = this.errors[p]; } });
    return { ok: !Object.keys(errors).length, errors: errors };
  }

  /**
   * Submits: validates, asks the server's checks, then hands the value to onSubmit, or files a proposal when the form has four eyes.
   * @returns {Promise<boolean>} whether it was submitted (or proposed)
   */
  submit() {
    if (this.state !== 'editing') { return Promise.resolve(false); }
    const res = this.validate();
    if (!res.ok && this.o.steps && this.o.steps.length) {           // the first problem's step comes forward
      const first = Object.keys(res.errors)[0], at = this.o.steps.findIndex((x) => x.id === this.stepOf(first));
      if (at >= 0 && at !== this.at) { this.at = at; this.render(); this.tried = true; this.refresh(); }
    }
    if (!res.ok) { this.emit('invalid', { errors: res.errors }); this.summary.focus(); announce(Object.keys(res.errors).length + ' problems: ' + res.errors[Object.keys(res.errors)[0]]); return Promise.resolve(false); }
    const values = this.getValue();
    this.busy(true);
    return Promise.resolve(this.o.check ? this.o.check(values) : null).then((server) => {
      if (server && Object.keys(server).length) { this.server = server; this.busy(false); this.refresh(); this.emit('invalid', { errors: server }); this.summary.focus(); return false; }
      if (this.o.fourEyes) { this.busy(false); return this.propose(values); }
      return Promise.resolve(this.o.onSubmit ? this.o.onSubmit(values) : null).then((result) => {
        this.busy(false);
        this.initial = JSON.parse(JSON.stringify(this.values));
        this.say('Saved', 'ok');
        this.emit('submitted', { values: values, result: result });
        return true;
      });
    }).catch((err) => { this.busy(false); this.say('Could not save: ' + (err && err.message ? err.message : err), 'bad'); return false; });
  }

  /** @private the four-eyes step: what changed, a comment, an approver */
  propose(values) {
    const fe = this.o.fourEyes, diff = changes(visibleValue(this.schema, this.initial), values);
    return new Promise((resolve) => {
      const dlg = this.el('dialog', { cls: 'di-dialog di-four-eyes', attrs: { 'aria-label': 'Submit for approval' } });
      const list = this.el('ul', { cls: 'di-changes' });
      diff.forEach((c) => list.appendChild(this.el('li', {}, [this.el('b', { text: c.path }), ' ', this.el('del', { text: c.old === undefined ? '(none)' : JSON.stringify(c.old) }), ' → ', this.el('ins', { text: c.value === undefined ? '(none)' : JSON.stringify(c.value) })])));
      const comment = this.el('textarea', { cls: 'di-field', attrs: { rows: '3', 'aria-label': 'Why (the approver reads it)', placeholder: 'Why: the approver reads this' } });
      const who = this.el('div', { cls: 'di-form-widget' });
      const approver = fe.approvers ? new Combobox(who, { source: fe.approvers, label: 'Approver', placeholder: 'Any approver' }) : null;
      const msg = this.el('div', { cls: 'di-msg', attrs: { 'aria-live': 'polite' } });
      const done = (ok) => { dlg.close(); dlg.remove(); if (approver) { approver.destroy(); } resolve(ok); };
      const send = () => {
        if (fe.requireComment !== false && !comment.value.trim()) { msg.textContent = 'Say why: the approver reads it'; comment.setAttribute('aria-invalid', 'true'); comment.focus(); return; }
        const proposal = { values: values, changes: diff, comment: comment.value.trim(), approver: approver && approver.getValue() ? approver.getValue().value : null };
        msg.textContent = 'Filing…';
        Promise.resolve(fe.submit(proposal)).then((result) => {
          this.state = 'proposed';
          this.proposal = { proposal: proposal, result: result || {} };
          this.render();
          this.say('Proposal ' + ((result && result.id) || '') + ' waits for approval' + (proposal.approver ? ' by ' + proposal.approver : '') + '; the form is read-only until it is decided', 'warn');
          this.emit('proposed', { proposal: proposal, result: result });
          done(true);
        }, (err) => { msg.textContent = 'Could not file the proposal: ' + (err && err.message ? err.message : err); });
      };
      dlg.append(this.el('h3', { text: 'Submit for approval' }), this.el('p', { text: diff.length ? diff.length + (diff.length === 1 ? ' change' : ' changes') + ' for a second person to approve:' : 'Nothing changed.' }), list,
        this.el('label', { cls: 'di-label', text: 'Comment' }), comment, approver ? this.el('label', { cls: 'di-label', text: 'Approver' }) : null, approver ? who : null, msg,
        this.el('div', { cls: 'di-form-actions' }, [this.el('button', { cls: 'di-btn di-primary', text: 'Submit', attrs: { type: 'button', disabled: diff.length ? null : true }, data: { act: 'propose' }, on: { click: send } }),
          this.el('button', { cls: 'di-btn', text: 'Cancel', attrs: { type: 'button' }, data: { act: 'cancel' }, on: { click: () => done(false) } })]));
      dlg.addEventListener('cancel', (e) => { e.preventDefault(); done(false); });
      this.doc.body.appendChild(dlg);
      dlg.showModal();
      comment.focus();
    });
  }

  /**
   * The proposal's decision came back: approved keeps the value as the new start; rejected puts the start back. Either way the form is
   * editable again.
   * @param {('approved'|'rejected')} decision
   * @param {string} [note]
   * @returns {void}
   */
  resolveProposal(decision, note) {
    if (this.state !== 'proposed') { return; }
    if (decision === 'approved') { this.initial = JSON.parse(JSON.stringify(this.values)); } else { this.values = JSON.parse(JSON.stringify(this.initial)); }
    this.state = 'editing';
    this.proposal = null;
    this.render();
    this.say(decision === 'approved' ? 'Approved' + (note ? ': ' + note : '') : 'Rejected' + (note ? ': ' + note : '') + '; the values are back as they were', decision === 'approved' ? 'ok' : 'bad');
  }

  /** @private */
  busy(on) { if (this.submitBtn) { this.submitBtn.disabled = on; } this.form.setAttribute('aria-busy', String(on)); }

  /**
   * One field's value set from outside (a page's own control, a server's answer); the field is drawn again.
   * @param {string} path
   * @param {*} value
   * @returns {void}
   */
  setField(path, value) {
    this.values = setPath(this.values, path, value);
    const p = this.parts.get(path), active = this.doc.activeElement;
    if (!p || (active && p.wrap.contains(active))) { this.narrow(path).forEach((q) => this.redrawField(q)); this.refresh(); return; }   // a field being typed in keeps its text
    this.redrawField(path);
    this.narrow(path).forEach((q) => this.redrawField(q));
    this.refresh();
  }

  /**
   * A list field's values replaced (a server's answer that arrives later: the measures of the model just chosen); the form drawn again,
   * the focus kept. A value no longer offered stays until a person changes it (its check says so).
   * @param {string} path the field in the schema, an array's item fields by name (filters.field)
   * @param {Array} list the values offered
   * @param {string[]} [labels] their words
   * @returns {void}
   */
  setOptions(path, list, labels) {
    let node = this.schema;
    String(path).split('.').forEach((k) => { while (node && node.type === 'array' && node.items) { node = node.items; } node = node && node.properties ? node.properties[k] : null; });
    if (!node) { throw new Error('no field ' + path); }
    const target = node.type === 'array' && node.items && !node.items.properties ? node.items : node;
    target.enum = list.slice();
    if (labels) { target.enumLabels = labels.slice(); } else { delete target.enumLabels; }
    this.render();
  }

  /**
   * A field's value.
   * @param {string} path
   * @returns {*}
   */
  getField(path) { return getPath(this.values, path); }

  /** @private a line above the fields */
  say(text, tone) { this.banner.hidden = !text; this.banner.textContent = text || ''; this.banner.className = 'di-form-banner di-tone-' + (tone || 'info'); announce(text); }

  /**
   * The value: the shown fields only.
   * @returns {Object}
   */
  getValue() { return visibleValue(this.schema, this.values); }

  /**
   * Replaces the value (and the start Reset goes back to).
   * @param {Object} v
   * @returns {void}
   */
  setValue(v) { this.initial = JSON.parse(JSON.stringify(v || {})); this.values = JSON.parse(JSON.stringify(this.initial)); this.touched.clear(); this.tried = false; this.at = 0; this.render(); }

  /**
   * Puts the start value back.
   * @returns {void}
   */
  reset() { this.values = JSON.parse(JSON.stringify(this.initial)); this.touched.clear(); this.tried = false; this.server = {}; this.at = 0; this.render(); this.emit('reset'); }

  /**
   * Problems from elsewhere (a server's answer), by path.
   * @param {Object<string, string>} errors
   * @returns {void}
   */
  setErrors(errors) { this.server = Object.assign({}, errors); this.tried = true; this.refresh(); }

  /**
   * Notes by path that do not stop a submit (a warning: "above the desk limit"), shown where a problem would be when there is none.
   * @param {Object<string, string>} notes
   * @returns {void}
   */
  setNotes(notes) { this.notes = Object.assign({}, notes); this.refresh(); }

  /**
   * Problems from elsewhere shown at once, without marking every field tried (a live check as people type).
   * @param {Object<string, string>} errors
   * @returns {void}
   */
  showErrors(errors) { this.server = Object.assign({}, errors); Object.keys(errors).forEach((p) => this.touched.add(p)); this.refresh(); }
}

function CSS_ESCAPE(s) { return String(s).replace(/([^\w-])/g, '\\$1'); }
