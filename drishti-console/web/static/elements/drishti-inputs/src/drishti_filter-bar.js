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
/* The filter bar: the filters of a page as chips ("Desk in Rates, FX", "MTM > 1m", "Trade date 1–6 Nov 2026"), a + Filter menu of the
   fields, a quick-search field and Clear all. A chip opens its editor (members with search and select-all; a condition and a value for a
   number or text; presets and two ends for a date), × takes it away. What it emits is drishti-core's filter model (the grid's own:
   {column: {type: set|number|text|date, ...}}) with the quick filter, on a bus under FILTER_CHANNEL: every grid and chart on that bus
   follows it (drishti-grid: grid.followFilters(bus); a chart: filterRows(rows, model)). FilterModelBar is the model, tested in Node. */
import { bus as sharedBus, FILTER_CHANNEL, filterWords, formatDateRange, announce, parseNumberText } from '../../drishti-core/src/drishti_index.js';
import { DATE_PRESETS, resolvePreset, parseDateInput, calendar } from './drishti_calendar.js';
import { InputBase, Popover, Listbox, normalizeOptions, searchOptions, uid } from './drishti_inputs-dom.js';
import { Select } from './drishti_select.js';

const OPS = { number: [['gt', '>'], ['gte', '≥'], ['lt', '<'], ['lte', '≤'], ['equals', '='], ['notEquals', '≠'], ['between', 'between']],
  text: [['contains', 'contains'], ['notContains', 'does not contain'], ['equals', 'is'], ['notEquals', 'is not'], ['startsWith', 'starts with'], ['endsWith', 'ends with']],
  list: [['has', 'contains'], ['anyOf', 'any of'], ['allOf', 'all of']] };

/**
 * A field the bar may filter.
 * @typedef {Object} FilterField
 * @property {string} id the column id the model uses
 * @property {string} [label]
 * @property {('set'|'number'|'text'|'date'|'list')} [type='set'] list: a list attribute (contains / any of / all of)
 * @property {Array} [options] a set's members (strings or {value, label})
 * @property {function(string): (Promise<Array>|Array)} [members] a set's members from a server, by search text
 */

/**
 * The filter bar's model: fields, the filter model, the chips' words and every edit.
 */
export class FilterModelBar {
  /**
   * @param {{fields: FilterField[], model: Object, quickFilter: string}} o
   */
  constructor(o) {
    this.fields = (o.fields || []).map((f) => Object.assign({ type: 'set', label: f.id }, f));
    this.model = Object.assign({}, o.model);
    this.quickFilter = o.quickFilter || '';
  }

  /**
   * A field by id.
   * @param {string} id
   * @returns {?FilterField}
   */
  field(id) { return this.fields.find((f) => f.id === id) || null; }

  /**
   * The chips: each filtered field and its words.
   * @returns {Array<{id: string, label: string, text: string}>}
   */
  chips() {
    return Object.keys(this.model).filter((k) => this.model[k]).map((k) => {
      const f = this.field(k) || { label: k }, m = this.model[k];
      let text;
      if (m.type === 'date' && m.op === 'between') { text = f.label + ' ' + formatDateRange(m.value, m.valueTo) + (m.preset ? ' · ' + ((DATE_PRESETS.find((p) => p.id === m.preset) || {}).label || m.preset) : ''); }
      else if (m.type === 'set' && m.values && m.values.length > 3) { text = f.label + ' in ' + m.values.slice(0, 2).join(', ') + ' and ' + (m.values.length - 2) + ' more'; }
      else { text = filterWords({ [k]: m }, () => f.label); }
      return { id: k, label: f.label, text: text };
    });
  }

  /**
   * Sets one field's filter (null takes it away).
   * @param {string} id
   * @param {?Object} filter a ColumnFilter
   * @returns {void}
   */
  set(id, filter) { if (filter) { this.model[id] = filter; } else { delete this.model[id]; } }

  /**
   * Keeps a set's members (none: the filter goes).
   * @param {string} id
   * @param {Array} values
   * @returns {void}
   */
  setMembers(id, values) { this.set(id, values && values.length ? { type: 'set', values: values.slice() } : null); }

