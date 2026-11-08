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
/* The wells as drawn (PIVOT_GUIDE.md section 2), three arrangements of one model (drishti_wells.js):

     ribbon (layout wells)    the compact line above the grid, the trader view (B): [View ▾] Rows [⋔ MGMT ▾ Desk › Book ▲▼] [Position ▾] +
                              · Columns [Risk class ▾] + · Filters [Trade status ▾] + · Values: the lit measure toggles ARE the values (▾ for
                              each one's function and show-as), the unlit ones a click away, ⋯ for the rest · Customize ›. No field list on
                              screen: each well's name or + opens its picker (R, C, V, F). With a views hook the View picker sits in front (C).
     panel (layout panel)     the panes beside the grid (A, Power BI's): Build (the four wells as lists, the switches) and Fields (search,
                              Hierarchies, the fields by folder, the measures by folder, More fields), collapsible to a rail; drag between them.
     lane (layout terminal)   the lane under the terminal's command line.

   A hierarchy is one chip with a level stepper (▲ up a level, ▼ the next level; Alt+↑ and Alt+↓). A chip's menu (drishti_grid-wells-menus.js)
   holds a value's function, show-as and options, a field's members, order and top N, moves and removal. A drop that cannot be made says why. */
import { h } from '../../drishti-core/src/drishti_index.js';
import { conditionText } from './drishti_grid-ui.js';
import { chipMenu, valueMenu, valueFilterMenu } from './drishti_grid-wells-menus.js';
import { fieldGroups, openPicker } from './drishti_grid-fields.js';
import { ViewPicker } from './drishti_grid-views.js';

