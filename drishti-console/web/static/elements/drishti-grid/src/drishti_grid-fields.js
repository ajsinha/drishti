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
/* The pivot's fields as a person meets them (PIVOT_GUIDE.md section 2): grouped like Power BI's field list, one catalogue for the compact
   line's pickers (B, drishti-inputs' FieldPicker) and the Fields pane (A). Hierarchies first, each one item with its levels (MGMT ›
   Division › Desk › Book); then the fields by display folder; the measures by folder (Σ); then "More fields", collapsed: the columns a
   hierarchy's levels are built from and the long tail. What a pick does to the wells (a level on or off, a field in or out, another value)
   is here too, so the line and the panes behave the same. */
import { FieldPicker } from '../../drishti-inputs/src/drishti_index.js';

const NAMES = { filters: 'Filters', rows: 'Rows', columns: 'Columns', values: 'Values' };
const HINTS = { rows: 'pick a hierarchy, then its levels; or a field', columns: 'what goes across', filters: 'what the view keeps',
  values: 'the figures: several of one field with different functions are allowed' };

/**
 * The icon of a field in a picker.
 * @param {Object} f a field (ColumnModel.fields)
 * @returns {string}
 */
export function fieldIcon(f) {
  if (f.role === 'measure' || (f.value && f.type === 'number' && f.measure)) { return 'Σ'; }
  return f.type === 'number' ? '#' : f.type === 'date' ? '◷' : 'Aa';
}

/**
 * Where a field is now, in words ("in Rows").
 * @param {Object} f a field with its wells (WellsModel.fieldList)
 * @returns {?string}
 */
function whereIs(f) { return f.in && f.in.length ? 'in ' + f.in.map((w) => NAMES[w]).join(', ') : null; }

/**
 * The groups of fields for a well: hierarchies, fields by folder, measures by folder, more fields (collapsed).
 * @param {import('./drishti_wells.js').WellsModel} w
 * @param {string} well rows, columns, values or filters
 * @returns {Array<import('../../drishti-inputs/src/drishti_field-picker.js').PickerGroup>}
 */
export function fieldGroups(w, well) {
  const fields = w.fieldList(''), byId = {};
  fields.forEach((f) => { byId[f.id] = f; });
  const groups = [], levelIds = new Set();
  const axis = well === 'rows' || well === 'columns';
  if (well !== 'values' && w.hierarchies.length) {
    groups.push({ id: 'hierarchies', label: 'Hierarchies', items: w.hierarchies.map((hh) => {
      hh.levels.forEach((l) => levelIds.add(l.id));
      const inWell = hh.levels.filter((l) => w.has(well, l.id));
      return { id: 'h:' + hh.id, label: hh.id, detail: hh.levels.map((l) => l.label).join(' › '), icon: '⋔', checked: inWell.length > 0,
        note: inWell.length ? 'in ' + NAMES[well] : null, open: inWell.length > 0,
        children: hh.levels.map((l) => {
          const f = byId[l.id] || {}, ok = w.canDrop(l.id, well);
          return { id: l.id, label: l.label, checked: w.has(well, l.id), disabled: !ok.ok, why: ok.ok ? null : ok.why,
            note: w.has(well, l.id) ? 'in ' + NAMES[well] : axis ? whereIs(f) : null };
        }) };
    }) });
  } else {
    w.hierarchies.forEach((hh) => hh.levels.forEach((l) => levelIds.add(l.id)));
  }
  const folders = new Map(), measures = new Map(), more = [];
  fields.forEach((f) => {
    if (levelIds.has(f.id)) { return; }
    const measure = f.role === 'measure' || (!f.rowGroup && f.value);
    if (axis && measure) { return; }                                                  // a figure goes in Values, not here
    if (well === 'values' && !measure && f.type !== 'number' && !f.value) { return; }
    const ok = w.canDrop(f.id, well);
    const item = { id: f.id, label: f.header, icon: fieldIcon(f), checked: w.has(well, f.id), disabled: !ok.ok, why: ok.ok ? null : ok.why,
      note: w.has(well, f.id) ? (well === 'values' ? countIn(w, f.id) : 'in ' + NAMES[well]) : axis ? whereIs(f) : null };
    if (f.more) { more.push(item); return; }
    const target = measure ? measures : folders;
    const g = f.folder || (measure ? 'Measures' : f.group && f.group !== 'Fields' ? f.group : 'Fields');
    if (!target.has(g)) { target.set(g, []); }
    target.get(g).push(item);
  });
  folders.forEach((items, g) => groups.push({ id: 'f:' + g, label: g, items: items, columns: items.length > 6 }));
  measures.forEach((items, g) => groups.push({ id: 'm:' + g, label: well === 'values' ? g : 'Measures · ' + g, items: items, columns: items.length > 6 }));
  if (more.length) { groups.push({ id: 'more', label: 'More fields', items: more, collapsed: true }); }
  return groups;
}

