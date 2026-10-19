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
/* Rupaka phase 3 (RUPAKA.md section 8): the table, the matrix, the slicers and the static visuals (text, image, button) of a report, plus the generic
   data table every visual can be shown as. A table sorts by a column header and can open a row's entity in the terminal; a matrix groups its rows by the row
   fields, spreads one field across its columns, and adds subtotals (collapsible) and totals. A total of a measure that adds up (sum, count) is the sum of the
   figures shown; a total of one that does not (ratio, average, distinct count, percentile, min, max) is the engine's own, worked out over the rows of the whole
   group and sent with the answer (`totals`); a table with `format.totals` ends with such a row too. Slicers: a list, a dropdown (the shared multi-select), a range, or tiles.
   The matrix itself is in bi-matrix.js. */
(function () {
  'use strict';
  const B = window.drishtiBi;
  const h = B.h;
  const V = B.visuals;
  const NONE = { dispose() {}, resize() {} };

  /** True for a measure whose total is the sum of its parts: a sum or a count and nothing else. */
  V.additive = function (ctx, name) {
    const m = ctx.index.measures[name];
    const e = m && m.expr ? m.expr : '';
    return /^\s*(sum|count)\s*\(/.test(e) && !/(avg|ratio|distinct|percentile|min\(|max\(|\/|\[)/.test(e);
  };

  /** The engine's totals of a visual's answer: `find(by, values, measure)` is the figure for the group with these values of these fields; undefined when the engine gave none. */
  V.trueTotals = function (res) {
    const levels = (res && res.totals) || [];
    return {
      any: levels.length > 0,
      find(by, values, measure) {
        const lv = levels.find((l) => l.by.length === by.length && l.by.every((f, i) => f === by[i]));
        if (!lv) { return undefined; }
        const mi = lv.columns.indexOf(measure);
        if (mi < 0 || (lv.masked || []).indexOf(measure) >= 0) { return undefined; }
        const row = lv.rows.find((r) => by.every((f, i) => String(r[lv.columns.indexOf(f)] == null ? '\u2013' : r[lv.columns.indexOf(f)]) === String(values[i])));
        return row ? row[mi] : undefined;
      }
    };
  };

  function cellText(v, spec, ctx, name, masked) {
    const isMeasure = !!ctx.index.measures[name];
    if (isMeasure || typeof v === 'number') { return B.fmt(v, V.numFmt(spec, ctx, name), masked); }
    return v == null ? (masked ? '•••' : '–') : String(v);
  }

  function terminalLink(ctx, name, v) {
    const col = ctx.index.columns[name];
    const kind = col && (col.entity || (['fact', 'dimension', 'bridge'].indexOf(col.kind) < 0 ? col.kind : ''));      // a model's table names its entity; a 2.1 table is one
    if (!col || !kind || v == null || !(col.key || col.role === 'key' || col.name === 'id')) { return null; }
    return h('a', { href: '/v/' + encodeURIComponent(kind) + '/' + encodeURIComponent(String(v)), class: 'bi-toterm-link', 'data-open-terminal': kind, title: 'Open in the terminal', text: String(v) });
  }

  // ---- table ---------------------------------------------------------------------------------------------------------

  /** A table of the visual's columns in the order its well lists them (or of every column of the result when `all` is set). */
  V.dataTable = function (host, spec, ctx, res, all) {
    const tb = V.table(res);
    const names = all ? tb.names : (spec.wells.columns || []).filter((n) => tb.at(n) >= 0);
    const bars = new Set((spec.format && spec.format.dataBars) || []);
    const sort = host.__sort || (host.__sort = { by: null, desc: false });
    let rows = tb.rows.map((r) => r);
    if (sort.by) {
      const i = tb.at(sort.by);
      rows.sort((a, b) => { const x = a[i], y = b[i]; const c = x == null ? -1 : y == null ? 1 : typeof x === 'number' && typeof y === 'number' ? x - y : String(x).localeCompare(String(y)); return sort.desc ? -c : c; });
    }
    const max = {};
    names.forEach((n) => { if (bars.has(n)) { max[n] = Math.max(1e-9, ...rows.map((r) => Math.abs(Number(r[tb.at(n)]) || 0))); } });
    const head = h('tr', {}, names.map((n) => {
      const isNum = !!ctx.index.measures[n];
      const btn = h('button', { type: 'button', class: 'bi-sorthead', 'data-sort': n }, B.label(n), sort.by === n ? h('i', { class: 'bi bi-' + (sort.desc ? 'caret-down-fill' : 'caret-up-fill'), 'aria-hidden': 'true' }) : null);
      btn.addEventListener('click', () => { sort.desc = sort.by === n ? !sort.desc : isNum; sort.by = n; V.dataTable(host, spec, ctx, res, all); });
      return h('th', { scope: 'col', class: isNum ? 'num' : '', 'aria-sort': sort.by === n ? (sort.desc ? 'descending' : 'ascending') : 'none' }, btn);
    }));
    const firstField = names.find((n) => !ctx.index.measures[n]);
    const styler = all ? null : V.styler(spec, tb, ctx);
    const f = spec.format || {};
    const body = h('tbody', {});
    rows.forEach((r, ri) => {
      const tr = h('tr', { 'data-row': '', class: f.rowBanding && ri % 2 === 1 ? 'bi-band' : '' });
      names.forEach((n) => {
        const i = tb.at(n), v = r[i], isMeasure = !!ctx.index.measures[n];
        const td = h('td', { class: isMeasure || typeof v === 'number' ? 'num' : '' });
        const link = !isMeasure && ctx.interactive ? terminalLink(ctx, n, v) : null;
        if (link) { td.appendChild(link); } else if (bars.has(n) && typeof v === 'number') {
          const w = h('span', { class: 'bi-bar' });
          w.style.width = Math.round(100 * Math.abs(v) / max[n]) + '%';
          td.appendChild(h('span', { class: 'bi-barcell' }, w, h('span', { class: 'bi-bartext', text: cellText(v, spec, ctx, n, tb.isMasked(n)) })));
        } else { td.textContent = cellText(v, spec, ctx, n, tb.isMasked(n)); }
        if (styler) { V.paintCell(td, styler.cell(n, v, r)); }                // conditional formatting (2.4.0): a rule, a colour scale, icons, a colour per value
        tr.appendChild(td);
      });
      if (ctx.interactive && firstField && ctx.onMark && !all) {
        tr.tabIndex = 0;
        tr.setAttribute('aria-label', 'Row ' + String(r[tb.at(firstField)]) + ': open the actions for it');
        const act = (e) => { const rc = tr.getBoundingClientRect(); ctx.onMark({ visual: spec.id, field: firstField, value: r[tb.at(firstField)], label: String(r[tb.at(firstField)]), row: true }, { x: e.clientX || rc.left + 20, y: e.clientY || rc.bottom, el: tr }); };
        tr.addEventListener('click', (e) => { if (!e.target.closest('a,button')) { act(e); } });
        tr.addEventListener('keydown', (e) => { if (e.key === 'Enter' && e.target === tr) { e.preventDefault(); act(e); } });
      }
      body.appendChild(tr);
    });
    let foot = null;
    const tt = V.trueTotals(res);
    if (spec.format && spec.format.totals && tt.any && !all && rows.length) {
      foot = h('tfoot', {}, h('tr', { class: 'bi-grandtotal', 'data-total-row': '' }, names.map((n, k) => {
        if (!ctx.index.measures[n]) { return h('th', { scope: 'row' }, k === 0 ? 'Total' : ''); }
        const v = tt.find([], [], n);
        const td = h('td', { class: 'num bi-total', 'data-measure': n, 'data-true-total': '' });
        td.textContent = v === undefined ? '–' : B.fmt(v, V.numFmt(spec, ctx, n), tb.isMasked(n));
        td.title = 'Worked out over all the rows, not added up from the rows shown';
        return td;
      })));
    }
    const table = h('table', { class: 'tbl bi-tbl bi-vgrid bi-vis-table' + (f.wrap ? ' bi-wrap' : ''), 'data-plain': true }, h('caption', { class: 'visually-hidden', text: spec.title || 'Table' }), h('thead', {}, head), body, foot);
    if (typeof f.fontSize === 'number') { table.style.fontSize = f.fontSize + 'px'; }
    B.clear(host).appendChild(h('div', { class: 'tbl-wrap bi-vis-scroll' }, table));
    if (!rows.length) { host.appendChild(h('p', { class: 'bi-vis-empty', role: 'status', text: 'No rows for this selection.' })); }
    return NONE;
  };

  // the matrix is bi-matrix.js (2.4.0: every level of the rows, stepped or tabular, values on rows, Expand all and Collapse all)

  // ---- slicer --------------------------------------------------------------------------------------------------------

  /** The names a relative-date slicer offers when the report lists none (the server works each out: business-day aware). */
  const RELATIVE = ['last-7-days', 'last-30-days', 'last-90-days', 'last-5-business-days', 'last-4-weeks', 'last-3-months', 'last-12-months', 'this-week', 'this-month', 'this-quarter',
    'this-year', 'previous-week', 'previous-month', 'previous-quarter', 'previous-year', 'all'];
  V.RELATIVE = RELATIVE;
  V.relativeLabel = function (name) {
    if (name === 'all') { return 'All dates'; }
    const m = /^last-(\d+)-(days|business-days|weeks|months)$/.exec(name);
    if (m) { return 'Last ' + (m[1] === '1' ? '' : m[1] + ' ') + m[2].replace('-', ' ').replace(/s$/, m[1] === '1' ? '' : 's'); }
    const p = /^(this|previous)-(week|month|quarter|year)$/.exec(name);
    return p ? (p[1] === 'this' ? 'This ' + p[2] + ' to date' : 'Previous ' + p[2]) : name;
  };

  V.slicer = function (host, spec, ctx, res) {
    if (spec.format && spec.format.mode === 'relative-date') {
      const options = spec.format.options && spec.format.options.length ? spec.format.options : RELATIVE;
      const cur = ctx.slicer && ctx.slicer.value ? (typeof ctx.slicer.value === 'string' ? ctx.slicer.value : ctx.slicer.value.relative) : (spec.format.default || 'all');
      const sel = h('select', { class: 'studio-select', 'aria-label': spec.title || 'Relative dates', 'data-relative': '' },
        options.map((o) => h('option', { value: o, text: V.relativeLabel(o), selected: o === cur })));
      sel.addEventListener('change', () => { if (ctx.slicer && ctx.slicer.onChange) { ctx.slicer.onChange({ relative: sel.value }); } });
      B.clear(host).appendChild(h('div', { class: 'bi-slicer', 'data-mode': 'relative-date' }, sel));
      return NONE;
    }
    const tb = V.table(res);
    const field = spec.wells.field[0];
    const mode = (spec.format && spec.format.mode) || 'list';
    const values = [];
    tb.rows.forEach((r) => { if (r[0] != null) { values.push(r[0]); } });
    const cur = ctx.slicer ? ctx.slicer.value : null;
    const set = (v) => { if (ctx.slicer && ctx.slicer.onChange) { ctx.slicer.onChange(v); } };
    const wrap = h('div', { class: 'bi-slicer', 'data-mode': mode });
    if (mode === 'range') {
      const col = B.column(ctx.index, field);
      const type = col && col.type === 'date' ? 'date' : col && col.type === 'timestamp' ? 'date' : 'number';
      const nums = values.map((v) => (type === 'number' ? Number(v) : String(v))).sort((a, b) => (a < b ? -1 : a > b ? 1 : 0));
      const from = h('input', { type, class: 'studio-in', 'aria-label': 'From', 'data-range': 'from', placeholder: nums.length ? String(nums[0]) : '', value: cur && cur.from != null ? String(cur.from) : '' });
      const to = h('input', { type, class: 'studio-in', 'aria-label': 'To', 'data-range': 'to', placeholder: nums.length ? String(nums[nums.length - 1]) : '', value: cur && cur.to != null ? String(cur.to) : '' });
      const hint = h('p', { class: 'bi-vis-note bi-bad', role: 'alert', 'data-range-hint': '', hidden: '' });
      const apply = () => {
        const f = from.value === '' ? null : type === 'number' ? Number(from.value) : from.value;
        const t = to.value === '' ? null : type === 'number' ? Number(to.value) : to.value;
        const inverted = f != null && t != null && f > t;
        hint.textContent = inverted ? 'From is after To, so nothing can match: swap them.' : '';
        hint.hidden = !inverted;
        from.setAttribute('aria-invalid', inverted ? 'true' : 'false');
        to.setAttribute('aria-invalid', inverted ? 'true' : 'false');
        set(f == null && t == null ? null : { from: f, to: t });
      };
      from.addEventListener('change', apply);
      to.addEventListener('change', apply);
      wrap.appendChild(h('label', { class: 'bi-range' }, h('span', { text: 'From' }), from));
      wrap.appendChild(h('label', { class: 'bi-range' }, h('span', { text: 'To' }), to));
      wrap.appendChild(hint);
      wrap.appendChild(h('p', { class: 'bi-vis-note', text: nums.length ? 'Values run from ' + nums[0] + ' to ' + nums[nums.length - 1] + '.' : 'No values.' }));
      B.clear(host).appendChild(wrap);
      return NONE;
    }
    const chosen = new Set(Array.isArray(cur) ? cur.map(String) : []);
    const boxes = [];
    const list = h('div', { class: 'bi-slicer-list', role: 'group', 'aria-label': spec.title || field });
    values.forEach((v) => {
      const box = h('input', { type: 'checkbox', value: String(v), 'data-slicer-value': String(v) });
      box.checked = chosen.has(String(v));
      boxes.push(box);
      list.appendChild(h('label', { class: 'bi-checkrow' }, box, h('span', { text: tb.isMasked(field) ? '•••' : String(v) })));
    });
    const emit = () => {
      const picked = boxes.filter((b) => b.checked).map((b) => { const orig = values.find((v) => String(v) === b.value); return orig; });
      set(picked.length && picked.length < boxes.length ? picked : picked.length === boxes.length && boxes.length ? picked : null);
    };
    if (mode === 'dropdown') {
      const details = h('details', { class: 'bi-dropdown', 'data-slicer-dropdown': '' }, h('summary', { text: 'All' }));
      boxes.forEach((b) => { b.addEventListener('change', () => {}); });
      wrap.appendChild(details);
      B.clear(host).appendChild(wrap);
      list.hidden = false;
      details.appendChild(list);
      const ms = window.drishtiModules && window.drishtiModules.multiselect;
      if (ms) { ms.decorate(details, { label: spec.title || field, boxes, onChange: emit }); } else { boxes.forEach((b) => b.addEventListener('change', emit)); }
      return NONE;
    }
    if ((spec.format || {}).style === 'tiles') {                                  // a tile slicer (2.4.0): the values as buttons, pressed when chosen
      const row = h('div', { class: 'bi-slicer-tiles', role: 'group', 'aria-label': spec.title || field });
      values.forEach((v, i) => {
        const b = h('button', { type: 'button', class: 'bi-slicer-tile', 'aria-pressed': chosen.has(String(v)) ? 'true' : 'false', 'data-slicer-tile': String(v), text: tb.isMasked(field) ? '•••' : String(v) });
        b.addEventListener('click', () => { boxes[i].checked = !boxes[i].checked; emit(); });
        row.appendChild(b);
      });
      wrap.setAttribute('data-style', 'tiles');
      wrap.appendChild(row);
      B.clear(host).appendChild(wrap);
      return NONE;
    }
    const q = h('input', { type: 'search', class: 'studio-in', placeholder: 'Search', 'aria-label': 'Search ' + (spec.title || field) });
    q.addEventListener('input', () => { const t = q.value.trim().toLowerCase(); boxes.forEach((b) => { b.closest('label').hidden = !!t && b.value.toLowerCase().indexOf(t) < 0; }); });
    boxes.forEach((b) => b.addEventListener('change', emit));
    const clear = h('button', { type: 'button', class: 'btn-pill btn-ghost bi-slicer-clear', text: 'Clear' });
    clear.addEventListener('click', () => { boxes.forEach((b) => { b.checked = false; }); set(null); });
    wrap.appendChild(h('div', { class: 'bi-slicer-head' }, q, clear));
    wrap.appendChild(list);
    B.clear(host).appendChild(wrap);
    return NONE;
  };

  // ---- static --------------------------------------------------------------------------------------------------------

  V.staticVisual = function (host, spec, ctx) {
    B.clear(host);
    if (spec.type === 'text') {
      const heading = spec.format && spec.format.size === 'heading';
      (spec.text || '').split(/\n+/).forEach((line) => host.appendChild(h(heading ? 'h3' : 'p', { class: 'bi-text', text: line })));
    } else if (spec.type === 'image') {
      host.appendChild(h('img', { class: 'bi-picture', src: spec.src || '', alt: spec.alt || '' }));
    } else if (spec.type === 'button') {
      const b = h('button', { type: 'button', class: 'btn-pill btn-accent bi-button', 'data-action': JSON.stringify(spec.action || {}), text: spec.text || spec.title || 'Go' });
      b.addEventListener('click', () => { if (ctx.interactive && ctx.navigate) { ctx.navigate(spec.action || {}); } });
      host.appendChild(b);
    }
    return NONE;
  };

  /** A visual of the 2.1 Perspective types (perspective-grid, -pivot, -chart): wave B draws no WebAssembly on BI pages (BI_DESIGN.md P3), so it is Drishti's own
   *  grid of the server's answer, every column the answer has (the server grouped it: nothing is pivoted or added up here). */
  V.perspectiveGrid = function (host, spec, ctx, res) {
    if (!res) { B.clear(host).appendChild(h('p', { class: 'bi-vis-empty', role: 'status', text: 'No rows.' })); return NONE; }
    return V.dataTable(host, spec, ctx, res, true);
  };

  /**
   * An entity view (RUPAKA.md section 20.3): the Drishti view of the entity whose key is selected on the page, inline. The key well names a field (a table's
   * id column); a click on a mark of another visual that selects one value of it shows that entity's view here. The view is the console's own page in its
   * embedded form, so it is built for the person looking: their masks and rights apply, not the report's author's. Outside the report viewer (the designer's
   * canvas, an embedded element) it shows what it will do instead.
   */
  V.entityView = function (host, spec, ctx) {
    const field = spec.wells.key && spec.wells.key[0];
    const col = field ? B.column(ctx.index, field) : null;
    const kind = (spec.format && spec.format.kind) || (col && col.kind) || '';
    const note = (text) => B.clear(host).appendChild(h('div', { class: 'bi-vis-note bi-entity-note', role: 'status', 'data-entity': 'none' }, h('p', { text })));
    if (!ctx.entity) { note('Shows the ' + (kind || 'entity') + ' view of the ' + (field ? B.label(field) : 'key') + ' selected in another visual, in the report viewer.'); return NONE; }
    if (!field || !kind) { note('Name the key field of an entity (a table\'s id column) in the key well.'); return NONE; }
    const sel = (ctx.selections || []).find((s) => s.field === field && s.values.length === 1);
    if (!sel) { note('Select a ' + B.label(field) + ' in another visual to see its ' + kind + ' here.'); return NONE; }
    const src = '/v/' + encodeURIComponent(kind) + '/' + encodeURIComponent(String(sel.values[0])) + '?embed=1';
    const frame = h('iframe', { class: 'bi-entity-frame', src, title: (spec.title || kind) + ': ' + sel.values[0], 'data-entity': String(sel.values[0]), loading: 'lazy' });
    B.clear(host).appendChild(frame);
    return NONE;
  };

  /** Draws any visual: a chart or a card (bi-visuals.js), else the table, the matrix, the slicer or a static one; `res` is null for static visuals. */
  V.draw = function (host, spec, ctx, res) {
    if (spec.type === 'entity-view') { return V.entityView(host, spec, ctx); }
    if (['text', 'image', 'button'].indexOf(spec.type) >= 0) { return V.staticVisual(host, spec, ctx); }
    if (spec.type === 'table') { return V.dataTable(host, spec, ctx, res, false); }
    if (spec.type === 'matrix') { return V.matrix(host, spec, ctx, res); }
    if (spec.type === 'slicer') { return V.slicer(host, spec, ctx, res); }
    if (spec.type.indexOf('perspective-') === 0) { return V.perspectiveGrid(host, spec, ctx, res); }
    if (spec.type === 'vega-lite' && B.vega) { return B.vega.draw(host, spec, ctx, res); }
    return V.drawChartOrCard(host, spec, ctx, res) || NONE;
  };
})();
