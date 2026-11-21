/*
 * Drishti Elements
 *
 * Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>.
 * All rights reserved.
 *
 * PROPRIETARY.
 *
 * This file is the proprietary property of Ashutosh Sinha.
 * Copying, use, modification or distribution of this file, via any medium,
 * is not permitted except with the express prior written permission of the
 * copyright holder.
 *
 * See the LICENSE file in the root of this repository.
 */
/* The table kinds: table (a document table of cells the producer formatted: sort by a column, a filter box, rows a page at a time, rows
   with children opened and closed, a level's own column heads, subtotals and a total), ladder (the same, the current row marked) and
   pivot (a pivot the producer aggregated: nested rows by their path, the column and grand totals, heat over the values). Sorting reads
   a numeric column's text as a number; nothing is computed or formatted beyond that order. A row with a pick raises the handle's pick
   event (a click or Enter). A patch that replaces the panel keeps the sort, the filter and what was opened. */
import { h } from '../../drishti-core/src/drishti_index.js';
import { defineKind } from './drishti_views-kinds.js';
import { drawCell, say } from './drishti_views-cells.js';
import { marksOf, hasEffects, clickable } from './drishti_views-state.js';

/** Rows drawn at first, and added by each "Show more". */
export const PAGE_ROWS = 100;

const list = (x) => (Array.isArray(x) ? x : []);
const textOf = (c) => (c && typeof c.text === 'string' ? c.text : '');

/**
 * The number a formatted text shows ("1,234.5", "-4.2%", "(1,200)", "12.3m"), or NaN: the order of a numeric column.
 * @param {string} t
 * @returns {number}
 */
export function numberOfText(t) {
  let s = String(t || '').trim();
  if (!s) { return NaN; }
  const neg = /^\(.*\)$/.test(s) || /^[−-]/.test(s);
  s = s.replace(/[(),\s−+-]/g, '');
  const m = /^[^\d.]*([\d.]+)\s*([kmbt%]?)/i.exec(s);
  if (!m) { return NaN; }
  const scale = { k: 1e3, m: 1e6, b: 1e9, t: 1e12 }[m[2].toLowerCase()] || 1;
  const n = parseFloat(m[1]) * scale;
  return neg ? -n : n;
}

/** One table, drawn again from its state (sort, filter, open rows, rows shown) when the data or the state changes. */
class TableView {
  constructor(body, panel, ctx, o) {
    this.ctx = ctx;
    this.o = o;
    this.sort = null;
    this.query = '';
    this.open = new Map();
    this.shown = PAGE_ROWS;
    const doc = ctx.doc;
    this.box = h(doc, 'div', { cls: 'dv-table' + (o.ladder ? ' dv-ladder' : '') });
    this.filter = h(doc, 'input', { cls: 'dv-filter', attrs: { type: 'search', 'aria-label': say(ctx.messages, 'filterRows'), placeholder: say(ctx.messages, 'filterRows') },
      on: { input: () => { this.query = this.filter.value.trim().toLowerCase(); this.shown = PAGE_ROWS; this.drawRows(); } } });
    this.count = h(doc, 'span', { cls: 'dv-count', attrs: { 'aria-live': 'polite' } });
    this.bar = h(doc, 'div', { cls: 'dv-table-bar' }, [this.filter, this.count]);
    this.table = h(doc, 'table', { cls: 'dv-tbl' });
    this.thead = h(doc, 'thead');
    this.tbody = h(doc, 'tbody');
    this.tfoot = h(doc, 'tfoot');
    this.table.append(this.thead, this.tbody, this.tfoot);
    this.scroll = h(doc, 'div', { cls: 'dv-tbl-scroll', attrs: { tabindex: '0', role: 'region', 'aria-label': panel.title || panel.id } }, [this.table]);
    this.more = h(doc, 'button', { cls: 'dv-more-btn', attrs: { type: 'button' }, on: { click: () => { this.shown += PAGE_ROWS; this.drawRows(); } } });
    this.note = h(doc, 'p', { cls: 'dv-more' });
    this.box.append(this.bar, this.scroll, this.more, this.note);
    this.tbody.addEventListener('click', (e) => this.onPick(e));
    this.tbody.addEventListener('keydown', (e) => { if (e.key === 'Enter') { this.onPick(e); } });
    body.appendChild(this.box);
    this.update(panel);
  }

