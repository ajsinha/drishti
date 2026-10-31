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
/* The table kinds (table, ladder and pivot): a plain table, a tree (children:), a drill-down (levels:), the Table | Pivot switch and the
   pivot table, ports of the macros table, tree_rows, drill, pivotable and pivot_table. Behaviours (sorting, filtering, paging, opening
   tree rows and drill levels) are in behave.js and attach to this DOM exactly as the console's scripts attach to the macros'. */
import { h, str } from './dom.js';
import { toneClass, heat, capitalize } from './format.js';
import { cellText } from './cells.js';

const num = (numeric, i) => !!(numeric || [])[i];
const tdCls = (numeric, i, c) => 'mono' + (num(numeric, i) ? ' num' : '') + toneClass(c);

function head(columns, numeric) {
  return h('thead', null, h('tr', null, (columns || []).map((c, i) => h('th', { class: num(numeric, i) ? 'num' : null }, str(c)))));
}

function foot(d) {
  if (!d.total && !d.more) { return null; }
  return h('tfoot', null,
    d.total ? h('tr', { class: 'total' }, (d.total.cells || []).map((c, i) => h('td', { class: tdCls(d.numeric, i, c) }, str(c.text)))) : null,
    d.more ? h('tr', { class: 'more' }, h('td', { colspan: String((d.columns || []).length) }, str(d.more))) : null);
}

/** The rows of a tree table: a row, then the rows under it; a row at depth k shows while k < expand. */
function treeRows(R, d, rows, depth, out) {
  for (const r of rows || []) {
    const kids = r.children || [], open = depth + 1 < d.expand;
    out.push(h('tr', { class: 'tr-node' + (r.highlight ? ' hl' : ''), 'data-depth': String(depth), 'data-kids': kids.length ? true : null,
      hidden: depth >= d.expand ? true : null, 'data-path': r.path || null },
    (r.cells || []).map((c, i) => h('td', { class: tdCls(d.numeric, i, c), 'data-path': c.path || null },
      i === 0 ? h('span', { class: 'tr-ind tr-d' + Math.min(depth, 24) }, kids.length
        ? h('button', { type: 'button', class: 'tr-tog', 'aria-expanded': open ? 'true' : 'false', 'aria-label': (open ? 'Collapse ' : 'Expand ') + str(c.text) }, open ? '▾' : '▸')
        : h('span', { class: 'tr-sp', 'aria-hidden': 'true' })) : null,
      cellText(R, c)))));
    treeRows(R, d, kids, depth + 1, out);
  }
  return out;
}

/** table(d): a plain table, or a tree when the Sutra says expand. */
export function table(R, d) {
  if (d.expand) {
    return h('div', { class: 'tbl-wrap' }, h('table', { class: 'tbl tbl-tree', 'data-plain': true, 'data-tree': true, 'data-no-search': d.search === false ? true : null },
      head(d.columns, d.numeric), h('tbody', null, treeRows(R, d, d.rows, 0, [])), foot(d)));
  }
  return h('div', { class: 'tbl-wrap' }, h('table', { class: 'tbl', 'data-no-search': d.search === false ? true : null },
    head(d.columns, d.numeric),
    h('tbody', null, (d.rows || []).map((r, ri) => h('tr', { class: r.highlight ? 'hl' : null, 'data-path': r.path || null, 'data-pick': r.pick || null,
      'data-ev': r.ev || null, tabindex: r.ev ? '0' : null, 'aria-selected': r.ev ? 'false' : null, 'data-r-row': String(ri) },
    (r.cells || []).map((c, i) => h('td', { class: tdCls(d.numeric, i, c), 'data-path': c.path || null }, cellText(R, c)))))),
    foot(d)));
}

