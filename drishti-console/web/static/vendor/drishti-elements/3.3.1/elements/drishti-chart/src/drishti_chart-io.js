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
/* A chart's export (PNG, SVG, CSV, the spec) and its live data (transactions by row id, throttled); Chart (drishti_chart-view.js)
   extends this and keeps the rest (the card, the tools, Explain, selection, keys, modes). */
import { h, toCsv, download, copyText } from '../../drishti-core/src/drishti_index.js';
import { specText } from './drishti_chart-spec.js';
import { rowId } from './drishti_chart-kinds.js';
import { svgOf } from './drishti_chart-parts.js';

/** A chart's export and live data; every member works on Chart's own fields. */
export class ChartIO {
  // ---- export -------------------------------------------------------------------------------------------------------------------------
  /** @private the export menu under its button */
  exportMenu(btn) {
    if (this.menu) { this.menu.remove(); this.menu = null; return; }
    const words = { png: 'PNG image (2x)', svg: 'SVG drawing', csv: 'CSV of the data', spec: 'Copy the spec' };
    const close = () => { if (this.menu) { this.menu.remove(); this.menu = null; this.doc.removeEventListener('keydown', onKey, true); } };
    const onKey = (e) => { if (e.key === 'Escape') { close(); btn.focus(); } };
    this.menu = h(this.doc, 'div', { cls: 'dc-menu', attrs: { role: 'menu', 'aria-label': 'Export' } }, this.spec.export.map((x) => h(this.doc, 'button', { cls: 'dc-menu-item', text: words[x],
      attrs: { type: 'button', role: 'menuitem', 'data-export': x }, on: { click: () => { close(); this.export(x); } } })));
    this.card.appendChild(this.menu);
    this.doc.addEventListener('keydown', onKey, true);
    const first = this.menu.querySelector('button');
    if (first) { first.focus(); }
  }

  /**
   * The chart as a picture, not downloaded: a PNG at twice the pixels (a data URL) or an SVG drawn off screen (its text), with what a
   * reader needs beside it (the title, the one-line summary, the data's date). Export uses it; so does Explain's snapshot attachment.
   * @param {('png'|'svg')} [what='png']
   * @returns {{what: string, data: string, file: string, type: string, title: string, summary: string, asOf: ?string}}
   */
  snapshot(what) {
    const kind = what || 'png', th = (this.envNow || this.env()).theme;
    const name = (this.spec.title || this.spec.kind).replace(/[^\w.-]+/g, '-').replace(/^-+|-+$/g, '').toLowerCase() || 'chart';
    const common = { title: this.spec.title || this.spec.kind, summary: (this.built && this.built.summary) || '', asOf: this.o.asOf || null };
    if (kind === 'png') { return Object.assign({ what: 'png', data: this.engine.getDataURL({ type: 'png', pixelRatio: 2, backgroundColor: th.surface }), file: name + '.png', type: 'image/png' }, common); }
    if (kind === 'svg') { return Object.assign({ what: 'svg', data: svgOf(this.E, Object.assign({}, this.engine.getOption(), this.map ? this.map.svgOption() : {}), this.canvas.clientWidth, this.canvas.clientHeight), file: name + '.svg', type: 'image/svg+xml' }, common); }
    throw new Error('no snapshot ' + what + ' (png, svg)');
  }

  /**
   * Exports the chart: a PNG at twice the pixels, an SVG drawn off screen, the data as CSV, or the spec (copied, and handed back).
   * @param {('png'|'svg'|'csv'|'spec')} what
   * @returns {string} the content (a data URL for PNG)
   */
  export(what) {
    const name = (this.spec.title || this.spec.kind).replace(/[^\w.-]+/g, '-').replace(/^-+|-+$/g, '').toLowerCase() || 'chart';
    let out, type, file;
    if (what === 'png' || what === 'svg') { const sn = this.snapshot(what); out = sn.data; file = sn.file; type = sn.type; }
    if (what === 'csv') { const t = this.map ? this.map.table() : this.built.table; out = toCsv({ columns: t.columns, rows: t.rows }); file = name + '.csv'; type = 'text/csv'; }
    if (what === 'spec') { out = specText(this.rawSpec, { rows: true }); file = name + '.json'; type = 'application/json'; copyText(this.win, out); }
    if (out === undefined) { throw new Error('no export ' + what + ' (png, svg, csv, spec)'); }
    if (what !== 'spec') { (this.o.download || ((f, c, t) => download(this.doc, f, c, t)))(file, out, type); }
    this.emit('exported', { what: what, file: file, size: out.length });
    return out;
  }

  // ---- live data ------------------------------------------------------------------------------------------------------------------
  /**
   * Applies a transaction by row id (the spec's id field): rows added, updated (merged) and removed; drawn again, throttled.
   * @param {{add: Object[], update: Object[], remove: Array<(string|Object)>}} tx
   * @returns {{added: number, updated: number, removed: number}}
   */
  apply(tx) {
    const at = new Map(this.rows.map((r, i) => [rowId(this.spec, r, i), i]));
    let updated = 0, removed = 0;
    (tx.update || []).forEach((u) => { const i = at.get(rowId(this.spec, u, -1)); if (i !== undefined) { this.rows[i] = Object.assign({}, this.rows[i], u); updated++; } });
    if (tx.remove && tx.remove.length) {
      const gone = new Set(tx.remove.map((r) => (typeof r === 'object' ? rowId(this.spec, r, -1) : String(r))));
      const before = this.rows.length;
      this.rows = this.rows.filter((r, i) => !gone.has(rowId(this.spec, r, i)));
      removed = before - this.rows.length;
    }
    (tx.add || []).forEach((r) => this.rows.push(r));
    const keep = this.spec.live && this.spec.live.keep;
    if (keep && this.rows.length > keep) { this.rows.splice(0, this.rows.length - keep); }
    this.later();
    const out = { added: (tx.add || []).length, updated: updated, removed: removed };
    this.emit('dataUpdated', out);
    return out;
  }

  /**
   * Appends rows (a feed's new points), drawn again throttled; the spec's live.keep caps how many stay.
   * @param {Object[]} rows
   * @returns {void}
   */
  append(rows) { this.apply({ add: rows }); }

  /**
   * Replaces every row.
   * @param {Object[]} rows
   * @returns {void}
   */
  replace(rows) { this.rows = rows.slice(); this.later(); this.emit('dataUpdated', { replaced: rows.length }); }

  /** @private one drawing per throttle window (the spec's live.throttleMs, default 250) */
  later() {
    if (this.timer) { return; }
    const ms = this.spec.live && this.spec.live.throttleMs !== undefined ? this.spec.live.throttleMs : 250;
    this.timer = this.win.setTimeout(() => { this.timer = 0; this.draw(); }, ms);
  }

  /**
   * Shows the crosshair and the tooltip at a row (a page's selected row followed by the chart); it comes back there after a resize.
   * @param {number} index the row's index in the chart's rows (the category's, for one series by category)
   * @returns {void}
   */
  pointAt(index) {
    this.pinned = index;
    if (this.engine && index >= 0) { this.engine.dispatchAction({ type: 'showTip', seriesIndex: 0, dataIndex: index }); }
  }

  // ---- modes and density ----------------------------------------------------------------------------------------------------------
}
