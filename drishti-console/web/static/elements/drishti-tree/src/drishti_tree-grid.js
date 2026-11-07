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
/* The tree grid (g6 B, the default mode): the hierarchy drawn by drishti-grid's own tree data (treeData and getDataPath: the grid's
   treegrid roles, open and close, keys, virtual rows, sorting within siblings), not a second tree grid. Every node is a row of its
   own, so the grid shows each node's figures as the hierarchy model holds them (an additive roll-up, or a non-additive figure the host
   gave) and never aggregates (clientAggregation off); a non-additive figure still waiting shows "…". The columns: the node, one per
   measure (drishti-grid marks a non-additive one ◆), "Σ children" beside a measure that asks for it, and a bar for a ratio. Open state follows the
   model both ways; a click selects; in the edit mode a row dragged onto another proposes the move. */
import { h, toneColour } from '../../drishti-core/src/drishti_index.js';
import { Grid } from '../../drishti-grid/src/drishti_index.js';
import { bandTone } from './drishti_tree-layout.js';

const LABEL = '__label';

/**
 * The rows of the grid: one per node, its path of keys (labels, made unique among siblings), its figures and its children's sums.
 * @param {import('./drishti_tree-model.js').HierarchyModel} model
 * @returns {{rows: Object[], idByKeys: Map<string, string>, keysById: Map<string, string[]>}}
 */
export function gridRows(model) {
  const rows = [], idByKeys = new Map(), keysById = new Map();
  const walk = (n, parentKeys) => {
    const sib = n.parent ? n.parent.children : model.roots;
    const twin = sib.some((s) => s !== n && s.label === n.label);
    const keys = parentKeys.concat([twin ? n.label + ' · ' + n.id : n.label]);
    keysById.set(n.id, keys);
    idByKeys.set(keys.join('\u001f'), n.id);
    const row = { __id: n.id, __keys: keys, __label: n.label, __kind: n.kind, __moved: n.moved };
    model.measures.forEach((m) => {
      row[m.field] = n.values[m.field] === undefined ? null : n.values[m.field];
      if (m.childSum) { row['__sum_' + m.field] = model.childSum(n.id, m.field); }
    });
    rows.push(row);
    n.children.forEach((c) => walk(c, keys));
  };
  model.roots.forEach((r) => walk(r, []));
  return { rows: rows, idByKeys: idByKeys, keysById: keysById };
}

/**
 * The tree grid of a Tree: a drishti-grid Grid over the hierarchy.
 */
export class TreeGrid {
  /**
   * @param {HTMLElement} host
   * @param {Object} tree the Tree (its model, theme, text(), select(), explainNode(), proposeMove(), o)
   */
  constructor(host, tree) {
    this.tree = tree;
    this.host = host;
    this.doc = host.ownerDocument;
    this.syncing = false;
    const model = tree.model, o = tree.o;
    this.data = gridRows(model);
    const cols = [{ id: LABEL, field: LABEL, header: o.title || 'Node', width: o.labelWidth || 300, sortable: true }];
    model.measures.forEach((m) => {
      cols.push({ id: m.field, field: m.field, header: m.label, type: 'number', additive: m.additive !== false, width: m.bar ? 150 : 112,
        description: m.additive === false ? 'not additive: a parent\'s figure is the server\'s, never the sum of its children' : undefined,
        format: (v) => tree.text(m.field, v), cellRenderer: (p) => this.cell(m, p) });
      if (m.childSum) {
        cols.push({ id: '__sum_' + m.field, field: '__sum_' + m.field, header: 'Σ children', type: 'number', width: 112, additive: true,
          description: 'the children\'s ' + m.label + ' added up: not a ' + m.label + ' (the gap is the diversification)', format: (v) => tree.text(m.field, v), cellClass: () => 'dt-dim' });
      }
    });
    this.grid = new Grid(host, {
      columns: cols, rows: this.data.rows, treeData: true, getDataPath: (r) => r.__keys, getRowId: (r) => r.__id, clientAggregation: false,
      layout: o.gridLayout || 'wells', density: tree.density, chrome: false, checkboxes: false, rowSelection: 'single', label: o.label || o.title || 'Hierarchy',
      explain: (ctx) => tree.explainNode(this.idOfRow(ctx.row), ctx.columnId && ctx.columnId !== LABEL ? ctx.columnId.replace(/^__sum_/, '') : null, true),
      decorateCell: (ctx) => this.decorate(ctx)
    });
    this.offs = [
      this.grid.model.on('rowExpanded', (p) => this.opened(p.path, true)),
      this.grid.model.on('rowCollapsed', (p) => this.opened(p.path, false)),
      this.grid.model.on('cellClicked', (p) => { const id = this.idOfRow(p.row); if (id && !this.syncing) { tree.select(id, { from: 'grid' }); } })
    ];
    this.syncOpen();
    this.onDown = (e) => this.dragStart(e);
    this.onUp = (e) => this.dragEnd(e);
    host.addEventListener('pointerdown', this.onDown);
    host.addEventListener('pointerup', this.onUp);
  }