const NAMES = { filters: 'Filters', rows: 'Rows', columns: 'Columns', values: 'Values' };
const LANE = { rows: 'ROWS', columns: 'COLS', values: 'VALS', filters: 'FILT' };
const KEYS = { rows: 'R', columns: 'C', values: 'V', filters: 'F' };
const MIME = 'text/x-drishti-field';
/** How many unlit measure toggles the line shows before ⋯. */
const TOGGLES = 8;

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
    this.views = view.o.views ? new ViewPicker(view) : null;
    this.openHier = new Set();
    this.closedGroups = new Set(['more']);
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
    if (how === 'panel') { this.panes(el); }
    else if (how === 'lane') {
      const search = this.searchBox();
      el.append(h(doc, 'div', { cls: 'dg-zones' }, ['rows', 'columns', 'values', 'filters'].map((w) => this.zone(w, how))),
        h(doc, 'div', { cls: 'dg-lane-fields' }, [h(doc, 'span', { cls: 'dg-zl', text: '+ FIELD' }), search].concat(this.unusedChips())));
    } else { this.line(el); }
    if (!el.dataset.dgWired) { el.dataset.dgWired = '1'; this.listen(el); }
    if (keepFocus) { const s = el.querySelector('.dg-field-search'); if (s) { s.focus(); s.setSelectionRange(s.value.length, s.value.length); } }
  }

  /** @private the field search of the panes and the lane */
  searchBox() {
    const search = h(this.doc, 'input', { cls: 'dg-field-search', attrs: { type: 'search', placeholder: 'Search fields  /', 'aria-label': 'Search fields', value: this.search } });
    search.addEventListener('input', () => { this.search = search.value; this.view.renderChrome(); });
    return search;
  }

  // ---- B: the compact line -----------------------------------------------------------------------------------------------------------

  /** @private the compact line: [View] Rows · Columns · Filters · Values · Customize › */
  line(el) {
    const doc = this.doc, dot = () => h(doc, 'span', { cls: 'dg-dotsep', text: '·', attrs: { 'aria-hidden': 'true' } });
    if (this.views) { el.append(this.views.button(), h(doc, 'span', { cls: 'dg-bar', attrs: { 'aria-hidden': 'true' } })); }
    el.append(this.zone('rows', 'line'), dot(), this.zone('columns', 'line'), dot(), this.zone('filters', 'line'), dot(), this.valuesLine(), h(doc, 'span', { cls: 'dg-sp' }));
    if (this.view.o.customize !== false) {
      el.append(h(doc, 'button', { cls: 'dg-customize', text: 'Customize ›', attrs: { type: 'button', title: 'The Fields and Build panes (\\)', 'aria-keyshortcuts': '\\' }, data: { act: 'customize' } }));
    }
  }

  /** @private Values on the line: the lit toggles (the values, in order, each with ▾), the unlit measures, ⋯ */
  valuesLine() {
    const doc = this.doc, w = this.w(), st = w.state;
    const items = st.values.map((v, i) => this.chip(v.colId, 'values', i));
    const used = new Set(st.values.map((v) => v.colId));
    const cands = w.fieldList('').filter((f) => !used.has(f.id) && !f.more && (f.role === 'measure' || (f.value && f.type === 'number')))
      .sort((a, b) => (a.role === 'measure' ? 0 : 1) - (b.role === 'measure' ? 0 : 1));   // the measures first, then the number fields
    cands.slice(0, TOGGLES).forEach((f) => items.push(h(doc, 'button', { cls: 'dg-vtoggle', text: f.header, attrs: { type: 'button', 'aria-pressed': 'false', title: 'Add ' + f.header + ' to Values' }, data: { field: f.id } })));
    items.push(h(doc, 'button', { cls: 'dg-add', text: cands.length > TOGGLES ? '⋯' : '+', attrs: { type: 'button', 'aria-label': 'Values: every measure and field (V)', title: 'Values (V)' }, data: { pick: 'values' } }));
    return h(doc, 'div', { cls: 'dg-zone dg-zone-values', attrs: { role: 'list', 'aria-label': 'Values' }, data: { well: 'values' } },
      [h(doc, 'button', { cls: 'dg-zl dg-pick', text: 'Values', attrs: { type: 'button', 'aria-keyshortcuts': 'V', title: 'Values (V)' }, data: { pick: 'values' } })].concat(items));
  }

  // ---- A: the panes ------------------------------------------------------------------------------------------------------------------

  /** @private Build (the wells, the switches) and Fields (the catalogue), or the rail when closed */
  panes(el) {
    const doc = this.doc, v = this.view;
    if (v.ui.panesClosed) {
      el.classList.add('dg-rail');
      el.append(h(doc, 'button', { cls: 'dg-rail-btn', text: '«', attrs: { type: 'button', 'aria-label': 'Open the Build and Fields panes', title: 'Open the panes' }, data: { act: 'panes' } }),
        h(doc, 'span', { cls: 'dg-rail-label', text: 'Build · Fields' }));
      return;
    }
    const head = (text, act) => h(doc, 'div', { cls: 'dg-pane-head' }, [h(doc, 'span', { text: text }), act ? h(doc, 'button', { cls: 'dg-pane-close', text: '»', attrs: { type: 'button', 'aria-label': 'Close the panes to a rail', title: 'Close the panes' }, data: { act: act } }) : null]);
    const build = h(doc, 'section', { cls: 'dg-build', attrs: { 'aria-label': 'Build' } }, [head('Build', 'panes')]);
    if (this.views) { build.append(h(doc, 'div', { cls: 'dg-pane-views' }, [this.views.button()])); }
    build.append(h(doc, 'div', { cls: 'dg-zones' }, ['rows', 'columns', 'values', 'filters'].map((w) => this.zone(w, 'panel'))), this.switches());
    if (v.o.customize !== false) { build.append(h(doc, 'button', { cls: 'dg-customize', text: '‹ Close the panes', attrs: { type: 'button', title: 'Back to the compact line (\\)' }, data: { act: 'customize' } })); }
    const list = h(doc, 'div', { cls: 'dg-field-list', attrs: { role: 'tree', 'aria-label': 'Fields' } }, this.fieldTree());
    const fields = h(doc, 'section', { cls: 'dg-fieldpane', attrs: { 'aria-label': 'Fields' } }, [head('Fields', null), this.searchBox(), list]);
    el.append(build, fields);
  }

  /** @private the Fields pane: hierarchies (each with its levels), the fields and the measures by folder, More fields */
  fieldTree() {
    const doc = this.doc, w = this.w(), out = [], q = this.search.toLowerCase();
    const groups = fieldGroups(w, 'rows').concat(fieldGroups(w, 'values').filter((g) => g.id.indexOf('m:') === 0));
    groups.forEach((g) => {
      const items = g.items.filter((it) => !q || (it.label + ' ' + (it.detail || '') + ' ' + (it.children || []).map((c) => c.label).join(' ')).toLowerCase().indexOf(q) >= 0);
      if (!items.length) { return; }
      const closed = !q && this.closedGroups.has(g.id);
      out.push(h(doc, 'div', { cls: 'dg-fgroup', attrs: { role: 'presentation' }, data: { group: g.id } }, [closed ? '▸ ' : '▾ ', g.label, h(doc, 'span', { cls: 'dg-fcount', text: String(items.length) })]));
      if (closed) { return; }
      items.forEach((it) => {
        if (it.children) {
          const open = this.openHier.has(it.id) || !!q;
          out.push(h(doc, 'div', { cls: 'dg-field dg-hfield', attrs: { role: 'treeitem', 'aria-expanded': String(open), draggable: 'true' }, data: { hier: it.id.slice(2), well: '', index: '-1' } },
            [h(doc, 'span', { cls: 'dg-twist', text: open ? '▾' : '▸', data: { act: 'hier-open' } }), h(doc, 'span', { cls: 'dg-ficon', text: it.icon }), h(doc, 'b', { text: it.label }),
              h(doc, 'span', { cls: 'dg-fdetail', text: it.detail }), it.checked ? h(doc, 'span', { cls: 'dg-fin', text: '✓' }) : null]));
          if (open) { it.children.forEach((l) => out.push(this.fieldRow(l, true))); }
          return;
        }
        out.push(this.fieldRow(it, false));
      });
    });
    return out;
  }

  /** @private a field in the Fields pane: its check (in any well), drag it to a well */
  fieldRow(it, level) {
    const doc = this.doc, w = this.w(), f = this.view.model.columns.get(it.id) || {};
    const inAny = ['rows', 'columns', 'values', 'filters'].some((x) => w.has(x, it.id));
    const box = h(doc, 'input', { attrs: { type: 'checkbox', 'aria-label': 'Use ' + it.label }, data: { use: it.id } });
    box.checked = inAny;
    return h(doc, 'div', { cls: 'dg-field' + (level ? ' dg-level' : ''), attrs: { role: 'treeitem', draggable: 'true', title: f.description || null }, data: { field: it.id, well: '', index: '-1' } },
      [box, it.icon ? h(doc, 'span', { cls: 'dg-ficon', text: it.icon }) : null, h(doc, 'span', { text: it.label }), f.additive === false && f.type === 'number' ? h(doc, 'span', { cls: 'dg-na', text: '◆' }) : null,
        it.note && !level ? h(doc, 'span', { cls: 'dg-ftype', text: it.note }) : null]);
  }

  /** @private the panes' switches */
  switches() {
    const doc = this.doc, v = this.view, m = v.model;
    const sw = (id, label, on) => { const b = h(doc, 'input', { attrs: { type: 'checkbox' }, data: { sw: id } }); b.checked = !!on; return h(doc, 'label', { cls: 'dg-switch' }, [b, ' ' + label]); };
    return h(doc, 'div', { cls: 'dg-switches' }, [sw('pivot', 'Pivot mode', m.pivotMode), sw('grand', 'Grand total column', v.ui.grandColumn),
      sw('above', 'Subtotals above', m.pivotExtra.subtotals === 'above'), sw('defer', 'Defer update', !!v.draft),
      h(doc, 'button', { cls: 'dg-btn', text: 'Update', attrs: { type: 'button', disabled: !v.draft || !v.draftDirty }, data: { act: 'apply-draft' } })]);
  }

  // ---- the wells and their chips ---------------------------------------------------------------------------------------------------

  /** @private the fields that are in no well (the terminal's lane) */
  unusedChips() { return this.w().fieldList(this.search).filter((f) => !f.in.length && !f.more && !f.hierarchy).map((f) => this.chip(f.id, '', -1)); }

  /** @private a well */
  zone(well, how) {
    const doc = this.doc, w = this.w(), st = w.state;
    let items;
    if (well === 'values') { items = st.values.map((v, i) => this.chip(v.colId, well, i)); }
    else if (well === 'filters') { items = st.filters.map((f, i) => this.chip(f.colId, well, i)); }
    else { items = how === 'lane' ? st[well].map((id, i) => this.chip(id, well, i)) : this.segmentChips(well); }
    if (well === 'filters') {
      (this.view.model.pivotExtra.valueFilters || []).forEach((f, i) => items.push(this.vfChip(f, i)));
      if (st.values.length && st.rows.length) { items.push(h(doc, 'button', { cls: 'dg-add-vf', text: '+ value filter', attrs: { type: 'button', 'aria-label': 'Add a value filter' }, data: { act: 'add-vf' } })); }
    }
    if (how === 'line' || how === 'panel') {
      items.push(h(doc, 'button', { cls: 'dg-add', text: '+', attrs: { type: 'button', 'aria-label': 'Add to ' + NAMES[well] + ' (' + KEYS[well] + ')', title: NAMES[well] + ' (' + KEYS[well] + ')' }, data: { pick: well } }));
    } else if (!items.length) { items.push(h(doc, 'span', { cls: 'dg-zone-hint', text: how === 'lane' ? '—' : 'Drop fields here' })); }
    const label = how === 'lane' ? h(doc, 'span', { cls: 'dg-zl', text: LANE[well] })
      : h(doc, 'button', { cls: 'dg-zl dg-pick', text: NAMES[well], attrs: { type: 'button', 'aria-keyshortcuts': KEYS[well], title: NAMES[well] + ' (' + KEYS[well] + ')' }, data: { pick: well } });
    return h(doc, 'div', { cls: 'dg-zone', attrs: { role: 'list', 'aria-label': NAMES[well] }, data: { well: well } }, [label].concat(items));
  }

  /** @private Rows or Columns as chips: a hierarchy's levels as one chip with its stepper, each other field its own */
  segmentChips(well) {
    return this.w().segments(well).map((s) => (s.hierarchy ? this.hierChip(s, well) : this.chip(s.ids[0], well, s.index)));
  }

  /** @private a hierarchy's chip: ⋔ MGMT ▾ Desk › Book ▲▼ × */
  hierChip(s, well) {
    const doc = this.doc, hh = s.hierarchy, labels = s.ids.map((id) => (hh.levels.find((l) => l.id === id) || {}).label || id);
    const text = hh.id + ' ' + labels.join(' › ');
    const deepest = hh.levels.findIndex((l) => l.id === s.ids[s.ids.length - 1]);
    return h(doc, 'span', { cls: 'dg-chip dg-hchip', attrs: { role: 'listitem', tabindex: '0', draggable: 'true', 'aria-label': text + ' in ' + NAMES[well] + '. Alt+Up and Alt+Down: a level up or down; Enter: its levels; Delete: remove', title: hh.title + ': ' + labels.join(' › ') },
      data: { hier: hh.id, well: well, field: s.ids[0], index: String(s.index) } }, [
      h(doc, 'span', { cls: 'dg-ficon', text: '⋔', attrs: { 'aria-hidden': 'true' } }), h(doc, 'b', { text: hh.id }),
      h(doc, 'button', { cls: 'dg-chip-menu', text: '▾', attrs: { type: 'button', tabindex: '-1', 'aria-label': 'The levels of ' + hh.id }, data: { pick: well } }),
      h(doc, 'span', { cls: 'dg-chip-label', text: labels.join(' › ') }),
      h(doc, 'span', { cls: 'dg-stepper' }, [
        h(doc, 'button', { cls: 'dg-step', text: '▲', attrs: { type: 'button', tabindex: '-1', 'aria-label': 'Up a level (Alt+Up)', title: 'Up a level (Alt+↑)', disabled: s.ids.length < 2 ? true : null }, data: { step: '-1' } }),
        h(doc, 'button', { cls: 'dg-step', text: '▼', attrs: { type: 'button', tabindex: '-1', 'aria-label': 'The next level (Alt+Down)', title: 'The next level (Alt+↓)', disabled: deepest >= hh.levels.length - 1 ? true : null }, data: { step: '1' } })]),
      h(doc, 'button', { cls: 'dg-chip-x', text: '×', attrs: { type: 'button', tabindex: '-1', 'aria-label': 'Remove ' + hh.id + ' from ' + NAMES[well] } })]);
  }

  /** @private a field's chip (in a well, or in the lane's field list when well is '') */
  chip(colId, well, index) {
    const doc = this.doc, w = this.w(), c = w.columns.get(colId);
    if (!c) { return h(doc, 'span', { text: colId }); }
    let text = c.header;
    if (well === 'values') {
      const v = w.state.values[index];
      text = v.as ? v.as : w.labelOf(v);
      if (v.showAs) { text += ' · ' + v.showAs.replace(/:1:/, ' vs ').replace(/^pct-/, '% '); }
    }
    if (well === 'filters') {
      const f = w.state.filters[index].filter;
      const n = f && f.excluded ? f.excluded.length : f && f.values ? -f.values.length : 0;
      if (n > 0) { text += ' (−' + n + ')'; } else if (n < 0) { text += ' (' + (-n) + ')'; }
    }
    const kids = [h(doc, 'span', { cls: 'dg-chip-label', text: text })];
    if (c.additive === false && c.type === 'number') { kids.push(h(doc, 'span', { cls: 'dg-na', text: '◆', attrs: { 'aria-label': 'computed per cell' } })); }
    kids.push(h(doc, 'button', { cls: 'dg-chip-menu', text: '▾', attrs: { type: 'button', tabindex: '-1', 'aria-label': 'Options of ' + text } }));
    if (well) { kids.push(h(doc, 'button', { cls: 'dg-chip-x', text: '×', attrs: { type: 'button', tabindex: '-1', 'aria-label': 'Remove ' + text + ' from ' + NAMES[well] } })); }
    return h(doc, 'span', { cls: 'dg-chip' + (c.type === 'number' ? ' dg-measure' : '') + (well === 'values' ? ' dg-vchip' : '') + (c.additive === false ? ' dg-chip-nadd' : ''),
      attrs: { role: 'listitem', tabindex: '0', draggable: 'true', title: c.description || null, 'aria-label': text + (well ? ' in ' + NAMES[well] : ', a field') + '. Enter for options, Delete to remove' },
      data: { field: colId, well: well, index: String(index) } }, kids);
  }

  /** @private a value filter's chip */
  vfChip(f, i) {
    const doc = this.doc, text = conditionText(f, (x) => this.view.fmtMeasure(f.by, x));
    return h(doc, 'span', { cls: 'dg-chip dg-vf', attrs: { role: 'listitem', tabindex: '0', 'aria-label': 'Value filter ' + text }, data: { vf: String(i) } },
      [h(doc, 'span', { cls: 'dg-chip-label', text: text }), h(doc, 'button', { cls: 'dg-chip-menu', text: '▾', attrs: { type: 'button', tabindex: '-1', 'aria-label': 'Edit the value filter' } }),
        h(doc, 'button', { cls: 'dg-chip-x', text: '×', attrs: { type: 'button', tabindex: '-1', 'aria-label': 'Remove the value filter' } })]);
  }

  // ---- pointer and keys ------------------------------------------------------------------------------------------------------------

  /** @private the wells' pointer and keyboard work */
  listen(el) {
    el.addEventListener('click', (e) => this.click(e));
    el.addEventListener('keydown', (e) => this.key(e));
    el.addEventListener('change', (e) => this.change(e));
    el.addEventListener('dragstart', (e) => {
      const c = e.target.closest('[data-field], [data-hier]');
      if (!c) { return; }
      this.dragging = { field: c.dataset.field || '', hier: c.dataset.hier || '', from: c.dataset.well || '', index: +(c.dataset.index || -1) };
      c.classList.add('dg-dragging');
      e.dataTransfer.effectAllowed = 'move';
      e.dataTransfer.setData(MIME, JSON.stringify(this.dragging));
      e.dataTransfer.setData('text/plain', this.dragging.field || this.dragging.hier);
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
      if (d.hier) { this.moveHierarchy(d.hier, d.from, z.dataset.well || ''); return; }
      if (!z.dataset.well) { if (d.from) { this.remove(d.from, d.from === 'values' || d.from === 'filters' ? d.index : d.field, d.field); } return; }
      const chips = Array.from(z.querySelectorAll('.dg-chip[data-field]'));
      const vertical = z.closest('.dg-wells-panel') !== null;
      let at = chips.findIndex((c) => { const r = c.getBoundingClientRect(); return vertical ? e.clientY < r.top + r.height / 2 : e.clientX < r.left + r.width / 2; });
      at = at < 0 ? this.w().state[z.dataset.well].length : +chips[at].dataset.index;
      this.move(d.field, d.from, d.index, z.dataset.well, at);
    });
  }

  /** @private a hierarchy dragged as one: its levels in the well it left go to the well it joins (its first level when from the Fields pane) */
  moveHierarchy(id, from, to) {
    const w = this.w(), v = this.view, hh = w.hierarchies.find((x) => x.id === id);
    if (!hh) { return; }
    const levels = from ? hh.levels.filter((l) => w.has(from, l.id)).map((l) => l.id) : [hh.levels[0].id];
    if (!to) { if (from) { w.setHierarchyLevels(from, id, []); v.wellsChanged(id + ' taken out of ' + NAMES[from]); } return; }
    if (to === 'rows' || to === 'columns') {
      if (from && from !== to && (from === 'rows' || from === 'columns')) { w.setHierarchyLevels(from, id, []); }
      w.setHierarchyLevels(to, id, from === to ? levels : levels.concat(hh.levels.filter((l) => w.has(to, l.id)).map((l) => l.id)));
      v.wellsChanged(id + ' to ' + NAMES[to]);
      return;
    }
    if (to === 'filters') { levels.forEach((l) => { if (!w.has('filters', l)) { w.drop(l, 'filters'); } }); v.wellsChanged(id + ' to Filters'); return; }
    v.say(hh.title + ' cannot be a value: its levels group the rows');
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

  /**
   * A field's header.
   * @param {string} id
   * @returns {string}
   */
  header(id) { const c = this.w().columns.get(id); return c ? c.header : id; }

  /**
   * Takes a field out of a well.
   * @param {string} well
   * @param {(string|number)} which the field, or a value's index
   * @param {string} field
   * @returns {void}
   */
  remove(well, which, field) {
    this.w().remove(well, which);
    this.view.wellsChanged('Removed ' + this.header(field) + ' from ' + NAMES[well]);
  }

  /**
   * Opens a well's picker (R, C, V, F; a click on the well's name, its + or a hierarchy's ▾).
   * @param {string} well
   * @param {Element} [anchor]
   * @returns {void}
   */
  pick(well, anchor) {
    const root = this.view.regions ? this.view.regions.wells : null;
    const at = anchor || (root && root.querySelector('[data-well="' + well + '"] .dg-pick')) || (root && root.querySelector('[data-pick="' + well + '"]')) || this.view.host;
    openPicker(this.view, well, at);
  }

  /**
   * Opens the View picker (Shift+V), when the grid has a views hook.
   * @returns {boolean} whether there is one
   */
  openViews() {
    if (!this.views) { return false; }
    const root = this.view.regions ? this.view.regions.wells : null, b = root && root.querySelector('.dg-viewbtn');
    this.views.open(b || this.view.host);
    return true;
  }

  /**
   * The first hierarchy in Rows a level up (−1) or down (+1): Alt+↑ and Alt+↓.
   * @param {number} dir
   * @param {string} [id] the hierarchy (default: the first one in Rows)
   * @returns {void}
   */
  step(dir, id) {
    const w = this.w(), seg = w.segments('rows').find((s) => s.hierarchy && (!id || s.hierarchy.id === id)) || w.segments('columns').find((s) => s.hierarchy && (!id || s.hierarchy.id === id));
    if (!seg) { this.view.say('No hierarchy in Rows: add one with R'); return; }
    const well = w.state.rows.indexOf(seg.ids[0]) >= 0 ? 'rows' : 'columns';
    const r = w.stepHierarchy(well, seg.hierarchy.id, dir);
    if (!r.ok) { this.view.say(r.why); return; }
    const now = w.segments(well).find((s) => s.hierarchy === seg.hierarchy);
    this.view.wellsChanged(seg.hierarchy.id + ': ' + (now ? now.ids.map((x) => (seg.hierarchy.levels.find((l) => l.id === x) || {}).label).join(' › ') : ''));
  }

  /**
   * The value filter menu (a new one when index is -1), from a column's menu or the Filters well.
   * @param {number} index
   * @param {Element} anchor
   * @returns {void}
   */
  valueFilterMenu(index, anchor) { valueFilterMenu(this, index, anchor); }

  /** @private */
  click(e) {
    const t = e.target, v = this.view, w = this.w();
    const act = t.closest('[data-act]');
    if (act) {
      const a = act.dataset.act;
      if (a === 'add-vf') { valueFilterMenu(this, -1, act); return; }
      if (a === 'apply-draft') { v.applyDraft(); return; }
      if (a === 'views') { this.views.open(act); return; }
      if (a === 'customize') { v.toggleCustomize(); return; }
      if (a === 'panes') { v.ui.panesClosed = !v.ui.panesClosed; v.regions.main.classList.toggle('dg-panes-closed', !!v.ui.panesClosed); v.renderChrome(); return; }
      if (a === 'hier-open') { const id = 'h:' + t.closest('[data-hier]').dataset.hier; if (this.openHier.has(id)) { this.openHier.delete(id); } else { this.openHier.add(id); } v.renderChrome(); return; }
    }
    const grp = t.closest('.dg-fgroup');
    if (grp) { const id = grp.dataset.group; if (this.closedGroups.has(id)) { this.closedGroups.delete(id); } else { this.closedGroups.add(id); } v.renderChrome(); return; }
    const step = t.closest('[data-step]');
    if (step) { const chip = step.closest('[data-hier]'); const r = w.stepHierarchy(chip.dataset.well, chip.dataset.hier, +step.dataset.step); if (!r.ok) { v.say(r.why); } else { v.wellsChanged(chip.dataset.hier + ' ' + (+step.dataset.step > 0 ? 'down a level' : 'up a level')); } return; }
    const hchip = t.closest('.dg-hchip');
    if (hchip) {
      if (t.closest('.dg-chip-x')) { w.setHierarchyLevels(hchip.dataset.well, hchip.dataset.hier, []); v.wellsChanged(hchip.dataset.hier + ' taken out of ' + NAMES[hchip.dataset.well]); return; }
      openPicker(v, hchip.dataset.well, hchip);
      return;
    }
    const pk = t.closest('[data-pick]');
    if (pk) { openPicker(v, pk.dataset.pick, pk); return; }
    const tog = t.closest('.dg-vtoggle');
    if (tog) { w.drop(tog.dataset.field, 'values'); v.wellsChanged(this.header(tog.dataset.field) + ' to Values'); return; }
    const vf = t.closest('.dg-vf');
    if (vf) {
      const i = +vf.dataset.vf;
      if (t.closest('.dg-chip-x')) { v.setValueFilters(v.model.pivotExtra.valueFilters.filter((x, k) => k !== i)); return; }
      valueFilterMenu(this, i, vf);
      return;
    }
    const chip = t.closest('[data-field]');
    if (!chip || t.matches('input')) { return; }
    const field = chip.dataset.field, well = chip.dataset.well, index = +chip.dataset.index;
    if (t.closest('.dg-chip-x')) { this.remove(well, well === 'values' ? index : field, field); return; }
    if (t.closest('.dg-chip-menu') || !well || well === 'values') { chipMenu(this, chip); }
  }

  /** @private a check in the Fields pane: a measure to Values, a field to Rows; unchecked, out of every well */
  change(e) {
    const t = e.target, v = this.view, w = this.w();
    if (t.dataset.use) {
      const id = t.dataset.use, c = w.columns.get(id);
      const measure = c.role === 'measure' || (c.measure && !c.enableRowGroup) || (c.enableValue && !c.enableRowGroup);
      if (t.checked) { w.drop(id, measure ? 'values' : c.enableRowGroup ? 'rows' : 'filters'); }
      else {
        const st = w.getState();
        w.setState({ rows: st.rows.filter((x) => x !== id), columns: st.columns.filter((x) => x !== id), values: st.values.filter((x) => x.colId !== id), filters: st.filters.filter((x) => x.colId !== id) });
      }
      v.wellsChanged((t.checked ? 'Added ' : 'Removed ') + c.header);
      return;
    }
    if (t.dataset.sw) { v.panelSwitch(t.dataset.sw, t.checked); }
  }

  /** @private a chip's keys: Enter opens its menu (a hierarchy: its levels), Delete removes, Ctrl+arrows move it, Alt+arrows step a hierarchy */
  key(e) {
    const chip = e.target.closest ? e.target.closest('.dg-chip') : null;
    if (!chip || e.target !== chip) { return; }
    const field = chip.dataset.field, well = chip.dataset.well, index = +chip.dataset.index, w = this.w();
    if (chip.dataset.hier) {
      if (e.altKey && (e.key === 'ArrowUp' || e.key === 'ArrowDown')) { e.preventDefault(); this.step(e.key === 'ArrowDown' ? 1 : -1, chip.dataset.hier); return; }
      if (e.key === 'Enter' || e.key === ' ' || e.key === 'ArrowDown') { e.preventDefault(); openPicker(this.view, well, chip); return; }
      if (e.key === 'Delete' || e.key === 'Backspace') { e.preventDefault(); w.setHierarchyLevels(well, chip.dataset.hier, []); this.view.wellsChanged(chip.dataset.hier + ' taken out of ' + NAMES[well]); }
      return;
    }
    if (e.key === 'Enter' || e.key === ' ' || e.key === 'ArrowDown') {
      e.preventDefault();
      if (chip.dataset.vf !== undefined) { valueFilterMenu(this, +chip.dataset.vf, chip); } else if (well === 'values') { valueMenu(this, chip, index); } else { chipMenu(this, chip); }
    } else if ((e.key === 'Delete' || e.key === 'Backspace') && well) {
      e.preventDefault();
      if (chip.dataset.vf !== undefined) { this.view.setValueFilters(this.view.model.pivotExtra.valueFilters.filter((x, k) => k !== +chip.dataset.vf)); }
      else { this.remove(well, well === 'values' ? index : field, field); }
    } else if ((e.ctrlKey || e.metaKey) && (e.key === 'ArrowLeft' || e.key === 'ArrowRight') && well) {
      e.preventDefault();
      this.move(field, well, index, well, e.key === 'ArrowLeft' ? Math.max(0, index - 1) : index + 2);
    }
  }

  /**
   * The wells' keys anywhere in the grid (not while typing): R, C, V, F open the pickers, Shift+V the views, \ the panes, Alt+↑ and Alt+↓
   * the first hierarchy's level. A host with its own keymap (the console's) registers the same through the Grid's methods.
   * @param {KeyboardEvent} e
   * @returns {boolean} whether the key was the wells'
   */
  hostKey(e) {
    const t = e.target, tag = t && t.tagName;
    if (e.defaultPrevented || e.ctrlKey || e.metaKey || (t && (tag === 'INPUT' || tag === 'TEXTAREA' || tag === 'SELECT' || t.isContentEditable))) { return false; }
    if (this.view.o.chrome === false || !this.view.model) { return false; }
    const k = e.key;
    if (e.altKey && (k === 'ArrowUp' || k === 'ArrowDown')) { e.preventDefault(); this.step(k === 'ArrowDown' ? 1 : -1); return true; }
    if (e.altKey) { return false; }
    if (k === '\\' && this.view.o.customize !== false) { e.preventDefault(); this.view.toggleCustomize(); return true; }
    if ((k === 'V') && e.shiftKey && this.views) { e.preventDefault(); this.openViews(); return true; }
    const well = { r: 'rows', c: 'columns', v: 'values', f: 'filters' }[String(k).toLowerCase()];
    if (well && !e.shiftKey && this.view.model.layout !== 'terminal') { e.preventDefault(); this.pick(well); return true; }
    return false;
  }
}