  /**
   * A number or text condition; a value that does not read takes nothing and says why.
   * @param {string} id
   * @param {string} op
   * @param {string} text
   * @param {string} [textTo] between's upper end
   * @returns {?string} the problem in words, or null
   */
  setCondition(id, op, text, textTo) {
    const f = this.field(id);
    if (f.type === 'number') {
      const v = parseNumberText(text), w = op === 'between' ? parseNumberText(textTo) : null;
      if (v === null || (op === 'between' && w === null)) { return 'Write a number such as 1,000,000 or 1m'; }
      this.set(id, op === 'between' ? { type: 'number', op: op, value: Math.min(v, w), valueTo: Math.max(v, w) } : { type: 'number', op: op, value: v });
      return null;
    }
    if (!String(text || '').trim()) { this.set(id, null); return null; }
    if (f.type === 'list') {                                     // values separated by commas: one for contains, several for any of / all of
      const vs = String(text).split(',').map((x) => x.trim()).filter(Boolean);
      this.set(id, { type: 'list', op: op === 'has' ? 'has' : op, values: op === 'has' ? vs.slice(0, 1) : vs });
      return null;
    }
    this.set(id, { type: 'text', op: op, value: String(text).trim() });
    return null;
  }

  /**
   * A date range: a preset (it moves with the business date) or two ends as typed.
   * @param {string} id
   * @param {{preset: string, from: string, to: string, asOf: string, calendar: string}} r
   * @returns {?string} the problem in words, or null
   */
  setDates(id, r) {
    let from = null, to = null;
    if (r.preset) { const x = resolvePreset(r.preset, { asOf: r.asOf, calendar: r.calendar }); from = x.from; to = x.to; }
    else {
      from = parseDateInput(r.from, { asOf: r.asOf, calendar: r.calendar }); to = parseDateInput(r.to, { asOf: r.asOf, calendar: r.calendar });
      if (!from || !to) { return 'Write both ends as 2026-11-06, 6 Nov 2026, T-5 or ME'; }
      if (from > to) { const x = from; from = to; to = x; }
    }
    this.set(id, { type: 'date', op: 'between', value: from, valueTo: to, preset: r.preset || undefined });
    return null;
  }

  /**
   * Takes every filter away (and the quick filter).
   * @returns {void}
   */
  clear() { this.model = {}; this.quickFilter = ''; }

  /**
   * What the bar emits: the model (a copy), the quick filter.
   * @returns {{model: Object, quickFilter: string}}
   */
  payload() { return { model: JSON.parse(JSON.stringify(this.model)), quickFilter: this.quickFilter }; }
}

/**
 * @typedef {Object} FilterBarOptions
 * @property {FilterField[]} fields
 * @property {Object} [model] the filters at the start
 * @property {Object} [bus] the drishti-core EventBus it emits on (default: the page's shared bus)
 * @property {string} [channel] the event name (default FILTER_CHANNEL)
 * @property {boolean} [quick=true] the quick-search field
 * @property {string} [asOf] the business date date presets count from
 * @property {string} [calendar] the business calendar of date fields
 * @property {string} [label='Filters']
 */

/**
 * The filter bar. Events: change {model, quickFilter}; the same payload on the bus under the channel.
 */
export class FilterBar extends InputBase {
  /**
   * @param {HTMLElement} host
   * @param {FilterBarOptions} options
   */
  constructor(host, options) {
    super(host, options, 'di-filter-bar');
    const o = this.o = Object.assign({ quick: true, label: 'Filters', channel: FILTER_CHANNEL }, this.o);
    this.bus = o.bus || sharedBus;
    this.id = uid('fb');
    this.state = new FilterModelBar({ fields: o.fields, model: o.model });
    this.pop = new Popover(this.doc, { cls: 'di-fb-pop', label: 'Filter', theme: host });
    this.chipsEl = this.el('div', { cls: 'di-fb-chips', attrs: { role: 'list', 'aria-label': o.label } });
    this.addBtn = this.el('button', { cls: 'di-btn di-fb-add', text: '+ Filter', attrs: { type: 'button', 'aria-haspopup': 'listbox' }, on: { click: () => this.addMenu() } });
    this.clearBtn = this.el('button', { cls: 'di-btn di-fb-clear', text: 'Clear all', attrs: { type: 'button' }, on: { click: () => { this.state.clear(); if (this.quickEl) { this.quickEl.value = ''; } this.changed('Every filter cleared'); } } });
    const parts = [this.el('span', { cls: 'di-fb-label', text: o.label, attrs: { id: this.id + '-label' } }), this.chipsEl, this.addBtn];
    if (o.quick) {
      this.quickEl = this.el('input', { cls: 'di-field di-fb-quick', attrs: { type: 'search', placeholder: 'Search every column', 'aria-label': 'Search every column' } });
      let t = 0;
      this.quickEl.addEventListener('input', () => { clearTimeout(t); t = setTimeout(() => { this.state.quickFilter = this.quickEl.value.trim(); this.changed(); }, 200); });
      parts.push(this.quickEl);
    }
    parts.push(this.clearBtn);
    host.appendChild(this.el('div', { cls: 'di-fb', attrs: { role: 'toolbar', 'aria-labelledby': this.id + '-label' } }, parts));
    this.render();
  }

