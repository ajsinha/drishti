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
/* The decomposition tree (docs/lenses/mockups/bi-depth, option A): one measure, split level by level by the dimension the reader picks at each
   column head. Every number comes from the host's load() (the pivot engine's own, for that node's slice): this file draws them and adds up
   nothing. A split of a measure that does not add says "Σ standalone − diversification = node" with the server's figures; a component measure
   is shown beside the standalone one. */
import { formatCompact, formatNumber } from '../../drishti-core/src/drishti_index.js';

const NS = 'http://www.w3.org/2000/svg';

/**
 * One column of the answer the host gives.
 * @typedef {Object} DecompositionAnswer
 * @property {{value: ?number, component: ?number, note: (string|undefined)}} node the node's own figures
 * @property {Array<{member: ?string, label: string, value: ?number, component: ?number, others: boolean, note: (string|undefined)}>} children each member's own figures
 * @property {boolean} additive whether the measure adds up (a plain sum)
 * @property {number} [sumStandalone] Σ of the members' standalone figures (a measure that does not add), from the server
 * @property {number} [diversification] Σ members − node, from the server
 * @property {?string} [componentLabel] the name of the component measure, when the source has one
 * @property {string} [unit] money, count…
 */

/**
 * The options of a decomposition tree.
 * @typedef {Object} DecompositionOptions
 * @property {string} title the measure's name (the root box and the first heading)
 * @property {Array<{field: string, label: string}>} splits the dimensions the reader may split by
 * @property {function({split: ?string, path: Array<{field: string, value: string}>}): Promise<DecompositionAnswer>} load one node's answer, asked of the host
 * @property {string[]} [first] the dimensions the columns start with (default: none open, the reader picks)
 * @property {function(?number, string): string} [format] a figure in words, given the value and the unit (default: compact)
 */

/**
 * The tree: the root's column, then one column per open split.
 */
export class Decomposition {
  /**
   * @param {HTMLElement} host
   * @param {DecompositionOptions} options
   */
  constructor(host, options) {
    this.host = host;
    this.o = options;
    this.doc = host.ownerDocument;
    this.levels = [];                                // {answer, split, selected}
    this.seq = 0;
    this.host.classList.add('dt-decomp');
    this.host.setAttribute('role', 'group');
    this.host.setAttribute('aria-label', 'Decomposition of ' + options.title);
    this.load(0, null, []);
  }

  fmt(v, unit) {
    if (v === null || v === undefined) { return '–'; }
    if (this.o.format) { return this.o.format(v, unit); }
    return unit === 'money' ? '$' + formatCompact(v) : formatCompact(v);
  }

  path(depth) {
    const out = [];
    for (let d = 0; d < depth; d++) {
      const l = this.levels[d], s = l && l.selected;
      if (l && s !== null && s !== undefined && l.split) { out.push({ field: l.split, value: l.answer.children[s].member }); }
    }
    return out;
  }

  /** Asks column depth anew (its split changed, or its parent was opened), drops those to its right. */
  load(depth, split, path) {
    this.levels.length = depth + 1;
    this.levels[depth] = { answer: null, split: split, selected: null, busy: true };
    this.draw();
    const seq = ++this.seq;
    this.o.load({ split: split, path: path }).then((a) => {
      if (seq !== this.seq) { return; }
      this.levels[depth] = { answer: a, split: split, selected: null };
      this.draw();
    }, (e) => {
      if (seq !== this.seq) { return; }
      this.levels[depth] = { answer: null, split: split, selected: null, error: (e && e.message) || String(e) };
      this.draw();
    });
  }

