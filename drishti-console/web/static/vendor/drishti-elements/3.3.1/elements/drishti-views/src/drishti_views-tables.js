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
   event (a click or Enter). A patch that replaces the panel keeps the sort, the filter and what was opened.
   With the host's tableTools (paint options): filters puts a funnel on each column heading (the column's values with their counts, a
   search, Select all, and on a number column a condition such as "> 1m" or "<= 250k"; the filters combine across columns and with the
   filter box, and a parent row stays when a child passes); chart opens the rows as shown (filtered, sorted) as a chart in a dialog beside
   the table (drishti-chart's TableChart). */
import { h, formatCount, passes, textSortKey, compareTextKeys } from '../../drishti-core/src/drishti_index.js';
import { defineKind } from './drishti_views-kinds.js';
import { drawCell, say } from './drishti_views-cells.js';
import { marksOf, hasEffects, clickable } from './drishti_views-state.js';

/** Rows drawn at first, and added by each "Show more". */
export const PAGE_ROWS = 100;

const list = (x) => (Array.isArray(x) ? x : []);
/** The tables' one button maker (type button; the caller's class, words, attributes and listeners). */
const tbtn = (doc, o) => h(doc, 'button', Object.assign({}, o, { attrs: Object.assign({ type: 'button' }, o.attrs || {}) }));
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

const BLANK = '\u0000';
const CONDITION = /^\s*(>=|<=|≥|≤|!=|≠|<>|>|<|=)?\s*(.+?)\s*$/;
const OPS = { '>': 'gt', '<': 'lt', '>=': 'gte', '≥': 'gte', '<=': 'lte', '≤': 'lte', '=': 'equals', '!=': 'notEquals', '≠': 'notEquals', '<>': 'notEquals' };

/**
 * A number column's condition as drishti-core's filter model ("> 1m", "<= 250k", "= 0", "1.5m"), or null when it is not one.
 * @param {string} t
 * @returns {?{type: string, op: string, value: number}}
 */
export function parseCondition(t) {
  const m = CONDITION.exec(String(t || ''));
  if (!m) { return null; }
  const n = numberOfText(m[2]);
  return isNaN(n) ? null : { type: 'number', op: OPS[m[1] || '='], value: n };
}

/** A column's filter (tableTools.filters): the values kept (a set of texts, BLANK for an empty cell), a number condition, or both. */
function cellPasses(text, numeric, f) {
  if (!f) { return true; }
  const t = String(text || '').trim();
  if (f.keep && !f.keep.has(t || BLANK)) { return false; }
  if (f.cond) { const n = numeric ? numberOfText(t) : NaN; return !isNaN(n) && passes(n, f.cond); }
  return true;
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
    this.cf = new Map();                                         // a column's index to its filter (tableTools.filters), kept through patches
    const doc = ctx.doc;
    this.box = h(doc, 'div', { cls: 'dv-table' + (o.ladder ? ' dv-ladder' : '') });
    this.filter = h(doc, 'input', { cls: 'dv-filter', attrs: { type: 'search', 'aria-label': say(ctx.messages, 'filterRows'), placeholder: say(ctx.messages, 'filterRows') },
      on: { input: () => { this.query = this.filter.value.trim().toLowerCase(); this.shown = PAGE_ROWS; this.drawRows(); } } });
    this.count = h(doc, 'span', { cls: 'dv-count', attrs: { 'aria-live': 'polite' } });
    this.clearBtn = tbtn(doc, { cls: 'dv-tool-btn dv-cf-clear', text: say(ctx.messages, 'clearFilters'), attrs: { type: 'button' }, on: { click: () => { this.cf.clear(); this.shown = PAGE_ROWS; this.drawHead(); this.drawRows(); } } });
    this.chartBtn = tbtn(doc, { cls: 'dv-tool-btn dv-chart-btn', text: say(ctx.messages, 'chartTable'), attrs: { type: 'button', 'data-chart-table': '', title: say(ctx.messages, 'chartTableTitle') },
      on: { click: () => this.chart() } });
    this.bar = h(doc, 'div', { cls: 'dv-table-bar' }, [this.filter, this.count, h(doc, 'span', { cls: 'dv-sp' }), this.clearBtn, this.chartBtn]);
    this.table = h(doc, 'table', { cls: 'dv-tbl' });
    this.thead = h(doc, 'thead');
    this.tbody = h(doc, 'tbody');
    this.tfoot = h(doc, 'tfoot');
    this.table.append(this.thead, this.tbody, this.tfoot);
    this.scroll = h(doc, 'div', { cls: 'dv-tbl-scroll', attrs: { tabindex: '0', role: 'region', 'aria-label': panel.title || panel.id } }, [this.table]);
    this.more = tbtn(doc, { cls: 'dv-more-btn', attrs: { type: 'button' }, on: { click: () => { this.shown += PAGE_ROWS; this.drawRows(); } } });
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
    this.filter.hidden = d.search !== true;
    this.chartBtn.hidden = !(this.ctx.tools || {}).chart;
    this.cf.forEach((f, i) => { if (i >= this.columns.length) { this.cf.delete(i); } });
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
      const btn = tbtn(doc, { cls: 'dv-sort', text: c, attrs: { type: 'button', title: say(this.ctx.messages, 'sortBy', { label: c }) },
        on: { click: () => { this.sort = { col: i, dir: dir === 'ascending' ? 'descending' : 'ascending' }; this.drawHead(); this.drawRows(); } } });
      const funnel = (this.ctx.tools || {}).filters ? tbtn(doc, { cls: 'dv-funnel' + (this.cf.has(i) ? ' dv-on' : ''), text: '⏷',
        attrs: { type: 'button', 'data-funnel': String(i), 'aria-haspopup': 'dialog', 'aria-pressed': this.cf.has(i) ? 'true' : 'false', 'aria-label': say(this.ctx.messages, 'filterColumn', { label: c }), title: say(this.ctx.messages, 'filterColumn', { label: c }) },
        on: { click: (e) => this.menu(i, e.currentTarget) } }) : null;
      tr.appendChild(h(doc, 'th', { cls: this.numeric[i] ? 'dv-num' : null, attrs: { scope: 'col', 'aria-sort': dir || null } }, [btn, funnel]));
    });
    this.clearBtn.hidden = !this.cf.size;
    this.bar.hidden = this.filter.hidden && this.chartBtn.hidden && this.clearBtn.hidden;
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
    if (!this.query && !this.cf.size) { return true; }
    const cells = list(r.cells);
    const own = (!this.query || cells.some((c) => textOf(c).toLowerCase().indexOf(this.query) >= 0))
      && Array.from(this.cf).every(([i, f]) => cellPasses(textOf(cells[i]), !!this.numeric[i], f));
    return own || list(r.children).some((k) => this.matches(k));
  }

  /** @private every row's cells, children too (the values a funnel lists) */
  allRows() {
    const out = [], walk = (rows) => rows.forEach((r) => { if (r && typeof r === 'object') { out.push(r); walk(list(r.children)); } });
    walk(list(this.d.rows));
    return out;
  }

  /**
   * A column's funnel menu: its values with counts (a search, Select all), a condition on a number column, OK, Clear and Cancel; Escape
   * closes it and the focus goes back to the funnel.
   * @private
   */
  menu(col, opener) {
    const ctx = this.ctx, doc = ctx.doc, m = ctx.messages, label = this.columns[col] || '';
    if (this.popup) { this.popup.close(); }
    const counts = new Map();
    this.allRows().forEach((r) => { const t = textOf(list(r.cells)[col]).trim() || BLANK; counts.set(t, (counts.get(t) || 0) + 1); });
    const values = Array.from(counts.keys()).sort((a, b) => (a === BLANK) - (b === BLANK) || compareTextKeys(textSortKey(a), textSortKey(b)));
    const cur = this.cf.get(col), kept = new Set(cur && cur.keep ? cur.keep : values);
    const search = h(doc, 'input', { cls: 'dv-filter dv-cf-search', attrs: { type: 'search', placeholder: say(m, 'searchValues'), 'aria-label': say(m, 'searchValues') } });
    const all = h(doc, 'input', { attrs: { type: 'checkbox', 'data-cf-all': '' } });
    const boxes = values.map((v) => {
      const input = h(doc, 'input', { attrs: { type: 'checkbox', 'data-cf-value': v === BLANK ? '' : v } });
      input.checked = kept.has(v);
      return { v: v, input: input, row: h(doc, 'label', { cls: 'dv-cf-item' }, [input, h(doc, 'span', { text: v === BLANK ? say(m, 'blankValue') : v }), h(doc, 'span', { cls: 'dv-count', text: formatCount(counts.get(v)) })]) };
    });
    const sync = () => { const shown = boxes.filter((b) => !b.row.hidden); all.checked = shown.every((b) => b.input.checked); all.indeterminate = !all.checked && shown.some((b) => b.input.checked); };
    all.addEventListener('change', () => { boxes.forEach((b) => { if (!b.row.hidden) { b.input.checked = all.checked; } }); sync(); });
    boxes.forEach((b) => b.input.addEventListener('change', sync));
    search.addEventListener('input', () => { const q = search.value.trim().toLowerCase(); boxes.forEach((b) => { b.row.hidden = !!q && (b.v === BLANK ? say(m, 'blankValue') : b.v).toLowerCase().indexOf(q) < 0; }); sync(); });
    sync();
    const numeric = !!this.numeric[col];
    const cond = numeric ? h(doc, 'input', { cls: 'dv-filter dv-cf-cond', attrs: { type: 'text', placeholder: say(m, 'conditionHint'), 'aria-label': say(m, 'conditionLabel', { label: label }) } }) : null;
    if (cond && cur && cur.condText) { cond.value = cur.condText; }
    const note = h(doc, 'p', { cls: 'dv-cf-note', attrs: { role: 'alert', hidden: true } });
    const close = (focus) => { pop.remove(); doc.removeEventListener('mousedown', outside, true); this.popup = null; if (focus && opener.isConnected) { opener.focus(); } };
    const apply = () => {
      const keep = new Set(boxes.filter((b) => b.input.checked).map((b) => b.v)), f = {};
      if (keep.size < values.length) { f.keep = keep; }
      if (cond && cond.value.trim()) {
        const c = parseCondition(cond.value);
        if (!c) { note.textContent = say(m, 'conditionBad'); note.hidden = false; cond.focus(); return; }
        f.cond = c; f.condText = cond.value.trim();
      }
      if (f.keep || f.cond) { this.cf.set(col, f); } else { this.cf.delete(col); }
      this.shown = PAGE_ROWS;
      close(false);
      this.drawHead();
      this.drawRows();
      const again = this.thead.querySelector('[data-funnel="' + col + '"]');     // the heading is drawn again: its new funnel takes the focus
      if (again) { again.focus(); }
      ctx.emit('filter', { panel: this.panel.id, column: col, label: label, active: this.cf.has(col) });
    };
    const btn = (text, cls, fn) => tbtn(doc, { cls: 'dv-tool-btn ' + cls, text: text, attrs: { type: 'button' }, on: { click: fn } });
    const pop = h(doc, 'div', { cls: 'dv-cf', attrs: { role: 'dialog', 'aria-label': say(m, 'filterColumn', { label: label }), 'data-cf-col': String(col) },
      on: { keydown: (e) => { if (e.key === 'Escape') { e.preventDefault(); e.stopPropagation(); close(true); } else if (e.key === 'Enter' && e.target && e.target.tagName === 'INPUT' && e.target.type !== 'checkbox') { e.preventDefault(); apply(); } } } }, [
      search,
      h(doc, 'label', { cls: 'dv-cf-item dv-cf-all' }, [all, h(doc, 'span', { text: say(m, 'selectAll') })]),
      h(doc, 'div', { cls: 'dv-cf-list' }, boxes.map((b) => b.row)),
      cond, note,
      h(doc, 'div', { cls: 'dv-cf-foot' }, [btn(say(m, 'ok'), 'dv-cf-ok', apply), btn(say(m, 'clear'), 'dv-cf-reset', () => { boxes.forEach((b) => { b.input.checked = true; b.row.hidden = false; }); search.value = ''; if (cond) { cond.value = ''; } apply(); }),
        btn(say(m, 'cancel'), 'dv-cf-cancel', () => close(true))])]);
    const outside = (e) => { if (!e.composedPath().includes(pop) && !e.composedPath().includes(opener)) { close(false); } };
    doc.addEventListener('mousedown', outside, true);
    const th = opener.closest('th');
    pop.style.setProperty('--dv-cf-left', Math.max(0, (th ? th.offsetLeft - this.scroll.scrollLeft : 0)) + 'px');
    this.box.appendChild(pop);
    this.popup = { close: () => close(false) };
    search.focus();
  }

  /** @private the rows as shown (filtered and sorted, every page), opened as a chart (tableTools.chart) */
  chart() {
    const rows = this.ordered(list(this.d.rows)).filter((r) => r && typeof r === 'object' && this.matches(r)).map((r) => list(r.cells).map(textOf));
    return this.ctx.chartTable({ title: this.panel.title || '', columns: this.columns.slice(), rows: rows }, this.chartBtn).catch(() => null);
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
    this.more.textContent = say(this.ctx.messages, 'showMore', { count: formatCount(Math.min(left, PAGE_ROWS)) });
    this.count.textContent = this.query ? say(this.ctx.messages, 'rowsMatch', { count: formatCount(all) }) : '';
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
        td.appendChild(tbtn(doc, { cls: 'dv-twisty', text: tree.open ? '▾' : '▸',
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

  dispose() { if (this.popup) { this.popup.close(); } this.box.remove(); }
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
        th.appendChild(tbtn(doc, { cls: 'dv-twisty', text: open ? '▾' : '▸', attrs: { type: 'button', 'aria-expanded': open ? 'true' : 'false',
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
    if (Number.isInteger(d.more) && d.more > 0) { this.box.appendChild(h(doc, 'p', { cls: 'dv-more', text: say(m, 'moreRows', { count: formatCount(d.more) }) })); }
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
