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
/* Chart any table: a table's rows as text (what a page shows: a document table, a grid's range) opened as a chart in a native <dialog>
   beside the table, never a new window. The columns are typed from their cells (a number when at least 80% of the cells that have a value
   read as one, drishti-core's textSortKey: 1.5m, (1,200), 12%; a date when they are ISO dates; else text); the smart default draws the first
   text column across and up to six number columns as series (a line over dates, else bars). The reader changes the kind, what goes across
   and the series; the chart is drishti-chart's card (its data table, export, Explain, full screen). Escape or Close shuts it and the focus
   goes back to the opener. tableChartSpec is the pure half (testable on its own). */
import { h, textSortKey, openDialog, closeDialog } from '../../drishti-core/src/drishti_index.js';
import { selectElement, button } from '../../drishti-inputs/src/drishti_index.js';
import { Chart } from './drishti_chart-view.js';

/** The kinds a table is offered as. */
export const TABLE_CHART_KINDS = Object.freeze(['bar', 'line', 'area', 'pie', 'scatter']);
/** The most series drawn by default. */
export const TABLE_CHART_SERIES = 6;

const ISO = /^\d{4}-\d{2}-\d{2}/;
const list = (x) => (Array.isArray(x) ? x : []);
const text = (v) => (v === null || v === undefined ? '' : String(v).replace(/\s+/g, ' ').trim());
const EMPTY = /^[—–-]$/;                                // a dash prints an empty cell

/** A cell's number (1.5m, (1,200), −30, 12%), or null. */
function numberOf(v) {
  if (typeof v === 'number') { return isFinite(v) ? v : null; }
  let t = text(v);
  if (!t || EMPTY.test(t)) { return null; }
  const neg = /^\(.*\)$/.test(t);
  if (neg) { t = t.slice(1, -1); }
  const k = textSortKey(t);
  return k.n == null || ISO.test(t) ? null : (neg ? -k.n : k.n);
}

/**
 * A table's fields and rows as values: each column's type from its cells, numbers read from their text.
 * @param {{columns: string[], rows: Array<Array<*>>}} table the headings and each row's cells (text as shown, or numbers)
 * @returns {{fields: Array<{name: string, type: ('number'|'date'|'text')}>, rows: Object[]}}
 */
export function tableChartData(table) {
  const seen = new Set();
  const names = list(table && table.columns).map((c, i) => {
    let n = text(c) || 'Column ' + (i + 1);
    while (seen.has(n)) { n += ' ' + (i + 1); }
    seen.add(n);
    return n;
  });
  const rows = list(table && table.rows).map((r) => list(r));
  const fields = names.map((name, i) => {
    let num = 0, date = 0, all = 0;
    rows.forEach((r) => {
      const v = r[i];
      if (typeof v === 'number') { if (isFinite(v)) { num++; all++; } return; }
      const t = text(v);
      if (!t || EMPTY.test(t)) { return; }
      all++;
      if (ISO.test(t) && !isNaN(Date.parse(t.slice(0, 10)))) { date++; } else if (numberOf(t) !== null) { num++; }
    });
    return { name: name, type: all && date / all >= 0.8 ? 'date' : all && num / all >= 0.8 ? 'number' : 'text' };
  });
  return {
    fields: fields,
    rows: rows.map((r) => {
      const o = {};
      fields.forEach((f, i) => {
        const v = r[i];
        o[f.name] = f.type === 'number' ? numberOf(v) : text(v);
      });
      return o;
    })
  };
}

/**
 * The chart spec of a table and the reader's choice: what goes across (x), the series, the kind. Left out: the smart default.
 * @param {{columns: string[], rows: Array<Array<*>>, title: string}} table
 * @param {{kind: string, x: string, series: string[]}} [choice]
 * @returns {{spec: Object, fields: Object[], choice: {kind: string, x: string, series: string[]}}}
 */
export function tableChartSpec(table, choice) {
  const data = tableChartData(table), fields = data.fields, c = choice || {};
  const numbers = fields.filter((f) => f.type === 'number').map((f) => f.name);
  const has = (n) => fields.some((f) => f.name === n);
  const x = has(c.x) ? c.x : ((fields.find((f) => f.type !== 'number') || fields[0] || {}).name || null);
  let series = list(c.series).filter((n) => numbers.indexOf(n) >= 0 && n !== x);
  if (!c.series) { series = numbers.filter((n) => n !== x).slice(0, TABLE_CHART_SERIES); }
  const xf = fields.find((f) => f.name === x) || {};
  const kind = TABLE_CHART_KINDS.indexOf(c.kind) >= 0 ? c.kind : xf.type === 'date' ? 'line' : 'bar';
  const spec = { kind: kind, title: text(table && table.title) || undefined, data: { rows: data.rows }, legend: series.length > 1 ? 'bottom' : false };
  if (kind === 'pie') {
    Object.assign(spec, { category: { field: x }, value: { field: series[0] || null } });
  } else if (kind === 'scatter') {
    Object.assign(spec, { x: { field: xf.type === 'number' ? x : (series[0] || x), type: 'value' }, y: { field: xf.type === 'number' ? series[0] : (series[1] || series[0]) } });
  } else {
    Object.assign(spec, { x: { field: x, type: 'category' }, series: series.map((n) => ({ field: n, name: n })) });
  }
  if (spec.title === undefined) { delete spec.title; }
  return { spec: spec, fields: fields, choice: { kind: kind, x: x, series: series } };
}

