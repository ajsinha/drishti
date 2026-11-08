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
/* A server's table of rows nested under rows (a table drawn as table.tbl-tree, each row's depth in data-depth, its toggle's
   aria-expanded the server's open state) drawn by drishti-tree, in two modes of one panel: the tree grid (the default: drishti-grid's
   tree data, a column per heading, each cell the server's own content, its text, tone and links; nothing computed or summed here) and
   the org chart of the same rows (drishti-tree's drawing: boxes with the first two other columns). A tree that is one chain (one top row,
   every row with at most one row under it) opens as the org chart, any other as the grid; a switch changes it. A filter box keeps the
   rows that match and their ancestors (the grid's tree filter; the org chart marks the matches and opens their branches). The server's
   table stays in the page, hidden, for print and for pages without scripts; its footer (a total, "n more") is shown under the panel.
   The mode, the open rows and the filter are kept by key, so a live patch that draws the table again keeps them. Generic: any domain. */
import { h, visualTheme, announce, textSortKey } from '../../drishti-core/src/drishti_index.js';
import { Grid } from '../../drishti-grid/src/drishti_index.js';
import { HierarchyModel } from './drishti_tree-model.js';
import { drawOrg } from './drishti_tree-draw.js';
import { keyMove } from './drishti_tree-io.js';

/** The modes of a table tree: the tree grid (the default) and the org chart. */
export const PANEL_MODES = Object.freeze(['grid', 'org']);

const NAMES = { grid: 'Tree grid', org: 'Org chart' };
const SEP = '\u001f';

/**
 * One row of a server's tree table.
 * @typedef {Object} TableTreeRow
 * @property {string} id its place in the table's order (r0, r1 ...)
 * @property {?string} parent the id of the row it is nested under (null at the top)
 * @property {number} depth 0 at the top
 * @property {string} label the first cell's text
 * @property {string[]} texts each cell's text
 * @property {Array<?string>} tones each cell's tone class (t-...), or null
 * @property {Array<?Element>} cells the server's cells: their content (links, badges) is shown as it is
 * @property {boolean} kids whether rows are nested under it
 * @property {boolean} open whether the server showed it open (its expand)
 * @property {boolean} highlight the row the server marked (hl)
 */

/**
 * A server's tree table, read.
 * @typedef {Object} TableTree
 * @property {string[]} headers the column headings
 * @property {boolean[]} numeric which columns hold figures (right aligned, ordered as numbers)
 * @property {TableTreeRow[]} rows in the table's order, each after the row it is nested under
 * @property {string[]} foot the footer's rows as text (a total, "n more")
 */

/**
 * What a table tree keeps between two drawings of the same table (a live patch).
 * @typedef {Object} TreePanelState
 * @property {string} [mode] 'grid' or 'org'
 * @property {string} [q] the filter's words
 * @property {string[]} [open] the ids of the open rows
 */

const clean = (s) => String(s || '').replace(/[▸▾]/g, '').replace(/\s+/g, ' ').trim();

/**
 * Reads a server's tree table (table.tbl-tree: tr[data-depth] rows; the first cell's toggle, .tr-tog, says whether it is open).
 * @param {HTMLTableElement} table
 * @returns {TableTree}
 */
export function readTableTree(table) {
  const heads = table.tHead && table.tHead.rows.length ? Array.prototype.slice.call(table.tHead.rows[0].cells) : [];
  const body = table.tBodies[0], stack = [], rows = [];
  Array.prototype.slice.call(body ? body.rows : []).forEach((tr, i) => {
    const depth = Math.max(0, parseInt(tr.getAttribute('data-depth'), 10) || 0), tog = tr.querySelector('.tr-tog');
    stack.length = depth;
    const cells = Array.prototype.slice.call(tr.cells);
    rows.push({ id: 'r' + i, parent: depth > 0 && stack[depth - 1] ? stack[depth - 1] : null, depth: depth, label: cells.length ? clean(cells[0].textContent) : '',
      texts: cells.map((c) => clean(c.textContent)), tones: cells.map((c) => ((' ' + c.className + ' ').match(/ (t-[a-z0-9-]+) /) || [])[1] || null), cells: cells,
      kids: tr.hasAttribute('data-kids') || !!tog, open: !!tog && tog.getAttribute('aria-expanded') === 'true', highlight: tr.classList.contains('hl') });
    stack[depth] = 'r' + i;
  });
  const foot = table.tFoot ? Array.prototype.slice.call(table.tFoot.rows).map((r) => Array.prototype.slice.call(r.cells).map((c) => clean(c.textContent)).filter(Boolean).join(' · ')).filter(Boolean) : [];
  return { headers: heads.map((c) => clean(c.textContent)), numeric: heads.map((c) => c.classList.contains('num')), rows: rows, foot: foot };
}

