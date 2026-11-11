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
/* The document table: the server's table drawn as a semantic <table>, every row in the DOM, with the console's table classes (tbl,
   num, tr-*, dr-*, pivot, heat-*). The grid speaks the server's table as it speaks the server's pivot: the data is the ViewModel's
   (columns, numeric, rows with cells, children, crumbs; totals, more), every figure and text the server's. A plain table, a tree
   (children, opened to expand levels), a drill-down (levels: one level at a time), a table that offers Pivot, the pivot kind's table (heat
   buckets, totals), and the plain Data table under a chart. What a cell shows (an entity link, an outside link) is the host's: the hooks.
   The behaviours (sort, filter, pages, tree rows, drill levels) are drishti_doc-behave.js. Nothing here parses markup. */
import { h, roundHalfUp } from '../../drishti-core/src/drishti_index.js';

/**
 * What the host draws in a document table: a cell's content, its tone class, a row's extra attributes.
 * @typedef {Object} DocTableHooks
 * @property {function(Object): (Node|string|Array)} [content] a cell's drawn value (default: its text)
 * @property {function(Object): string} [tone] a cell's tone class, with its leading space (default: ' t-<tone>' or '')
 * @property {function(Object, number): Object} [rowAttrs] extra attributes on a plain table's row (null and false are left out)
 */

const str = (v) => (v === null || v === undefined ? '' : String(v));
const defTone = (c) => (c && c.tone ? ' t-' + c.tone : '');

function flat(out, c) {
  if (c === null || c === undefined || c === false) { return out; }
  if (Array.isArray(c)) { c.forEach((x) => flat(out, x)); return out; }
  out.push(typeof c === 'object' && c.nodeType ? c : String(c));
  return out;
}

/** h with the renderer's shape: (tag, attrs, ...children), arrays flattened, numbers as text. */
function mk(doc) {
  return (tag, attrs, ...children) => h(doc, tag, { attrs: attrs || null }, flat([], children));
}

function ctx(doc, hooks) {
  const hk = hooks || {};
  return { e: mk(doc), content: hk.content || ((c) => str(c && c.text)), tone: hk.tone || defTone, rowAttrs: hk.rowAttrs || null };
}

const num = (numeric, i) => !!(numeric || [])[i];
const tdCls = (k, numeric, i, c) => 'mono' + (num(numeric, i) ? ' num' : '') + k.tone(c);

function head(k, columns, numeric) {
  return k.e('thead', null, k.e('tr', null, (columns || []).map((c, i) => k.e('th', { class: num(numeric, i) ? 'num' : null }, str(c)))));
}

function foot(k, d) {
  if (!d.total && !d.more) { return null; }
  return k.e('tfoot', null,
    d.total ? k.e('tr', { class: 'total' }, (d.total.cells || []).map((c, i) => k.e('td', { class: tdCls(k, d.numeric, i, c) }, str(c.text)))) : null,
    d.more ? k.e('tr', { class: 'more' }, k.e('td', { colspan: String((d.columns || []).length) }, str(d.more))) : null);
}

/** The rows of a tree table: a row, then the rows under it; a row at depth k shows while k < expand. */
function treeRows(k, d, rows, depth, out) {
  for (const r of rows || []) {
    const kids = r.children || [], open = depth + 1 < d.expand;
    out.push(k.e('tr', { class: 'tr-node' + (r.highlight ? ' hl' : ''), 'data-depth': String(depth), 'data-kids': kids.length ? true : null,
      hidden: depth >= d.expand ? true : null, 'data-path': r.path || null },
    (r.cells || []).map((c, i) => k.e('td', { class: tdCls(k, d.numeric, i, c), 'data-path': c.path || null },
      i === 0 ? k.e('span', { class: 'tr-ind tr-d' + Math.min(depth, 24) }, kids.length
        ? k.e('button', { type: 'button', class: 'tr-tog', 'aria-expanded': open ? 'true' : 'false', 'aria-label': (open ? 'Collapse ' : 'Expand ') + str(c.text) }, open ? '▾' : '▸')
        : k.e('span', { class: 'tr-sp', 'aria-hidden': 'true' })) : null,
      k.content(c)))));
    treeRows(k, d, kids, depth + 1, out);
  }
  return out;
}

