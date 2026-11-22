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
/* A resizable split: two or more panes side by side (horizontal) or one above another (vertical), a draggable separator between each
   pair. A pane has a minimum size (px) and may have a maximum; sizes are kept as shares of the container, so the panes follow the
   container when it is resized. The separator is a focusable role=separator: the arrows move it by 10 px (Shift: 50), Home and End take
   it to its smallest and largest, Enter or a double-click puts it back where it began. Sizes may be remembered (options.persistKey,
   in the browser's local storage, never required: without storage the sizes simply start from the options). */
import { InputBase } from './drishti_inputs-dom.js';
import { clamp } from '../../drishti-core/src/drishti_index.js';

/**
 * @typedef {Object} SplitPaneDef
 * @property {string} [id] the pane's name (default pane<n>)
 * @property {(HTMLElement|string)} [content] an element put into the pane, or text
 * @property {number} [size] its starting share (a number; shares are scaled to add up to one; default: equal)
 * @property {number} [min=80] its smallest size in px
 * @property {number} [max] its largest size in px
 * @property {string} [label] what the separator before it resizes (aria-label), default "Resize <previous> and <this>"
 */

/**
 * @typedef {Object} SplitPaneOptions
 * @property {('horizontal'|'vertical')} [direction='horizontal'] horizontal puts the panes side by side
 * @property {SplitPaneDef[]} panes at least two
 * @property {string} [persistKey] remember the sizes in local storage under this key
 * @property {number} [step=10] px one arrow key moves a separator
 * @property {string} [label] names the group (aria-label)
 */


/** Reads remembered shares; never throws. */
function recall(win, key) {
  try { const v = JSON.parse(win.localStorage.getItem('drishti.split.' + key)); return Array.isArray(v) ? v : null; } catch (e) { return null; }
}

/**
 * A split pane. Events: resize {sizes: {id: share}, px: {id: px}} (on every move; a drag also emits resized {…} when it ends).
 */
export class SplitPane extends InputBase {
  /**
   * @param {HTMLElement} host
   * @param {SplitPaneOptions} options
   */
  constructor(host, options) {
    super(host, options, 'di-split');
    const o = this.o;
    if (!Array.isArray(o.panes) || o.panes.length < 2) { throw new Error('a split pane needs at least two panes'); }
    this.vertical = o.direction === 'vertical';
    host.classList.add(this.vertical ? 'di-split-v' : 'di-split-h');
    host.setAttribute('role', 'group');
    if (o.label) { host.setAttribute('aria-label', o.label); }
    this.defs = o.panes.map((p, i) => ({ id: p.id || 'pane' + (i + 1), min: p.min === undefined ? 80 : p.min, max: p.max === undefined ? Infinity : p.max, label: p.label }));
    this.start = this.normal(o.panes.map((p) => (typeof p.size === 'number' && p.size > 0 ? p.size : 1)));
    const kept = o.persistKey ? recall(this.win, o.persistKey) : null;
    this.shares = kept && kept.length === this.defs.length ? this.normal(kept) : this.start.slice();
    this.panes = [];
    this.bars = [];
    this.defs.forEach((d, i) => {
      const content = o.panes[i].content;
      const pane = this.el('div', { cls: 'di-split-pane', data: { pane: d.id }, attrs: { id: 'di-split-' + d.id + '-' + this.uidn() } });
      if (typeof content === 'string') { pane.textContent = content; } else if (content) { pane.appendChild(content); }
      this.panes.push(pane);
      host.appendChild(pane);
      if (i < this.defs.length - 1) {
        const bar = this.el('div', { cls: 'di-split-bar', data: { bar: String(i) }, attrs: { role: 'separator', tabindex: '0', 'aria-orientation': this.vertical ? 'horizontal' : 'vertical',
          'aria-controls': pane.id, 'aria-label': d.label || o.panes[i + 1].label || 'Resize ' + d.id + ' and ' + this.defs[i + 1].id } });
        bar.addEventListener('keydown', (e) => this.onKey(e, i));
        bar.addEventListener('dblclick', () => this.reset(i));
        bar.addEventListener('pointerdown', (e) => this.drag(e, i));
        this.bars.push(bar);
        host.appendChild(bar);
      }
    });
    this.paint();
    if (this.win.ResizeObserver) { this.watch = new this.win.ResizeObserver(() => this.paint()); this.watch.observe(host); }
  }

  /** @private a counter for ids */
  uidn() { return (SplitPane.n = (SplitPane.n || 0) + 1); }

  /** @private shares that add up to one */
  normal(a) { const t = a.reduce((x, y) => x + y, 0) || 1; return a.map((x) => x / t); }

  /** @private the container's length along the split, less the bars */
  room() {
    const r = this.host.getBoundingClientRect(), len = this.vertical ? r.height : r.width;
    return Math.max(0, len - this.bars.reduce((t, b) => t + (this.vertical ? b.offsetHeight : b.offsetWidth) || 0, 0));
  }

  /** @private draws the shares: flex grow in proportion, the minimums as CSS minimums */
  paint() {
    this.panes.forEach((p, i) => {
      const d = this.defs[i];
      p.style.flex = (this.shares[i] * 1000).toFixed(3) + ' 1 0px';
      p.style[this.vertical ? 'minHeight' : 'minWidth'] = d.min + 'px';
      p.style[this.vertical ? 'maxHeight' : 'maxWidth'] = d.max === Infinity ? '' : d.max + 'px';
    });
    const px = this.pixels();
    this.bars.forEach((b, i) => {
      b.setAttribute('aria-valuenow', String(Math.round(px[i])));
      b.setAttribute('aria-valuemin', String(this.defs[i].min));
      if (this.defs[i].max !== Infinity) { b.setAttribute('aria-valuemax', String(this.defs[i].max)); }
    });
  }