  /**
   * Draws the chips.
   * @returns {void}
   */
  render() {
    this.chipsEl.textContent = '';
    this.state.chips().forEach((c) => this.chipsEl.appendChild(this.el('span', { cls: 'di-chip di-fb-chip', attrs: { role: 'listitem' }, data: { field: c.id } }, [
      this.el('button', { cls: 'di-chip-label di-fb-edit', text: c.text, attrs: { type: 'button', 'aria-haspopup': 'dialog', title: 'Change: ' + c.text }, on: { click: (e) => this.edit(c.id, e.currentTarget) } }),
      this.el('button', { cls: 'di-chip-x', text: '×', attrs: { type: 'button', 'aria-label': 'Remove the filter ' + c.text }, on: { click: () => { this.state.set(c.id, null); this.changed('Removed: ' + c.text); } } })])));
    this.clearBtn.disabled = !this.state.chips().length && !this.state.quickFilter;
  }

  /** @private the model changed: draw, tell the component's listeners and the bus */
  changed(words) {
    this.render();
    const p = this.state.payload();
    this.emit('change', p);
    this.bus.emit(this.o.channel, Object.assign({ source: this.id }, p));
    if (words) { announce(words); }
  }

  /** @private + Filter: the fields to pick from */
  addMenu() {
    const box = this.el('div', { cls: 'di-fb-menu' });
    const list = new Listbox(this.doc, { label: 'Fields', onPick: (it) => { this.pop.close(); this.edit(it.value, this.addBtn); } });
    const search = this.el('input', { cls: 'di-field di-search', attrs: { type: 'search', placeholder: 'Find a field', 'aria-label': 'Find a field', 'aria-controls': list.id } });
    const all = this.state.fields.map((f) => ({ value: f.id, label: f.label, detail: this.state.model[f.id] ? 'filtered' : f.type }));
    const draw = () => { list.render(searchOptions(normalizeOptions(all), search.value), () => false); list.setActive(0, search); };
    search.addEventListener('input', draw);
    search.addEventListener('keydown', (e) => list.key(e, search));
    box.append(search, list.el);
    draw();
    this.pop.open(this.addBtn, box);
  }

