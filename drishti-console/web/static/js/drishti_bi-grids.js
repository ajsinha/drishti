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
(function (g) { g.Drishti = g.Drishti || {}; })(typeof window !== 'undefined' ? window : globalThis); /* one namespace for everything the console puts on window (tools/dev/asset_prefix.py) */
/* Rupaka phase 3 (HOW_IT_FITS.md §A28 › Rūpaka: the original design and every phase as built, section 8): the table, the matrix, the slicers and the static visuals (text, image, button) of a report, plus the generic
   data table every visual can be shown as. A table sorts by a column header and can open a row's entity in the terminal; a matrix groups its rows by the row
   fields, spreads one field across its columns, and adds subtotals (collapsible) and totals. A total of a measure that adds up (sum, count) is the sum of the
   figures shown; a total of one that does not (ratio, average, distinct count, percentile, min, max) is the engine's own, worked out over the rows of the whole
   group and sent with the answer (`totals`); a table with `format.totals` ends with such a row too. Slicers: a list, a dropdown (the shared multi-select), a range, or tiles.
   The matrix itself is in drishti_bi-matrix.js. A table is drawn by drishti-grid (Drishti Elements), every figure the engine's as it came. */
(function () {
  'use strict';
  const B = window.drishtiBi;
  const h = B.h;
  const V = B.visuals;
  const NONE = { dispose() {}, resize() {} };

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
    const kind = B.terminalKind(ctx.index.columns[name]);           // a key of an entity's table, or a column that references a kind
    if (!kind || v == null) { return null; }
    return h('a', { href: '/v/' + encodeURIComponent(kind) + '/' + encodeURIComponent(String(v)), class: 'bi-toterm-link', 'data-open-terminal': kind, title: 'Open in the terminal', text: String(v) });
  }

  // ---- table ---------------------------------------------------------------------------------------------------------

  /** The grid a visual's table is drawn with (drishti-grid, Drishti Elements), once the page holds it; a page without it says so. */
  function gridLib(host, again) {
    const E = window.Drishti && window.Drishti.elements;
    if (E && E.grid) { return E.grid; }
    if (E && E.load) {
      host.__gridWait = again;
      E.load('drishti-grid').then(() => { if (host.__gridWait === again) { host.__gridWait = null; again(); } },
        (e) => { B.clear(host).appendChild(h('p', { class: 'bi-vis-empty', role: 'status', text: 'The table could not be drawn: ' + (e && e.message ? e.message : e) })); });
      B.clear(host).appendChild(h('p', { class: 'bi-vis-empty', role: 'status', text: 'Loading the table…' }));
      return null;
    }
    B.clear(host).appendChild(h('p', { class: 'bi-vis-empty', role: 'status', text: 'The table needs the Elements grid (drishti-grid), which this page did not load.' }));
    return null;
  }

  /**
   * A table of the visual's columns in the order its well lists them (or of every column of the result when `all` is set), drawn by
   * drishti-grid: sorted by a heading, the first column pinned, every figure the engine's as it came (the grid sums nothing:
   * clientAggregation false), conditional formats and data bars, a link to an entity in the terminal, a row's actions on a click, and
   * the engine's own totals under it (worked out over all the rows, never added up from the rows shown).
   */
  V.dataTable = function (host, spec, ctx, res, all) {
    const G = gridLib(host, () => V.dataTable(host, spec, ctx, res, all));
    if (!G) { return NONE; }
    const tb = V.table(res);
    const names = all ? tb.names : (spec.wells.columns || []).filter((n) => tb.at(n) >= 0);
    const f = spec.format || {};
    const bars = (f.dataBars || []).filter((n) => names.indexOf(n) >= 0);
    const styler = all ? null : V.styler(spec, tb, ctx);
    const firstField = names.find((n) => !ctx.index.measures[n]);
    const ids = names.map((n, i) => 'c' + i);
    const numeric = names.map((n) => !!ctx.index.measures[n] || tb.rows.some((r) => typeof r[tb.at(n)] === 'number'));
    const columns = names.map((n, i) => ({
      id: ids[i], field: ids[i], header: B.label(n), type: numeric[i] ? 'number' : 'text', pinned: i === 0 ? 'left' : null, width: numeric[i] ? 120 : 160,
      enableRowGroup: false, enablePivot: false, enableValue: false, additive: false, description: n,
      format: (v) => cellText(v, spec, ctx, n, tb.isMasked(n))
    }));
    const rows = tb.rows.map((r, k) => { const o = { __k: String(k), __r: r }; names.forEach((n, i) => { o[ids[i]] = r[tb.at(n)]; }); return o; });
    const key = names.join('\u0001') + '|' + (all ? 1 : 0);
    const old = host.__grid;
    if (old && old.key === key && host.contains(old.box)) {            // a live redraw of the same columns: the rows in place (sort and scroll kept)
      old.spec = spec; old.ctx = ctx;
      old.grid.setRows(rows);
      foot(host, old.box, spec, ctx, res, tb, names, all, rows.length);
      return old.handle;
    }
    if (old) { old.grid.destroy(); }
    const box = h('div', { class: 'bi-grid-box' + (f.wrap ? ' bi-wrap' : '') + (f.rowBanding ? ' bi-banded' : ''), 'data-grid': '' });
    if (typeof f.fontSize === 'number') { box.style.fontSize = f.fontSize + 'px'; }
    B.clear(host).appendChild(box);
    const st = host.__grid = { key, box, spec, ctx, grid: null, handle: null };
    const decorate = (c) => {
      if (c.kind !== 'leaf' || !c.data) { return null; }
      const i = ids.indexOf(c.columnId), n = names[i], v = c.value;
      const out = {};
      const link = !ctx.index.measures[n] && st.ctx.interactive ? terminalLink(st.ctx, n, v) : null;
      if (link) { out.content = link; }
      const style = styler && !all ? styler.cell(n, v, c.data.__r) : null;  // conditional formatting (2.4.0): a rule, a colour scale, icons, a colour per value
      if (style) {
        out.style = { color: style.colour || null, 'background-color': style.background || null, 'font-weight': style.colour ? '600' : null };
        if (style.background) { out.cls = 'bi-cf-bg'; }
        if (style.icon) {
          const ic = h('i', { class: 'bi bi-' + style.icon.name + ' bi-cf-icon', 'aria-hidden': 'true', 'data-cf-icon': style.icon.name });
          ic.style.color = style.icon.colour;
          out.before = h('span', { class: 'bi-cf-mark' }, ic, h('span', { class: 'visually-hidden', text: style.icon.words + ': ' }));
          out.title = style.icon.words;
        }
      }
      return out.content || out.style || out.before ? out : null;
    };
    const grid = st.grid = new G.Grid(box, {
      columns, rows, getRowId: (r) => r.__k, layout: 'wells', density: 'compact', chrome: false, checkboxes: false, rowSelection: 'none',
      clientAggregation: false, label: spec.title || 'Table', decorateCell: decorate,
      conditionalFormats: bars.map((n) => ({ column: ids[names.indexOf(n)], type: 'dataBar' }))
    });
    if (ctx.interactive && firstField && ctx.onMark && !all) {            // a click on a row, or Enter on it: the actions for its first field's member
      const fi = ids[names.indexOf(firstField)];
      const act = (data) => {
        const at = box.querySelector('.dg-cell.dg-focus') || box;
        const rc = at.getBoundingClientRect(), v = data[fi];
        st.ctx.onMark({ visual: spec.id, field: firstField, value: v, label: String(v), row: true }, { x: rc.left + 20, y: rc.bottom, el: at });
      };
      box.addEventListener('drishti:cell-clicked', (e) => { const d = e.detail || {}; if (d.kind === 'leaf' && d.data) { act(d.data); } });
      box.addEventListener('keydown', (e) => {
        if (e.key !== 'Enter' || e.altKey || e.ctrlKey || e.metaKey) { return; }
        const f = grid.model.navigator.focus, x = f ? grid.model.rowAt(f.row) : null;
        if (x && x.kind === 'leaf') { e.preventDefault(); e.stopPropagation(); act(x.row); }
      }, true);
    }
    foot(host, box, spec, ctx, res, tb, names, all, rows.length);
    st.handle = { dispose() { grid.destroy(); if (host.__grid === st) { host.__grid = null; } }, resize() { grid.refresh(); } };
    return st.handle;
  };

  /** Under a table: the engine's own totals (format.totals), or a word when no row is left. */
  function foot(host, box, spec, ctx, res, tb, names, all, count) {
    host.querySelectorAll('.bi-grid-foot, .bi-vis-empty').forEach((x) => x.remove());
    const tt = V.trueTotals(res);
    if (spec.format && spec.format.totals && tt.any && !all && count) {
      host.appendChild(h('div', { class: 'bi-grid-foot bi-grandtotal', 'data-total-row': '', role: 'group', 'aria-label': 'Totals, worked out over all the rows' }, names.map((n, k) => {
        if (!ctx.index.measures[n]) { return h('span', { class: 'bi-total-label', text: k === 0 ? 'Total' : '' }); }
        const v = tt.find([], [], n);
        return h('span', { class: 'num bi-total', 'data-measure': n, 'data-true-total': '', title: 'Worked out over all the rows, not added up from the rows shown' },
          h('span', { class: 'bi-total-name', text: B.label(n) + ' ' }), h('b', { text: v === undefined ? '–' : B.fmt(v, V.numFmt(spec, ctx, n), tb.isMasked(n)) }));
      })));
    }
    if (!count) { host.appendChild(h('p', { class: 'bi-vis-empty', role: 'status', text: 'No rows for this selection.' })); }
  }

  // the matrix is drishti_bi-matrix.js (2.4.0: every level of the rows, stepped or tabular, values on rows, Expand all and Collapse all)

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
      const sel = B.select({ 'aria-label': spec.title || 'Relative dates', 'data-relative': '' }, options.map((o) => [o, V.relativeLabel(o)]), options.indexOf(cur) >= 0 ? cur : undefined);   // drishti-inputs' Select
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
      const I = window.Drishti.elements && window.Drishti.elements.inputs;
      if (type === 'date' && I) { [from, to].forEach((x) => I.upgradeDateInput(x, { placeholder: x.placeholder })); }   // drishti-inputs' date picker
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
    if (mode === 'dropdown') {                                                   // drishti-inputs' multi-select over the slicer's checkboxes
      const box = h('div', { class: 'bi-dropdown', 'data-slicer-dropdown': '' });
      wrap.appendChild(box);
      B.clear(host).appendChild(wrap);
      box.appendChild(list);
      const I = window.Drishti.elements && window.Drishti.elements.inputs;
      const up = I ? I.upgradeCheckboxes(list, { label: spec.title || field }) : null;
      if (up) { up.picker.on('change', emit); } else { boxes.forEach((b) => b.addEventListener('change', emit)); }
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

  /** A visual of the 2.1 Perspective types (perspective-grid, -pivot, -chart): wave B draws no WebAssembly on BI pages (HOW_IT_FITS.md §A28 P3), so it is Drishti's own
   *  grid of the server's answer, every column the answer has (the server grouped it: nothing is pivoted or added up here). */
  V.perspectiveGrid = function (host, spec, ctx, res) {
    if (!res) { B.clear(host).appendChild(h('p', { class: 'bi-vis-empty', role: 'status', text: 'No rows.' })); return NONE; }
    return V.dataTable(host, spec, ctx, res, true);
  };

  /**
   * An entity view (HOW_IT_FITS.md §A28 › Rūpaka: the original design and every phase as built, section 20.3): the Drishti view of the entity whose key is selected on the page, inline. The key well names a field (a table's
   * id column); a click on a mark of another visual that selects one value of it shows that entity's view here. The view is the console's own page in its
   * embedded form, so it is built for the person looking: their masks and rights apply, not the report's author's. Outside the report viewer (the designer's
   * canvas, an embedded element) it shows what it will do instead.
   */
  V.entityView = function (host, spec, ctx) {
    const field = spec.wells.key && spec.wells.key[0];
    const col = field ? B.column(ctx.index, field) : null;
    const kind = (spec.format && spec.format.kind) || (col && (col.entity || col.kind)) || '';          // a model's table: the kind of entity its rows are
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

  // ---- specs drawn by drishti-chart: a vega-lite visual, a Python visual's Vega-Lite or drishti-chart answer (O1: Vega is retired) ----
  /** The rows of a query result as objects. A field keeps the name after the table (`trades.desk` is `desk`); the table is put back (`trades_desk`) when two share a name. */
  V.records = function (res) {
    const names = res.columns.map((c) => c.name);
    const short = names.map((n) => n.slice(n.lastIndexOf('.') + 1));
    const keys = names.map((n, i) => (short.indexOf(short[i]) !== short.lastIndexOf(short[i]) ? n.replace(/\./g, '_') : short[i]));
    return res.rows.map((r) => { const o = {}; keys.forEach((k, i) => { o[k] = r[i]; }); return o; });
  };

  const chartLib = () => window.Drishti && window.Drishti.elements && window.Drishti.elements.chart;
  const chartNote = (host, text) => { B.clear(host).appendChild(h('p', { class: 'bi-vis-empty', role: 'status', text })); return NONE; };
  /** The handle a frame disposes, for a drishti-chart drawn in host (chart.ready marks the host's data-ready). */
  function chartHandle(host, chart, from) {
    host.setAttribute('data-chart-from', from);
    if (chart && chart.ready) { chart.ready.then(() => host.setAttribute('data-ready', '1')); }
    return { dispose() { B.clear(host); host.removeAttribute('data-ready'); }, resize() { if (chart && chart.engine) { chart.engine.resize(); } } };
  }

  /** A drishti-chart spec (a Python visual's `output: drishti-chart`) drawn by drishti-chart; a spec the library would draw wrong is refused with its problems. */
  V.drishtiChart = function (host, cs, spec) {
    const C = chartLib();
    if (!C) { return chartNote(host, 'drishti-chart did not load.'); }
    const problems = C.checkSpec(cs);
    if (problems.length) { return chartNote(host, 'Not drawn: the drishti-chart spec has problems: ' + problems.map((p) => p.path + ' ' + p.message).join('; ') + '.'); }
    if (cs.data && cs.data.source) { return chartNote(host, 'Not drawn: a Python chart carries its rows (data.rows); it loads nothing from an address (' + cs.data.source + ').'); }
    const copy = Object.assign({ title: undefined }, cs);
    return chartHandle(host, C.chartIn(host, copy, { density: 'compact' }), 'drishti-chart');
  };

  /** A Vega-Lite specification (its data inline or `rows`) translated and drawn by drishti-chart, or refused in words with the alternative: never drawn wrong. */
  V.vegaLite = function (host, vl, spec, rows) {
    const C = chartLib();
    if (!C) { return chartNote(host, 'drishti-chart did not load.'); }
    const got = C.vegaLiteIn(host, vl, { rows: rows, density: 'compact' });
    return got.chart ? chartHandle(host, got.chart, 'vega-lite') : NONE;
  };

  /** A `type: vega-lite` visual: the specification of `format.spec`, its data the rows of the visual's query (the wells). */
  V.vegaLiteVisual = function (host, spec, ctx, res) {
    const vl = spec.format && spec.format.spec;
    if (!vl || typeof vl !== 'object') { return chartNote(host, 'This visual has no Vega-Lite specification (format: { spec: ... }).'); }
    return V.vegaLite(host, vl, spec, V.records(res));
  };

  /** Draws any visual: a chart or a card (drishti_bi-visuals.js), else the table, the matrix, the slicer or a static one; `res` is null for static visuals. */
  V.draw = function (host, spec, ctx, res) {
    if (res && res.wells) { spec = Object.assign({}, spec, { wells: res.wells }); }       // a field parameter (param.<name>) drawn as the field or measure chosen (2.6.0)
    if (spec.type === 'entity-view') { return V.entityView(host, spec, ctx); }
    if (spec.type === 'decomposition') { return V.decomposition(host, spec, ctx); }
    if (['text', 'image', 'button'].indexOf(spec.type) >= 0) { return V.staticVisual(host, spec, ctx); }
    if (spec.type === 'table') { return V.dataTable(host, spec, ctx, res, false); }
    if (spec.type === 'matrix') { return V.matrix(host, spec, ctx, res); }
    if (spec.type === 'slicer') { return V.slicer(host, spec, ctx, res); }
    if (spec.type.indexOf('perspective-') === 0) { return V.perspectiveGrid(host, spec, ctx, res); }
    if (spec.type === 'vega-lite') { return V.vegaLiteVisual(host, spec, ctx, res); }
    return V.drawChartOrCard(host, spec, ctx, res) || NONE;
  };
})();
