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
/* The wells' menus (BI_GUIDE.md sections P2 and 4), the same in the compact line, the panes and the terminal's lane. A value's ▾: the
   functions valid for its field in plain words (Sum, Count, Distinct count, Average, Weighted average…, Min, Max, Median, Percentile…,
   Std dev, Custom ƒ…), an invalid one off with its reason; a value computed per cell (VaR, ES) says so and offers the host's options
   instead; Show value as (only the forms the server computes); another value of the same field; moves and removal. A field's ▾ in Rows,
   Columns or Filters: its members, its order and top N, moves. The value filter menu. Nothing is computed here: the wells change and the
   server answers. */
import { FUNC_ARGS, funcLabel } from './drishti_wells.js';
import { parseCondition } from './drishti_grid-ui.js';

const NAMES = { filters: 'Filters', rows: 'Rows', columns: 'Columns', values: 'Values' };
const PER_CELL = 'Computed per cell by the server from the cell\'s own members: a total is never a sum of its cells (Σ − total = the diversification)';

/** @private whether a column's figure is computed per cell (VaR, ES) */
function perCell(c) { return !!c && (c.perCell || (c.measure && c.additive === false)); }

/**
 * A chip's menu: a value's functions and show-as, a field's members, order and top N, moves, removal.
 * @param {Object} wv the WellsView
 * @param {Element} chip
 * @returns {void}
 */
export function chipMenu(wv, chip) {
  const field = chip.dataset.field, well = chip.dataset.well, index = +chip.dataset.index, v = wv.view, w = wv.w(), c = w.columns.get(field);
  if (well === 'values') { valueMenu(wv, chip, index); return; }
  const items = () => {
    const out = [];
    if (well === 'filters') {
      const f = w.state.filters[index].filter, ex = new Set(f && f.excluded ? f.excluded.map(String) : []);
      out.push({ heading: 'Show ' + c.header });
      v.membersOf(field).slice(0, 200).forEach((mm) => out.push({ id: 'member:' + mm.text, label: mm.text + (mm.count !== undefined ? '  (' + mm.count + ')' : ''), checked: !ex.has(mm.text), keep: true }));
      out.push({ id: 'members-all', label: 'Select all' }, { id: 'members-none', label: 'Clear all' }, { sep: true });
    }
    if ((well === 'rows' || well === 'columns') && v.model.pivotMode) {
      const s = (v.model.pivotExtra.sort || {})[c.field], first = w.toView().values[0];
      out.push({ heading: 'Order of ' + c.header }, { id: 'order-label', label: 'By name', checked: !s || s.by === 'label' });
      if (first) { out.push({ id: 'order-measure', label: 'By ' + first.as + ', largest first', checked: !!s && s.by === first.as }, { id: 'top-5', label: 'Top 5 by ' + first.as + ' (the rest in Others)', checked: !!(v.model.pivotExtra.top || {})[c.field] }); }
      out.push({ sep: true });
    }
    if (!well) { ['rows', 'columns', 'values', 'filters'].forEach((t) => out.push({ id: 'to:' + t, label: 'Add to ' + NAMES[t], disabled: !w.canDrop(field, t).ok, why: w.canDrop(field, t).why })); }
    else {
      ['rows', 'columns', 'values', 'filters'].filter((t) => t !== well).forEach((t) => out.push({ id: 'to:' + t, label: 'Move to ' + NAMES[t], disabled: !w.canDrop(field, t).ok, why: w.canDrop(field, t).why }));
      out.push({ id: 'left', label: 'Move left', key: 'Ctrl+←', disabled: index === 0 }, { id: 'right', label: 'Move right', key: 'Ctrl+→' }, { sep: true }, { id: 'remove', label: 'Remove', key: 'Delete', icon: '×' });
    }
    return out;
  };
  v.menu.open(items, { anchor: chip, label: c.header + ' options', theme: v.host, run: (id) => chipAction(wv, id, field, well, index) });
}

/**
 * A value's ▾ (BI_GUIDE.md section P4): its function, show-as, another value of the field, moves and removal.
 * @param {Object} wv the WellsView
 * @param {Element} anchor
 * @param {number} index the value's place
 * @returns {void}
 */
