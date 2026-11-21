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
/* The form panel (kind form, contract 1.2) and the action buttons of a panel's header. The form is drishti-inputs' Form drawn from the panel's
   fields (types, labels, options, required, min and max, the starting values the ViewModel computed from initial:), with its own Submit and
   Cancel. Submit hands the values to the painter's fire (a refused submit shows the messages the host's check hook answered, each at its
   field; nothing is evaluated here: the rules are the Rachana runtime's), Cancel runs the cancel effects without checks. An action button
   fires its place in the panel's list; confirm asks first; a requires is enabled only when the host's allowed hook answers true (no hook, or any other answer: disabled). */
import { h, openDialog, closeDialog } from '../../drishti-core/src/drishti_index.js';
import { defineKind } from './drishti_views-kinds.js';
import { say } from './drishti_views-cells.js';
import { formFields } from './drishti_views-state.js';

const list = (x) => (Array.isArray(x) ? x : []);
const isObj = (v) => v !== null && typeof v === 'object' && !Array.isArray(v);

/** The drishti-inputs field type of a form field's type. */
const TYPES = Object.freeze({ number: 'number', integer: 'integer', money: 'money', boolean: 'boolean', date: 'date', time: 'time', datetime: 'datetime' });

/**
 * The object schema drishti-inputs' Form draws for a form panel's fields.
 * @param {Object} panel a form panel
 * @returns {Object}
 */
export function formSchema(panel) {
  const properties = {}, required = [];
  formFields(panel).forEach((f) => {
    const opts = list(f.options).filter(isObj), s = { type: TYPES[f.type] || 'string', title: typeof f.label === 'string' && f.label ? f.label : f.id };
    if (opts.length) {
      s.type = 'string';
      s.enum = opts.map((o) => String(o.value));
      s.enumLabels = opts.map((o) => String(o.label === undefined || o.label === null ? o.value : o.label));
      if (f.type === 'enum') { s.widget = opts.length > 3 ? 'select' : 'radio'; }
    }
    if (typeof f.min === 'number') { s[s.type === 'string' ? 'minLength' : 'minimum'] = f.min; }
    if (typeof f.max === 'number') { s[s.type === 'string' ? 'maxLength' : 'maximum'] = f.max; }
    if (typeof f.hint === 'string' && f.hint) { s.description = f.hint; }
    properties[f.id] = s;
    if (f.required === true) { required.push(f.id); }
  });
  return { type: 'object', properties: properties, required: required, order: formFields(panel).map((f) => f.id) };
}

/**
 * The values a form starts with (the ViewModel's, the empty ones left out).
 * @param {Object} panel
 * @returns {Object}
 */
export function formValues(panel) {
  const v = panel && panel.data && isObj(panel.data.values) ? panel.data.values : {}, out = {};
  Object.keys(v).forEach((k) => { if (v[k] !== null && v[k] !== undefined) { out[k] = v[k]; } });
  return out;
}

/**
 * Where each problem the host answered belongs in the form: a rule's message at the rule's first field, a message that begins with a
 * field's label at that field, any other at the first field. The form shows them in its summary and at those fields.
 * @param {Object} panel
 * @param {Object[]} problems
 * @returns {Object<string, string>} field id to message
 */
export function problemsByField(panel, problems) {
  const fields = formFields(panel), rules = panel.form && Array.isArray(panel.form.rules) ? panel.form.rules.filter(isObj) : [], out = {};
  list(problems).forEach((p) => {
    const msg = String(p.message);
    const rule = rules.find((r) => r.message === msg && Array.isArray(r.fields) && r.fields.length);
    const byLabel = fields.find((f) => typeof f.label === 'string' && f.label && msg.indexOf(f.label) === 0);
    const id = rule ? rule.fields[0] : byLabel ? byLabel.id : fields.length ? fields[0].id : '';
    out[id] = out[id] ? out[id] + '; ' + msg : msg;
  });
  return out;
}