/**
 * Whether a tree is one chain: one top row and every row with at most one row under it (an org chart reads it better than a table).
 * @param {TableTreeRow[]} rows
 * @returns {boolean}
 */
export function isChain(rows) {
  if (rows.length < 2 || rows.filter((r) => r.parent === null).length !== 1) { return false; }
  const under = new Map();
  rows.forEach((r) => { if (r.parent !== null) { under.set(r.parent, (under.get(r.parent) || 0) + 1); } });
  return Array.from(under.values()).every((n) => n === 1);
}

/** A number from a figure's text (1,234.5; −3; 12.5%; 10k), for ordering only: what is shown is always the text. */
function figure(text) { const k = textSortKey(text); return k && typeof k.n === 'number' && !isNaN(k.n) ? k.n : null; }

/**
 * A table tree: the tree grid or the org chart of a server's tree table, with the mode switch and the filter box.
 */
export class TreePanel {
  /**
   * @param {HTMLElement} host the element the panel fills
   * @param {{tree: TableTree, mode: string, label: string, search: boolean, density: string, fitRows: number, state: TreePanelState,
   *   onState: function(TreePanelState): void}} options mode: grid, org, or auto (the default: org for a chain); search default true
   */
  constructor(host, options) {
    const o = this.o = Object.assign({ search: true, density: 'compact', fitRows: 30 }, options);
    if (!o.density) { o.density = 'compact'; }                // a host's density left undefined (treeTables passes its own)
    this.host = host;
    this.doc = host.ownerDocument;
    this.tree = o.tree;
    const st = o.state || {};
    this.mode = PANEL_MODES.indexOf(st.mode) >= 0 ? st.mode : PANEL_MODES.indexOf(o.mode) >= 0 ? o.mode : (isChain(this.tree.rows) ? 'org' : 'grid');
    this.q = st.q || '';
    this.open = new Set(st.open || this.tree.rows.filter((r) => r.open).map((r) => r.id));
    this.byId = new Map(this.tree.rows.map((r) => [r.id, r]));
    this.paths = new Map();                                // each row's path of keys: its label, made unique among its siblings (invisibly)
    this.idByPath = new Map();
    const seen = new Map();
    this.tree.rows.forEach((r) => {
      const k = (r.parent || '') + SEP + r.label, n = seen.get(k) || 0, path = (r.parent ? this.paths.get(r.parent) : []).concat([r.label + '\u200b'.repeat(n)]);
      seen.set(k, n + 1);
      this.paths.set(r.id, path);
      this.idByPath.set(path.join(SEP), r.id);
    });
    this.selected = null;
    this.build();
    this.draw();
  }

  /** @private the bar (filter, count, the two modes), the view, the footer */
  build() {
    const doc = this.doc, o = this.o;
    this.host.classList.add('dt', 'dt-tp');
    this.host.setAttribute('data-density', o.density);
    this.host.textContent = '';
    this.box = o.search ? h(doc, 'input', { cls: 'dt-input dt-search dt-tp-q', attrs: { type: 'search', placeholder: 'Filter rows', 'aria-label': 'Filter rows' } }) : null;
    if (this.box) { this.box.value = this.q; this.box.addEventListener('input', () => this.filter(this.box.value)); }
    this.count = h(doc, 'span', { cls: 'dt-lbl', attrs: { 'aria-live': 'polite' } });
    this.seg = h(doc, 'span', { cls: 'dt-seg', attrs: { role: 'group', 'aria-label': 'Show as' } }, PANEL_MODES.map((m) => h(doc, 'button', { text: NAMES[m], attrs: { type: 'button', 'aria-pressed': 'false' }, data: { mode: m } })));
    this.seg.addEventListener('click', (e) => { const b = e.target.closest && e.target.closest('[data-mode]'); if (b) { this.setMode(b.getAttribute('data-mode')); } });
    this.view = h(doc, 'div', { cls: 'dt-tp-view' });
    this.view.addEventListener('click', (e) => this.clicked(e));
    this.view.addEventListener('dblclick', (e) => { const n = e.target.closest && e.target.closest('[data-id]'); if (n && this.mode === 'org') { this.follow(n.getAttribute('data-id')); } });
    this.view.addEventListener('keydown', (e) => this.key(e));
    const foot = this.tree.foot.length ? h(doc, 'div', { cls: 'dt-tp-foot' }, this.tree.foot.map((t) => h(doc, 'div', { text: t }))) : null;
    this.host.appendChild(h(doc, 'div', { cls: 'dt-tp-bar' }, [this.box, this.count, h(doc, 'span', { cls: 'dt-sp' }), this.seg]));
    this.host.appendChild(this.view);
    if (foot) { this.host.appendChild(foot); }
  }