  update(panel) {
    this.panel = panel;
    const d = panel.data || {};
    this.d = d;
    this.marks = new Map();                                      // a row that carries effects (contract 1.2) to its place among the marks
    marksOf(panel).forEach((m, i) => { if (hasEffects(m)) { this.marks.set(m, i); } });
    this.columns = list(d.columns).length ? list(d.columns).map(String) : (list(d.levels)[0] && list(list(d.levels)[0].columns)) || [];
    this.numeric = list(d.numeric);
    this.expandDepth = Number.isInteger(d.expand) ? d.expand : 0;
    this.bar.hidden = d.search !== true;
    this.drawHead();
    this.drawRows();
    while (this.tfoot.firstChild) { this.tfoot.removeChild(this.tfoot.firstChild); }
    if (d.total && typeof d.total === 'object') { this.tfoot.appendChild(this.row(d.total, 0, 'dv-total', 'th')); }
    this.note.textContent = typeof d.more === 'string' ? d.more : '';
    this.note.hidden = !this.note.textContent;
  }

  drawHead() {
    const doc = this.ctx.doc, tr = h(doc, 'tr');
    this.columns.forEach((c, i) => {
      const dir = this.sort && this.sort.col === i ? this.sort.dir : null;
      const btn = h(doc, 'button', { cls: 'dv-sort', text: c, attrs: { type: 'button', title: say(this.ctx.messages, 'sortBy', { label: c }) },
        on: { click: () => { this.sort = { col: i, dir: dir === 'ascending' ? 'descending' : 'ascending' }; this.drawHead(); this.drawRows(); } } });
      tr.appendChild(h(doc, 'th', { cls: this.numeric[i] ? 'dv-num' : null, attrs: { scope: 'col', 'aria-sort': dir || null } }, [btn]));
    });
    while (this.thead.firstChild) { this.thead.removeChild(this.thead.firstChild); }
    this.thead.appendChild(tr);
  }

  /** The rows in order: sorted among their siblings, filtered (a parent stays when a child matches, and opens). */
  ordered(rows) {
    const s = this.sort;
    if (!s) { return rows; }
    const num = !!this.numeric[s.col], sign = s.dir === 'descending' ? -1 : 1;
    const key = (r) => textOf(list(r && r.cells)[s.col]);
    return rows.slice().sort((a, b) => {
      if (num) {
        const x = numberOfText(key(a)), y = numberOfText(key(b));
        if (isNaN(x) || isNaN(y)) { return isNaN(x) - isNaN(y); }
        return (x - y) * sign;
      }
      return key(a).localeCompare(key(b), undefined, { numeric: true }) * sign;
    });
  }

  matches(r) {
    if (!this.query) { return true; }
    if (list(r.cells).some((c) => textOf(c).toLowerCase().indexOf(this.query) >= 0)) { return true; }
    return list(r.children).some((k) => this.matches(k));
  }

  drawRows() {
    const doc = this.ctx.doc, out = [];
    let all = 0, count = 0;
    const walk = (rows, depth, prefix) => {
      this.ordered(rows).forEach((r, i) => {
        if (!r || typeof r !== 'object' || !this.matches(r)) { return; }
        if (depth === 0) { all++; if (all > this.shown) { return; } }
        count++;
        const id = typeof r.path === 'string' && r.path ? r.path : prefix + i;
        const kids = list(r.children);
        const open = kids.length && (this.query ? true : (this.open.has(id) ? this.open.get(id) : depth < this.expandDepth));
        out.push(this.row(r, depth, null, 'td', kids.length ? { id: id, open: open } : null));
        if (open) {
          const lv = list(this.d.levels)[depth + 1];
          if (lv && list(lv.columns).length) { out.push(this.subHead(lv, depth + 1)); }
          walk(kids, depth + 1, id + '/');
          if (r.kidsTotal && typeof r.kidsTotal === 'object') { out.push(this.row(r.kidsTotal, depth + 1, 'dv-subtotal', 'td')); }
        }
      });
    };
    walk(list(this.d.rows), 0, '');
    while (this.tbody.firstChild) { this.tbody.removeChild(this.tbody.firstChild); }
    out.forEach((tr) => this.tbody.appendChild(tr));
    if (!count) { this.tbody.appendChild(h(doc, 'tr', { cls: 'dv-empty-row' }, [h(doc, 'td', { text: this.d.emptyLabel || say(this.ctx.messages, 'noData'), attrs: { colspan: String(Math.max(1, this.columns.length)) } })])); }
    const left = all - Math.min(all, this.shown);
    this.more.hidden = left <= 0;
    this.more.textContent = say(this.ctx.messages, 'showMore', { count: Math.min(left, PAGE_ROWS).toLocaleString('en-US') });
    this.count.textContent = this.query ? say(this.ctx.messages, 'rowsMatch', { count: all.toLocaleString('en-US') }) : '';
  }

