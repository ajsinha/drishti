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
/* Snapshots in Explain: a picture of a chart (a PNG data URL or an SVG) with the person's note, kept for the page, shown under Explain's
   Workbook part and handed to the host as data (toJSON) to put in a workbook, a pack or a message. The browser builds no workbook (the
   server writes it, as the person asking): this keeps what the person saw and said beside the number, nothing more. A picture is
   drawn through an <img> (an SVG is a data URL, never inserted as markup), the note as text. */
import { ELEMENTS_VERSION } from '../../../vendor/drishti-elements/3.1.0/elements/drishti-core/src/drishti_index.js';

const IMAGE = /^data:image\/(png|svg\+xml);/;

/**
 * @typedef {Object} Snapshot
 * @property {string} id
 * @property {string} title the chart's title
 * @property {string} image a data URL (PNG or SVG)
 * @property {string} note the person's words about it
 * @property {string} summary the chart's one-line description (the alternative to the picture)
 * @property {?string} asOf the data's date
 * @property {string} at when it was taken (ISO)
 */

/** The snapshots of a page. */
export class SnapshotStore {
  /**
   * @param {{now: function(): Date}} [o]
   */
  constructor(o) { this.items = []; this.subs = []; this.now = (o && o.now) || (() => new Date()); this.n = 0; }

  /**
   * Listens to changes.
   * @param {function(SnapshotStore): void} fn
   * @returns {function(): void} stops
   */
  onChange(fn) { this.subs.push(fn); return () => { this.subs = this.subs.filter((f) => f !== fn); }; }

  /** @private */
  changed() { this.subs.forEach((f) => f(this)); }

  /**
   * Keeps a chart's picture with a note. The chart is anything with snapshot(what): drishti-chart's Chart.
   * @param {{snapshot: function(string): Object}} chart
   * @param {{note: string, what: ('png'|'svg')}} [o]
   * @returns {Snapshot}
   */
  take(chart, o) {
    const sn = chart.snapshot((o && o.what) || 'png');
    const image = sn.what === 'svg' ? 'data:image/svg+xml;charset=utf-8,' + encodeURIComponent(sn.data) : sn.data;
    return this.add({ title: sn.title, image: image, note: (o && o.note) || '', summary: sn.summary, asOf: sn.asOf });
  }

  /**
   * Keeps a picture already made (a data URL of a PNG or an SVG; anything else is refused).
   * @param {{title: string, image: string, note: string, summary: string, asOf: ?string}} s
   * @returns {Snapshot}
   */
  add(s) {
    if (!IMAGE.test(String(s.image || ''))) { throw new Error('a snapshot is a PNG or SVG data URL'); }
    const item = { id: 'snap-' + (++this.n), title: String(s.title || 'Chart'), image: s.image, note: String(s.note || ''), summary: String(s.summary || ''), asOf: s.asOf || null, at: this.now().toISOString() };
    this.items.push(item);
    this.changed();
    return item;
  }

  /**
   * Sets a snapshot's note.
   * @param {string} id
   * @param {string} note
   * @returns {void}
   */
  setNote(id, note) { const s = this.items.find((x) => x.id === id); if (s) { s.note = String(note); this.changed(); } }

  /**
   * Takes a snapshot away.
   * @param {string} id
   * @returns {void}
   */
  remove(id) { this.items = this.items.filter((x) => x.id !== id); this.changed(); }

  /**
   * The snapshots as data, for a host to put in a workbook, a pack or a message (the pictures as data URLs).
   * @returns {{elements: string, snapshots: Snapshot[]}}
   */
  toJSON() { return { elements: ELEMENTS_VERSION, snapshots: this.items.map((x) => Object.assign({}, x)) }; }

  /**
   * The snapshots drawn: each picture (with its summary as the alternative text), its note in a text box that keeps what is typed, and Remove.
   * @param {Document} doc
   * @returns {HTMLElement}
   */
  panel(doc) {
    const box = doc.createElement('section');
    box.className = 'dx-snaps';
    box.setAttribute('aria-label', 'Chart snapshots with notes');
    const draw = () => {
      while (box.firstChild) { box.removeChild(box.firstChild); }
      if (!this.items.length) { return; }
      const head = doc.createElement('h4'); head.textContent = 'Chart snapshots'; box.appendChild(head);
      this.items.forEach((s) => {
        const fig = doc.createElement('figure'); fig.className = 'dx-snap';
        const img = doc.createElement('img'); img.src = s.image; img.alt = s.summary || s.title; fig.appendChild(img);
        const cap = doc.createElement('figcaption'); cap.textContent = s.title + (s.asOf ? ' · as of ' + s.asOf : ''); fig.appendChild(cap);
        const note = doc.createElement('textarea'); note.className = 'dx-snap-note'; note.value = s.note; note.rows = 2; note.setAttribute('aria-label', 'Note on ' + s.title);
        note.addEventListener('change', () => this.setNote(s.id, note.value)); fig.appendChild(note);
        const rm = doc.createElement('button'); rm.type = 'button'; rm.className = 'dx-snap-rm'; rm.textContent = 'Remove'; rm.addEventListener('click', () => this.remove(s.id)); fig.appendChild(rm);
        box.appendChild(fig);
      });
    };
    this.onChange(draw);
    draw();
    return box;
  }
}
