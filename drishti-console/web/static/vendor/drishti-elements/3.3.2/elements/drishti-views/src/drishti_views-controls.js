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
/* A view's controls (ViewModel.controls): dropdowns, cascading lists and top-n, multi-selects, toggles and segmented choices, dates and date
   ranges, number ranges, sliders and search, each drawn by drishti-inputs' own component (Select, MultiSelect, Switch, ButtonGroup,
   DatePicker, DateRangePicker, WhatIf; a search or a number is the library's field) in the bar under the title line, a side bar or a panel's
   header (placement strip, side or header). Each control is a [data-ctl=<id>] holder with its label, drawn in the first paint with its
   height kept, so nothing moves when drishti-inputs arrives and fills it. A reader's pick goes to the handle's change(): the control's change
   effects, the host's bind with every ctl.<id> value the reader set (the panels the control reads drawn again), and the control-change
   event for the host. Typing (search, a number) counts when the reader pauses. Nothing inline: no handler or style attribute is written. */
import { h } from '../../drishti-core/src/drishti_index.js';
import { say } from './drishti_views-cells.js';
import { requestText } from './drishti_views-state.js';

/** The control kinds drawn (contract 1: ControlView.kind); another kind is drawn as a search field. */
export const CONTROL_KINDS = Object.freeze(['dropdown', 'cascading', 'top-n', 'multi-select', 'toggle', 'segmented', 'date', 'date-range', 'number-range', 'slider', 'search']);

/** Milliseconds of rest before a typed value (search, a number) counts. */
export const TYPING_PAUSE_MS = 250;

const isObject = (v) => v !== null && typeof v === 'object' && !Array.isArray(v);

/**
 * The control values a host gives: a query text ("legs=2&ctl.when=2026-01-01..") or an object, keyed by the control's id (ctl. dropped).
 * @param {(string|Object<string, *>)} given
 * @returns {Object<string, string>}
 */
export function controlValues(given) {
  const out = {}, put = (k, v) => { const t = requestText(v); out[String(k).indexOf('ctl.') === 0 ? String(k).slice(4) : String(k)] = t === null ? '' : t; };
  if (typeof given === 'string' && given) { new URLSearchParams(given.replace(/^\?/, '')).forEach((v, k) => put(k, v)); } else if (isObject(given)) { Object.keys(given).forEach((k) => put(k, given[k])); }
  return out;
}

/**
 * The query text of control values (ctl.<id>=<value>&...), commas and colons kept readable.
 * @param {Object<string, string>} values
 * @param {string[]} [ids] these, in this order (default every value)
 * @returns {string}
 */
export function controlQuery(values, ids) {
  return (ids || Object.keys(values || {})).map((id) => 'ctl.' + encodeURIComponent(id) + '=' + encodeURIComponent(values[id] == null ? '' : values[id]))
    .join('&').replace(/%2C/g, ',').replace(/%3A/g, ':');
}

/**
 * A ControlView's value as text: a multi-select's values joined with commas, a range from..to, empty when none.
 * @param {Object} c
 * @returns {string}
 */
export function controlText(c) {
  if (c.kind === 'multi-select' && Array.isArray(c.values) && c.values.length) { return c.values.map(String).join(','); }
  return typeof c.value === 'string' ? c.value : '';
}

/** @private the value handed to change(): a multi-select's list, else the text */
const handed = (d, text) => (d.kind === 'multi-select' ? (text ? text.split(',') : []) : text);

/** The controls of one painted ViewModel, drawn into the view's header, side bar and panels' headers. */
export class Controls {
  /**
   * @param {Object} view the view's handle (ViewHandle): its ctx, frames, library loading, change() and the reader's values (view.ctl)
   * @param {Object[]} list the ViewModel's controls
   * @param {{drawn: string[], all: boolean}} panels the panels drawn (all: no panels option)
   */
  constructor(view, list, panels) {
    this.view = view;
    this.ctx = view.ctx;
    this.doc = view.ctx.doc;
    this.ui = new Map();
    this.timers = new Map();
    this.gone = false;
    const drawn = new Set(panels.drawn), store = view.ctl;
    const reads = (d) => !Array.isArray(d.reads) || panels.all || d.reads.some((r) => r === 'strip' || r === 'title' || drawn.has(String(r)));
    this.defs = (Array.isArray(list) ? list : []).filter((d) => isObject(d) && typeof d.id === 'string' && d.id && (d.placement === 'header' && d.panel ? drawn.has(String(d.panel)) : reads(d)));
    this.defs.forEach((d) => { if ('value' in d || 'values' in d || !store.touched.has(d.id)) { store.values[d.id] = controlText(d); } });
    this.bars = {};
    this.status = h(this.doc, 'span', { cls: 'dv-ctl-status', attrs: { role: 'status', 'aria-live': 'polite', 'data-ctl-status': '' } });
  }

