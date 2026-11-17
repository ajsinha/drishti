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
/* The selection chip and its history: what is selected across the cards of a page (a grid's rows, a chart's marks, a tree's nodes on the
   shared channels of drishti-link), shown as one chip ("12 selected · Desk Rates ×") with a Clear, and a short history to go back to a
   selection. The history is a model (SelectionHistory, runs under Node); the chip listens to the bus and draws it. */
import { h } from './drishti_dom.js';
import { SELECTION_EVENT, publishSelection } from './drishti_link.js';
import { bus as sharedBus } from './drishti_events.js';

/** The last selections of a channel, newest first, each once. */
export class SelectionHistory {
  /**
   * @param {number} [limit=10]
   */
  constructor(limit) { this.limit = limit || 10; this.items = []; }

  /**
   * Remembers a selection (an empty one is not kept; the same set again moves to the front).
   * @param {string} channel
   * @param {string[]} ids
   * @param {string} [label] words for it ("12 rows")
   * @returns {void}
   */
  push(channel, ids, label) {
    if (!ids || !ids.length) { return; }
    const key = channel + '|' + ids.slice().sort().join(',');
    this.items = [{ key: key, channel: channel, ids: ids.slice(), label: label || ids.length + ' selected', at: Date.now() }].concat(this.items.filter((x) => x.key !== key)).slice(0, this.limit);
  }

  /**
   * The history, newest first.
   * @returns {Array<{channel: string, ids: string[], label: string, at: number}>}
   */
  list() { return this.items.slice(); }
}

/**
 * @typedef {Object} ChipOptions
 * @property {string} channel the channel to follow (the one the cards share)
 * @property {Object} [bus] default drishti-core's
 * @property {function(string[]): string} [describe] the words for a selection ("Desk Rates, Credit")
 * @property {number} [history=10]
 */

/** The chip: the selection now, a Clear, and a History menu that brings an earlier selection back (published on the channel). */
export class SelectionChip {
  /**
   * @param {HTMLElement} host
   * @param {ChipOptions} o
   */
  constructor(host, o) {
    this.o = o;
    this.doc = host.ownerDocument;
    this.bus = o.bus || sharedBus;
    this.history = new SelectionHistory(o.history);
    this.ids = [];
    this.el = h(this.doc, 'span', { cls: 'drishti-selchip', attrs: { role: 'status', 'aria-live': 'polite' } });
    host.appendChild(this.el);
    this.off = this.bus.on(SELECTION_EVENT, (p) => { if (p && p.channel === o.channel) { this.set(p.ids || []); } });
    this.draw();
  }

  /** @private */
  set(ids) { this.ids = ids.slice(); this.history.push(this.o.channel, this.ids, this.words(this.ids)); this.draw(); }

  /** @private */
  words(ids) { return this.o.describe ? this.o.describe(ids) : ids.length + (ids.length === 1 ? ' selected' : ' selected'); }

  /**
   * Clears the selection of the channel (every card hears it).
   * @returns {void}
   */
  clear() { publishSelection(this.o.channel, [], { bus: this.bus, source: this }); }

  /**
   * Brings an earlier selection back.
   * @param {number} index its place in the history (0: the newest)
   * @returns {void}
   */
  restore(index) { const it = this.history.list()[index]; if (it) { publishSelection(this.o.channel, it.ids, { bus: this.bus, source: this }); } }

  /**
   * Draws the chip again.
   * @returns {void}
   */
  draw() {
    const doc = this.doc;
    while (this.el.firstChild) { this.el.removeChild(this.el.firstChild); }
    this.el.hidden = !this.ids.length && !this.history.list().length;
    if (this.ids.length) {
      this.el.appendChild(h(doc, 'span', { cls: 'drishti-selchip-text', text: this.words(this.ids) }));
      this.el.appendChild(h(doc, 'button', { cls: 'drishti-selchip-clear', text: '×', attrs: { type: 'button', 'aria-label': 'Clear the selection' }, on: { click: () => this.clear() } }));
    } else { this.el.appendChild(h(doc, 'span', { cls: 'drishti-selchip-text', text: 'Nothing selected' })); }
    const past = this.history.list();
    if (past.length) {
      /* the earlier selections as a disclosure of buttons (drishti-core draws no select: that is drishti-inputs', which core does not require) */
      const list = h(doc, 'ul', { cls: 'drishti-selchip-past', attrs: { 'aria-label': 'Earlier selections' } },
        past.map((x, i) => h(doc, 'li', {}, [h(doc, 'button', { cls: 'drishti-selchip-pick', text: x.label, attrs: { type: 'button' }, on: { click: () => this.restore(i) } })])));
      this.el.appendChild(h(doc, 'details', { cls: 'drishti-selchip-history' }, [h(doc, 'summary', { text: 'History' }), list]));
    }
  }

  /**
   * Stops listening and takes the chip out.
   * @returns {void}
   */
  destroy() { this.off(); this.el.remove(); }
}
