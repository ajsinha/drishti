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
/* The wells as drawn: the field list (searched) and the four wells, Filters, Rows, Columns and Values, as chips a person drags between
   them (or moves from the chip's menu: the keyboard's way). Three arrangements of one model: the ribbon above the grid (layout wells),
   the side tool panel with a grouped field list, checkboxes and switches (layout panel), the lane under the terminal's command line
   (layout terminal). A chip's menu holds its aggregation (a non-additive value says the server computes it), a filter's members, the
   value filters and top N of the pivot, a move to another well and the removal. A drop that cannot be made says why. */
import { h } from './drishti_grid-dom.js';
import { VALUE_FUNCS } from './drishti_wells.js';
import { aggFunc } from './drishti_aggregate.js';
import { parseCondition, conditionText } from './drishti_grid-ui.js';

const NAMES = { filters: 'Filters', rows: 'Rows', columns: 'Columns', values: 'Values' };
const LANE = { rows: 'ROWS', columns: 'COLS', values: 'VALS', filters: 'FILT' };
const MIME = 'text/x-drishti-field';

/**
 * The wells and the field list of a grid's view.
 */
export class WellsView {
  /**
   * @param {Object} view the Grid (drishti_grid-view.js)
   */
  constructor(view) {
    this.view = view;
    this.doc = view.doc;
    this.search = '';
    this.dragging = null;
  }

  /** @private the wells model being edited (the draft while updates are deferred) */
  w() { return this.view.activeWells(); }

  /**
   * Draws the wells in an arrangement into an element.
   * @param {HTMLElement} el
   * @param {('ribbon'|'panel'|'lane')} how
   * @returns {void}
   */
  render(el, how) {
    const doc = this.doc;
    const keepFocus = doc.activeElement && el.contains(doc.activeElement) && doc.activeElement.classList.contains('dg-field-search');
    el.textContent = '';
    el.className = 'dg-wells dg-wells-' + how;
    const order = how === 'lane' ? ['rows', 'columns', 'values', 'filters'] : how === 'panel' ? ['filters', 'columns', 'rows', 'values'] : ['filters', 'rows', 'columns', 'values'];
    const zones = h(doc, 'div', { cls: 'dg-zones' }, order.map((w) => this.zone(w, how)));
    const search = h(doc, 'input', { cls: 'dg-field-search', attrs: { type: 'search', placeholder: 'Find a field', 'aria-label': 'Find a field', value: this.search } });
    search.addEventListener('input', () => { this.search = search.value; this.view.renderChrome(); });
    if (how === 'panel') {
      el.append(h(doc, 'div', { cls: 'dg-field-list', attrs: { role: 'group', 'aria-label': 'Fields' } }, [search].concat(this.groupedFields())), zones, this.switches());
    } else if (how === 'lane') {
      el.append(zones, h(doc, 'div', { cls: 'dg-lane-fields' }, [h(doc, 'span', { cls: 'dg-zl', text: '+ FIELD' }), search].concat(this.unusedChips())));
    } else {
      el.append(h(doc, 'span', { cls: 'dg-zl', text: 'Fields' }), h(doc, 'div', { cls: 'dg-fields', attrs: { role: 'list', 'aria-label': 'Fields' } }, [search].concat(this.unusedChips())),
        h(doc, 'span', { cls: 'dg-zl', text: 'Wells' }), zones);
    }
    if (!el.dataset.dgWired) { el.dataset.dgWired = '1'; this.listen(el); }
    if (keepFocus) { const s = el.querySelector('.dg-field-search'); s.focus(); s.setSelectionRange(s.value.length, s.value.length); }
  }

  /** @private the fields matching the search */
  fields() { return this.w().fieldList(this.search); }

  /** @private a chip per field in no well */
  unusedChips() { return this.fields().filter((f) => !f.in.length).map((f) => this.chip(f.id, '', -1)); }