  /**
   * Opens a field's editor under an anchor.
   * @param {string} id
   * @param {Element} [anchor]
   * @returns {Promise<void>}
   */
  edit(id, anchor) {
    const f = this.state.field(id);
    if (!f) { return Promise.resolve(); }
    const at = anchor || this.addBtn, cur = this.state.model[id], box = this.el('div', { cls: 'di-fb-editor', data: { field: id } });
    let first = null;
    const msg = this.el('div', { cls: 'di-msg', attrs: { 'aria-live': 'polite' } });
    const head = this.el('div', { cls: 'di-fb-editor-head', text: f.label });
    const done = this.el('button', { cls: 'di-btn di-primary', text: 'Apply', attrs: { type: 'button' }, data: { act: 'apply' } });
    const foot = this.el('div', { cls: 'di-form-actions' }, [msg, this.el('span', { cls: 'di-sp' }), done]);
    box.append(head);
    if (f.type === 'set') {
      const picked = new Set(cur && cur.values ? cur.values.map(String) : []);
      const list = new Listbox(this.doc, { multiple: true, label: f.label, onPick: (it) => { const k = String(it.value); if (picked.has(k)) { picked.delete(k); } else { picked.add(k); } draw(); } });
      const search = this.el('input', { cls: 'di-field di-search', attrs: { type: 'search', placeholder: 'Search ' + f.label, 'aria-label': 'Search ' + f.label, 'aria-controls': list.id } });
      let members = normalizeOptions(f.options || []);
      const draw = () => { const shown = searchOptions(members, search.value); list.render(shown, (it) => picked.has(String(it.value))); if (list.active < 0) { list.setActive(0, search); } count.textContent = picked.size + ' picked'; };
      const count = this.el('span', { cls: 'di-count' });
      const all = this.el('button', { cls: 'di-btn', text: 'Select all', attrs: { type: 'button' }, data: { act: 'all' }, on: { click: () => { searchOptions(members, search.value).forEach((it) => picked.add(String(it.value))); draw(); } } });
      const none = this.el('button', { cls: 'di-btn', text: 'None', attrs: { type: 'button' }, data: { act: 'none' }, on: { click: () => { picked.clear(); draw(); } } });
      search.addEventListener('input', () => { if (f.members) { Promise.resolve(f.members(search.value)).then((m) => { members = normalizeOptions(m); draw(); }); } else { draw(); } });
      search.addEventListener('keydown', (e) => list.key(e, search));
      box.append(search, this.el('div', { cls: 'di-ms-head' }, [all, none, count]), list.el);
      done.addEventListener('click', () => { this.state.setMembers(id, members.filter((m) => picked.has(String(m.value))).map((m) => m.value).concat(Array.from(picked).filter((k) => !members.some((m) => String(m.value) === k)))); this.pop.close(true); this.changed(); });
      if (f.members) { Promise.resolve(f.members('')).then((m) => { members = normalizeOptions(m); draw(); }); } else { draw(); }
    } else if (f.type === 'date') {
      const cal = calendar(this.o.calendar), preset = this.pick('Preset', [{ value: '', label: 'Custom range' }].concat(DATE_PRESETS.map((p) => ({ value: p.id, label: p.label }))), cur && cur.preset ? cur.preset : '');
      const from = this.el('input', { cls: 'di-field', attrs: { type: 'text', 'aria-label': 'From', placeholder: 'From: T-5, 1 Nov 2026' } });
      const to = this.el('input', { cls: 'di-field', attrs: { type: 'text', 'aria-label': 'To', placeholder: 'To: T, ME' } });
      if (cur && !cur.preset) { from.value = cur.value; to.value = cur.valueTo; }
      box.append(preset, this.el('div', { cls: 'di-range-ends' }, [from, this.el('span', { text: '→' }), to]));
      first = from;
      done.addEventListener('click', () => {
        const why = this.state.setDates(id, { preset: preset.component.getValue() || null, from: from.value, to: to.value, asOf: this.o.asOf, calendar: cal });
        if (why) { msg.textContent = why; return; }
        this.pop.close(true);
        this.changed();
      });
    } else {
      const ops = OPS[f.type === 'number' ? 'number' : f.type === 'list' ? 'list' : 'text'], op = this.pick('Condition', ops.map(([k, w]) => ({ value: k, label: w })), cur && cur.op ? cur.op : ops[0][0]);
      const val = this.el('input', { cls: 'di-field', attrs: { type: 'text', 'aria-label': 'Value', placeholder: f.type === 'number' ? '1m, 250k, −5,000' : f.type === 'list' ? 'fx, rates' : 'Text' } });
      const val2 = this.el('input', { cls: 'di-field', attrs: { type: 'text', 'aria-label': 'Up to', placeholder: 'and' } });
      if (cur) { val.value = cur.values && f.type === 'list' ? cur.values.join(', ') : cur.value === undefined ? '' : String(cur.value); val2.value = cur.valueTo === undefined ? '' : String(cur.valueTo); }
      const showTo = () => { val2.hidden = op.component.getValue() !== 'between'; };
      op.component.on('change', showTo);
      first = val;
      showTo();
      const apply = () => { const why = this.state.setCondition(id, op.component.getValue(), val.value, val2.value); if (why) { msg.textContent = why; val.setAttribute('aria-invalid', 'true'); return; } this.pop.close(true); this.changed(); };
      [val, val2].forEach((x) => x.addEventListener('keydown', (e) => { if (e.key === 'Enter') { e.preventDefault(); apply(); } }));
      done.addEventListener('click', apply);
      box.append(this.el('div', { cls: 'di-fb-cond' }, [op, val, val2]));
    }
    box.appendChild(foot);
    this.pop.open(at, box);
    if (first) { first.focus(); }                                          // the value, not the condition's Select: typing starts the value
    return Promise.resolve();
  }

  /** @private a choice in the filter's panel: drishti-inputs' Select in a <drishti-select> (its list a panel nested in this one) */
  pick(label, options, value) {
    const box = this.el('drishti-select', { cls: 'di-fb-pick' });
    box.component = new Select(box, { label, options, value, search: false });
    return box;
  }

  /**
   * The filter model and the quick filter.
   * @returns {{model: Object, quickFilter: string}}
   */
  getValue() { return this.state.payload(); }

  /**
   * Sets the filters (and tells the bus).
   * @param {{model: Object, quickFilter: string}} v
   * @returns {void}
   */
  setValue(v) { this.state.model = Object.assign({}, v && v.model); this.state.quickFilter = (v && v.quickFilter) || ''; if (this.quickEl) { this.quickEl.value = this.state.quickFilter; } this.changed(); }
}
