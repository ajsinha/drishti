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
/* The three layouts' chrome around one grid body (ELEMENTS.md 5.1):

     wells     toolbar · ribbon (fields with search, Filters Rows Columns Values) · [grid + status | Explain drawer]
     panel     [toolbar · grid · status | side tool panel: grouped fields, 2×2 wells, switches] · Explain card at the cell
     terminal  command line · lane of wells · toolbar · grid · status · Explain split pane (steps, worst scenarios, trend) · F-keys

   The toolbar (pivot, expand, collapse, pin totals, formatting, export, view), the status bar (the rows, the pivot's source, figures
   still asked of the server, the value filters, the selection's count, sum, average, min and max with the non-additive warning), the
   command line and the function keys are drawn here; what they run is drishti_grid-actions.js. */
import { h } from '../../drishti-core/src/drishti_index.js';
import { formatValue } from './drishti_columns.js';
import { rangeStats } from './drishti_selection.js';
import { renderExplain } from './drishti_grid-explain.js';
import { FUNCTION_KEYS, commandText, parseCommand, statusItems, conditionText } from './drishti_grid-ui.js';
import { toolbarAction } from './drishti_grid-actions.js';
import { selectElement } from '../../drishti-inputs/src/drishti_index.js';

/**
 * Builds the layout's regions in the grid's element (again after a change of layout); the grid's body moves into the new one.
 * @param {Object} v the Grid
 * @returns {void}
 */
export function buildShell(v) {
  const doc = v.doc, L = v.model.layout, R = v.regions = { layout: v.model.layout };
  if (v.root) { v.root.remove(); }
  const root = v.root = h(doc, 'div', { cls: 'dg-root dg-layout-' + L + (v.o.chrome === false ? ' dg-bare' : '') });
  R.toolbar = h(doc, 'div', { cls: 'dg-toolbar', attrs: { role: 'toolbar', 'aria-label': (v.o.title || 'Grid') + ' tools' } });
  R.wells = h(doc, 'div', { cls: 'dg-wells' });
  R.status = h(doc, 'div', { cls: 'dg-status', attrs: { 'aria-label': 'Status' } });
  R.center = h(doc, 'div', { cls: 'dg-gridarea' }, [v.body.scroller, R.status]);
  if (L === 'panel') {
    R.side = h(doc, 'aside', { cls: 'dg-side', attrs: { 'aria-label': 'Pivot tool panel' } }, [h(doc, 'div', { cls: 'dg-side-head', text: 'Pivot' }), R.wells]);
    R.card = h(doc, 'div', { cls: 'dg-card', attrs: { role: 'dialog', 'aria-label': 'Explain', hidden: true } });
    R.center.insertBefore(R.toolbar, R.center.firstChild);
    R.main = h(doc, 'div', { cls: 'dg-main' }, [R.center, R.side, R.card]);
    root.append(R.main);
  } else if (L === 'terminal') {
    R.cmd = h(doc, 'div', { cls: 'dg-cmd' });
    R.split = h(doc, 'section', { cls: 'dg-split', attrs: { 'aria-label': 'Explain' } });
    R.fkeys = h(doc, 'div', { cls: 'dg-fkeys', attrs: { role: 'toolbar', 'aria-label': 'Function keys' } });
    R.main = h(doc, 'div', { cls: 'dg-main' }, [R.center]);
    root.append(R.cmd, R.wells, R.toolbar, R.main, R.split, R.fkeys);
    buildCommand(v);
  } else {
    R.drawer = h(doc, 'aside', { cls: 'dg-drawer', attrs: { 'aria-label': 'Explain', hidden: true } });
    R.main = h(doc, 'div', { cls: 'dg-main' }, [R.center, R.drawer]);
    root.append(R.toolbar, R.wells, R.main);
  }
  root.addEventListener('click', (e) => {
    const b = e.target.closest('[data-act]');
    if (!b || !(R.toolbar.contains(b) || (R.fkeys && R.fkeys.contains(b)))) { return; }
    e.stopPropagation();
    toolbarAction(v, b.dataset.act, b);
  });
  v.host.insertBefore(root, v.host.firstChild);
}