  /** @private the panel's grouped list with checkboxes */
  groupedFields() {
    const doc = this.doc, groups = new Map();
    this.fields().forEach((f) => { const g = f.group || (f.value ? 'Measures' : 'Fields'); if (!groups.has(g)) { groups.set(g, []); } groups.get(g).push(f); });
    const out = [];
    groups.forEach((list, g) => {
      out.push(h(doc, 'div', { cls: 'dg-fgroup', text: g }));
      list.forEach((f) => {
        const box = h(doc, 'input', { attrs: { type: 'checkbox', 'aria-label': 'Use ' + f.header }, data: { use: f.id } });
        box.checked = f.in.length > 0;
        out.push(h(doc, 'div', { cls: 'dg-field', attrs: { draggable: 'true' }, data: { field: f.id, well: '', index: '-1' } }, [box, h(doc, 'span', { text: f.header }),
          f.value && !f.additive ? h(doc, 'span', { cls: 'dg-na', text: '◆' }) : null, h(doc, 'span', { cls: 'dg-ftype', text: f.value ? (f.additive ? 'Σ' : 'server') : 'abc' })]));
      });
    });
    return out;
  }

  /** @private the panel's switches */
  switches() {
    const doc = this.doc, v = this.view, m = v.model;
    const sw = (id, label, on) => { const b = h(doc, 'input', { attrs: { type: 'checkbox' }, data: { sw: id } }); b.checked = !!on; return h(doc, 'label', { cls: 'dg-switch' }, [b, ' ' + label]); };
    return h(doc, 'div', { cls: 'dg-switches' }, [sw('pivot', 'Pivot mode', m.pivotMode), sw('grand', 'Grand total column', v.ui.grandColumn),
      sw('above', 'Subtotals above', m.pivotExtra.subtotals === 'above'), sw('defer', 'Defer update', !!v.draft),
      h(doc, 'button', { cls: 'dg-btn', text: 'Update', attrs: { type: 'button', disabled: !v.draft || !v.draftDirty }, data: { act: 'apply-draft' } })]);
  }

  /** @private a well */
  zone(well, how) {
    const doc = this.doc, w = this.w(), st = w.state;
    const label = how === 'lane' ? LANE[well] : NAMES[well];
    const items = well === 'values' ? st.values.map((v, i) => this.chip(v.colId, well, i)) : well === 'filters' ? st.filters.map((f, i) => this.chip(f.colId, well, i)) : st[well].map((id, i) => this.chip(id, well, i));
    if (well === 'filters') {
      (this.view.model.pivotExtra.valueFilters || []).forEach((f, i) => items.push(this.vfChip(f, i)));
      if (st.values.length && st.rows.length) { items.push(h(doc, 'button', { cls: 'dg-add-vf', text: '+ value filter', attrs: { type: 'button', 'aria-label': 'Add a value filter' }, data: { act: 'add-vf' } })); }
    }
    if (!items.length) { items.push(h(doc, 'span', { cls: 'dg-zone-hint', text: how === 'lane' ? '—' : 'Drop fields here' })); }
    return h(doc, 'div', { cls: 'dg-zone', attrs: { role: 'list', 'aria-label': NAMES[well] }, data: { well: well } }, [h(doc, 'span', { cls: 'dg-zl', text: label })].concat(items));
  }

