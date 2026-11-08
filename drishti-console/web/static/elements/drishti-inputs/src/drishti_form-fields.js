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
/* A form's fields, drawn (drishti_form.js holds the state): each field by its widget (text, a number read loosely and shown grouped,
   money with its currency, a select or radios, a row of checkboxes, a checkbox or switch, the date, time, date-and-time and range
   pickers, the multi-select, the combobox, the tree picker, the code editor, a formula (a one-line code editor with the page's
   completion: options.mode, default rachana, and options.complete), a fieldset for an object, repeatable groups for an array of
   objects) or by a custom widget a page defines (defineWidget, or the form's widgets option). A field with a name is submitted by a
   surrounding server form: a native control carries the name, a picker writes hidden inputs. A field's hook puts data attributes on its
   control, so tutorials and tests find it. */
import { formatNumber } from '../../drishti-core/src/drishti_index.js';
import { fieldsOf, getPath, parseField, words, currencyOf, offeredValues } from './drishti_schema.js';
import { DatePicker } from './drishti_date-picker.js';
import { DateRangePicker } from './drishti_date-range.js';
import { TimePicker, DateTimePicker } from './drishti_time-picker.js';
import { MultiSelect } from './drishti_multiselect.js';
import { Combobox } from './drishti_combobox.js';
import { TreePicker } from './drishti_tree-picker.js';
import { CodeEditor } from './drishti_code-editor.js';
import { upgradeCodeArea } from './drishti_code-upgrade.js';

const WIDGETS = new Map();

/**
 * What a custom widget is given.
 * @typedef {Object} WidgetContext
 * @property {Object} form the Form
 * @property {Object} field the field's schema
 * @property {string} path
 * @property {string} label
 * @property {*} value
 * @property {string} id the id its focusable element should take (the label points at it)
 * @property {boolean} readOnly
 * @property {boolean} required
 * @property {string} describedBy the ids of its help and problem, for aria-describedby
 * @property {function(*): void} set gives the form a new value
 * @property {function(): void} touch marks the field touched (its problem shows)
 */

/**
 * A custom widget: draws a field's control. Returns its element and, as it can, setValue(v), focus(), input (the focusable element)
 * and destroy().
 * @typedef {function(WidgetContext): {el: HTMLElement, setValue: function(*): void, focus: function(): void, input: HTMLElement, destroy: function(): void}} WidgetFactory
 */

/**
 * Defines a widget every form may name in a field's widget (a form's own widgets option wins over it).
 * @param {string} name
 * @param {WidgetFactory} factory
 * @returns {void}
 */
export function defineWidget(name, factory) { WIDGETS.set(name, factory); }

/**
 * A field's widget when the schema names none.
 * @param {Object} fs
 * @returns {string}
 */
export function defaultWidget(fs) {
  if (fs.enum) { return fs.enum.length <= 3 ? 'radio' : 'select'; }
  if (fs.type === 'boolean') { return 'checkbox'; }
  if (fs.type === 'date' || fs.type === 'time' || fs.type === 'datetime' || fs.type === 'daterange' || fs.type === 'money') { return fs.type; }
  if (fs.type === 'array' && fs.items && fs.items.enum) { return 'multiselect'; }
  if (fs.format === 'textarea') { return 'textarea'; }
  if (fs.format === 'code') { return 'code'; }
  return 'text';
}

/**
 * Puts a field's hook on its control: a name gives data-<name>, an object its attributes.
 * @param {Element} el
 * @param {(string|Object<string, string>)} hook
 * @returns {void}
 */
export function applyHook(el, hook) {
  if (!el || !hook) { return; }
  if (typeof hook === 'string') { el.setAttribute(hook.indexOf('data-') === 0 ? hook : 'data-' + hook, ''); return; }
  Object.keys(hook).forEach((k) => { if (k === 'class') { String(hook[k]).split(/\s+/).filter(Boolean).forEach((c) => el.classList.add(c)); } else { el.setAttribute(k, hook[k] === true ? '' : String(hook[k])); } });
}

