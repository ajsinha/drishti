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
   proposal's number. Labels are real <label>s, problems are tied to their fields (aria-describedby, aria-invalid). */
import { announce, formatNumber } from '../../drishti-core/src/drishti_index.js';
import { fieldsOf, defaults, getPath, setPath, conditionHolds, validate, visibleValue, changes, parseField, words } from './drishti_schema.js';
import { InputBase, uid } from './drishti_inputs-dom.js';
import { DatePicker } from './drishti_date-picker.js';
import { DateRangePicker } from './drishti_date-range.js';
import { MultiSelect } from './drishti_multiselect.js';
import { Combobox } from './drishti_combobox.js';
import { TreePicker } from './drishti_tree-picker.js';
import { CodeEditor } from './drishti_code-editor.js';

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
 */

/**
 * A form from a schema. Events: change {path, value, values}, invalid {errors}, submitted {values, result}, proposed {proposal, result}, reset.
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
    this.parts = new Map();
    this.state = this.o.readOnly ? 'readonly' : 'editing';
    this.id = uid('form');
    this.form = this.el('form', { cls: 'di-form-el' + (this.o.layout === 'columns' ? ' di-columns' : ''), attrs: { novalidate: true, 'aria-label': this.schema.title || 'Form' } });
    this.form.addEventListener('submit', (e) => { e.preventDefault(); this.submit(); });
    host.appendChild(this.form);
    this.render();
  }

  // ---- drawing ---------------------------------------------------------------------------------------------------------------------------
  /**
   * Draws the form again (after an array changed shape); the focus goes back to the field that had it.
   * @returns {void}
   */
  render() {
    const had = this.doc.activeElement && this.form.contains(this.doc.activeElement) ? this.doc.activeElement.closest('[data-path]') : null;
    const path = had ? had.dataset.path : null;
    this.parts.forEach((p) => { if (p.widget && p.widget.destroy) { p.widget.destroy(); } });
    this.parts.clear();
    this.form.textContent = '';
    if (this.schema.title) { this.form.appendChild(this.el('h3', { cls: 'di-form-title', text: this.schema.title })); }
    if (this.schema.description) { this.form.appendChild(this.el('p', { cls: 'di-help', text: this.schema.description })); }
    this.summary = this.el('div', { cls: 'di-form-summary', attrs: { role: 'alert', hidden: true, tabindex: '-1' } });
    this.banner = this.el('div', { cls: 'di-form-banner', attrs: { role: 'status', hidden: true } });
    this.form.append(this.summary, this.banner);
    const body = this.el('div', { cls: 'di-form-body' });
    fieldsOf(this.schema, '').forEach((f) => body.appendChild(this.field(f)));
    this.form.appendChild(body);
    this.form.appendChild(this.actions());
    this.refresh();
    if (path) { const el = this.form.querySelector('[data-path="' + path + '"] input, [data-path="' + path + '"] button, [data-path="' + path + '"] select'); if (el) { el.focus(); } }
  }

  /** @private the buttons */
  actions() {
    const bar = this.el('div', { cls: 'di-form-actions' });
    if (this.state === 'readonly') { return bar; }
    this.submitBtn = this.el('button', { cls: 'di-btn di-primary', text: this.o.fourEyes ? 'Submit for approval' : (this.o.submitLabel || 'Save'), attrs: { type: 'submit' }, data: { act: 'submit' } });
    bar.append(this.submitBtn, this.el('button', { cls: 'di-btn', text: 'Reset', attrs: { type: 'button' }, data: { act: 'reset' }, on: { click: () => this.reset() } }));
    return bar;
  }

  /** @private one field's block */
  field(f) {
    const fs = f.field, id = this.id + '-' + f.path.replace(/[^\w]/g, '_'), ro = this.state !== 'editing' || !!fs.readOnly;
    const wrap = this.el('div', { cls: 'di-form-field di-t-' + fs.type, data: { path: f.path } });
    const part = { f: f, wrap: wrap, id: id, widget: null, input: null };
    this.parts.set(f.path, part);
    const msg = this.el('div', { cls: 'di-msg', attrs: { id: id + '-msg', 'aria-live': 'polite' } });
    const help = fs.description ? this.el('div', { cls: 'di-help', text: fs.description, attrs: { id: id + '-help' } }) : null;
    const described = [help ? id + '-help' : null, id + '-msg'].filter(Boolean).join(' ');
    const label = (forId) => this.el(forId ? 'label' : 'span', { cls: 'di-label', attrs: forId ? { for: forId } : { id: id + '-label' } }, [f.label, f.required ? this.el('span', { cls: 'di-req', text: ' *', attrs: { 'aria-hidden': 'true' } }) : null]);
    const v = getPath(this.values, f.path), widget = fs.widget || this.defaultWidget(fs);
    const set = (val) => this.change(f.path, val);
    const touch = () => { this.touched.add(f.path); this.refresh(); };
    if (fs.type === 'object') {
      const box = this.el('fieldset', { cls: 'di-fieldset' }, [this.el('legend', { text: f.label })]);
      if (help) { box.appendChild(help); }
      fieldsOf(fs, f.path).forEach((sub) => box.appendChild(this.field(sub)));
      wrap.appendChild(box);
      return wrap;
    }
    if (fs.type === 'array' && fs.items && fs.items.type === 'object') { return this.repeat(f, wrap, msg, help, ro); }
    const mount = (Cls, opts) => { const host = this.el('div', { cls: 'di-form-widget' }); part.widget = new Cls(host, Object.assign({ label: f.label }, fs.options, opts)); return host; };
    let control;
    switch (widget) {
      case 'checkbox': case 'switch': {
        const box = part.input = this.el('input', { attrs: { type: 'checkbox', id: id, role: widget === 'switch' ? 'switch' : null, 'aria-describedby': described, disabled: ro || null } });
        box.checked = !!v;
        box.addEventListener('change', () => { set(box.checked); touch(); });
        wrap.append(this.el('label', { cls: 'di-check-label', attrs: { for: id } }, [box, ' ' + f.label]), help, msg);
        return wrap;
      }
      case 'select': case 'radio': {
        const opts = (fs.enum || []).map((x, i) => ({ value: x, label: fs.enumLabels ? fs.enumLabels[i] : String(x) }));
        if (widget === 'radio') {
          control = this.el('div', { cls: 'di-radios', attrs: { role: 'radiogroup', 'aria-labelledby': id + '-label', 'aria-describedby': described } });
          opts.forEach((o, i) => {
            const r = this.el('input', { attrs: { type: 'radio', name: id, id: id + '-' + i, disabled: ro || null } });
            r.checked = v === o.value;
            r.addEventListener('change', () => { if (r.checked) { set(o.value); touch(); } });
            control.appendChild(this.el('label', { cls: 'di-radio', attrs: { for: id + '-' + i } }, [r, ' ' + o.label]));
          });
          wrap.append(label(null), control, help, msg);
          return wrap;
        }
        const sel = part.input = this.el('select', { cls: 'di-field', attrs: { id: id, 'aria-describedby': described, disabled: ro || null, 'aria-required': f.required ? 'true' : null } });
        sel.appendChild(this.el('option', { text: f.required ? 'Pick one' : '(none)', attrs: { value: '' } }));
        opts.forEach((o, i) => { const op = this.el('option', { text: o.label, attrs: { value: String(i) } }); if (v === o.value) { op.selected = true; } sel.appendChild(op); });
        sel.addEventListener('change', () => { set(sel.value === '' ? undefined : opts[+sel.value].value); touch(); });
        control = sel;
        break;
      }
      case 'date': control = mount(DatePicker, { value: v, businessOnly: fs.options && fs.options.businessOnly !== undefined ? fs.options.businessOnly : false, required: f.required }); break;
      case 'daterange': control = mount(DateRangePicker, { value: v }); break;
      case 'multiselect': control = mount(MultiSelect, { options: fs.items && fs.items.enum ? fs.items.enum.map((x, i) => ({ value: x, label: fs.items.enumLabels ? fs.items.enumLabels[i] : String(x) })) : (fs.options || {}).options, value: v || [] }); break;
      case 'combobox': control = mount(Combobox, { value: v === undefined || v === null ? null : { value: v, label: String(v) } }); break;
      case 'tree': control = mount(TreePicker, { value: v || [] }); break;
      case 'code': control = mount(CodeEditor, { value: v || '', readOnly: ro, height: (fs.options && fs.options.height) || 120 }); break;
      case 'textarea': {
        const ta = part.input = this.el('textarea', { cls: 'di-field', attrs: { id: id, rows: '3', 'aria-describedby': described, readonly: ro || null, placeholder: fs.placeholder || null } });
        ta.value = v === undefined || v === null ? '' : String(v);
        ta.addEventListener('input', () => set(ta.value === '' ? undefined : ta.value));
        ta.addEventListener('blur', touch);
        control = ta;
        break;
      }
      default: {
        const numeric = fs.type === 'number' || fs.type === 'integer';
        const inp = part.input = this.el('input', { cls: 'di-field' + (numeric ? ' di-num' : ''), attrs: { id: id, type: fs.format === 'email' ? 'email' : 'text', inputmode: numeric ? 'decimal' : null,
          'aria-describedby': described, readonly: ro || null, placeholder: fs.placeholder || null, 'aria-required': f.required ? 'true' : null, autocomplete: 'off' } });
        const show = (x) => (x === undefined || x === null ? '' : numeric && typeof x === 'number' ? formatNumber(x, { maxDecimals: 10 }) : String(x));
        inp.value = show(v);
        inp.addEventListener('input', () => {
          const r = parseField(fs, inp.value, f.label);
          part.parseError = r.error;
          set(r.error ? (numeric ? NaN : undefined) : r.value);
        });
        inp.addEventListener('blur', () => { if (!part.parseError && numeric) { inp.value = show(getPath(this.values, f.path)); } touch(); });
        control = inp;
      }
    }
    if (part.widget) {
      part.widget.on('change', (d) => { set(widget === 'combobox' ? (d.value ? d.value.value : undefined) : widget === 'code' ? d.value : (d.value !== undefined ? d.value : d)); touch(); });
      control.setAttribute('aria-describedby', described);
    }
    const lab = part.input ? label(id) : label(null);
    if (!part.input && control.firstElementChild) { control.setAttribute('role', 'group'); control.setAttribute('aria-labelledby', id + '-label'); }
    wrap.append(lab, control, help, msg);
    return wrap;
  }

  /** @private a field's widget when the schema names none */
  defaultWidget(fs) {
    if (fs.enum) { return fs.enum.length <= 3 ? 'radio' : 'select'; }
    if (fs.type === 'boolean') { return 'checkbox'; }
    if (fs.type === 'date') { return 'date'; }
    if (fs.type === 'daterange') { return 'daterange'; }
    if (fs.type === 'array' && fs.items && fs.items.enum) { return 'multiselect'; }
    if (fs.format === 'textarea') { return 'textarea'; }
    if (fs.format === 'code') { return 'code'; }
    return 'text';
  }

  /** @private an array of objects: repeatable groups */
  repeat(f, wrap, msg, help, ro) {
    const fs = f.field, list = getPath(this.values, f.path) || [];
    const box = this.el('fieldset', { cls: 'di-fieldset di-repeat' }, [this.el('legend', { text: f.label + ' (' + list.length + ')' })]);
    if (help) { box.appendChild(help); }
    list.forEach((item, i) => {
      const p = f.path + '[' + i + ']', name = (fs.items.title || words(f.key).replace(/s$/, '')) + ' ' + (i + 1);
      const group = this.el('div', { cls: 'di-repeat-item', attrs: { role: 'group', 'aria-label': name }, data: { index: String(i) } }, [this.el('div', { cls: 'di-repeat-head' }, [this.el('b', { text: name }), this.el('span', { cls: 'di-sp' }),
        ro ? null : this.el('button', { cls: 'di-btn', text: '↑', attrs: { type: 'button', 'aria-label': 'Move ' + name + ' up', disabled: i === 0 || null }, on: { click: () => this.moveItem(f.path, i, -1) } }),
        ro ? null : this.el('button', { cls: 'di-btn', text: '↓', attrs: { type: 'button', 'aria-label': 'Move ' + name + ' down', disabled: i === list.length - 1 || null }, on: { click: () => this.moveItem(f.path, i, 1) } }),
        ro ? null : this.el('button', { cls: 'di-btn', text: 'Remove', attrs: { type: 'button', 'aria-label': 'Remove ' + name, disabled: (fs.minItems && list.length <= fs.minItems) || null }, data: { act: 'remove' }, on: { click: () => this.removeItem(f.path, i) } })])]);
      fieldsOf(fs.items, p).forEach((sub) => group.appendChild(this.field(sub)));
      box.appendChild(group);
    });
    if (!ro) { box.appendChild(this.el('button', { cls: 'di-btn', text: 'Add ' + (fs.items.title || words(f.key).replace(/s$/, '')).toLowerCase(), attrs: { type: 'button', disabled: (fs.maxItems && list.length >= fs.maxItems) || null }, data: { act: 'add' }, on: { click: () => this.addItem(f.path) } })); }
    box.appendChild(msg);
    wrap.appendChild(box);
    return wrap;
  }

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
    if (redraw) { this.touched.add(path); this.render(); } else { this.refresh(); }
    this.emit('change', { path: path, value: value, values: this.getValue() });
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
      const shown = this.shown(path);
      p.wrap.hidden = !shown;
      const why = shown && (this.touched.has(path) || this.tried) ? this.errors[path] : null;
      const msg = p.wrap.querySelector('#' + CSS_ESCAPE(p.id) + '-msg');
      if (msg) { msg.textContent = why || ''; }
      p.wrap.classList.toggle('di-invalid', !!why);
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
      const list = this.el('ul', { cls: 'di-diff' });
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
  setValue(v) { this.initial = JSON.parse(JSON.stringify(v || {})); this.values = JSON.parse(JSON.stringify(this.initial)); this.touched.clear(); this.tried = false; this.render(); }

  /**
   * Puts the start value back.
   * @returns {void}
   */
  reset() { this.values = JSON.parse(JSON.stringify(this.initial)); this.touched.clear(); this.tried = false; this.server = {}; this.render(); this.emit('reset'); }

  /**
   * Problems from elsewhere (a server's answer), by path.
   * @param {Object<string, string>} errors
   * @returns {void}
   */
  setErrors(errors) { this.server = Object.assign({}, errors); this.tried = true; this.refresh(); }
}

function CSS_ESCAPE(s) { return String(s).replace(/([^\w-])/g, '\\$1'); }