const form = {
  paint(body, panel, ctx) {
    const doc = ctx.doc, box = h(doc, 'div', { cls: 'dv-form', attrs: { 'data-form': String(panel.id) } });
    body.appendChild(box);
    let drawn = null, gone = false;
    const id = String(panel.id), d = panel.data || {};
    Promise.all([ctx.library('drishti-inputs'), ctx.styles('drishti-inputs')]).then(([I]) => {
      if (gone) { return; }
      drawn = new I.Form(box, {
        schema: formSchema(panel), value: formValues(panel), label: panel.title || id, doneText: '', resetButton: false, layout: 'stacked',
        submitLabel: typeof d.submitLabel === 'string' && d.submitLabel ? d.submitLabel : say(ctx.messages, 'submit'),
        cancelLabel: typeof d.cancelLabel === 'string' && d.cancelLabel ? d.cancelLabel : say(ctx.messages, 'cancel'),
        check: (values) => ctx.checkForm(id, values).then((problems) => problemsByField(panel, problems)),
        onSubmit: (values) => ctx.submitForm(id, values),
        onCancel: () => ctx.fire(id, 'cancel')
      });
      box.setAttribute('data-drawn', '');
      ctx.emit('drawn', { panel: id, kind: 'form' });
    }).catch((e) => { if (!gone) { box.textContent = say(ctx.messages, 'dataFailed'); ctx.emit('error', { code: 'panel.paint', panel: id, error: e }); } });
    return {
      /** @returns {Object} the drishti-inputs Form, for a host that needs it */
      component() { return drawn; },
      dispose() { gone = true; if (drawn) { try { drawn.destroy(); } catch (e) { /* a library's teardown never stops the view's */ } } box.remove(); }
    };
  }
};
defineKind('form', form);

/**
 * Asks the reader a question in the page's own dialog and answers whether they said yes.
 * @param {Object} ctx the paint's context
 * @param {string} text
 * @returns {Promise<boolean>}
 */
export function ask(ctx, text) {
  const doc = ctx.doc;
  return new Promise((resolve) => {
    const dlg = h(doc, 'dialog', { cls: 'dv-ask', attrs: { 'aria-label': text } });
    const yes = h(doc, 'button', { cls: 'dv-btn dv-btn-main', text: say(ctx.messages, 'yes'), attrs: { type: 'button', 'data-ask': 'yes' }, on: { click: () => closeDialog(dlg, 'yes') } });
    const no = h(doc, 'button', { cls: 'dv-btn', text: say(ctx.messages, 'no'), attrs: { type: 'button', 'data-ask': 'no' }, on: { click: () => closeDialog(dlg, 'no') } });
    dlg.append(h(doc, 'p', { text: text }), h(doc, 'div', { cls: 'dv-ask-bar' }, [no, yes]));
    dlg.addEventListener('close', () => { const ok = dlg.returnValue === 'yes'; dlg.remove(); resolve(ok); });
    (ctx.root || doc.body).appendChild(dlg);
    openDialog(dlg, { focus: no });
  });
}

/**
 * The buttons of a panel's header, in order (an empty list: none). Each fires its place (trigger action, mark = its place), after asking
 * when it has a confirm; a button whose requires the host does not allow is disabled.
 * @param {Object} ctx the paint's context
 * @param {Object} panel
 * @param {HTMLElement} into the element that holds them (emptied first)
 * @returns {void}
 */
export function drawActions(ctx, panel, into) {
  const doc = ctx.doc;
  while (into.firstChild) { into.removeChild(into.firstChild); }
  const acts = list(panel.actions).filter(isObj);
  into.hidden = !acts.length;
  acts.forEach((a, i) => {
    const allowed = typeof a.requires !== 'string' || !a.requires || ctx.allowed(a.requires);
    const b = h(doc, 'button', { cls: 'dv-btn' + (a.tone ? ' dv-t-' + a.tone : ''), text: String(a.label), attrs: { type: 'button', 'data-action': String(a.id), disabled: allowed ? null : '' } });
    b.addEventListener('click', () => {
      const go = () => ctx.fire(String(panel.id), 'action', i);
      if (a.confirm === true || (typeof a.confirm === 'string' && a.confirm)) { ctx.ask(typeof a.confirm === 'string' ? a.confirm : say(ctx.messages, 'sure', { label: String(a.label) })).then((ok) => { if (ok) { go(); } }); } else { go(); }
    });
    into.appendChild(b);
  });
}