  /** @private a measure's cell: "…" while the host's figure waits, a bar for a ratio */
  cell(m, p) {
    const doc = p.doc, row = p.row;
    if ((p.value === null || p.value === undefined) && m.additive === false && row && this.tree.model.isPending(row.__id, m.field)) { return h(doc, 'span', { cls: 'dg-text dt-wait', text: '…', attrs: { title: 'asked of the server' } }); }
    if (!m.bar || typeof p.value !== 'number') { return null; }
    const max = m.bar.max || 1, tone = bandTone(p.value, m.bar.bands);
    const fill = h(doc, 'i', { style: { width: Math.round(Math.max(0, Math.min(1, p.value / max)) * 100) + '%', 'background-color': toneColour(this.tree.theme, tone || 'accent') } });
    return h(doc, 'span', { cls: 'dg-text dt-barcell' }, [h(doc, 'span', { cls: 'dt-bar', attrs: { 'aria-hidden': 'true' } }, [fill]), h(doc, 'span', { text: p.text })]);
  }

  /** @private the node's marks (found, proposed, selected) on its first cell */
  decorate(ctx) {
    if (ctx.columnId !== LABEL) { return null; }
    const id = this.idOfRow(ctx.row);
    if (!id) { return null; }
    const n = this.tree.model.node(id), t = this.tree;
    const cls = (t.hits.has(id) ? 'dt-hit ' : '') + (n && n.moved ? 'dt-moved ' : '') + (t.selected === id ? 'dt-rowsel' : '');
    return { cls: cls.trim() || null, after: n && n.moved ? h(this.doc, 'span', { cls: 'dt-tag', text: 'proposed' }) : n && n.also.length ? h(this.doc, 'span', { cls: 'dt-tag dt-shared', text: '⧉ ' + (n.also.length + 1) + ' parents' }) : null };
  }

  /**
   * The node id of a display row.
   * @param {number} r
   * @returns {?string}
   */
  idOfRow(r) { const x = this.grid ? this.grid.model.rowAt(r) : null; return x && x.row ? x.row.__id : null; }

  /** @private the grid opened or closed a node: the model follows */
  opened(keys, open) {
    if (this.syncing) { return; }
    const id = this.data.idByKeys.get(keys.join('\u001f'));
    if (id) { this.syncing = true; try { this.tree.model.toggle(id, open); } finally { this.syncing = false; } }
  }

  /** The grid's open nodes as the model's. @returns {void} */
  syncOpen() {
    if (this.syncing) { return; }
    this.syncing = true;
    try {
      const rm = this.grid.model.rowModel, m = this.tree.model;
      rm.expanded.clear();
      rm.expandLevel = 0;
      m.open.forEach((id) => { const n = m.node(id), k = this.data.keysById.get(id); if (n && n.children.length && k) { rm.expanded.add(k.join('\u001f')); } });
      rm.flatten();
      rm.events.emit('rowsChanged', { reason: 'expand' });
    } finally { this.syncing = false; }
  }

  /** The figures, the shape (a move) or the marks changed: the rows again. @returns {void} */
  refresh() {
    this.data = gridRows(this.tree.model);
    this.grid.setRows(this.data.rows);
    this.syncOpen();
  }

  /** Redraws the marks (a search, a selection). @returns {void} */
  repaint() { this.grid.body.invalidate(); this.grid.body.schedule(); }

  /**
   * Shows and selects a node's row.
   * @param {string} id
   * @returns {void}
   */
  select(id) {
    const g = this.grid.model, i = g.rowModel.indexOfId(id);
    this.syncing = true;
    try {
      g.selection.selectAll(id ? [id] : []);
      if (i >= 0) { g.navigator.setFocus(i, 0); this.grid.afterFocus(true); }
    } finally { this.syncing = false; }
    this.repaint();
  }

  /**
   * The density.
   * @param {string} d
   * @returns {void}
   */
  setDensity(d) { this.grid.setDensity(d); }

  /** @private the edit mode: a row pressed */
  dragStart(e) {
    if (!this.tree.editing || e.button !== 0) { return; }
    const c = e.target.closest && e.target.closest('[data-r]');
    this.from = c ? this.idOfRow(Number(c.getAttribute('data-r'))) : null;
  }

  /** @private the edit mode: released on another row, the move proposed */
  dragEnd(e) {
    const from = this.from;
    this.from = null;
    if (!this.tree.editing || !from) { return; }
    const t = this.doc.elementFromPoint ? this.doc.elementFromPoint(e.clientX, e.clientY) : e.target;
    const c = t && t.closest && t.closest('[data-r]');
    const to = c ? this.idOfRow(Number(c.getAttribute('data-r'))) : null;
    if (to && to !== from) { this.tree.proposeMove(from, to); }
  }

  /** Takes the grid down. @returns {void} */
  destroy() {
    this.offs.forEach((off) => off());
    this.host.removeEventListener('pointerdown', this.onDown);
    this.host.removeEventListener('pointerup', this.onUp);
    this.grid.destroy();
  }
}