  /** @private draws the mode */
  draw() {
    this.host.setAttribute('data-mode', this.mode);
    this.seg.querySelectorAll('[data-mode]').forEach((b) => b.setAttribute('aria-pressed', String(b.getAttribute('data-mode') === this.mode)));
    if (this.mode === 'grid') { this.drawGrid(); } else { this.drawOrgChart(); }
  }

  /** @private the tree grid: drishti-grid's tree data over the rows, every cell the server's */
  drawGrid() {
    if (this.grid) { return; }
    const doc = this.doc, t = this.tree;
    this.view.textContent = '';
    const box = h(doc, 'div', { cls: 'dt-tp-grid' });
    this.view.appendChild(box);
    const content = (row, i) => {
      const src = row.cells[i];
      if (!src || !src.querySelector('a, span:not(.tr-ind):not(.tr-sp), b, i, button:not(.tr-tog)')) { return null; }
      const out = h(doc, 'span', { cls: 'dg-text' });
      Array.prototype.forEach.call(src.childNodes, (n) => { if (!(n.classList && n.classList.contains('tr-ind'))) { out.appendChild(n.cloneNode(true)); } });
      return out;
    };
    const cols = t.headers.map((head, i) => ({ id: 'c' + i, header: head || ' ', type: t.numeric[i] && i > 0 ? 'number' : 'text', width: i === 0 ? 260 : 130, additive: false,
      valueGetter: (r) => (t.numeric[i] && i > 0 ? figure(r.texts[i]) : r.texts[i]), format: (v, r) => (r && r.texts ? r.texts[i] || '' : (v == null ? '' : String(v))),
      cellClass: (v, r) => (r && r.tones && r.tones[i]) || null, cellRenderer: (p) => content(p.row, i) }));
    this.grid = new Grid(box, { columns: cols, rows: t.rows, getRowId: (r) => r.id, treeData: true, getDataPath: (r) => this.paths.get(r.id), clientAggregation: false,
      groupDefaultExpanded: 0, layout: 'wells', density: this.o.density, chrome: false, checkboxes: false, rowSelection: 'none', fitRows: this.o.fitRows,
      label: this.o.label || t.headers[0] || 'Rows', getRowClass: (r) => (r.highlight ? 'dt-tp-hl' : null) });
    this.offs = [this.grid.model.on('rowExpanded', (p) => this.opened(p.path, true)), this.grid.model.on('rowCollapsed', (p) => this.opened(p.path, false))];
    this.syncGrid();
    if (this.q) { this.grid.model.setQuickFilter(this.q); }
    this.counted();
  }

  /** @private the grid's open rows as the panel's */
  syncGrid() {
    const rm = this.grid.model.rowModel;
    rm.expandLevel = 0;
    rm.expanded.clear();
    this.open.forEach((id) => { const p = this.paths.get(id); if (p && this.byId.get(id).kids) { rm.expanded.add(p.join(SEP)); } });
    rm.flatten();
    rm.events.emit('rowsChanged', { reason: 'expand' });
    this.grid.refresh();
  }

  /** @private a row opened or closed in the grid */
  opened(path, open) {
    const id = path && this.idByPath.get(path.join(SEP));
    if (!id) { return; }
    if (open) { this.open.add(id); } else { this.open.delete(id); }
    this.saveState();
  }