// ---- drill-down -------------------------------------------------------------------------------------------------------------
function drRows(R, pid, rows, numeric, prefix, deeper, noun) {
  return h('tbody', null, (rows || []).map((r, ri) => {
    const path = prefix + ri, kids = r.children || [];
    return h('tr', { class: r.highlight ? 'hl' : null, 'data-path': r.path || null, 'data-dr-row': path },
      (r.cells || []).map((c, i) => h('td', { class: tdCls(numeric, i, c) }, i === 0 && deeper
        ? h('a', { class: 'dr-open', href: '?dr.' + pid + '=' + path, 'data-dr-open': path, title: 'Open ' + str(r.crumb) + ': ' + kids.length + ' ' + noun,
          'aria-label': 'Open ' + str(r.crumb) + ': ' + kids.length + ' ' + noun }, (c.text != null && c.text !== '') ? str(c.text) : str(r.crumb),
        h('span', { class: 'dr-n', 'aria-hidden': 'true' }, kids.length + ' ▸'))
        : cellText(R, c))));
  }));
}
function drFoot(total, numeric) {
  return total ? h('tfoot', null, h('tr', { class: 'total' }, (total.cells || []).map((c, i) => h('td', { class: tdCls(numeric, i, c) }, str(c.text))))) : null;
}
function drLevels(R, pid, d, rows, prefix, depth, out) {
  const lv = d.levels[depth], ph = depth === 0 ? d.columns : d.levels[depth - 1].columns;
  const deeper = depth + 1 < d.levels.length, noun = deeper ? String(d.levels[depth + 1].title || 'rows').toLowerCase() : '';
  (rows || []).forEach((r, ri) => {
    const path = prefix + ri, kids = r.children || [];
    out.push(h('div', { class: 'dr-lv', 'data-dr-path': path, 'data-dr-crumb': str(r.crumb), 'data-dr-depth': String(depth + 1), hidden: true },
      h('dl', { class: 'dr-parent', 'aria-label': str(r.crumb) }, (r.cells || []).map((c, i) => (c.text == null || c.text === '') ? null
        : h('div', null, h('dt', null, str((ph || [])[i])), h('dd', { class: 'mono' + toneClass(c) }, cellText(R, c))))),
      kids.length
        ? h('div', { class: 'tbl-wrap' }, h('table', { class: 'tbl dr-tbl', 'aria-label': (lv.title || 'Rows') + ' of ' + str(r.crumb) },
          h('caption', { class: 'dr-cap' }, (lv.title || 'Rows') + ' ', h('span', { class: 'dr-count' }, String(kids.length))),
          head(lv.columns, lv.numeric), drRows(R, pid, kids, lv.numeric, path + '.', deeper, noun), drFoot(r.kidsTotal, lv.numeric)))
        : h('p', { class: 'pnl-empty', role: 'status' }, h('i', { class: 'bi bi-inbox', 'aria-hidden': 'true' }), ' No ' + String(lv.title || 'rows').toLowerCase())));
    if (deeper && kids.length) { drLevels(R, pid, d, kids, path + '.', depth + 1, out); }
  });
  return out;
}

/** drill(p): a table whose Sutra says levels: one level shows at a time, a row opens the rows under it. */
export function drill(R, p) {
  const d = p.data, noun = String(d.levels[0].title || 'rows').toLowerCase(), root = d.root || p.title || '';
  return h('div', { class: 'drill', 'data-drill': p.id, 'data-dr-root': root },
    h('nav', { class: 'dr-crumbs', 'aria-label': 'Where you are in ' + (p.title || p.id) },
      h('ol', { 'data-dr-crumbs': true }, h('li', { 'aria-current': 'location' }, root)),
      h('button', { type: 'button', class: 'fk dr-up', 'data-dr-up': true, hidden: true, title: 'Up one level (Esc)', 'aria-label': 'Up one level' }, h('b', null, 'Esc'), ' Up')),
    h('div', { class: 'dr-lv on', 'data-dr-path': '', 'data-dr-depth': '0' },
      h('div', { class: 'tbl-wrap' }, h('table', { class: 'tbl dr-tbl', 'data-no-search': d.search === false ? true : null, 'aria-label': p.title || p.id },
        h('caption', { class: 'dr-cap' }, (p.title || '') + ' ', h('span', { class: 'dr-count' }, String((d.rows || []).length))),
        head(d.columns, d.numeric), drRows(R, p.id, d.rows, d.numeric, '', true, noun), drFoot(d.total, d.numeric),
        d.more ? h('tfoot', null, h('tr', { class: 'more' }, h('td', { colspan: String((d.columns || []).length) }, str(d.more)))) : null))),
    drLevels(R, p.id, d, d.rows, '', 0, []));
}