export function valueMenu(wv, anchor, index) {
  const v = wv.view, w = wv.w(), val = w.state.values[index];
  if (!val) { return; }
  const c = w.columns.get(val.colId), header = c ? c.header : val.colId;
  const items = () => {
    const cur = w.state.values[index] || val, out = [];
    if (perCell(c)) {
      out.push({ heading: header + ' · computed per cell' }, { note: c.perCellNote || PER_CELL });
      const opts = wv.opts = v.o.valueOptions ? v.o.valueOptions(c, cur) || {} : {};
      (opts.items || []).forEach((it) => out.push(it.id ? Object.assign({}, it, { id: 'opt:' + it.id }) : it));
    } else {
      out.push({ heading: 'Summarize ' + header + ' by' });
      w.funcsFor(cur.colId).forEach((f) => {
        const on = cur.aggFunc === f.name;
        const label = on && cur.args && cur.args.length ? funcLabel(f.name).replace('…', '') + ' (' + (FUNC_ARGS[f.name].kind === 'field' ? 'by ' + wv.header(String(cur.args[0])) : cur.args.join(', ')) + ')' : f.label;
        out.push({ id: 'agg:' + f.name, label: label + (f.server && !FUNC_ARGS[f.name] && v.o.clientAggregation !== false && !v.o.serverPivot ? ' (server)' : ''), checked: on, disabled: f.disabled, why: f.why, showWhy: true });
      });
      if (out.length === 1) { out.push({ note: header + ' is its own measure (' + w.measureOf(cur) + '): Custom ƒ… writes another' }); }
      if (v.o.customFormulas !== false) { out.push({ id: 'custom', label: funcLabel('custom'), checked: cur.aggFunc === 'custom' }); }
      (v.o.customFunctions || []).forEach((f) => out.push({ id: 'fn:' + f.name, label: 'ƒ ' + (f.label || f.name), checked: !!cur.formula && cur.formula.indexOf(f.name + '(') === 0 }));
    }
    const forms = v.model.showAs || [];
    if (forms.length) {
      out.push({ sep: true }, { heading: 'Show value as' }, { id: 'show:', label: 'The figure itself', checked: !cur.showAs });
      const dims = w.state.rows.concat(w.state.columns);
      forms.forEach((s) => {
        const pct = String(s.id).indexOf('pct-') === 0 && s.needs !== 'dimension';
        const off = pct && perCell(c) ? header + ' does not add up (its parts exceed its total): a share of a total would mislead' : null;
        if (s.needs === 'dimension') {
          dims.forEach((d) => { const f = (w.columns.get(d) || {}).field || d, id = s.id + ':1:' + f; out.push({ id: 'show:' + id, label: s.label + ' ' + wv.header(d), checked: cur.showAs === id }); });
        } else { out.push({ id: 'show:' + s.id, label: s.label, checked: cur.showAs === s.id, disabled: !!off, why: off, showWhy: true }); }
      });
    }
    out.push({ sep: true });
    if (!perCell(c)) { out.push({ id: 'again', label: 'Add another value of ' + header }); }
    out.push({ id: 'left', label: 'Move left', key: 'Ctrl+←', disabled: index === 0 }, { id: 'right', label: 'Move right', key: 'Ctrl+→' }, { id: 'remove', label: 'Remove', key: 'Delete', icon: '×' });
    return out;
  };
  v.menu.open(items, { anchor: anchor, label: header + ' value options', theme: v.host, run: (id, value) => valueAction(wv, anchor, index, id, value) });
}

/** @private what a value menu's item does */
function valueAction(wv, anchor, index, id, value) {
  const v = wv.view, w = wv.w(), val = w.state.values[index], c = w.columns.get(val.colId), header = c ? c.header : val.colId;
  const said = (r, what) => { if (!r.ok) { v.say(r.why); return; } v.wellsChanged(what); };
  if (id.indexOf('agg:') === 0) {
    const fn = id.slice(4);
    if (FUNC_ARGS[fn]) { argsMenu(wv, anchor, index, fn); return; }
    said(w.setAggregation(index, fn), header + ': ' + funcLabel(fn));
    return;
  }
  if (id === 'custom' || id.indexOf('fn:') === 0) { formulaMenu(wv, anchor, index, id.indexOf('fn:') === 0 ? id.slice(3) : null); return; }
  if (id.indexOf('show:') === 0) { w.setShowAs(index, id.slice(5) || null); v.wellsChanged(header + (id.length > 5 ? ' shown as ' + id.slice(5) : ' as its figure')); return; }
  if (id.indexOf('opt:') === 0) { if (wv.opts && wv.opts.run) { wv.opts.run(id.slice(4), c, val); } return; }
  if (id === 'again') {
    const other = w.funcsFor(val.colId).filter((f) => !f.disabled && !FUNC_ARGS[f.name] && f.name !== val.aggFunc)[0];
    w.drop(val.colId, 'values', index + 1, { aggFunc: other ? other.name : val.aggFunc });
    v.wellsChanged('Another value of ' + header);
    return;
  }
  if (id === 'left' || id === 'right') { wv.move(val.colId, 'values', index, 'values', id === 'left' ? Math.max(0, index - 1) : index + 2); return; }
  if (id === 'remove') { wv.remove('values', index, val.colId); }
}