/**
 * A table panel's table (the macro table): plain, or a tree when the data says expand (rows with children, open to that depth).
 * @param {Document} doc
 * @param {Object} d the panel's data: columns, numeric, rows ({cells, highlight, path, pick, ev, children}), total, more, expand, search
 * @param {DocTableHooks} [hooks]
 * @returns {HTMLElement} div.tbl-wrap holding the table
 */
export function docTable(doc, d, hooks) {
  const k = ctx(doc, hooks);
  if (d.expand) {
    return k.e('div', { class: 'tbl-wrap' }, k.e('table', { class: 'tbl tbl-tree', 'data-plain': true, 'data-tree': true, 'data-no-search': d.search === false ? true : null },
      head(k, d.columns, d.numeric), k.e('tbody', null, treeRows(k, d, d.rows, 0, [])), foot(k, d)));
  }
  return k.e('div', { class: 'tbl-wrap' }, k.e('table', { class: 'tbl', 'data-no-search': d.search === false ? true : null },
    head(k, d.columns, d.numeric),
    k.e('tbody', null, (d.rows || []).map((r, ri) => k.e('tr', Object.assign({ class: r.highlight ? 'hl' : null, 'data-path': r.path || null, 'data-pick': r.pick || null,
      'data-ev': r.ev || null, tabindex: r.ev ? '0' : null, 'aria-selected': r.ev ? 'false' : null }, k.rowAttrs ? k.rowAttrs(r, ri) : null),
    (r.cells || []).map((c, i) => k.e('td', { class: tdCls(k, d.numeric, i, c), 'data-path': c.path || null }, k.content(c)))))),
    foot(k, d)));
}

/**
 * A table that offers Pivot (the macro pivotable): the table, and under it the link to the pivot screen on the panel's rows when the
 * data names a pivot source.
 * @param {Document} doc
 * @param {{id: string, title: string, data: Object}} panel
 * @param {DocTableHooks} [hooks]
 * @returns {(HTMLElement|HTMLElement[])} the table, or the table and the Pivot link
 */
export function docPivotable(doc, panel, hooks) {
  const src = panel.data.pivot && panel.data.pivot.source;
  if (!src) { return docTable(doc, panel.data, hooks); }
  const e = mk(doc), title = panel.title || panel.id;
  return [docTable(doc, panel.data, hooks), e('p', { class: 'pv-open' }, e('a', { class: 'btn btn-sm btn-outline-secondary', href: '/pivot?source=' + encodeURIComponent(src).replace(/%2F/g, '/'),
    title: 'Pivot ' + title + ': rows, columns, values and filters, every total from its own rows (BI_GUIDE.md)' }, 'Pivot'))];
}

// ---- drill-down -------------------------------------------------------------------------------------------------------------
function drRows(k, pid, rows, numeric, prefix, deeper, noun) {
  return k.e('tbody', null, (rows || []).map((r, ri) => {
    const path = prefix + ri, kids = r.children || [];
    return k.e('tr', { class: r.highlight ? 'hl' : null, 'data-path': r.path || null, 'data-dr-row': path },
      (r.cells || []).map((c, i) => k.e('td', { class: tdCls(k, numeric, i, c) }, i === 0 && deeper
        ? k.e('a', { class: 'dr-open', href: '?dr.' + pid + '=' + path, 'data-dr-open': path, title: 'Open ' + str(r.crumb) + ': ' + kids.length + ' ' + noun,
          'aria-label': 'Open ' + str(r.crumb) + ': ' + kids.length + ' ' + noun }, (c.text !== null && c.text !== undefined && c.text !== '') ? str(c.text) : str(r.crumb),
        k.e('span', { class: 'dr-n', 'aria-hidden': 'true' }, kids.length + ' ▸'))
        : k.content(c))));
  }));
}

function drFoot(k, total, numeric) {
  return total ? k.e('tfoot', null, k.e('tr', { class: 'total' }, (total.cells || []).map((c, i) => k.e('td', { class: tdCls(k, numeric, i, c) }, str(c.text))))) : null;
}