  /**
   * The reader's value of a control now.
   * @param {string} id
   * @returns {string} its text (empty when none)
   */
  value(id) { const v = this.view.ctl.values[id]; return v == null ? '' : String(v); }

  /**
   * Draws every control's holder in its place (the frames exist), then fills them with drishti-inputs' components.
   * @param {Object} screen the view's Screen (its header element)
   * @param {Map<string, Object>} frames the panels' frames
   * @param {string} [focus] the control that had the focus before a full paint: it gets it back
   * @returns {?HTMLElement} the side bar, when a control is placed there (the caller lays it out beside the panels)
   */
  mount(screen, frames, focus) {
    if (!this.defs.length) { return null; }
    const m = this.ctx.messages;
    this.defs.forEach((d) => {
      const slot = this.holder(d);
      const frame = d.placement === 'header' ? frames.get(String(d.panel)) : null;
      if (frame) {
        let box = frame.head.querySelector('.dv-ctl-head');
        if (!box) { box = h(this.doc, 'span', { cls: 'dv-ctl-head', attrs: { role: 'group', 'aria-label': say(m, 'ctlPanel') } }); frame.head.insertBefore(box, frame.acts); }
        box.appendChild(slot.wrap);
      } else {
        this.bar(d.placement === 'side' ? 'side' : 'strip', screen).appendChild(slot.wrap);
      }
    });
    screen.element.appendChild(this.status);
    this.ready = Promise.all([this.view.library('drishti-inputs'), this.view.styles('drishti-inputs')]).then(([I]) => {
      if (this.gone) { return; }
      this.defs.forEach((d) => this.fill(I, d));
      this.I = I;
      if (focus) { this.focus(focus); }
      this.ctx.emit('drawn', { controls: this.defs.map((d) => d.id) });
    }).catch((e) => { if (!this.gone) { this.ctx.emit('error', { code: 'view.controls', error: e }); } });
    return this.bars.side || null;
  }

  /** @private the strip's bar (under the title line, before the banners) or the side bar, made once */
  bar(place, screen) {
    if (this.bars[place]) { return this.bars[place]; }
    const m = this.ctx.messages;
    const b = place === 'side' ? h(this.doc, 'aside', { cls: 'dv-ctl-bar dv-ctl-side', attrs: { 'aria-label': say(m, 'ctlSide'), 'data-ctl-bar': 'side' } })
      : h(this.doc, 'div', { cls: 'dv-ctl-bar', attrs: { role: 'group', 'aria-label': say(m, 'ctlBar'), 'data-ctl-bar': 'strip' } });
    if (place === 'strip') { screen.element.insertBefore(b, screen.banner); }
    this.bars[place] = b;
    return b;
  }

  /** @private one control's holder: [data-ctl], its label and the slot the component fills (its height kept by the sheet) */
  holder(d) {
    const id = this.ctx.uid('ctl-' + d.id), kind = CONTROL_KINDS.indexOf(d.kind) >= 0 ? d.kind : 'search';
    const label = h(this.doc, 'span', { cls: 'dv-ctl-l', text: typeof d.label === 'string' && d.label ? d.label : d.id, attrs: { id: id + '-l' } });
    const slot = h(this.doc, 'span', { cls: 'dv-ctl-w' });
    const wrap = h(this.doc, 'div', { cls: 'dv-ctl dv-ctl-' + kind, attrs: { 'data-ctl': d.id, 'data-kind': kind, role: 'group', 'aria-labelledby': id + '-l' } }, [label, slot]);
    const u = { wrap: wrap, slot: slot, id: id, kind: kind, set: () => {}, focus: () => {}, destroy: () => {}, sig: sigOf(d) };
    this.ui.set(d.id, u);
    return u;
  }