/** @private the terminal's command line: the wells as a command, read back on Enter */
function buildCommand(v) {
  const doc = v.doc, R = v.regions;
  const input = R.cmdInput = h(doc, 'input', { cls: 'dg-cmd-in', attrs: { type: 'text', 'aria-label': 'Pivot command: ROWS fields BY fields VALUES fields FILTER fields', spellcheck: 'false' } });
  const go = () => {
    const fields = v.model.columns.all().map((c) => ({ id: c.id, header: c.header }));
    const r = parseCommand(input.value, fields);
    if (r.error) { input.setAttribute('aria-invalid', 'true'); v.say(r.error); return; }
    input.removeAttribute('aria-invalid');
    v.activeWells().setState(r.wells);
    v.wellsChanged('Command run');
  };
  input.addEventListener('keydown', (e) => { if (e.key === 'Enter') { e.preventDefault(); go(); } });
  R.cmdMeta = h(doc, 'span', { cls: 'dg-cmd-meta' });
  R.cmd.append(h(doc, 'span', { cls: 'dg-cmd-label', text: 'PIVOT' }), input, h(doc, 'button', { cls: 'dg-btn dg-go', text: 'GO', attrs: { type: 'button', 'aria-label': 'Run the command' }, on: { click: go } }), R.cmdMeta);
}

/** @private a toolbar button */
function button(doc, act, text, o) {
  o = o || {};
  return h(doc, 'button', { cls: 'dg-btn' + (o.pressed ? ' on' : ''), text: text, attrs: { type: 'button', 'aria-pressed': o.pressed === undefined ? null : String(!!o.pressed),
    'aria-haspopup': o.menu ? 'menu' : null, title: o.title || null, disabled: o.disabled || null, 'aria-expanded': o.expanded === undefined ? null : String(!!o.expanded) }, data: { act: act } });
}

/**
 * Draws the chrome again: the toolbar, the wells, the command line, the function keys, the status bar and the Explain area.
 * @param {Object} v the Grid
 * @returns {void}
 */
export function renderChrome(v) {
  const doc = v.doc, m = v.model, R = v.regions, L = m.layout, piv = m.pivotMode;
  // toolbar
  const tb = R.toolbar;
  const quick = tb.querySelector('.dg-quick');
  const quickFocused = quick && doc.activeElement === quick;
  tb.textContent = '';
  tb.append(h(doc, 'span', { cls: 'dg-title', text: v.o.title || '' }));
  if (v.live) { tb.append(h(doc, 'span', { cls: 'dg-badge dg-live', text: 'live' })); }
  if (v.o.subtitle) { tb.append(h(doc, 'span', { cls: 'dg-meta', text: v.o.subtitle })); }
  tb.append(h(doc, 'span', { cls: 'dg-sp' }));
  if (v.phone && piv) {
    const ms = v.measures(), sel = selectElement(doc, { options: ms, value: v.ui.phoneMeasure && ms.indexOf(v.ui.phoneMeasure) >= 0 ? v.ui.phoneMeasure : undefined,
      label: 'Measure shown', search: false }, { class: 'dg-measure' });                    // drishti-inputs' Select
    sel.component.on('change', (d) => v.switchMeasure(d.value));
    tb.append(sel);
  }
  if (v.phone) { tb.append(button(doc, 'toggle-fields', 'Fields', { expanded: v.ui.fieldsOpen })); }
  if (!piv) {
    const q = h(doc, 'input', { cls: 'dg-quick', attrs: { type: 'search', placeholder: 'Filter rows', 'aria-label': 'Filter rows', value: m.rowModel.quickFilter || '' } });
    q.addEventListener('input', () => m.setQuickFilter(q.value));
    tb.append(q);
    if (quickFocused) { q.focus(); }
  }
  tb.append(button(doc, 'toggle-pivot', 'Pivot', { pressed: piv, title: 'Pivot ⇄ flat (F5)' }));
  if (piv) {
    tb.append(button(doc, 'expand-all', 'Expand all'), button(doc, 'collapse-all', 'Collapse all'), button(doc, 'pin-totals', 'Pin totals', { pressed: v.ui.pinTotals }));
  }
  tb.append(button(doc, 'menu-format', 'Formatting ▾', { menu: true }), button(doc, 'menu-export', 'Export ▾', { menu: true }), button(doc, 'menu-view', 'View ▾', { menu: true }));
  // wells
  const how = L === 'panel' ? 'panel' : L === 'terminal' ? 'lane' : 'ribbon';
  if (v.o.chrome === false) { R.wells.textContent = ''; } else { v.wells.render(R.wells, how); }      // a bare grid draws no wells: none of their fields and wells in the host's page
  const hideWells = v.phone && !v.ui.fieldsOpen;
  (R.side || R.wells).hidden = hideWells;
  // the terminal's command line and keys
  if (R.cmdInput && doc.activeElement !== R.cmdInput) { R.cmdInput.value = commandText(v.activeWells().getState()); }
  if (R.cmdMeta) { R.cmdMeta.textContent = (m.answer ? (m.answer.computedBy === 'browser' ? 'computed here' : 'server') + ' · ' + (m.answer.notes && m.answer.notes.length ? 'notes' : 'exact') : '') + (v.o.subtitle ? ' · ' + v.o.subtitle : ''); }
  if (R.fkeys) {
    R.fkeys.textContent = '';
    FUNCTION_KEYS.forEach((k) => R.fkeys.append(h(doc, 'button', { cls: 'dg-fkey', attrs: { type: 'button', 'aria-keyshortcuts': k.key }, data: { act: k.action } }, [h(doc, 'b', { text: k.key }), ' ' + k.label])));
  }
  renderStatus(v);
  renderExplainArea(v);
}