/** @private a function's argument: wavg's weight field, percentile's quantile */
function argsMenu(wv, anchor, index, fn) {
  const v = wv.view, w = wv.w(), val = w.state.values[index], header = wv.header(val.colId), a = FUNC_ARGS[fn];
  const items = () => {
    if (a.kind === 'field') {
      const out = [{ heading: funcLabel(fn).replace('…', '') + ' of ' + header + ': the weight' }];
      w.columns.fields('').filter((f) => f.type === 'number' && !f.measure && f.id !== val.colId).forEach((f) => out.push({ id: 'arg:' + f.id, label: f.header, checked: fn === val.aggFunc && val.args && val.args[0] === f.id }));
      if (out.length === 1) { out.push({ note: 'No other number field to weigh by' }); }
      return out;
    }
    const q = fn === val.aggFunc && val.args ? val.args[0] : a.default;
    return [{ heading: funcLabel(fn).replace('…', '') + ' of ' + header }].concat([0.5, 0.9, 0.95, 0.99].map((x) => ({ id: 'arg:' + x, label: 'p' + Math.round(x * 100) + (x === 0.5 ? ' (the median)' : ''), checked: +q === x })),
      [{ id: 'arg-typed', label: a.label, input: { placeholder: String(a.default), value: String(q) } }]);
  };
  v.menu.open(items, { anchor: anchor, label: funcLabel(fn), theme: v.host, run: (id, typed) => {
    const arg = id === 'arg-typed' ? String(typed || '').trim() : id.slice(4);
    const r = w.setAggregation(index, fn, [a.kind === 'number' ? +arg : arg]);
    if (!r.ok) { v.say(r.why); return; }
    v.wellsChanged(header + ': ' + w.labelOf(w.state.values[index]));
  } });
}

/**
 * A custom formula for a value: the host's formula editor when it has one (GridOptions.formulaEditor, the console's Rachana editor),
 * else a text field; the server checks the formula when it answers (a refusal says why, in words).
 * @param {Object} wv the WellsView
 * @param {Element} anchor
 * @param {number} index
 * @param {?string} fn a custom function's name to start from (wret)
 * @returns {void}
 */
export function formulaMenu(wv, anchor, index, fn) {
  const v = wv.view, w = wv.w(), val = w.state.values[index], c = w.columns.get(val.colId);
  const start = val.formula || (fn ? fn + '(' + (c ? c.field : val.colId) + ')' : 'sum(' + (c ? c.field : val.colId) + ')');
  const done = (text, label) => { const r = w.setFormula(index, text, label); if (!r.ok) { v.say(r.why); return; } v.wellsChanged('Formula: ' + text + ' (the server checks it)'); };
  if (v.o.formulaEditor) { v.o.formulaEditor({ formula: start, field: c, value: val, done: done }); return; }
  v.menu.open([{ heading: 'Custom formula' }, { note: 'A Rachana formula or a custom function: sum(PV) / sum(NOTIONAL), wret(RET, W). The server checks it when it answers.' },
    { id: 'formula', label: 'Formula', input: { placeholder: 'sum(PV) / sum(NOTIONAL)', value: start } }], { anchor: anchor, label: 'Custom formula', theme: v.host, run: (id, text) => done(text) });
}

/**
 * What a field chip's menu item does.
 * @param {Object} wv the WellsView
 * @param {string} id
 * @param {string} field
 * @param {string} well
 * @param {number} index
 * @returns {void}
 */
