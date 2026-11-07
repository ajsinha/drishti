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
/* The selection every part of a designer shares: the canvas, the outline, the inspector and the YAML pane follow one Selection, so a
   click in any of them selects in all. Ids in the order they were picked; the primary one is the last picked (the inspector's and the
   YAML's). A plain pick replaces, Shift or Ctrl adds or toggles. Each designer page makes its own (never a shared global). */
import { EventBus } from '../../drishti-core/src/drishti_index.js';

/**
 * The selected ids. Events: change {ids, primary, source}.
 */
export class Selection {
  /**
   * An empty selection.
   */
  constructor() {
    /** @type {string[]} */
    this.ids = [];
    this.events = new EventBus();
  }

  /**
   * Listens to one of its events; answers the function that stops listening.
   * @param {string} name
   * @param {function(Object): void} fn
   * @returns {function(): void}
   */
  on(name, fn) { return this.events.on(name, fn); }

  /**
   * The primary id (the last picked), or null.
   * @returns {?string}
   */
  primary() { return this.ids.length ? this.ids[this.ids.length - 1] : null; }

  /**
   * Whether an id is selected.
   * @param {string} id
   * @returns {boolean}
   */
  has(id) { return this.ids.indexOf(id) >= 0; }

  /**
   * How many are selected.
   * @returns {number}
   */
  size() { return this.ids.length; }

  /**
   * The selected ids, in picking order.
   * @returns {string[]} a copy, in picking order
   */
  list() { return this.ids.slice(); }

  /**
   * Selects exactly these ids (an id or null for one or none).
   * @param {(string|string[]|null)} ids
   * @param {string} [source] who selected (canvas, outline, yaml ...): a listener may ignore its own echo
   * @returns {void}
   */
  set(ids, source) {
    const next = (Array.isArray(ids) ? ids : ids === null || ids === undefined ? [] : [ids]).filter((x, i, a) => x !== null && x !== undefined && a.indexOf(x) === i);
    if (next.length === this.ids.length && next.every((x, i) => x === this.ids[i])) { return; }
    this.ids = next;
    this.events.emit('change', { ids: this.list(), primary: this.primary(), source: source || '' });
  }

  /**
   * A pick as a pointer or the keys make it: plain replaces, additive (Shift, Ctrl, Cmd) toggles the id in or out.
   * @param {string} id
   * @param {boolean} additive
   * @param {string} [source]
   * @returns {void}
   */
  pick(id, additive, source) {
    if (!additive) { this.set(id, source); return; }
    this.set(this.has(id) ? this.ids.filter((x) => x !== id) : this.ids.concat([id]), source);
  }

  /**
   * Keeps only the ids that still exist (after a delete, an undo, a reload).
   * @param {function(string): boolean} exists
   * @returns {void}
   */
  prune(exists) { const keep = this.ids.filter(exists); if (keep.length !== this.ids.length) { this.set(keep, 'prune'); } }

  /**
   * Selects nothing.
   * @returns {void}
   */
  clear() { this.set([]); }
}