/**
 * Draws one field into its block (the form keeps the part: {f, wrap, id, widget, input, custom, names}).
 * @param {Object} form the Form
 * @param {{key: string, path: string, field: Object, label: string, required: boolean}} f
 * @returns {HTMLElement}
 */
export function drawField(form, f) {
  const fs = f.field, id = form.id + '-' + f.path.replace(/[^\w]/g, '_'), ro = form.state !== 'editing' || !!fs.readOnly;
  const wrap = form.el('div', { cls: 'di-form-field di-t-' + fs.type, attrs: { [form.pathAttr || 'data-path']: f.path } });
  const part = { f: f, wrap: wrap, id: id, widget: null, input: null, custom: null, names: null };
  form.parts.set(f.path, part);
  const msg = form.el('div', { cls: 'di-msg', attrs: { id: id + '-msg', 'aria-live': 'polite' } });
  const help = fs.description ? form.el('div', { cls: 'di-help', text: fs.description, attrs: { id: id + '-help' } }) : null;
  const described = [help ? id + '-help' : null, id + '-msg'].filter(Boolean).join(' ');
  const label = (forId) => form.el(forId ? 'label' : 'span', { cls: 'di-label', attrs: forId ? { for: forId, id: id + '-label' } : { id: id + '-label' } }, [f.label, f.required ? form.el('span', { cls: 'di-req', text: ' *', attrs: { 'aria-hidden': 'true' } }) : null]);
  const v = getPath(form.values, f.path), widget = fs.widget || defaultWidget(fs);
  const set = (val) => form.change(f.path, val);
  const touch = () => { form.touched.add(f.path); form.refresh(); };
  const named = (el) => { if (fs.name && el) { el.setAttribute('name', fs.name); } return el; };
  if (fs.type === 'object') {
    const box = form.el('fieldset', { cls: 'di-fieldset' }, [form.el('legend', { text: f.label })]);
    if (help) { box.appendChild(help); }
    fieldsOf(fs, f.path).forEach((sub) => box.appendChild(drawField(form, sub)));
    applyHook(box, fs.hook);
    wrap.appendChild(box);
    return wrap;
  }
  if (fs.type === 'array' && fs.items && fs.items.type === 'object') { return drawRepeat(form, f, wrap, msg, help, ro); }
  const factory = (form.o.widgets && form.o.widgets[widget]) || WIDGETS.get(widget);
  const mount = (Cls, opts) => { const host = form.el('div', { cls: 'di-form-widget' }); part.widget = new Cls(host, Object.assign({ label: f.label }, fs.options, opts)); part.names = fs.name ? form.el('span', { cls: 'di-names', attrs: { hidden: true } }) : null; return host; };
  const opts = () => offeredValues(fs.items && fs.items.enum ? Object.assign({}, fs.items, { dependsOn: fs.dependsOn, parents: fs.parents }) : fs, form.values, f.path).map((x) => {
    const all = fs.enum || (fs.items && fs.items.enum) || [], labels = fs.enumLabels || (fs.items && fs.items.enumLabels), i = all.indexOf(x);
    return { value: x, label: labels && i >= 0 ? labels[i] : String(x) };
  });
  let control;
  if (factory) {
    const ret = part.custom = factory({ form: form, field: fs, path: f.path, label: f.label, value: v, id: id, readOnly: ro, required: f.required, describedBy: described, set: set, touch: touch });
    control = ret.el;
    part.input = ret.input || null;
    part.names = fs.name ? form.el('span', { cls: 'di-names', attrs: { hidden: true } }) : null;
    applyHook(part.input || control, fs.hook);
    wrap.append(fs.noLabel ? '' : part.input ? label(part.input.id || id) : label(null), control, help, msg);
    if (part.names) { wrap.appendChild(part.names); }
    return wrap;
  }
  switch (widget) {
    case 'checkbox': case 'switch': {
      const box = part.input = named(form.el('input', { attrs: { type: 'checkbox', id: id, role: widget === 'switch' ? 'switch' : null, 'aria-describedby': described, disabled: ro || null,
        value: fs.nameValue !== undefined ? String(fs.nameValue) : 'true' } }));
      box.checked = !!v;
      box.addEventListener('change', () => { set(box.checked); touch(); });
      applyHook(box, fs.hook);
      wrap.append(form.el('label', { cls: 'di-check-label', attrs: { for: id } }, [box, ' ' + f.label]), help, msg);
      return wrap;
    }
    case 'checkboxes': {
      control = form.el('div', { cls: 'di-checks', attrs: { role: 'group', 'aria-labelledby': id + '-label', 'aria-describedby': described } });
      const chosen = (Array.isArray(v) ? v : []).map(String);
      opts().forEach((o, i) => {
        const c = named(form.el('input', { attrs: { type: 'checkbox', id: id + '-' + i, value: String(o.value), disabled: ro || null } }));
        if (fs.optionHook) { c.setAttribute('data-' + fs.optionHook, String(o.value)); }
        c.checked = chosen.indexOf(String(o.value)) >= 0;
        c.addEventListener('change', () => { set(Array.from(control.querySelectorAll('input:checked')).map((x) => opts().find((y) => String(y.value) === x.value).value)); touch(); });
        control.appendChild(form.el('label', { cls: 'di-check-label', attrs: { for: id + '-' + i } }, [c, ' ' + o.label]));
      });
      applyHook(control, fs.hook);
      wrap.append(label(null), control, help, msg);
      return wrap;
    }
    case 'select': case 'radio': {
      const list = opts();
      if (widget === 'radio') {
        control = form.el('div', { cls: 'di-radios', attrs: { role: 'radiogroup', 'aria-labelledby': id + '-label', 'aria-describedby': described } });
        list.forEach((o, i) => {
          const r = form.el('input', { attrs: { type: 'radio', name: fs.name || id, id: id + '-' + i, value: String(o.value), disabled: ro || null } });
          if (fs.optionHook) { r.setAttribute('data-' + fs.optionHook, String(o.value)); }
          r.checked = v === o.value;
          r.addEventListener('change', () => { if (r.checked) { set(o.value); touch(); } });
          control.appendChild(form.el('label', { cls: 'di-radio', attrs: { for: id + '-' + i } }, [r, ' ' + o.label]));
        });
        applyHook(control, fs.hook);
        wrap.append(label(null), control, help, msg);
        return wrap;
      }
      const sel = part.input = named(form.el('select', { cls: 'di-field', attrs: { id: id, 'aria-describedby': described, disabled: ro || null, 'aria-required': f.required ? 'true' : null } }));
      if (fs.allowEmpty !== false) { sel.appendChild(form.el('option', { text: fs.emptyLabel || (f.required ? 'Pick one' : '(none)'), attrs: { value: '' } })); }
      list.forEach((o) => { const op = form.el('option', { text: o.label, attrs: { value: String(o.value) } }); if (v === o.value) { op.selected = true; } sel.appendChild(op); });
      sel.addEventListener('change', () => { const o = list.find((x) => String(x.value) === sel.value); set(o ? o.value : undefined); touch(); });
      control = sel;
      break;
    }
    case 'date': control = mount(DatePicker, { value: v, businessOnly: fs.options && fs.options.businessOnly !== undefined ? fs.options.businessOnly : false, required: f.required }); break;
    case 'time': control = mount(TimePicker, { value: v, step: fs.stepMinutes || 15, required: f.required }); break;
    case 'datetime': control = mount(DateTimePicker, { value: v, timeZone: fs.timeZone, step: fs.stepMinutes || 15, required: f.required }); break;
    case 'daterange': control = mount(DateRangePicker, { value: v }); break;
    case 'multiselect': control = mount(MultiSelect, { options: fs.items && fs.items.enum ? opts() : (fs.options || {}).options, value: v || [] }); break;
    case 'combobox': control = mount(Combobox, { value: v === undefined || v === null ? null : { value: v, label: String(v) } }); break;
    case 'tree': control = mount(TreePicker, { value: v || [] }); break;
    case 'code': control = mount(CodeEditor, { value: v || '', readOnly: ro, height: (fs.options && fs.options.height) || 120 }); break;
    case 'formula': {                                         // a one-line code editor; the input holds the value (upgradeCodeArea)
      const fo = fs.options || {};
      const inp = part.input = named(form.el('input', { cls: 'di-field di-formula', attrs: { id: id, type: 'text', 'data-di-code': fo.mode || 'rachana', 'aria-describedby': described,
        readonly: ro || null, placeholder: fs.placeholder || null, autocomplete: 'off', spellcheck: 'false' } }));
      inp.value = v === undefined || v === null ? '' : String(v);
      inp.drishtiCodeOptions = { complete: fo.complete, label: f.label };
      inp.addEventListener(fs.lazy ? 'change' : 'input', () => set(inp.value === '' ? undefined : inp.value));
      inp.addEventListener('blur', touch);
      Promise.resolve().then(() => { if (inp.isConnected && !inp.drishtiCode) { upgradeCodeArea(inp); } });   // once in the page (a page's observer may be first)
      control = inp;
      break;
    }
    case 'textarea': {
      const ta = part.input = named(form.el('textarea', { cls: 'di-field', attrs: { id: id, rows: String((fs.options && fs.options.rows) || 3), 'aria-describedby': described, readonly: ro || null, placeholder: fs.placeholder || null } }));
      ta.value = v === undefined || v === null ? '' : String(v);
      ta.addEventListener(fs.lazy ? 'change' : 'input', () => set(ta.value === '' ? undefined : ta.value));
      ta.addEventListener('blur', touch);
      control = ta;
      break;
    }
    default: {
      const numeric = fs.type === 'number' || fs.type === 'integer' || fs.type === 'money', money = fs.type === 'money';
      const inp = part.input = named(form.el('input', { cls: 'di-field' + (numeric ? ' di-num' : ''), attrs: { id: id, type: fs.format === 'email' ? 'email' : 'text', inputmode: numeric ? 'decimal' : null,
        'aria-describedby': described, readonly: ro || null, placeholder: fs.placeholder || (money ? '1.5m, 250k or 1,000,000' : null), 'aria-required': f.required ? 'true' : null, autocomplete: 'off' } }));
      const show = (x) => (x === undefined || x === null ? '' : numeric && typeof x === 'number' && !isNaN(x) ? formatNumber(x, { maxDecimals: money ? 2 : 10, minus: '-' }) : String(x));
      inp.value = show(v);
      inp.addEventListener(fs.lazy ? 'change' : 'input', () => {
        const r = parseField(fs, inp.value, f.label);
        part.parseError = r.error ? (fs.parseMessage || r.error) : null;
        set(r.error ? (numeric ? NaN : undefined) : r.value);
      });
      inp.addEventListener('blur', () => { if (!part.parseError && numeric) { inp.value = show(getPath(form.values, f.path)); } touch(); });
      control = inp;
      if (money) {                                            // the currency beside the amount: fixed, or what another field holds
        part.ccy = form.el('span', { cls: 'di-unit', text: currencyOf(fs, form.values, f.path) || '—', attrs: { id: id + '-ccy', title: 'Currency', 'data-ccy-of': f.path } });
        inp.setAttribute('aria-describedby', described + ' ' + id + '-ccy');
        control = form.el('div', { cls: 'di-control di-adorned' }, [inp, part.ccy]);
      }
    }
  }
  if (part.widget) {
    part.widget.on('change', (d) => { set(widget === 'combobox' ? (d.value ? d.value.value : undefined) : widget === 'code' ? d.value : (d.value !== undefined ? d.value : d)); touch(); });
    control.setAttribute('aria-describedby', described);
    applyHook(part.widget.input || part.widget.button || control, fs.hook);
  } else {
    applyHook(part.input || control, fs.hook);
  }
  const lab = part.input ? label(id) : label(null);
  if (!part.input && control.firstElementChild) { control.setAttribute('role', 'group'); control.setAttribute('aria-labelledby', id + '-label'); }
  wrap.append(lab, control, help, msg);
  if (part.names) { wrap.appendChild(part.names); }
  return wrap;
}