  subHead(level, depth) {
    const doc = this.ctx.doc;
    return h(doc, 'tr', { cls: 'dv-sub-h', style: { '--dv-depth': String(depth) } }, list(level.columns).map((c, i) => h(doc, 'th', { text: String(c), cls: list(level.numeric)[i] ? 'dv-num' : null, attrs: { scope: 'col' } })));
  }

  row(r, depth, cls, tag, tree) {
    const doc = this.ctx.doc, cells = list(r.cells);
    const picks = typeof r.pick === 'string' && r.pick, mark = this.marks.get(r);
    const tr = h(doc, 'tr', { cls: [cls, r.highlight ? 'dv-hl' : '', picks || mark !== undefined ? 'dv-pick' : ''].filter(Boolean).join(' ') || null,
      attrs: { 'aria-current': r.highlight && this.o.ladder ? 'true' : null, tabindex: picks || mark !== undefined ? '0' : null, title: typeof r.crumb === 'string' ? r.crumb : null },
      data: Object.assign({ depth: String(depth) }, picks ? { pick: r.pick } : {}, typeof r.ev === 'string' && r.ev ? { ev: r.ev } : {}, mark !== undefined ? { mark: String(mark) } : {}),
      style: { '--dv-depth': String(depth) } });
    const n = Math.max(cells.length, this.columns.length);
    for (let i = 0; i < n; i++) {
      const td = h(doc, i === 0 && tag === 'th' ? 'th' : 'td', { cls: this.numeric[i] ? 'dv-num' : null, attrs: i === 0 && tag === 'th' ? { scope: 'row' } : null });
      if (i === 0 && tree) {
        const label = textOf(cells[0]);
        td.appendChild(h(doc, 'button', { cls: 'dv-twisty', text: tree.open ? '▾' : '▸',
          attrs: { type: 'button', 'aria-expanded': tree.open ? 'true' : 'false', 'aria-label': say(this.ctx.messages, tree.open ? 'collapseRow' : 'expandRow', { label: label }) },
          on: { click: (e) => { e.stopPropagation(); this.open.set(tree.id, !tree.open); this.drawRows(); } } }));
      }
      td.appendChild(drawCell(this.ctx, cells[i], 'span'));
      tr.appendChild(td);
    }
    return tr;
  }

  onPick(e) {
    const tr = e.target && e.target.closest ? e.target.closest('tr[data-pick], tr[data-mark]') : null;
    if (!tr || (e.target.closest('button, a') && e.type === 'click')) { return; }
    if (tr.hasAttribute('data-pick')) { this.ctx.emit('pick', { panel: this.panel.id, pick: tr.getAttribute('data-pick'), ev: tr.getAttribute('data-ev') || null }); }
    if (tr.hasAttribute('data-mark')) { this.ctx.fire(String(this.panel.id), 'row-click', Number(tr.getAttribute('data-mark'))); }
  }

  dispose() { this.box.remove(); }
}

const tableKind = (ladder) => ({ paint: (body, panel, ctx) => new TableView(body, panel, ctx, { ladder: ladder }) });

/* ---- pivot: rows nested by their path, totals, heat ---- */
class PivotView {
  constructor(body, panel, ctx) {
    this.ctx = ctx;
    this.open = new Map();
    this.box = h(ctx.doc, 'div', { cls: 'dv-table dv-pivot' });
    body.appendChild(this.box);
    this.update(panel);
  }

  update(panel) {
    this.panel = panel;
    this.d = panel.data || {};
    this.draw();
  }