  /** Opens a member (its children below, by the next unused split or the one already chosen). */
  open(depth, index) {
    const l = this.levels[depth];
    if (!l || !l.answer || !l.answer.children[index] || l.answer.children[index].others) { return; }
    l.selected = index;
    const used = this.levels.slice(0, depth + 1).map((x) => x.split);
    const next = (this.levels[depth + 1] && this.levels[depth + 1].split && used.indexOf(this.levels[depth + 1].split) < 0) ? this.levels[depth + 1].split
      : (this.o.splits.find((s) => used.indexOf(s.field) < 0) || {}).field;
    this.levels.length = depth + 1;
    if (next) { this.load(depth + 1, next, this.path(depth + 1)); } else { this.draw(); }
  }

  /** Splits column depth by a dimension. */
  splitBy(depth, field) {
    this.load(depth, field || null, this.path(depth));
  }

  /** The words of a split's line, every figure the server's. */
  line(a, label) {
    if (!a.children.length) { return null; }
    if (a.additive || a.diversification === undefined) { return 'The parts add up to ' + this.fmt(a.node.value, a.unit) + (a.additive ? '' : ' (no diversification figure)'); }
    return { sum: this.fmt(a.sumStandalone, a.unit), div: this.fmt(a.diversification, a.unit), node: this.fmt(a.node.value, a.unit),
      pct: a.sumStandalone ? formatNumber(100 * a.diversification / a.sumStandalone, { decimals: 0 }) + '% offset' : '', label: label };
  }

  el(tag, cls, text) {
    const e = this.doc.createElement(tag);
    if (cls) { e.className = cls; }
    if (text !== undefined) { e.textContent = text; }
    return e;
  }

  /** @private one box */
  box(depth, i, item, unit, max, selected, componentLabel) {
    const b = this.el('button', 'dt-box' + (selected ? ' dt-box-on' : '') + (item.others ? ' dt-box-others' : ''));
    b.type = 'button';
    b.setAttribute('aria-pressed', selected ? 'true' : 'false');
    b.dataset.depth = String(depth);
    b.dataset.index = String(i);
    const top = this.el('div', 'dt-box-top');
    top.appendChild(this.el('span', 'dt-box-label', item.label));
    top.appendChild(this.el('span', 'dt-box-value', this.fmt(item.value, unit)));
    b.appendChild(top);
    if (item.component !== null && item.component !== undefined) {
      const c = this.el('div', 'dt-box-comp');
      c.appendChild(this.el('span', '', componentLabel || 'Component'));
      c.appendChild(this.el('span', 'dt-box-value', this.fmt(item.component, unit)));
      b.appendChild(c);
    }
    const bar = this.el('div', 'dt-box-bar'), fill = this.el('div', 'dt-box-fill');
    fill.style.width = (max > 0 && item.value ? Math.max(2, Math.round(Math.abs(item.value) / max * 100)) : 0) + '%';
    bar.appendChild(fill);
    b.appendChild(bar);
    if (item.note) { b.title = item.note; }
    if (depth >= 0) { b.addEventListener('click', () => this.open(depth, i)); }
    return b;
  }

