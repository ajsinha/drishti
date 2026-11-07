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
/* Explain as every component draws it: what a component's Explain hook answered (words, an element, or the ExplainResult shape below)
   for a figure: a grid's cell, a chart's mark, a map's place. One column (a drawer, a card, a side sheet) or the terminal's three panes
   (the explanation, the worst scenarios, the trend). The figures are the hook's (the server's Explain inside Drishti); nothing is
   computed here. Each component styles it under its class prefix (dg-ex-*, dc-ex-*). */
import { h } from './drishti_dom.js';

/**
 * @typedef {Object} ExplainResult
 * @property {string} [title] what is explained ("VaR 99% 1d")
 * @property {string} [value] the figure as shown
 * @property {string} [scope] where it holds (the members, the date)
 * @property {Array<{label: string, value: string}>} [steps] how it was made, in order
 * @property {Array<{label: string, value: number, text: string}>} [parts] what it is made of (a bar each; a diversification part too)
 * @property {string} [partsTitle] the parts' heading ("Stand-alone by Book")
 * @property {Array<{label: string, value: string}>} [scenarios] the worst scenarios (the terminal's middle pane)
 * @property {number[]} [trend] the figure over time (the terminal's right pane)
 * @property {number} [limit] a limit drawn on the trend
 * @property {Array<{label: string, href: string}>} [links] where to go next
 * @property {string} [note] a last word (an approximation, a fallback)
 */

/**
 * @typedef {Object} ExplainState
 * @property {number} row
 * @property {number} col
 * @property {boolean} loading
 * @property {?(ExplainResult|string|Node)} result
 * @property {?Error} error
 */

const SVG = 'http://www.w3.org/2000/svg';

/**
 * A small line of figures over time, as SVG (the colours are the style sheet's: .<prefix>-spark).
 * @param {Document} doc
 * @param {number[]} values
 * @param {number} [limit] a dashed line at the limit
 * @param {string} [prefix='dx'] the class prefix of the component drawing it (dg: the grid, dc: the chart)
 * @returns {SVGElement}
 */
export function sparkline(doc, values, limit, prefix) {
  const P = prefix || 'dx';
  const w = 280, ht = 90, svg = doc.createElementNS(SVG, 'svg');
  svg.setAttribute('viewBox', '0 0 ' + w + ' ' + ht);
  svg.setAttribute('class', P + '-spark');
  svg.setAttribute('role', 'img');
  svg.setAttribute('aria-label', 'trend of ' + values.length + ' points, last ' + (values.length ? values[values.length - 1] : ''));
  const all = values.concat(typeof limit === 'number' ? [limit] : []);
  const lo = Math.min.apply(null, all), hi = Math.max.apply(null, all), span = hi - lo || 1;
  const y = (v) => (ht - 8 - (ht - 16) * (v - lo) / span).toFixed(1);
  const pts = values.map((v, i) => (values.length > 1 ? (i * (w - 4) / (values.length - 1) + 2) : w / 2).toFixed(1) + ',' + y(v)).join(' ');
  const line = doc.createElementNS(SVG, 'polyline');
  line.setAttribute('points', pts);
  line.setAttribute('class', P + '-spark-line');
  svg.appendChild(line);
  if (typeof limit === 'number') {
    const l = doc.createElementNS(SVG, 'line');
    [['x1', 0], ['x2', w], ['y1', y(limit)], ['y2', y(limit)]].forEach(([k, v]) => l.setAttribute(k, String(v)));
    l.setAttribute('class', P + '-spark-limit');
    svg.appendChild(l);
  }
  return svg;
}