  draw() {
    const ctx = this.ctx, doc = ctx.doc, d = this.d, m = ctx.messages;
    const cols = list(d.columns).map(String), rows = list(d.rows);
    const withTotal = rows.some((r) => r && r.total);
    const vals = [];
    rows.forEach((r) => list(r && r.values).forEach((v) => { if (typeof v === 'number' && isFinite(v)) { vals.push(v); } }));
    const lo = typeof d.min === 'number' ? d.min : Math.min.apply(null, vals.length ? vals : [0]);
    const hi = typeof d.max === 'number' ? d.max : Math.max.apply(null, vals.length ? vals : [0]);
    const expand = Number.isInteger(d.expand) ? d.expand : Infinity;
    const thead = h(doc, 'thead');
    const first = list(d.levels).length ? list(d.levels).join(' › ') : (d.by || '');
    if (typeof d.across === 'string' && d.across) {
      thead.appendChild(h(doc, 'tr', { cls: 'dv-across' }, [h(doc, 'td'), h(doc, 'th', { text: d.across, attrs: { scope: 'colgroup', colspan: String(cols.length) } }), withTotal ? h(doc, 'td') : null]));
    }
    thead.appendChild(h(doc, 'tr', {}, [h(doc, 'th', { text: first, attrs: { scope: 'col' } })].concat(cols.map((c) => h(doc, 'th', { cls: 'dv-num', text: c, attrs: { scope: 'col' } })),
      withTotal ? [h(doc, 'th', { cls: 'dv-num', text: say(m, 'total'), attrs: { scope: 'col' } })] : [])));
    const tbody = h(doc, 'tbody');
    const closed = [];
    rows.forEach((r, i) => {
      if (!r || typeof r !== 'object') { return; }
      const path = list(r.path).map(String), depth = Math.max(0, path.length - 1), key = path.length ? path.join('\u0001') : String(i);
      if (closed.some((p) => path.length > p.length && p.every((s, k) => s === path[k]))) { return; }
      const tr = h(doc, 'tr', { cls: r.group ? 'dv-group' : null, data: { depth: String(depth) }, style: { '--dv-depth': String(depth) } });
      const th = h(doc, 'th', { attrs: { scope: 'row' } });
      if (r.group) {
        const open = this.open.has(key) ? this.open.get(key) : depth < expand;
        if (!open) { closed.push(path); }
        th.appendChild(h(doc, 'button', { cls: 'dv-twisty', text: open ? '▾' : '▸', attrs: { type: 'button', 'aria-expanded': open ? 'true' : 'false',
          'aria-label': say(m, open ? 'collapseRow' : 'expandRow', { label: r.label || '' }) }, on: { click: () => { this.open.set(key, !open); this.draw(); } } }));
      }
      th.appendChild(h(doc, 'span', { text: typeof r.label === 'string' ? r.label : '' }));
      tr.appendChild(th);
      const cells = list(r.cells), values = list(r.values);
      for (let c = 0; c < cols.length; c++) {
        const td = h(doc, 'td', { cls: 'dv-num' }, [drawCell(ctx, cells[c], 'span')]);
        this.markCell(td, cells[c], i * cols.length + c);                      // contract 1.2: a cell with a figure is a point-click mark
        const v = values[c];
        if (d.heat === true && typeof v === 'number' && isFinite(v) && hi > lo) {
          td.classList.add('dv-heat');
          td.style.setProperty('--dv-heat', String(Math.round(((v - lo) / (hi - lo)) * 100) / 100));
        }
        tr.appendChild(td);
      }
      if (withTotal) { tr.appendChild(h(doc, 'td', { cls: 'dv-num dv-total' }, [drawCell(ctx, r.total, 'span')])); }
      tbody.appendChild(tr);
    });
    const tfoot = h(doc, 'tfoot');
    if (list(d.totals).length) {
      tfoot.appendChild(h(doc, 'tr', { cls: 'dv-total' }, [h(doc, 'th', { text: say(m, 'total'), attrs: { scope: 'row' } })].concat(list(d.totals).map((c) => h(doc, 'td', { cls: 'dv-num' }, [drawCell(ctx, c, 'span')])))));
    }
    const table = h(doc, 'table', { cls: 'dv-tbl' }, [thead, tbody, tfoot]);
    while (this.box.firstChild) { this.box.removeChild(this.box.firstChild); }
    this.box.appendChild(h(doc, 'div', { cls: 'dv-tbl-scroll', attrs: { tabindex: '0', role: 'region', 'aria-label': this.panel.title || this.panel.id } }, [table]));
    if (Number.isInteger(d.more) && d.more > 0) { this.box.appendChild(h(doc, 'p', { cls: 'dv-more', text: say(m, 'moreRows', { count: d.more.toLocaleString('en-US') }) })); }
  }

  /** @private a cell that is a mark (contract 1.2): its effects run on a click (state), its ev handed to the host (the panel's event) */
  markCell(td, cell, ix) {
    const ev = cell && typeof cell.ev === 'string' ? cell.ev : null;
    const acts = clickable(this.ctx, td, this.panel, 'point-click', ix, cell);
    if (ev === null) { return; }
    if (!acts) {
      td.classList.add('dv-pick');
      td.setAttribute('tabindex', '0');
      td.setAttribute('role', 'button');
    }
    const send = () => this.ctx.emit('point', { panel: this.panel.id, ev: ev });
    td.addEventListener('click', send);
    td.addEventListener('keydown', (e) => { if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); send(); } });
  }

  dispose() { this.box.remove(); }
}

defineKind('table', tableKind(false));
defineKind('ladder', tableKind(true));
defineKind('pivot', { paint: (body, panel, ctx) => new PivotView(body, panel, ctx) });