let seq = 0;

/**
 * A table opened as a chart: the dialog, its settings and drishti-chart's card. new TableChart(root, table, options).open().
 */
export class TableChart {
  /**
   * @param {(Element|ShadowRoot)} root where the dialog goes (the view's root, so its sheets style it)
   * @param {{columns: string[], rows: Array<Array<*>>, title: string}} table
   * @param {{opener: HTMLElement, chart: Object, choice: Object, onClose: function(): void}} [o] opener: gets the focus back; chart: the
   *   chart's own options (ChartOptions); choice: the kind, x and series to start with
   */
  constructor(root, table, o) {
    this.root = root;
    this.table = table;
    this.o = o || {};
    const doc = this.doc = (root.ownerDocument || root);
    this.id = 'dc-tc-' + (++seq);
    const title = text(table && table.title);
    this.settings = h(doc, 'div', { cls: 'dc-tc-set', attrs: { role: 'group', 'aria-label': 'Chart settings' } });
    this.host = h(doc, 'div', { cls: 'dc-tc-chart' });
    this.close = button(doc, { label: 'Close', variant: 'bare', cls: 'dc-tc-close', data: { act: 'close' }, onClick: () => this.shut() });
    this.dialog = h(doc, 'dialog', { cls: 'dc-tc', attrs: { 'aria-labelledby': this.id + '-t' } }, [
      h(doc, 'div', { cls: 'dc-tc-head' }, [h(doc, 'h2', { cls: 'dc-tc-title', text: 'Chart' + (title ? ' · ' + title : ''), attrs: { id: this.id + '-t' } }), this.close]),
      this.settings, this.host]);
    this.dialog.addEventListener('close', () => this.gone());
    this.built = tableChartSpec(table, this.o.choice);
  }

  /**
   * Shows the dialog with the chart drawn.
   * @returns {Promise<TableChart>} once the chart is drawn
   */
  open() {
    this.root.appendChild(this.dialog);
    openDialog(this.dialog, { focus: this.close });
    this.chart = new Chart(this.host, this.built.spec, Object.assign({ height: 360 }, this.o.chart || {}));
    this.drawSettings();
    return Promise.resolve(this.chart.ready).then(() => this);
  }

  /**
   * Draws another choice (kind, x, series).
   * @param {{kind: string, x: string, series: string[]}} choice
   * @returns {Promise<void>}
   */
  choose(choice) {
    this.built = tableChartSpec(this.table, Object.assign({}, this.built.choice, choice));
    this.drawSettings();
    return Promise.resolve(this.chart.setSpec(this.built.spec));
  }

  /** @private the kind, across and series settings */
  drawSettings() {
    const doc = this.doc, c = this.built.choice, fields = this.built.fields, s = this.settings;
    s.textContent = '';
    const select = (key, label, options, value) => {
      const el = selectElement(doc, { options: options, value: value, label: label, search: false }, { id: this.id + '-' + key, 'data-tc': key });
      el.component.on('change', (d) => { const x = {}; x[key] = d.value; this.choose(x); });
      return h(doc, 'span', { cls: 'dc-tc-field' }, [h(doc, 'label', { text: label, attrs: { for: this.id + '-' + key } }), el]);
    };
    s.appendChild(select('kind', 'Type', TABLE_CHART_KINDS.slice(), c.kind));
    s.appendChild(select('x', c.kind === 'pie' ? 'Slices' : 'Across', fields.map((f) => f.name), c.x));
    const numbers = fields.filter((f) => f.type === 'number' && f.name !== c.x);
    const box = h(doc, 'fieldset', { cls: 'dc-tc-series' }, [h(doc, 'legend', { text: numbers.length ? 'Series' : 'No number column to chart' })]);
    numbers.forEach((f) => {
      const on = c.series.indexOf(f.name) >= 0;
      const input = h(doc, 'input', { attrs: { type: 'checkbox', 'data-tc-series': f.name }, on: { change: () => {
        const next = numbers.map((n) => n.name).filter((n) => (n === f.name ? input.checked : c.series.indexOf(n) >= 0));
        this.choose({ series: next });
      } } });
      input.checked = on;
      box.appendChild(h(doc, 'label', { cls: 'dc-tc-s' }, [input, h(doc, 'span', { text: f.name })]));
    });
    s.appendChild(box);
  }

  /**
   * Closes the dialog (the focus goes back to the opener).
   * @returns {void}
   */
  shut() { closeDialog(this.dialog); }

  /** @private the dialog closed: the chart disposed, the dialog removed */
  gone() {
    if (this.chart) { this.chart.destroy(); this.chart = null; }
    this.dialog.remove();
    if (this.o.opener && this.o.opener.focus && this.o.opener.isConnected) { this.o.opener.focus(); }
    if (typeof this.o.onClose === 'function') { this.o.onClose(); }
  }
}

/**
 * Opens a table as a chart in a dialog inside root.
 * @param {(Element|ShadowRoot)} root
 * @param {{columns: string[], rows: Array<Array<*>>, title: string}} table
 * @param {Object} [o] TableChart's options
 * @returns {TableChart}
 * @example openTableChart(view.shadowRoot, { title: 'Flows', columns: ['Date', 'Amount'], rows: [['2026-01-02', '1.5m']] }, { opener: btn })
 */
export function openTableChart(root, table, o) { const t = new TableChart(root, table, o); t.open(); return t; }