  /** @private a field's chip (in a well, or in the field list when well is '') */
  chip(colId, well, index) {
    const doc = this.doc, w = this.w(), c = w.columns.get(colId);
    if (!c) { return h(doc, 'span', { text: colId }); }
    let text = c.header;
    if (well === 'values') {
      const v = w.state.values[index];
      text = c.additive === false ? c.header : c.header + (v.aggFunc && v.aggFunc !== 'sum' ? ' (' + (aggFunc(v.aggFunc) ? aggFunc(v.aggFunc).label.toLowerCase() : v.aggFunc) + ')' : '');
    }
    if (well === 'filters') {
      const f = w.state.filters[index].filter;
      const n = f && f.excluded ? f.excluded.length : f && f.values ? -f.values.length : 0;
      if (n > 0) { text += ' (−' + n + ')'; } else if (n < 0) { text += ' (' + (-n) + ')'; }
    }
    const kids = [h(doc, 'span', { cls: 'dg-chip-label', text: text })];
    if (c.additive === false && c.type === 'number') { kids.push(h(doc, 'span', { cls: 'dg-na', text: '◆', attrs: { 'aria-label': 'not additive' } })); }
    kids.push(h(doc, 'button', { cls: 'dg-chip-menu', text: '▾', attrs: { type: 'button', tabindex: '-1', 'aria-label': 'Options of ' + text } }));
    if (well) { kids.push(h(doc, 'button', { cls: 'dg-chip-x', text: '×', attrs: { type: 'button', tabindex: '-1', 'aria-label': 'Remove ' + text + ' from ' + NAMES[well] } })); }
    return h(doc, 'span', { cls: 'dg-chip' + (c.type === 'number' ? ' dg-measure' : '') + (c.additive === false ? ' dg-chip-nadd' : ''),
      attrs: { role: 'listitem', tabindex: '0', draggable: 'true', 'aria-label': text + (well ? ' in ' + NAMES[well] : ', a field') + '. Enter for options, Delete to remove' },
      data: { field: colId, well: well, index: String(index) } }, kids);
  }

  /** @private a value filter's chip */
  vfChip(f, i) {
    const doc = this.doc, text = conditionText(f, (x) => this.view.fmtMeasure(f.by, x));
    return h(doc, 'span', { cls: 'dg-chip dg-vf', attrs: { role: 'listitem', tabindex: '0', 'aria-label': 'Value filter ' + text }, data: { vf: String(i) } },
      [h(doc, 'span', { cls: 'dg-chip-label', text: text }), h(doc, 'button', { cls: 'dg-chip-menu', text: '▾', attrs: { type: 'button', tabindex: '-1', 'aria-label': 'Edit the value filter' } }),
        h(doc, 'button', { cls: 'dg-chip-x', text: '×', attrs: { type: 'button', tabindex: '-1', 'aria-label': 'Remove the value filter' } })]);
  }

  /** @private the wells' pointer and keyboard work */
  listen(el) {
    el.addEventListener('click', (e) => this.click(e));
    el.addEventListener('keydown', (e) => this.key(e));
    el.addEventListener('change', (e) => this.change(e));
    el.addEventListener('dragstart', (e) => {
      const c = e.target.closest('[data-field]');
      if (!c) { return; }
      this.dragging = { field: c.dataset.field, from: c.dataset.well || '', index: +c.dataset.index };
      c.classList.add('dg-dragging');
      e.dataTransfer.effectAllowed = 'move';
      e.dataTransfer.setData(MIME, JSON.stringify(this.dragging));
      e.dataTransfer.setData('text/plain', this.dragging.field);
    });
    el.addEventListener('dragend', () => { this.dragging = null; el.querySelectorAll('.dg-over').forEach((z) => z.classList.remove('dg-over')); });
    el.addEventListener('dragover', (e) => {
      const z = e.target.closest('.dg-zone, .dg-fields, .dg-field-list, .dg-lane-fields');
      if (!z) { return; }
      e.preventDefault();
      e.dataTransfer.dropEffect = 'move';
      el.querySelectorAll('.dg-over').forEach((x) => { if (x !== z) { x.classList.remove('dg-over'); } });
      z.classList.add('dg-over');
    });
    el.addEventListener('drop', (e) => {
      const z = e.target.closest('.dg-zone, .dg-fields, .dg-field-list, .dg-lane-fields');
      if (!z) { return; }
      e.preventDefault();
      z.classList.remove('dg-over');
      let d = this.dragging;
      if (!d) { try { d = JSON.parse(e.dataTransfer.getData(MIME)); } catch (err) { d = null; } }
      if (!d) { return; }
      if (!z.dataset.well) { if (d.from) { this.remove(d.from, d.from === 'values' || d.from === 'filters' ? d.index : d.field, d.field); } return; }
      const chips = Array.from(z.querySelectorAll('.dg-chip[data-field]'));
      const vertical = z.closest('.dg-wells-panel') !== null;
      let at = chips.findIndex((c) => { const r = c.getBoundingClientRect(); return vertical ? e.clientY < r.top + r.height / 2 : e.clientX < r.left + r.width / 2; });
      if (at < 0) { at = chips.length; }
      this.move(d.field, d.from, d.index, z.dataset.well, at);
    });
  }