// ---- the Table | Pivot switch ------------------------------------------------------------------------------------------------
/** pivotable(p): the table alone, or with the Pivot tab the Sutra opts into (built when first shown, behave.js). */
export function pivotable(R, p) {
  if (!p.data.pivot) { return table(R, p.data); }
  const id = p.id, title = p.title || p.id;
  return h('div', { class: 'pv-box' },
    h('div', { class: 'pv-switch', role: 'tablist', 'aria-label': 'Show ' + title + ' as' },
      h('button', { type: 'button', role: 'tab', class: 'on', id: 'pv-' + id + '-t-table', 'data-pv-tab': 'table', 'aria-selected': 'true', 'aria-controls': 'pv-' + id + '-table' }, 'Table'),
      h('button', { type: 'button', role: 'tab', id: 'pv-' + id + '-t-pivot', 'data-pv-tab': 'pivot', 'aria-selected': 'false', 'aria-controls': 'pv-' + id + '-pivot', tabindex: '-1',
        title: 'Rearrange these rows as a pivot: rows, columns, values and filters' }, 'Pivot')),
    h('div', { class: 'pv-pane-table', id: 'pv-' + id + '-table', role: 'tabpanel', 'aria-labelledby': 'pv-' + id + '-t-table' }, table(R, p.data)),
    // the grid's spec and the reader's saved arrangement, as the macro's pivot_host: the Pivot tab's grid (pivot.js) reads them
    h('div', { class: 'pv-host', id: 'pv-' + id + '-pivot', role: 'tabpanel', 'aria-labelledby': 'pv-' + id + '-t-pivot', hidden: true, 'data-pivot': JSON.stringify(p.data.pivot),
      'data-pivot-panel': id, 'data-pivot-saved': p.pivotSaved ? JSON.stringify(p.pivotSaved) : null }));
}

// ---- the pivot kind ------------------------------------------------------------------------------------------------------------
/** pivot(d): rows by one field, or nested by several: the plain table, which the Pivot grid (tree-rows.js) redraws from data-pv-tree. */
export function pivot(R, d) {
  const t = pivotTable(R, d);
  return d.levels ? h('div', { class: 'pv-tree-host', 'data-pv-tree': JSON.stringify(d) }, t) : t;
}

function pivotTable(R, d) {
  const by = d.by || '', cols = d.columns || [], lo = d.min, hi = d.max;
  return h('div', { class: 'tbl-wrap' }, h('table', { class: 'tbl pivot' + (d.heat ? ' heat' : '') },
    h('thead', null, h('tr', null, h('th', { scope: 'col' }, by.slice(0, 1).toUpperCase() + by.slice(1)),
      cols.map((c) => h('th', { scope: 'col', class: 'num' }, str(c))), d.totals ? h('th', { scope: 'col', class: 'num' }, 'Total') : null)),
    h('tbody', null, (d.rows || []).map((r) => h('tr', null,
      h('th', { scope: 'row', class: r.path ? 'pv-i' + r.path.length : null }, r.group ? h('b', null, str(r.label)) : str(r.label)),
      (r.cells || []).map((c, i) => {
        const v = (r.values || [])[i];
        return h('td', { class: 'mono num' + toneClass(c) + (d.heat && typeof v === 'number' ? ' heat-' + heat(v, lo, hi) : '') }, str(c.text));
      }),
      r.total ? h('td', { class: 'mono num pv-total' + toneClass(r.total) }, str(r.total.text)) : null))),
    (d.totals || d.more) ? h('tfoot', null,
      d.totals ? h('tr', { class: 'total' }, h('th', { scope: 'row' }, 'Total'), d.totals.map((c) => h('td', { class: 'mono num' + toneClass(c) }, str(c.text)))) : null,
      d.more ? h('tr', { class: 'more' }, h('td', { colspan: String(cols.length + 2) }, d.more + ' more rows')) : null) : null));
}

export { capitalize };