/** @private the main explanation: title, figure, scope, steps, parts, links */
function main(doc, r, P) {
  const out = h(doc, 'div', { cls: P + '-ex-main' });
  out.appendChild(h(doc, 'h3', { cls: P + '-ex-title', text: 'Explain' + (r.title ? ' · ' + r.title : '') }));
  if (r.value) { out.appendChild(h(doc, 'div', { cls: P + '-ex-value', text: r.value })); }
  if (r.scope) { out.appendChild(h(doc, 'div', { cls: P + '-ex-scope', text: r.scope })); }
  if (r.steps && r.steps.length) {
    out.appendChild(h(doc, 'ol', { cls: P + '-ex-steps' }, r.steps.map((s) => h(doc, 'li', {}, [h(doc, 'span', { text: s.label }), h(doc, 'span', { cls: P + '-ex-fig', text: s.value || '' })]))));
  }
  if (r.parts && r.parts.length) {
    const mx = Math.max.apply(null, r.parts.map((p) => Math.abs(p.value || 0)).concat([1e-9]));
    out.appendChild(h(doc, 'div', { cls: P + '-ex-sub', text: r.partsTitle || 'Made of' }));
    out.appendChild(h(doc, 'div', { cls: P + '-ex-bars' }, r.parts.slice(0, 8).map((p) => h(doc, 'div', { cls: P + '-ex-bar' }, [
      h(doc, 'span', { text: p.label }),
      h(doc, 'span', { cls: P + '-ex-track' }, [h(doc, 'span', { cls: P + '-ex-fill' + ((p.value || 0) < 0 ? ' ' + P + '-neg' : ''), style: { width: Math.round(100 * Math.abs(p.value || 0) / mx) + '%' } })]),
      h(doc, 'span', { cls: P + '-ex-fig', text: p.text || String(p.value) })]))));
  }
  if (r.note) { out.appendChild(h(doc, 'p', { cls: P + '-ex-note', text: r.note })); }
  if (r.links && r.links.length) { out.appendChild(h(doc, 'div', { cls: P + '-ex-links' }, r.links.map((l) => h(doc, 'a', { text: l.label, attrs: { href: l.href || '#' } })))); }
  return out;
}

/**
 * Draws an Explain state into its place: 'drawer' and 'card' one column, 'split' three panes (the explanation, the worst scenarios, the
 * trend).
 * @param {HTMLElement} el the place
 * @param {?ExplainState} st nothing yet: the hint of how to ask
 * @param {('drawer'|'card'|'split')} mode
 * @param {{onClose: function(): void, explainable: boolean, prefix: string, hint: string}} [o] onClose: a close button; prefix: the
 *   component's class prefix (default dx); hint: the words shown before anything is asked
 * @returns {void}
 */
export function renderExplain(el, st, mode, o) {
  const doc = el.ownerDocument;
  o = o || {};
  const P = o.prefix || 'dx';
  el.textContent = '';
  if (o.onClose && st) { el.appendChild(h(doc, 'button', { cls: P + '-ex-close', text: '×', attrs: { type: 'button', 'aria-label': 'Close Explain' }, on: { click: o.onClose } })); }
  if (!st) {
    el.appendChild(h(doc, 'div', { cls: P + '-ex-main' }, [h(doc, 'h3', { cls: P + '-ex-title', text: 'Explain' }),
      h(doc, 'p', { cls: P + '-ex-hint', text: o.hint || (o.explainable === false ? 'There is no Explain hook here.' : 'Select a figure and press ' + (mode === 'split' ? 'F2' : 'E') + ': how it was made, its scope and what drives it.') })]));
    return;
  }
  if (st.loading) { el.appendChild(h(doc, 'div', { cls: P + '-ex-main ' + P + '-ex-loading', attrs: { 'aria-busy': 'true' } }, [h(doc, 'h3', { cls: P + '-ex-title', text: 'Explain' }), h(doc, 'p', { text: 'Asking how this figure was made…' })])); return; }
  if (st.error) { el.appendChild(h(doc, 'div', { cls: P + '-ex-main' }, [h(doc, 'h3', { cls: P + '-ex-title', text: 'Explain' }), h(doc, 'p', { cls: P + '-ex-error', text: String(st.error.message || st.error) })])); return; }
  const r = st.result;
  if (r && typeof r === 'object' && typeof r.nodeType === 'number') { el.appendChild(r); return; }
  const res = typeof r === 'string' ? { note: r } : (r || {});
  if (mode !== 'split') { el.appendChild(main(doc, res, P)); return; }
  const worst = h(doc, 'div', { cls: P + '-ex-pane' }, [h(doc, 'h3', { cls: P + '-ex-title', text: 'Worst scenarios' })]);
  (res.scenarios || []).slice(0, 10).forEach((s, i) => worst.appendChild(h(doc, 'div', { cls: P + '-ex-row' }, [h(doc, 'i', { text: String(i + 1) }), h(doc, 'span', { text: s.label }), h(doc, 'span', { cls: P + '-ex-fig ' + P + '-neg', text: s.value })])));
  if (!(res.scenarios || []).length) { worst.appendChild(h(doc, 'p', { cls: P + '-ex-hint', text: 'No scenarios for this figure.' })); }
  const trend = h(doc, 'div', { cls: P + '-ex-pane' }, [h(doc, 'h3', { cls: P + '-ex-title', text: 'Trend' })]);
  if (res.trend && res.trend.length) { trend.appendChild(sparkline(doc, res.trend, res.limit, P)); } else { trend.appendChild(h(doc, 'p', { cls: P + '-ex-hint', text: 'No history for this figure.' })); }
  el.append(h(doc, 'div', { cls: P + '-ex-pane' }, [main(doc, res, P)]), worst, trend);
}