  /**
   * Moves a field from a well (or the field list: from '') to a well at a place; a drop that cannot be made says why.
   * @param {string} field
   * @param {string} from rows, columns, values, filters or ''
   * @param {number} fromIndex its place in the well it leaves
   * @param {string} to
   * @param {number} [at] the place in the well it joins (default: the end)
   * @returns {boolean} whether it moved
   */
  move(field, from, fromIndex, to, at) {
    const w = this.w(), v = this.view;
    if (from === to && from) {
      const target = at === undefined ? w.state[to].length - 1 : at > fromIndex ? at - 1 : at;
      if (target !== fromIndex) { w.reorder(to, fromIndex, target); }
      v.wellsChanged('Moved ' + this.header(field) + ' in ' + NAMES[to]);
      return true;
    }
    const ok = w.canDrop(field, to);
    if (!ok.ok) { v.say(ok.why); return false; }
    if (from === 'values') { w.remove('values', fromIndex); }
    else if (from === 'filters') { w.remove('filters', field); }
    else if (from && (to === 'values' || to === 'filters')) { w.remove(from, field); }
    w.drop(field, to, at);
    v.wellsChanged(this.header(field) + ' to ' + NAMES[to]);
    return true;
  }

  /** @private */
  header(id) { const c = this.w().columns.get(id); return c ? c.header : id; }

  /** @private takes a field out of a well */
  remove(well, which, field) {
    this.w().remove(well, which);
    this.view.wellsChanged('Removed ' + this.header(field) + ' from ' + NAMES[well]);
  }

  /** @private */
  click(e) {
    const t = e.target, v = this.view;
    const act = t.closest('[data-act]');
    if (act && act.dataset.act === 'add-vf') { this.valueFilterMenu(-1, act); return; }
    if (act && act.dataset.act === 'apply-draft') { v.applyDraft(); return; }
    const vf = t.closest('.dg-vf');
    if (vf) {
      const i = +vf.dataset.vf;
      if (t.closest('.dg-chip-x')) { v.setValueFilters(v.model.pivotExtra.valueFilters.filter((x, k) => k !== i)); return; }
      this.valueFilterMenu(i, vf);
      return;
    }
    const chip = t.closest('[data-field]');
    if (!chip || t.matches('input')) { return; }
    const field = chip.dataset.field, well = chip.dataset.well, index = +chip.dataset.index;
    if (t.closest('.dg-chip-x')) { this.remove(well, well === 'values' ? index : field, field); return; }
    if (t.closest('.dg-chip-menu') || !well) { this.chipMenu(chip); }
  }

  /** @private */
  change(e) {
    const t = e.target, v = this.view, w = this.w();
    if (t.dataset.use) {
      const id = t.dataset.use, c = w.columns.get(id);
      if (t.checked) { w.drop(id, c.enableValue ? 'values' : c.enableRowGroup ? 'rows' : 'filters'); }
      else {
        const st = w.getState();
        w.setState({ rows: st.rows.filter((x) => x !== id), columns: st.columns.filter((x) => x !== id), values: st.values.filter((x) => x.colId !== id), filters: st.filters.filter((x) => x.colId !== id) });
      }
      v.wellsChanged((t.checked ? 'Added ' : 'Removed ') + c.header);
      return;
    }
    if (t.dataset.sw) { v.panelSwitch(t.dataset.sw, t.checked); }
  }

