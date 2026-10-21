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
/* UI Board P4 (UI_BOARD.md section 10, 12.4): a chip's ViewModel painted into HTML. The server sends {chip, title, body, pins, controls, keys}
   (12.3: the pins hold the supplier's rows in the chip's own column names, a `many` column as [{name, value}], a document pin as one row); this
   turns it into the console's own renderers, never new ones:
   - a `visual` body (the bi-* chips) and the chart panel kinds (line, area, hbar, waterfall, gauge, metric) go to the BI visual renderers
     (bi-visuals.js, bi-grids.js: drishtiBi.visuals.draw) with the chip's columns as the wells;
   - a table-like body (table, ladder, pivot, timeline, status, links, provenance and anything else with rows) is a `table.tbl`, which tables.js
     pages, filters and gives its Chart button (Chart any table);
   - kv is a definition list, markdown a note, search and date-range are inputs that send their out pins.
   A selection calls opts.onSelect(pin, value): a row sends its `keys` entry (the server's id or masked-id token), a chart mark its value.
   Everything is built with DOM calls and textContent, never markup strings. Exposes window.drishtiUiBoard.paint. */
(function () {
  'use strict';
  const U = (window.drishtiUiBoard = window.drishtiUiBoard || {});
  const MASK = '•••';

  function h(tag, attrs, ...kids) {
    const el = document.createElement(tag);
    Object.keys(attrs || {}).forEach((k) => {
      const v = attrs[k];
      if (k === 'text') { el.textContent = v; } else if (k === 'class') { el.className = v; } else if (v === true) { el.setAttribute(k, ''); } else if (v !== false && v != null) { el.setAttribute(k, v); }
    });
    (function add(list) { list.forEach((c) => { if (c == null || c === false) { return; } if (Array.isArray(c)) { add(c); } else { el.appendChild(typeof c === 'string' ? document.createTextNode(c) : c); } }); })(kids);
    return el;
  }
  function clear(el) { el.replaceChildren(); return el; }                  // atomic: a blur handler fired by the removal cannot trip it
  U.h = h;
  U.clear = clear;

  /** The body's first panel: {kind, type, wells, on-select, ...}. */
  function panelOf(vm) { const b = vm && vm.body; return (b && Array.isArray(b.panels) && b.panels[0]) || {}; }

  /** The first data pin's value: rows (an array) or a document (an object). */
  function dataOf(vm) {
    const pins = (vm && vm.pins) || {};
    let rows = null, doc = null, name = '';
    Object.keys(pins).forEach((k) => {
      const v = pins[k];
      if (rows == null && Array.isArray(v)) { rows = v; name = name || k; } else if (doc == null && v && typeof v === 'object' && !Array.isArray(v)) { doc = v; name = name || k; }
    });
    return { rows, doc, name };
  }

  const isNum = (v) => typeof v === 'number' && isFinite(v);
  function fmt(v) {
    if (v == null) { return ''; }
    if (isNum(v)) { return Math.abs(v) >= 1000 || !Number.isInteger(v) ? v.toLocaleString(undefined, { maximumFractionDigits: 2 }) : String(v); }
    if (typeof v === 'object') { return JSON.stringify(v); }
    return String(v);
  }

  /** The rows as a grid: a `many` column becomes one column per name (NOTIONAL, MTM_USD). {cols: [{name, label, numeric, masked}], rows: [[...]], cells: [[...]]} */
  U.flatten = function (rows) {
    const cols = [], seen = {};
    const add = (name, label) => { if (!(name in seen)) { seen[name] = cols.length; cols.push({ name, label, numeric: true, masked: false, any: false }); } return seen[name]; };
    const grid = (rows || []).map((r) => {
      const out = [];
      Object.keys(r || {}).forEach((k) => {
        const v = r[k];
        if (Array.isArray(v) && v.every((x) => x && typeof x === 'object' && 'name' in x)) {
          v.forEach((nv) => { out[add(nv.name, nv.name)] = nv.value; });
        } else { out[add(k, k)] = v; }
      });
      return out;
    });
    grid.forEach((r) => cols.forEach((c, i) => {
      const v = r[i];
      if (v === MASK) { c.masked = true; } else if (v != null && v !== '') { c.any = true; if (!isNum(v)) { c.numeric = false; } }
    }));
    cols.forEach((c) => { if (!c.any) { c.numeric = false; } });
    return { cols, rows: grid };
  };

  // ---- a table ------------------------------------------------------------------------------------------------------------------------------

  function table(host, vm, data, opts) {
    const p = panelOf(vm), g = U.flatten(data.rows), keys = vm.keys || null;
    const sel = p['on-select'] || null, pin = sel && sel.emits, col = sel && typeof sel.value === 'string' ? sel.value.replace(/^@\./, '') : 'id';
    if (!g.rows.length) { host.appendChild(h('p', { class: 'ub-empty', role: 'status', text: 'No rows for this chip: the supplier answered none you may see, or the filters exclude them.' })); return; }
    const t = h('table', { class: 'tbl ub-tbl', 'aria-label': vm.title || 'Rows', 'data-instance': opts.instance || '' });
    t.appendChild(h('thead', {}, h('tr', {}, g.cols.map((c) => h('th', { scope: 'col', class: c.numeric ? 'num' : null, title: c.masked ? c.label + ' (masked for you)' : null }, c.label, c.masked ? h('i', { class: 'bi bi-eye-slash', 'aria-hidden': 'true' }) : null)))));
    const tb = h('tbody');
    const at = g.cols.findIndex((c) => c.name === col);
    g.rows.forEach((r, i) => {
      const key = keys && keys[i] != null ? keys[i] : (at >= 0 ? r[at] : null);
      const tr = h('tr', { 'data-key': key == null ? null : String(key), tabindex: pin && key != null ? '0' : null, class: pin && key != null ? 'ub-pick' : null },
        g.cols.map((c, j) => h('td', { class: c.numeric ? 'num' : (r[j] === MASK ? 'ub-masked' : null) }, fmt(r[j]))));
      if (pin && key != null) {
        const pick = () => {
          tb.querySelectorAll('tr[aria-selected="true"]').forEach((x) => x.setAttribute('aria-selected', 'false'));
          tr.setAttribute('aria-selected', 'true');
          opts.onSelect && opts.onSelect(pin, key);
        };
        tr.addEventListener('click', pick);
        tr.addEventListener('keydown', (e) => { if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); pick(); } });
      }
      tb.appendChild(tr);
    });
    t.appendChild(tb);
    host.appendChild(h('div', { class: 'tbl-wrap ub-tbl-wrap' }, t));
  }

  // ---- the BI renderers ---------------------------------------------------------------------------------------------------------------------

  const PANEL_AS_BI = { line: 'line', area: 'area', hbar: 'bar', waterfall: 'waterfall', gauge: 'gauge', metric: 'card', histogram: 'column' };

  /** A well of the body ('@rows.values') as the grid's column names. */
  function wellCols(expr, rows, g) {
    const m = /^@[\w-]+\.([\w-]+)$/.exec(String(expr || ''));
    if (!m) { return []; }
    const first = (rows || [])[0] || {};
    const v = first[m[1]];
    if (Array.isArray(v)) { return v.map((nv) => nv.name); }
    return g.cols.some((c) => c.name === m[1]) ? [m[1]] : [];
  }

  function visual(host, vm, data, opts) {
    const B = window.drishtiBi, V = B && B.visuals;
    const p = panelOf(vm), g = U.flatten(data.rows || (data.doc ? [data.doc] : []));
    if (!V || !V.draw || !g.rows.length) { return false; }
    const type = p.kind === 'visual' ? p.type : PANEL_AS_BI[p.kind];
    if (!type || type === 'table' || type === 'matrix') { return false; }
    const wells = {};
    Object.keys(p.wells || {}).forEach((w) => { wells[w] = wellCols(p.wells[w], data.rows, g); });
    if (!wells.values || !wells.values.length) { wells.values = g.cols.filter((c) => c.numeric).map((c) => c.name); }
    if (!wells.axis || !wells.axis.length) { const d = g.cols.find((c) => !c.numeric); wells.axis = d ? [d.name] : []; }
    if (!wells.values.length) { return false; }
    const spec = { id: opts.instance || 'chip', type, title: vm.title, wells, format: {} };
    const res = { columns: g.cols.map((c) => ({ name: c.name, type: c.numeric ? 'number' : 'string' })), rows: g.rows, meta: { masked: g.cols.filter((c) => c.masked).map((c) => c.name) } };
    const sel = p['on-select'];
    const box = h('div', { class: 'ub-visual bi-vis-body', 'data-visual': type });
    host.appendChild(box);
    const t = B.tokens ? B.tokens(host) : {};
    const ctx = { index: { measures: {}, columns: {}, tables: {}, hierarchies: {} }, palette: B.palette ? B.palette('default', t) : [], t, interactive: !!sel, selected: null, selections: [], formats: {},
      onMark: (mark) => { if (sel && sel.emits && opts.onSelect) { opts.onSelect(sel.emits, mark.value); } }, navigate: () => {} };
    try {
      const drawn = V.draw(box, spec, ctx, res);
      host.__dispose = drawn && drawn.dispose;
      return true;
    } catch (e) {
      host.removeChild(box);
      return false;
    }
  }

  // ---- the small ones -----------------------------------------------------------------------------------------------------------------------

  function kv(host, vm, data) {
    const d = data.doc || ((data.rows || [])[0]) || {};
    const dl = h('dl', { class: 'ub-kv' });
    Object.keys(d).forEach((k) => {
      const v = d[k];
      if (Array.isArray(v)) { v.forEach((nv) => { dl.appendChild(h('dt', { text: nv.name })); dl.appendChild(h('dd', { class: nv.value === MASK ? 'ub-masked' : null, text: fmt(nv.value) })); }); } else { dl.appendChild(h('dt', { text: k })); dl.appendChild(h('dd', { class: v === MASK ? 'ub-masked' : null, text: fmt(v) })); }
    });
    host.appendChild(dl.childNodes.length ? dl : h('p', { class: 'ub-empty', text: 'Nothing to show.' }));
  }

  function note(host, vm) {
    const text = String((vm.controls && vm.controls.text) || (vm.pins && vm.pins.text) || '');
    const box = h('div', { class: 'ub-note' });
    text.split(/\n{2,}/).forEach((para) => box.appendChild(h('p', { text: para.replace(/^#+\s*/, '') })));
    host.appendChild(box);
  }

  function search(host, vm, opts) {
    const p = panelOf(vm), id = 'ubq-' + (opts.instance || 'chip');
    const input = h('input', { type: 'search', class: 'studio-in ub-search', id, placeholder: 'Search, then Enter', 'aria-label': vm.title || 'Search' });
    input.addEventListener('keydown', (e) => { if (e.key === 'Enter') { e.preventDefault(); opts.onSelect && opts.onSelect(p.emits || 'filter-out', input.value.trim()); } });
    host.appendChild(input);
  }

  function dates(host, vm, opts) {
    const p = panelOf(vm), c = vm.controls || {};
    const from = h('input', { type: 'date', class: 'studio-in', value: c['default-from'] || '', 'aria-label': 'From' });
    const to = h('input', { type: 'date', class: 'studio-in', value: c['default-to'] || '', 'aria-label': 'To' });
    const send = () => {
      if (!opts.onSelect) { return; }
      opts.onSelect('from', from.value || null);
      opts.onSelect('to', to.value || null);
      ((p['on-change'] || []).some((x) => x.emits === 'period')) && opts.onSelect('period', from.value + '/' + to.value);
    };
    from.addEventListener('change', send);
    to.addEventListener('change', send);
    host.appendChild(h('div', { class: 'ub-dates' }, h('label', {}, 'From ', from), h('label', {}, 'To ', to)));
  }

  /** Paints one chip's ViewModel into host (emptied first). opts: {instance, onSelect(pin, value), meta}. */
  U.paint = function (host, vm, opts) {
    opts = opts || {};
    if (host.__dispose) { try { host.__dispose(); } catch (e) { /* the old chart is gone */ } host.__dispose = null; }
    clear(host);
    const p = panelOf(vm), data = dataOf(vm), kind = p.kind || '';
    host.setAttribute('data-kind', kind);
    if (kind === 'kv') { kv(host, vm, data); } else if (kind === 'markdown') { note(host, vm); } else if (kind === 'search') { search(host, vm, opts); } else if (kind === 'date-range') { dates(host, vm, opts); } else if (!visual(host, vm, data, opts)) {
      if (data.rows) { table(host, vm, data, opts); } else if (data.doc) { kv(host, vm, data); } else { host.appendChild(h('p', { class: 'ub-empty', text: 'Nothing to show.' })); }
    }
    const meta = opts.meta || {};
    const foot = [];
    if (meta.truncated) { foot.push('First ' + meta.rows + ' of ' + (meta.total || 'more') + ' rows'); }
    if (meta.masked && meta.masked.length) { foot.push('Masked for you: ' + meta.masked.join(', ')); }
    if (meta.reused) { foot.push('Shared answer (same access)'); }
    if (foot.length) { host.appendChild(h('p', { class: 'ub-foot text-muted-d', text: foot.join(' · ') })); }
  };
})();