  /** @private the org chart: drishti-tree's drawing of the same rows */
  drawOrgChart() {
    if (this.grid) { this.offs.forEach((off) => off()); this.grid.destroy(); this.grid = null; }
    const doc = this.doc, t = this.tree;
    const shown = t.headers.map((x, i) => i).filter((i) => i > 0).slice(0, 2);
    const model = this.model || (this.model = new HierarchyModel({ rows: t.rows.map((r) => { const x = { id: r.id, parent: r.parent, label: r.label, kind: '' }; shown.forEach((i) => { x['c' + i] = r.texts[i] || null; }); return x; }),
      measures: shown.map((i) => ({ field: 'c' + i, label: t.headers[i], rollup: false })) }));
    model.open = new Set(Array.from(this.open).filter((id) => model.node(id)));
    const hits = new Set(this.q ? model.search(this.q, { open: true }).map((m) => m.id) : []);
    this.open = new Set(model.open);
    const theme = visualTheme({ element: this.host, density: this.o.density });
    const d = drawOrg(doc, { model: model, theme: theme, text: (f, v) => String(v), selected: this.selected, hits: hits, colourBy: null, shown: shown.map((i) => 'c' + i),
      sizeBy: null, focus: null, editing: false });
    const vw = this.view.getBoundingClientRect().width || this.host.getBoundingClientRect().width || d.width;
    const zoom = Math.max(0.5, Math.min(1, (vw - 8) / d.width));
    d.svg.setAttribute('width', String(Math.round(d.width * zoom)));
    d.svg.setAttribute('height', String(Math.round(d.height * zoom)));
    d.svg.setAttribute('aria-label', (this.o.label || t.headers[0] || 'Rows') + ' as an org chart');
    this.view.textContent = '';
    this.view.appendChild(h(doc, 'div', { cls: 'dt-canvas dt-tp-org' }, [d.svg]));
    const at = this.view.querySelector('[data-id="' + (this.active || '') + '"]') || this.view.querySelector('[data-id]');
    if (at) { at.setAttribute('tabindex', '0'); }
    this.hits = hits;
    this.counted();
  }

  /** @private how many rows the filter keeps */
  counted() {
    if (!this.q) { this.count.textContent = ''; return; }
    const words = this.q.toLowerCase().split(/\s+/).filter(Boolean);       // as the grid's filter: every word in some cell of the row
    const n = this.mode === 'org' ? (this.hits ? this.hits.size : 0) : this.tree.rows.filter((r) => words.every((w) => r.texts.some((t) => t.toLowerCase().indexOf(w) >= 0))).length;
    this.count.textContent = n + ' of ' + this.tree.rows.length + ' rows (filtered)';
  }

  /**
   * Draws the rows in another mode (the open rows and the filter stay).
   * @param {('grid'|'org')} mode
   * @returns {void}
   */
  setMode(mode) {
    if (PANEL_MODES.indexOf(mode) < 0 || mode === this.mode) { return; }
    this.mode = mode;
    this.draw();
    announce('Shown as ' + NAMES[mode].toLowerCase());
    this.saveState();
  }

  /**
   * Keeps the rows that match the words and their ancestors (empty: every row).
   * @param {string} q
   * @returns {void}
   */
  filter(q) {
    this.q = String(q || '').trim();
    if (this.box && this.box.value.trim() !== this.q) { this.box.value = this.q; }
    if (this.mode === 'grid' && this.grid) { this.grid.model.setQuickFilter(this.q); this.counted(); } else { this.drawOrgChart(); }
    this.saveState();
  }

  /**
   * Opens or closes a row.
   * @param {string} id
   * @param {boolean} [open] default: the other state
   * @returns {void}
   */
  toggle(id, open) {
    const now = open === undefined ? !this.open.has(id) : !!open;
    if (now) { this.open.add(id); } else { this.open.delete(id); }
    if (this.grid) { this.syncGrid(); } else { this.drawOrgChart(); }
    this.saveState();
  }

  /** @private a click in the org chart: a toggle, or a box selected */
  clicked(e) {
    if (this.mode !== 'org' || !e.target.closest) { return; }
    const tog = e.target.closest('[data-toggle]'), node = e.target.closest('[data-id]');
    if (tog) { this.active = tog.getAttribute('data-toggle'); this.toggle(this.active); return; }
    if (node) { this.active = this.selected = node.getAttribute('data-id'); this.drawOrgChart(); const r = this.byId.get(this.selected); if (r) { announce(r.texts.filter(Boolean).join(', ')); } }
  }

  /** @private opens a row's link (its first cell's): the server's own link, so the host's navigation applies */
  follow(id) {
    const r = this.byId.get(id), a = r && r.cells.map((c) => c && c.querySelector('a[href]')).find(Boolean);
    if (a) { a.click(); }
  }