  /** @private a chip's keys: Enter opens its menu, Delete removes, Ctrl+arrows move it in its well */
  key(e) {
    const chip = e.target.closest ? e.target.closest('.dg-chip') : null;
    if (!chip || e.target !== chip) { return; }
    const field = chip.dataset.field, well = chip.dataset.well, index = +chip.dataset.index;
    if (e.key === 'Enter' || e.key === ' ' || e.key === 'ArrowDown') {
      e.preventDefault();
      if (chip.dataset.vf !== undefined) { this.valueFilterMenu(+chip.dataset.vf, chip); } else { this.chipMenu(chip); }
    } else if ((e.key === 'Delete' || e.key === 'Backspace') && well) {
      e.preventDefault();
      if (chip.dataset.vf !== undefined) { this.view.setValueFilters(this.view.model.pivotExtra.valueFilters.filter((x, k) => k !== +chip.dataset.vf)); }
      else { this.remove(well, well === 'values' ? index : field, field); }
    } else if ((e.ctrlKey || e.metaKey) && (e.key === 'ArrowLeft' || e.key === 'ArrowRight') && well) {
      e.preventDefault();
      this.move(field, well, index, well, e.key === 'ArrowLeft' ? Math.max(0, index - 1) : index + 2);
    }
  }

  /** @private a chip's menu: aggregation, members, sort and top N, moves, removal */
  chipMenu(chip) {
    const field = chip.dataset.field, well = chip.dataset.well, index = +chip.dataset.index, v = this.view, w = this.w(), c = w.columns.get(field);
    const items = () => {
      const out = [];
      if (well === 'values') {
        const val = w.state.values[index];
        if (c.additive === false) { out.push({ heading: 'Aggregation · computed by the server' }, { id: 'agg-server', label: c.measure || c.header, checked: true, disabled: true, why: c.header + ' is not additive: every total is the server\'s figure for its group, never a sum made in the browser' }); }
        else { out.push({ heading: 'Aggregation' }); VALUE_FUNCS.forEach((f) => out.push({ id: 'agg:' + f, label: aggFunc(f).label, checked: val.aggFunc === f })); }
        out.push({ sep: true });
      }
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
    v.menu.open(items, { anchor: chip, label: c.header + ' options', theme: v.host, run: (id) => this.chipAction(id, field, well, index) });
  }

  /** @private */
  chipAction(id, field, well, index) {
    const v = this.view, w = this.w(), c = w.columns.get(field);
    if (id.indexOf('agg:') === 0) { const r = w.setAggregation(index, id.slice(4)); if (!r.ok) { v.say(r.why); } v.wellsChanged(c.header + ': ' + aggFunc(id.slice(4)).label); return; }
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
    if (id.indexOf('to:') === 0) { this.move(field, well, index, id.slice(3)); return; }
    if (id === 'left' || id === 'right') { this.move(field, well, index, well, id === 'left' ? Math.max(0, index - 1) : index + 2); return; }
    if (id === 'remove') { this.remove(well, well === 'values' ? index : field, field); }
  }

  /**
   * The value filter menu (a new one when index is -1): the measure, a condition from the figures or typed ("> 400k").
   * @param {number} index
   * @param {Element} anchor
   * @returns {void}
   */
  valueFilterMenu(index, anchor) {
    const v = this.view, w = this.w();
    const view = w.toView(), dims = view.rows, measures = view.values.map((x) => x.as);
    if (!dims.length || !measures.length) { v.say('A value filter needs a row field and a value'); return; }
    const list = (v.model.pivotExtra.valueFilters || []).slice();
    const cur = index >= 0 ? Object.assign({}, list[index]) : { dim: dims[dims.length - 1], by: measures[0], op: 'gt', value: 0 };
    const save = (f) => { if (index >= 0) { list[index] = f; } else { list.push(f); } v.setValueFilters(list); };
    const items = () => {
      const med = v.levelFigures(cur.dim, cur.by);
      const out = [{ heading: 'Value filter on ' + this.header(cur.dim) }];
      dims.forEach((d) => out.push({ id: 'dim:' + d, label: 'Level: ' + this.header(d), checked: cur.dim === d, keep: true }));
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
}