  /** @private the column of one level, its head and boxes */
  column(depth, l) {
    const col = this.el('section', 'dt-col');
    col.dataset.depth = String(depth);
    const head = this.el('div', 'dt-col-head');
    if (depth === 0 && !l.split) {
      head.appendChild(this.el('span', 'dt-col-title', this.o.title));
    } else {
      head.appendChild(this.el('span', 'dt-col-by', 'Split by'));
    }
    const sel = this.el('select', 'dt-split');
    sel.setAttribute('aria-label', 'Split by');
    const none = this.el('option', '', 'choose…'); none.value = '';
    sel.appendChild(none);
    const used = this.levels.slice(0, depth).map((x) => x.split);
    this.o.splits.filter((s) => used.indexOf(s.field) < 0).forEach((s) => { const op = this.el('option', '', s.label); op.value = s.field; sel.appendChild(op); });
    sel.value = l.split || '';
    sel.addEventListener('change', () => this.splitBy(depth, sel.value));
    head.appendChild(sel);
    col.appendChild(head);
    if (l.busy) { col.appendChild(this.el('p', 'dt-col-note', 'Working…')); return col; }
    if (l.error) { const p = this.el('p', 'dt-col-note dt-col-error', l.error); p.setAttribute('role', 'alert'); col.appendChild(p); return col; }
    const a = l.answer;
    if (depth === 0) {
      const root = this.box(-1, 0, { label: 'Total', value: a.node && a.node.value, component: null }, a.unit, Math.abs((a.node && a.node.value) || 0), true, a.componentLabel);
      root.classList.add('dt-box-root');
      col.appendChild(root);
    }
    if (!l.split) { return col; }
    const line = this.line(a);
    if (line) {
      const lineEl = this.el('div', 'dt-line');
      lineEl.setAttribute('role', 'note');
      if (typeof line === 'string') { lineEl.textContent = line; } else {
        lineEl.appendChild(this.el('span', '', 'Σ standalone ' + line.sum + ' − '));
        lineEl.appendChild(this.el('span', 'dt-line-div', 'diversification ' + line.div));
        lineEl.appendChild(this.el('span', 'dt-line-eq', ' = ' + line.node));
        if (line.pct) { lineEl.appendChild(this.el('span', 'dt-line-pct', ' (' + line.pct + ')')); }
      }
      col.appendChild(lineEl);
    }
    if (!a.children.length) { col.appendChild(this.el('p', 'dt-col-note', 'No members.')); return col; }
    const max = a.children.reduce((m, c) => Math.max(m, Math.abs(c.value || 0)), 0);
    a.children.forEach((c, i) => col.appendChild(this.box(depth, i, c, a.unit, max, l.selected === i, a.componentLabel)));
    return col;
  }

  /** @private the links from the opened box to the next column's boxes */
  links(strip) {
    const svg = this.doc.createElementNS(NS, 'svg');
    svg.setAttribute('class', 'dt-links');
    svg.setAttribute('aria-hidden', 'true');
    strip.appendChild(svg);
    const base = strip.getBoundingClientRect();
    const cols = strip.querySelectorAll('.dt-col');
    let w = 0, h = 0;
    cols.forEach((c) => { const r = c.getBoundingClientRect(); w = Math.max(w, r.right - base.left); h = Math.max(h, r.bottom - base.top); });
    svg.setAttribute('width', String(Math.ceil(w)));
    svg.setAttribute('height', String(Math.ceil(h)));
    for (let d = 0; d + 1 < cols.length; d++) {
      const from = cols[d].querySelector('.dt-box-on') || cols[d].querySelector('.dt-box-root');
      const targets = cols[d + 1].querySelectorAll('.dt-box');
      if (!from || !targets.length) { continue; }
      const f = from.getBoundingClientRect();
      targets.forEach((t) => {
        const r = t.getBoundingClientRect(), x0 = f.right - base.left, y0 = f.top + f.height / 2 - base.top, x1 = r.left - base.left, y1 = r.top + r.height / 2 - base.top, mid = (x0 + x1) / 2;
        const p = this.doc.createElementNS(NS, 'path');
        p.setAttribute('d', 'M' + x0 + ' ' + y0 + ' C ' + mid + ' ' + y0 + ', ' + mid + ' ' + y1 + ', ' + x1 + ' ' + y1);
        p.setAttribute('class', 'dt-link' + (t.classList.contains('dt-box-on') ? ' dt-link-on' : ''));
        svg.appendChild(p);
      });
    }
  }

  /** Draws the whole strip (columns, then the links over them). */
  draw() {
    while (this.host.firstChild) { this.host.removeChild(this.host.firstChild); }
    const strip = this.el('div', 'dt-strip');
    this.host.appendChild(strip);
    this.levels.forEach((l, d) => strip.appendChild(this.column(d, l)));
    this.links(strip);
  }

  /** Draws again at the size the host now has. */
  resize() { this.draw(); }

  /** Empties the host. */
  dispose() { this.seq++; while (this.host.firstChild) { this.host.removeChild(this.host.firstChild); } }
}