export function chipAction(wv, id, field, well, index) {
  const v = wv.view, w = wv.w(), c = w.columns.get(field);
  if (id.indexOf('agg:') === 0) { const r = w.setAggregation(index, id.slice(4)); if (!r.ok) { v.say(r.why); } v.wellsChanged(c.header + ': ' + w.funcLabel(id.slice(4))); return; }
  if (id.indexOf('member:') === 0) {
    const text = id.slice(7), f = w.state.filters[index].filter, ex = new Set(f && f.excluded ? f.excluded.map(String) : []);
    if (ex.has(text)) { ex.delete(text); } else { ex.add(text); }
    w.setFilter(field, ex.size ? { type: 'set', excluded: Array.from(ex) } : null);
    v.wellsChanged(null);
    return;
  }
  if (id === 'members-all') { w.setFilter(field, null); v.wellsChanged('All of ' + c.header); return; }
  if (id === 'members-none') { w.setFilter(field, { type: 'set', excluded: v.membersOf(field).map((m) => m.text) }); v.wellsChanged('None of ' + c.header); return; }
  if (id === 'order-label' || id === 'order-measure') {
    const sort = Object.assign({}, v.model.pivotExtra.sort);
    const first = w.toView().values[0];
    if (id === 'order-label') { delete sort[c.field]; } else { sort[c.field] = { by: first.as, desc: true }; }
    v.setPivotOptions({ sort: sort }, 'Order of ' + c.header + ' changed');
    return;
  }
  if (id === 'top-5') {
    const top = Object.assign({}, v.model.pivotExtra.top), first = w.toView().values[0];
    if (top[c.field]) { delete top[c.field]; } else { top[c.field] = { n: 5, by: first.as }; }
    v.setPivotOptions({ top: top }, 'Top 5 of ' + c.header);
    return;
  }
  if (id.indexOf('to:') === 0) { wv.move(field, well, index, id.slice(3)); return; }
  if (id === 'left' || id === 'right') { wv.move(field, well, index, well, id === 'left' ? Math.max(0, index - 1) : index + 2); return; }
  if (id === 'remove') { wv.remove(well, well === 'values' ? index : field, field); }
}

/**
 * The value filter menu (a new one when index is -1): the measure, a condition from the figures or typed ("> 400k").
 * @param {Object} wv the WellsView
 * @param {number} index
 * @param {Element} anchor
 * @returns {void}
 */
export function valueFilterMenu(wv, index, anchor) {
  const v = wv.view, w = wv.w();
  const view = w.toView(), dims = view.rows, measures = view.values.map((x) => x.as);
  if (!dims.length || !measures.length) { v.say('A value filter needs a row field and a value'); return; }
  const list = (v.model.pivotExtra.valueFilters || []).slice();
  const cur = index >= 0 ? Object.assign({}, list[index]) : { dim: dims[dims.length - 1], by: measures[0], op: 'gt', value: 0 };
  const save = (f) => { if (index >= 0) { list[index] = f; } else { list.push(f); } v.setValueFilters(list); };
  const items = () => {
    const med = v.levelFigures(cur.dim, cur.by);
    const out = [{ heading: 'Value filter on ' + wv.header(cur.dim) }];
    dims.forEach((d) => out.push({ id: 'dim:' + d, label: 'Level: ' + wv.header(d), checked: cur.dim === d, keep: true }));
    out.push({ sep: true }, { heading: 'Measure' });
    measures.forEach((m) => out.push({ id: 'by:' + m, label: m, checked: cur.by === m, keep: true }));
    out.push({ sep: true }, { heading: 'Condition' }, { id: 'c:gt:0', label: cur.by + ' > 0' }, { id: 'c:lt:0', label: cur.by + ' < 0' });
    if (med) { out.push({ id: 'c:gt:' + med.median, label: cur.by + ' > ' + v.fmtMeasure(cur.by, med.median) + ' (the median)' }, { id: 'c:gt:' + med.p75, label: cur.by + ' > ' + v.fmtMeasure(cur.by, med.p75) + ' (the top quarter)' }); }
    out.push({ id: 'custom', label: 'Condition', input: { placeholder: '> 400k', value: index >= 0 ? (cur.op === 'gt' ? '> ' : cur.op === 'lt' ? '< ' : '') + cur.value : '' } });
    if (index >= 0) { out.push({ sep: true }, { id: 'remove', label: 'Remove this filter', icon: '×' }); }
    return out;
  };
  v.menu.open(items, { anchor: anchor, label: 'Value filter', theme: v.host, run: (id, value) => {
    if (id.indexOf('dim:') === 0) { cur.dim = id.slice(4); return; }
    if (id.indexOf('by:') === 0) { cur.by = id.slice(3); return; }
    if (id.indexOf('c:') === 0) { const [, op, n] = id.split(':'); save(Object.assign(cur, { op: op, value: +n })); return; }
    if (id === 'custom') { const c = parseCondition(value); if (!c) { v.say('Write a condition such as > 400k or < 0'); return; } save(Object.assign(cur, c)); return; }
    if (id === 'remove') { v.setValueFilters(list.filter((x, k) => k !== index)); }
  } });
}