  /** @private the panes' sizes in px as drawn */
  pixels() {
    return this.panes.map((p) => { const r = p.getBoundingClientRect(); return this.vertical ? r.height : r.width; });
  }

  /** @private moves the separator i by delta px, keeping every pane within its min and max */
  move(i, delta, from) {
    const room = this.room();
    if (room <= 0) { return false; }
    const base = from || this.pixels(), a = this.defs[i], b = this.defs[i + 1];
    const total = base[i] + base[i + 1];
    let na = clamp(base[i] + delta, Math.max(a.min, total - b.max), Math.min(a.max, total - b.min));
    if (!(na >= 0)) { return false; }
    const next = base.slice();
    next[i] = na; next[i + 1] = total - na;
    const sum = next.reduce((x, y) => x + y, 0) || 1;
    this.shares = next.map((x) => x / sum);
    this.paint();
    return true;
  }

  /** @private */
  onKey(e, i) {
    const step = (this.o.step || 10) * (e.shiftKey ? 5 : 1), fwd = this.vertical ? 'ArrowDown' : 'ArrowRight', back = this.vertical ? 'ArrowUp' : 'ArrowLeft';
    let moved = null;
    if (e.key === fwd) { moved = this.move(i, step); } else if (e.key === back) { moved = this.move(i, -step); }
    else if (e.key === 'Home') { moved = this.move(i, -1e9); } else if (e.key === 'End') { moved = this.move(i, 1e9); }
    else if (e.key === 'Enter') { this.reset(i); moved = true; }
    if (moved === null) { return; }
    e.preventDefault();
    if (moved) { this.settle(); }
  }

  /** @private a pointer drag on separator i */
  drag(e, i) {
    if (e.button !== undefined && e.button !== 0) { return; }
    e.preventDefault();
    const bar = this.bars[i], from = this.pixels(), at = this.vertical ? e.clientY : e.clientX;
    this.host.classList.add('di-split-dragging');
    try { bar.setPointerCapture(e.pointerId); } catch (x) { /* a synthetic pointer has none to capture */ }
    const onMove = (m) => { if (this.move(i, (this.vertical ? m.clientY : m.clientX) - at, from)) { this.announce(); } };
    const done = () => {
      bar.removeEventListener('pointermove', onMove); bar.removeEventListener('pointerup', done); bar.removeEventListener('pointercancel', done);
      this.host.classList.remove('di-split-dragging');
      this.settle();
    };
    bar.addEventListener('pointermove', onMove); bar.addEventListener('pointerup', done); bar.addEventListener('pointercancel', done);
  }

  /** @private */
  announce() { this.emit('resize', { sizes: this.getSizes(), px: this.sizesPx() }); }

  /** @private a move is finished: remembered and told */
  settle() {
    if (this.o.persistKey) { try { this.win.localStorage.setItem('drishti.split.' + this.o.persistKey, JSON.stringify(this.shares)); } catch (e) { /* no storage: sizes are not remembered */ } }
    this.announce();
    this.emit('resized', { sizes: this.getSizes(), px: this.sizesPx() });
  }

  /** @private the pair i back to the shares it began with */
  reset(i) {
    const keep = this.shares[i] + this.shares[i + 1], pair = this.start[i] + this.start[i + 1] || 1;
    this.shares[i] = keep * this.start[i] / pair; this.shares[i + 1] = keep * this.start[i + 1] / pair;
    this.paint();
    this.settle();
  }

  /**
   * The panes' shares of the container, by id (they add up to one).
   * @returns {Object<string, number>}
   */
  getSizes() { const r = {}; this.defs.forEach((d, i) => { r[d.id] = this.shares[i]; }); return r; }

  /**
   * The panes' sizes in px as drawn, by id.
   * @returns {Object<string, number>}
   */
  sizesPx() { const r = {}, px = this.pixels(); this.defs.forEach((d, i) => { r[d.id] = px[i]; }); return r; }

  /**
   * Sets the shares by id (any numbers; they are scaled to add up to one); a pane left out keeps its share. No event.
   * @param {Object<string, number>} sizes
   * @returns {void}
   */
  setSizes(sizes) {
    const next = this.defs.map((d, i) => (typeof sizes[d.id] === 'number' && sizes[d.id] > 0 ? sizes[d.id] : this.shares[i]));
    this.shares = this.normal(next);
    this.paint();
  }

  /**
   * A pane's element, to fill or read.
   * @param {string} id
   * @returns {(HTMLElement|null)}
   */
  pane(id) { return this.panes[this.defs.findIndex((d) => d.id === id)] || null; }

  /**
   * The value is the shares by id.
   * @returns {Object<string, number>}
   */
  getValue() { return this.getSizes(); }

  /**
   * Sets the shares (as setSizes).
   * @param {Object<string, number>} v
   * @returns {void}
   */
  setValue(v) { this.setSizes(v || {}); }

  /**
   * Takes the split out of its element (the panes' content stays in the page's hands: it is removed with the element).
   * @returns {void}
   */
  destroy() { if (this.watch) { this.watch.disconnect(); } super.destroy(); }
}