/** @private how many values a field gives ("in Values ×2") */
function countIn(w, id) { const n = w.state.values.filter((v) => v.colId === id).length; return 'in Values' + (n > 1 ? ' ×' + n : ''); }

/**
 * What a pick in a well's picker (or a check in the Fields pane) does: a hierarchy on or off (its first level), one of its levels on or
 * off, a field in or out; in Values a field is added again (several values of one field) or its last value taken away.
 * @param {Object} v the Grid
 * @param {string} well
 * @param {Object} item the picked item (a hierarchy's is h:<id>)
 * @param {?Object} child the level picked, else null
 * @returns {void}
 */
export function pickField(v, well, item, child) {
  const w = v.activeWells();
  if (item.id.indexOf('h:') === 0) {
    const hh = w.hierarchies.find((x) => 'h:' + x.id === item.id);
    const now = hh.levels.filter((l) => w.has(well, l.id)).map((l) => l.id);
    let next;
    if (child) { next = now.indexOf(child.id) >= 0 ? now.filter((x) => x !== child.id) : now.concat([child.id]); }
    else { next = now.length ? [] : [hh.levels[0].id]; }
    if (well === 'filters') {
      hh.levels.forEach((l) => { if (now.indexOf(l.id) >= 0 && next.indexOf(l.id) < 0) { w.remove('filters', l.id); } else if (now.indexOf(l.id) < 0 && next.indexOf(l.id) >= 0) { w.drop(l.id, 'filters'); } });
    } else { w.setHierarchyLevels(well, hh.id, next); }
    v.wellsChanged(hh.id + (next.length ? ': ' + next.map((x) => (hh.levels.find((l) => l.id === x) || {}).label).join(' › ') : ' taken out of ' + NAMES[well]));
    return;
  }
  const c = w.columns.get(item.id);
  if (!c) { return; }
  if (well === 'values') {
    if (item.checked && !v._addAgain) {
      const at = w.state.values.map((x) => x.colId).lastIndexOf(item.id);
      w.remove('values', at);
      v.wellsChanged('Removed ' + c.header + ' from Values');
    } else { w.drop(item.id, 'values'); v.wellsChanged(c.header + ' to Values'); }
    return;
  }
  if (w.has(well, item.id)) { w.remove(well, item.id); v.wellsChanged('Removed ' + c.header + ' from ' + NAMES[well]); return; }
  const ok = w.canDrop(item.id, well);
  if (!ok.ok) { v.say(ok.why); return; }
  w.drop(item.id, well);
  v.wellsChanged(c.header + ' to ' + NAMES[well]);
}

/**
 * Opens a well's field picker under an anchor (R, C, V, F or a click on the well's name or +).
 * @param {Object} v the Grid
 * @param {string} well
 * @param {Element} anchor
 * @returns {FieldPicker}
 */
export function openPicker(v, well, anchor) {
  if (v.picker && v.picker.isOpen()) { v.picker.close(); }
  const p = v.picker = new FieldPicker(v.doc, { label: NAMES[well] + ' fields', title: NAMES[well], hint: HINTS[well], theme: v.host,
    groups: () => fieldGroups(v.activeWells(), well),
    onPick: (item, child) => pickField(v, well, item, child),
    keys: '↑↓ move · → levels · Space on/off · Enter use · Esc close',
    link: v.o.customize === false ? null : { label: v.model.layout === 'panel' ? 'Close the panes ›' : 'Customize ›', run: () => v.toggleCustomize() } });
  p.open(anchor);
  return p;
}