  /** @private fills a holder's slot with the component of its kind */
  fill(I, d) {
    const u = this.ui.get(d.id), doc = this.doc, m = this.ctx.messages, label = typeof d.label === 'string' && d.label ? d.label : d.id;
    while (u.slot.firstChild) { u.slot.removeChild(u.slot.firstChild); }
    const opts = (Array.isArray(d.options) ? d.options : []).filter(isObject).map((o) => ({ value: String(o.value), label: o.label != null && o.label !== '' ? String(o.label) : String(o.value) }));
    const cur = () => this.value(d.id), put = (text, pause) => this.pick(d, text, pause), host = (tag) => h(doc, tag || 'span', { cls: 'dv-ctl-c' });
    let comp = null, focusEl = null, set = () => {};
    const kind = u.kind;
    if (kind === 'dropdown' || kind === 'cascading' || kind === 'top-n') {
      const choices = (!d.required && kind !== 'top-n' ? [{ value: '', label: say(m, 'ctlAll') }] : []).concat(opts);
      const known = (v) => (choices.some((o) => o.value === v) ? v : '');
      const el = host('drishti-select');
      comp = new I.Select(el, { options: choices, value: known(cur()), label: label });
      el.component = comp;                          // the element's API (as <drishti-select>'s): set before it joins the page, so it makes no second one
      comp.on('change', (e) => put(e.value === null || e.value === undefined ? '' : String(e.value)));
      focusEl = comp.control;
      set = () => comp.setValue(known(cur()));
      u.slot.appendChild(el);
    } else if (kind === 'multi-select') {
      const el = host();
      comp = new I.MultiSelect(el, { options: opts, value: cur() ? cur().split(',') : [], label: label, placeholder: say(m, 'ctlAll') });
      comp.on('change', (e) => put((e.value || []).map(String).join(',')));
      focusEl = comp.input;
      set = () => comp.setValue(cur() ? cur().split(',') : []);
      u.slot.appendChild(el);
    } else if (kind === 'toggle' && opts.length === 2) {
      const el = host();
      comp = new I.Switch(el, { label: label, value: cur() === opts[1].value });
      comp.on('change', (e) => put(e.value ? opts[1].value : opts[0].value));
      focusEl = comp.button;
      set = () => { comp.setValue(cur() === opts[1].value); comp.button.setAttribute('aria-description', (opts.find((o) => o.value === cur()) || opts[0]).label); };
      u.slot.appendChild(el);
    } else if (kind === 'toggle' || kind === 'segmented') {
      const el = host();
      comp = new I.ButtonGroup(el, { label: label, mode: 'single', value: cur(), buttons: opts.map((o) => ({ label: o.label, data: { value: o.value } })) });
      comp.on('change', (e) => put(e.value === null ? '' : String(e.value)));
      focusEl = comp.buttons[0] || null;
      set = () => comp.setValue(cur() || null);
      u.slot.appendChild(el);
    } else if (kind === 'date') {
      const el = host();
      comp = new I.DatePicker(el, { value: cur() || null, businessOnly: false, label: label });
      comp.on('change', (e) => put(e.value || ''));
      focusEl = comp.input;
      set = () => comp.setValue(cur() || null);
      u.slot.appendChild(el);
    } else if (kind === 'date-range') {
      const el = host();
      const presets = opts.map((o) => { const p = o.value.split('..'); return { id: o.value, label: o.label, from: p[0], to: p[1] }; }).filter((p) => p.from && p.to);
      const span = () => { const t = cur(), p = t.split('..'); return { from: p[0] || null, to: p[1] || null, preset: presets.some((x) => x.id === t) ? t : null }; };
      comp = new I.DateRangePicker(el, { presets: presets.length ? presets : false, label: label, openEnded: true });
      comp.setValue(span());
      comp.on('change', (e) => put(e.from || e.to ? (e.from || '') + '..' + (e.to || '') : ''));
      focusEl = comp.button;
      set = () => comp.setValue(span());
      u.slot.appendChild(el);
    } else if (kind === 'slider' && isFinite(parseFloat(d.min)) && isFinite(parseFloat(d.max)) && parseFloat(d.max) > parseFloat(d.min)) {
      const el = host(), lo = parseFloat(d.min), hi = parseFloat(d.max), step = parseFloat(d.step) > 0 ? parseFloat(d.step) : 1;
      const now = () => { const p = cur().split('..'), a = parseFloat(p[0]), b = parseFloat(p[1]); return d.range ? [isFinite(a) ? a : lo, isFinite(b) ? b : hi] : (isFinite(a) ? a : lo); };
      comp = new I.WhatIf(el, { mode: d.range ? 'range' : 'slider', label: label, min: lo, max: hi, step: step, value: now(), debounce: TYPING_PAUSE_MS });
      comp.on('change', (e) => put(Array.isArray(e.value) ? e.value[0] + '..' + e.value[1] : String(e.value)));
      focusEl = (comp.inputs || [])[0] || null;
      set = () => comp.setValue(now());
      u.wrap.classList.add('dv-ctl-own-label');           // the slider names itself
      u.slot.appendChild(el);
    } else if (kind === 'number-range' || kind === 'slider') {
      const field = (end) => h(doc, 'input', { cls: 'di-field dv-ctl-num', attrs: { type: 'number', step: 'any', id: end === 'from' ? u.id : null, 'data-end': end,
        'aria-label': say(m, end === 'from' ? 'ctlFrom' : 'ctlTo', { label: label }), min: d.min != null ? String(d.min) : null, max: d.max != null ? String(d.max) : null,
        placeholder: end === 'from' ? (d.min != null ? String(d.min) : '') : (d.max != null ? String(d.max) : '') } });
      const a = field('from'), z = field('to');
      const join = (pause) => put(a.value || z.value ? a.value + '..' + z.value : '', pause);
      [a, z].forEach((x) => { x.addEventListener('change', () => join(0)); x.addEventListener('input', () => join(TYPING_PAUSE_MS)); });
      focusEl = a;
      set = () => { const p = cur().split('..'), active = rootActive(a); if (active !== a) { a.value = p[0] || ''; } if (active !== z) { z.value = p[1] || ''; } };
      u.slot.append(a, h(doc, 'span', { cls: 'dv-ctl-dash', text: '–', attrs: { 'aria-hidden': 'true' } }), z);
    } else {
      const q = h(doc, 'input', { cls: 'di-field dv-ctl-search', attrs: { type: 'search', id: u.id, autocomplete: 'off', spellcheck: 'false', placeholder: say(m, 'ctlSearch'), 'aria-labelledby': u.id + '-l' } });
      q.addEventListener('input', () => put(q.value, TYPING_PAUSE_MS));
      q.addEventListener('change', () => put(q.value, 0));
      focusEl = q;
      set = () => { if (rootActive(q) !== q) { q.value = cur(); } };
      u.slot.appendChild(q);
    }
    if (d.truncated && opts.length) { u.slot.appendChild(h(doc, 'span', { cls: 'dv-ctl-note', text: say(m, 'ctlFirst', { count: opts.length }) })); }
    u.set = set;
    u.focus = () => { if (focusEl && focusEl.focus) { focusEl.focus(); } };
    u.destroy = () => { if (comp && typeof comp.destroy === 'function') { try { comp.destroy(); } catch (e) { /* a component's teardown never stops the view's */ } } };
    u.sig = sigOf(d);
    set();
  }