function drLevels(k, pid, d, rows, prefix, depth, out) {
  const lv = d.levels[depth], ph = depth === 0 ? d.columns : d.levels[depth - 1].columns;
  const deeper = depth + 1 < d.levels.length, noun = deeper ? String(d.levels[depth + 1].title || 'rows').toLowerCase() : '';
  (rows || []).forEach((r, ri) => {
    const path = prefix + ri, kids = r.children || [];
    out.push(k.e('div', { class: 'dr-lv', 'data-dr-path': path, 'data-dr-crumb': str(r.crumb), 'data-dr-depth': String(depth + 1), hidden: true },
      k.e('dl', { class: 'dr-parent', 'aria-label': str(r.crumb) }, (r.cells || []).map((c, i) => (c.text === null || c.text === undefined || c.text === '') ? null
        : k.e('div', null, k.e('dt', null, str((ph || [])[i])), k.e('dd', { class: 'mono' + k.tone(c) }, k.content(c))))),
      kids.length
        ? k.e('div', { class: 'tbl-wrap' }, k.e('table', { class: 'tbl dr-tbl', 'aria-label': (lv.title || 'Rows') + ' of ' + str(r.crumb) },
          k.e('caption', { class: 'dr-cap' }, (lv.title || 'Rows') + ' ', k.e('span', { class: 'dr-count' }, String(kids.length))),
          head(k, lv.columns, lv.numeric), drRows(k, pid, kids, lv.numeric, path + '.', deeper, noun), drFoot(k, r.kidsTotal, lv.numeric)))
        : k.e('p', { class: 'pnl-empty', role: 'status' }, k.e('i', { class: 'bi bi-inbox', 'aria-hidden': 'true' }), ' No ' + String(lv.title || 'rows').toLowerCase())));
    if (deeper && kids.length) { drLevels(k, pid, d, kids, path + '.', depth + 1, out); }
  });
  return out;
}

/**
 * A table whose data has levels (the macro drill): one level shows at a time, the breadcrumb above it, a row opens the rows under it.
 * The behaviour is enhanceDocTables'.
 * @param {Document} doc
 * @param {{id: string, title: string, data: Object}} panel data: columns, numeric, rows (with children and crumb), levels
 *   ({title, columns, numeric} per level under the first), total, more, root, search
 * @param {DocTableHooks} [hooks]
 * @returns {HTMLElement} div.drill
 */
export function docDrill(doc, panel, hooks) {
  const k = ctx(doc, hooks), p = panel, d = p.data, noun = String(d.levels[0].title || 'rows').toLowerCase(), root = d.root || p.title || '';
  return k.e('div', { class: 'drill', 'data-drill': p.id, 'data-dr-root': root },
    k.e('nav', { class: 'dr-crumbs', 'aria-label': 'Where you are in ' + (p.title || p.id) },
      k.e('ol', { 'data-dr-crumbs': true }, k.e('li', { 'aria-current': 'location' }, root)),
      k.e('button', { type: 'button', class: 'fk dr-up', 'data-dr-up': true, hidden: true, title: 'Up one level (Esc)', 'aria-label': 'Up one level' }, k.e('b', null, 'Esc'), ' Up')),
    k.e('div', { class: 'dr-lv on', 'data-dr-path': '', 'data-dr-depth': '0' },
      k.e('div', { class: 'tbl-wrap' }, k.e('table', { class: 'tbl dr-tbl', 'data-no-search': d.search === false ? true : null, 'aria-label': p.title || p.id },
        k.e('caption', { class: 'dr-cap' }, (p.title || '') + ' ', k.e('span', { class: 'dr-count' }, String((d.rows || []).length))),
        head(k, d.columns, d.numeric), drRows(k, p.id, d.rows, d.numeric, '', true, noun), drFoot(k, d.total, d.numeric),
        d.more ? k.e('tfoot', null, k.e('tr', { class: 'more' }, k.e('td', { colspan: String((d.columns || []).length) }, str(d.more)))) : null))),
    drLevels(k, p.id, d, d.rows, '', 0, []));
}

// ---- the pivot kind --------------------------------------------------------------------------------------------------------------
/**
 * The heat bucket 0..9 of a figure between the smallest and the largest (5 when the scale is flat), rounded half up as the server
 * rounds.
 * @param {number} v
 * @param {number} lo
 * @param {number} hi
 * @returns {number}
 */
export function heatBucket(v, lo, hi) {
  const span = (typeof lo === 'number' && typeof hi === 'number' && hi > lo) ? hi - lo : 0;
  return span ? Math.trunc(Number(roundHalfUp((v - lo) / span * 9, 0))) : 5;
}

/**
 * The pivot kind's table (the macros pivot and pivot_table): rows by one field, or nested by several (the plain table, which a host's
 * pivot grid may redraw from data-pv-tree), with heat buckets, row totals and the totals row.
 * @param {Document} doc
 * @param {Object} d the panel's data: by, columns, rows ({label, path, group, cells, values, total}), heat, min, max, totals, more, levels
 * @param {DocTableHooks} [hooks]
 * @returns {HTMLElement}
 */