/** @private an array of objects: repeatable groups */
function drawRepeat(form, f, wrap, msg, help, ro) {
  const fs = f.field, list = getPath(form.values, f.path) || [];
  const box = form.el('fieldset', { cls: 'di-fieldset di-repeat' }, [form.el('legend', { text: f.label }), form.el('span', { cls: 'di-count', text: list.length + (list.length === 1 ? ' item' : ' items') })]);
  if (help) { box.appendChild(help); }
  list.forEach((item, i) => {
    const p = f.path + '[' + i + ']', name = (fs.items.title || words(f.key).replace(/s$/, '')) + ' ' + (i + 1);
    const group = form.el('div', { cls: 'di-repeat-item', attrs: { role: 'group', 'aria-label': name }, data: { index: String(i) } }, [form.el('div', { cls: 'di-repeat-head' }, [form.el('b', { text: name }), form.el('span', { cls: 'di-sp' }),
      ro ? null : form.el('button', { cls: 'di-btn', text: '↑', attrs: { type: 'button', 'aria-label': 'Move ' + name + ' up', disabled: i === 0 || null }, on: { click: () => form.moveItem(f.path, i, -1) } }),
      ro ? null : form.el('button', { cls: 'di-btn', text: '↓', attrs: { type: 'button', 'aria-label': 'Move ' + name + ' down', disabled: i === list.length - 1 || null }, on: { click: () => form.moveItem(f.path, i, 1) } }),
      ro ? null : form.el('button', { cls: 'di-btn', text: 'Remove', attrs: { type: 'button', 'aria-label': 'Remove ' + name, disabled: (fs.minItems && list.length <= fs.minItems) || null }, data: { act: 'remove' }, on: { click: () => form.removeItem(f.path, i) } })])]);
    const subs = fieldsOf(fs.items, p);
    subs.forEach((sub) => group.appendChild(drawField(form, subs.length === 1 ? Object.assign({}, sub, { label: name }) : sub)));   // one field an item: it is named as the item
    box.appendChild(group);
  });
  if (!ro) { box.appendChild(form.el('button', { cls: 'di-btn', text: fs.addLabel || 'Add ' + (fs.items.title || words(f.key).replace(/s$/, '')).toLowerCase(), attrs: { type: 'button', disabled: (fs.maxItems && list.length >= fs.maxItems) || null }, data: { act: 'add' }, on: { click: () => form.addItem(f.path) } })); }
  box.appendChild(msg);
  applyHook(box, fs.hook);
  wrap.appendChild(box);
  return wrap;
}

/**
 * Writes a named field's hidden inputs from its value (one per item of a list; a boolean only when on); a hidden field writes none.
 * @param {Object} part
 * @param {*} value
 * @param {boolean} shown
 * @returns {void}
 */
export function writeNames(part, value, shown) {
  const fs = part.f.field;
  if (!fs.name) { return; }
  part.wrap.querySelectorAll('[name]').forEach((e) => { if (!e.closest('.di-names')) { e.disabled = !shown; } });
  if (!part.names) { return; }
  const doc = part.names.ownerDocument;
  part.names.textContent = '';
  if (!shown || value === undefined || value === null || value === '' || value === false) { return; }
  const vals = Array.isArray(value) ? value : typeof value === 'object' ? [value.from ? value.from + '..' + (value.to || '') : ''] : [value === true ? (fs.nameValue !== undefined ? fs.nameValue : 'true') : value];
  vals.forEach((x) => { const i = doc.createElement('input'); i.type = 'hidden'; i.name = fs.name; i.value = String(x); part.names.appendChild(i); });
}