  /** @private a reader's pick: kept and shown at once; handed to the view's change() now, or once the reader pauses */
  pick(d, text, pause) {
    if (this.gone || text === this.value(d.id)) { return; }
    const was = this.timers.get(d.id);
    if (was) { clearTimeout(was); }
    const go = () => { this.timers.delete(d.id); if (!this.gone) { this.view.change(d.id, handed(d, text)); } };
    if (pause) { this.timers.set(d.id, setTimeout(go, pause)); } else { go(); }
  }

  /**
   * A value taken by the view (a pick, or the host's change()): the widget shows it.
   * @param {string} id
   * @returns {void}
   */
  show(id) { const u = this.ui.get(id); if (u) { u.set(); } }

  /**
   * The controls a bind answered: new options draw the control again (not while the reader is in it), a corrected value is shown.
   * @param {Object[]} list
   * @returns {void}
   */
  reconcile(list) {
    (Array.isArray(list) ? list : []).filter(isObject).forEach((n) => {
      const i = this.defs.findIndex((d) => d.id === n.id), u = this.ui.get(n.id);
      if (i < 0 || !u) { return; }
      this.defs[i] = n;
      const inside = u.wrap.contains(rootActive(u.wrap));
      if ('value' in n || 'values' in n) { this.view.ctl.values[n.id] = controlText(n); }
      if (this.I && u.sig !== sigOf(n) && !inside) { u.destroy(); this.fill(this.I, n); } else { u.set(); }
    });
  }

  /**
   * Says in the status region (read by a screen reader) what the last change did.
   * @param {string} text
   * @returns {void}
   */
  say(text) { this.status.textContent = text; }

  /**
   * The control that holds the focus.
   * @returns {?string} its id, or null
   */
  focused() {
    const a = rootActive(this.status);
    for (const [id, u] of this.ui) { if (a && u.wrap.contains(a)) { return id; } }
    return null;
  }

  /**
   * Puts the focus on a control's component.
   * @param {string} id
   * @returns {void}
   */
  focus(id) { const u = this.ui.get(String(id)); if (u) { u.focus(); } }

  /**
   * Stops the pending picks and takes every component out (an open list closes).
   * @returns {void}
   */
  dispose() {
    this.gone = true;
    this.timers.forEach((t) => clearTimeout(t));
    this.timers.clear();
    this.ui.forEach((u) => u.destroy());
    this.ui.clear();
  }
}

/** @private what makes a control drawn again: its choices and bounds */
function sigOf(d) { return JSON.stringify([d.kind, d.options, d.min, d.max, d.step, d.range, d.required]); }

/** @private the focused element of the root a node is in (a shadow root's own) */
function rootActive(node) {
  const r = node && node.getRootNode ? node.getRootNode() : null;
  return r && 'activeElement' in r ? r.activeElement : (node && node.ownerDocument ? node.ownerDocument.activeElement : null);
}