  /** @private the org chart's keys: arrows walk the tree, + and − open and close, Enter opens the row's link */
  key(e) {
    if (this.mode !== 'org' || e.ctrlKey || e.metaKey || e.altKey || !this.model) { return; }
    const order = this.model.visible().map((n) => n.id), mv = keyMove(e.key, this.model, order, this.active || order[0], true);
    if (!mv) { return; }
    e.preventDefault();
    this.active = mv.to;
    if (mv.act === 'open' || mv.act === 'close') { this.toggle(mv.to, mv.act === 'open'); } else if (mv.act === 'explain') { this.follow(mv.to); return; } else if (mv.act === 'select') { this.selected = mv.to; this.drawOrgChart(); }
    const el = this.view.querySelector('[data-id="' + mv.to + '"]');
    if (el) { this.view.querySelectorAll('[data-id][tabindex="0"]').forEach((x) => x.setAttribute('tabindex', '-1')); el.setAttribute('tabindex', '0'); el.focus(); }
  }

  /**
   * What the panel keeps for the next drawing of the same table.
   * @returns {TreePanelState}
   */
  getState() { return { mode: this.mode, q: this.q, open: Array.from(this.open) }; }

  /** @private */
  saveState() { if (this.o.onState) { this.o.onState(this.getState()); } }

  /** Takes the panel down. @returns {void} */
  destroy() {
    if (this.grid) { this.offs.forEach((off) => off()); this.grid.destroy(); this.grid = null; }
    this.host.textContent = '';
  }
}

/**
 * A host's page address for its table trees' filters (treeTables' options.address).
 * @typedef {Object} TreeAddress
 * @property {function(string): ?string} read the filter's words in the address for a panel id (null: none)
 * @property {function(string, string): void} write the panel's filter's words into the address ('' takes them out)
 */

/**
 * Draws every server tree table under a scope (table.tbl-tree not yet drawn) as a table tree: the panel goes before the table's
 * wrapper, which stays in the page hidden (print, no scripts). Panels whose table left the page are taken down on the next call.
 * @param {ParentNode} scope a view, a panel a live patch replaced, a shadow root
 * @param {{memory: Object<string, TreePanelState>, scope: string, density: string, live: TreePanel[], address: TreeAddress}} [options]
 *   memory: the state by table (kept by the host across calls, so a live patch keeps the mode, the open rows and the filter); scope: a
 *   prefix of the keys (the page's address); live: the panels drawn before (those whose table left the page are destroyed); address:
 *   the host's page address, which carries each panel's filter (a link or a reload shows the same rows)
 * @returns {TreePanel[]} the panels drawn now and still in the page
 */
export function treeTables(scope, options) {
  const o = options || {}, memory = o.memory || {}, live = (o.live || []).filter((p) => { if (p.host.isConnected) { return true; } p.destroy(); return false; });
  const tables = Array.prototype.slice.call(scope.querySelectorAll ? scope.querySelectorAll('table.tbl-tree:not([data-tree-ready])') : []);
  if (scope.matches && scope.matches('table.tbl-tree:not([data-tree-ready])')) { tables.push(scope); }
  tables.forEach((t) => {
    t.setAttribute('data-tree-ready', '');
    const tree = readTableTree(t);
    if (!tree.rows.length) { return; }
    const pnl = t.closest('[data-panel], section[id], .pnl'), pid = pnl ? (pnl.getAttribute('data-panel') || pnl.id || 'tree') : 'tree';
    const key = (o.scope || '') + '|' + pid + '|' + (pnl ? Array.prototype.indexOf.call(pnl.querySelectorAll('table.tbl-tree'), t) : 0);
    const wrap = t.closest('.tbl-wrap') || t, doc = t.ownerDocument;
    const host = h(doc, 'div', {});
    wrap.parentNode.insertBefore(host, wrap);
    wrap.classList.add('dt-tp-src');
    wrap.hidden = true;
    const title = pnl ? clean((pnl.querySelector('h2, h3') || {}).textContent) : '';
    try {
      live.push(new TreePanel(host, { tree: tree, label: title || tree.headers[0], search: !t.hasAttribute('data-no-search'), density: o.density,
        state: memory[key] || seed(o.address, pid), onState: (s) => { memory[key] = s; if (o.address) { o.address.write(pid, s.q || ''); } } }));
    } catch (e) {
      host.remove();
      wrap.hidden = false;                                  // the server's table stays as drawn
      if (doc.defaultView && doc.defaultView.console) { doc.defaultView.console.warn('drishti-tree: a tree table could not be drawn', e); }
    }
  });
  return live;
}

/** @private a panel's first state from the host's address (its filter's words), else none */
function seed(address, pid) {
  const q = address ? address.read(pid) : null;
  return q ? { q: q } : undefined;
}