export function docPivot(doc, d, hooks) {
  const k = ctx(doc, hooks), t = pivotTable(k, d);
  return d.levels ? k.e('div', { class: 'pv-tree-host', 'data-pv-tree': JSON.stringify(d) }, t) : t;
}

function pivotTable(k, d) {
  const by = d.by || '', cols = d.columns || [], lo = d.min, hi = d.max;
  return k.e('div', { class: 'tbl-wrap' }, k.e('table', { class: 'tbl pivot' + (d.heat ? ' heat' : '') },
    k.e('thead', null, k.e('tr', null, k.e('th', { scope: 'col' }, by.slice(0, 1).toUpperCase() + by.slice(1)),
      cols.map((c) => k.e('th', { scope: 'col', class: 'num' }, str(c))), d.totals ? k.e('th', { scope: 'col', class: 'num' }, 'Total') : null)),
    k.e('tbody', null, (d.rows || []).map((r) => k.e('tr', null,
      k.e('th', { scope: 'row', class: r.path ? 'pv-i' + r.path.length : null }, r.group ? k.e('b', null, str(r.label)) : str(r.label)),
      (r.cells || []).map((c, i) => {
        const v = (r.values || [])[i];
        return k.e('td', { class: 'mono num' + k.tone(c) + (d.heat && typeof v === 'number' ? ' heat-' + heatBucket(v, lo, hi) : '') }, str(c.text));
      }),
      r.total ? k.e('td', { class: 'mono num pv-total' + k.tone(r.total) }, str(r.total.text)) : null))),
    (d.totals || d.more) ? k.e('tfoot', null,
      d.totals ? k.e('tr', { class: 'total' }, k.e('th', { scope: 'row' }, 'Total'), d.totals.map((c) => k.e('td', { class: 'mono num' + k.tone(c) }, str(c.text)))) : null,
      d.more ? k.e('tr', { class: 'more' }, k.e('td', { colspan: String(cols.length + 2) }, d.more + ' more rows')) : null) : null));
}

// ---- the Data table under a chart -----------------------------------------------------------------------------------------------
/**
 * The plain Data table a chart keeps (nothing is only in the picture): in a details element whose summary is "Data", a table that is
 * never sorted (data-plain). A heading is a string (numeric after the first) or {text, num}; a row's first cell is its row heading
 * (o.rowHeader draws it, e.g. an entity link), every other cell a figure (td.mono.num) or {content, cls} for a cell of another class
 * (cls null: none). A row heading that is a Node is drawn as it is.
 * @param {Document} doc
 * @param {Array<(string|{text: string, num: boolean})>} headRow
 * @param {Array<Array<*>>} rows
 * @param {{summary: string, details: boolean, rowHeader: function(*, number): (Node|string|Array)}} [o] details false: the
 *   div.tbl-wrap alone, without the details and its summary
 * @returns {HTMLElement} details.xchart-data (or div.tbl-wrap)
 */
export function docPlainTable(doc, headRow, rows, o) {
  o = o || {};
  const e = mk(doc);
  const head0 = (c, ri) => (o.rowHeader ? o.rowHeader(c, ri) : (c && typeof c === 'object' && c.nodeType ? c : str(c)));
  const th = (x, i) => (x && typeof x === 'object' ? e('th', { scope: 'col', class: x.num ? 'num' : null }, str(x.text)) : e('th', { scope: 'col', class: i ? 'num' : null }, str(x)));
  const td = (c) => (c && typeof c === 'object' && !c.nodeType && 'content' in c ? e('td', { class: c.cls === undefined ? 'mono num' : c.cls }, c.content) : e('td', { class: 'mono num' }, typeof c === 'object' && c && c.nodeType ? c : str(c)));
  const wrap = e('div', { class: 'tbl-wrap' }, e('table', { class: 'tbl', 'data-plain': true },
    e('thead', null, e('tr', null, headRow.map(th))),
    e('tbody', null, rows.map((r, ri) => e('tr', null, r.map((c, i) => (i === 0 ? e('th', { scope: 'row' }, head0(c, ri)) : td(c))))))));
  return o.details === false ? wrap : e('details', { class: 'xchart-data' }, e('summary', null, o.summary || 'Data'), wrap);
}