/**
 * Draws the status bar: what is shown and where it came from, figures still asked of the server, the value filters, the selected rows,
 * and the selected range's figures (no sum of a non-additive measure: a warning instead).
 * @param {Object} v the Grid
 * @returns {void}
 */
export function renderStatus(v) {
  const doc = v.doc, m = v.model, st = v.regions.status, piv = m.pivotMode;
  if (!st) { return; }
  st.textContent = '';
  const item = (text, cls) => st.append(h(doc, 'span', { cls: 'dg-st' + (cls ? ' ' + cls : ''), text: text }));
  const shown = m.shownColumns();
  if (piv) { item(m.answer ? 'Pivot · ' + (m.answer.computedBy === 'browser' ? 'computed in the browser' : 'by the server') : 'Pivot', 'dg-st-mode'); }
  else { item(m.serverSide ? 'Server-side rows' : 'Rows in the page', 'dg-st-mode'); }
  item(m.rowCount().toLocaleString('en-US') + ' rows × ' + v.displayColumns().all.length + ' columns', 'dg-st-count');
  const pending = piv && m.answer && m.answer.pending ? m.answer.pending.length : 0;
  if (pending) { item('… ' + pending + ' figures asked of the server', 'dg-st-pending'); }
  (m.pivotExtra.valueFilters || []).forEach((f) => item('value filter: ' + conditionText(f, (x) => v.fmtMeasure(f.by, x)), 'dg-st-vf'));
  const n = m.selection.selectedIds().length;
  if (n) { item(n + (n === 1 ? ' row' : ' rows') + ' selected', 'dg-st-rows'); }
  const ranges = m.selection.ranges;
  if (ranges.length) {
    const c0 = shown[ranges[0].c1] || { type: 'number' };
    const fmt = (x) => formatValue(x, c0.type === 'number' && !c0.rowHeader ? c0 : { type: 'number' });
    const sel = h(doc, 'span', { cls: 'dg-st dg-st-range' });
    statusItems(rangeStats(ranges, (r, c) => m.cellValue(r, c), (j) => ({ header: shown[j] ? shown[j].measure || shown[j].header : '', additive: shown[j] ? shown[j].additive : true })), fmt).forEach((it) => sel.append(h(doc, 'span', { cls: it.warn ? 'dg-warn' : 'dg-agg' }, [it.label + ' ', h(doc, 'b', { text: it.value })])));
    st.append(sel);
  }
  st.append(h(doc, 'span', { cls: 'dg-sp' }));
  if (shown.some((c) => c.additive === false && c.type === 'number')) { item('◆ totals by the server', 'dg-st-nadd'); }
}

/**
 * Draws Explain where the layout keeps it: the drawer (wells), the card at the cell (panel), the split pane (terminal).
 * @param {Object} v the Grid
 * @returns {void}
 */
export function renderExplainArea(v) {
  const R = v.regions;
  if (!R) { return; }
  const L = R.layout;
  const close = () => v.closeExplain();
  if (L === 'terminal') { renderExplain(R.split, v.ex, 'split', { explainable: !!v.o.explain }); return; }
  const place = L === 'panel' ? R.card : R.drawer;
  place.hidden = !v.exOpen;
  R.main.classList.toggle('dg-open', !!v.exOpen);
  if (!v.exOpen) { return; }
  renderExplain(place, v.ex, L === 'panel' ? 'card' : 'drawer', { onClose: close, explainable: !!v.o.explain });
  if (L === 'panel' && v.ex) {
    const cell = v.doc.getElementById(v.uid + '-r' + v.ex.row + '-c' + v.ex.col);
    const box = R.main.getBoundingClientRect();
    if (cell) {
      const r = cell.getBoundingClientRect(), w = place.offsetWidth || 330;
      let left = r.right - box.left + 8;
      if (left + w > box.width) { left = Math.max(8, r.left - box.left - w - 8); }
      place.style.left = left + 'px';
      place.style.top = Math.max(8, Math.min(r.top - box.top - 12, box.height - place.offsetHeight - 8)) + 'px';
    }
  }
}
