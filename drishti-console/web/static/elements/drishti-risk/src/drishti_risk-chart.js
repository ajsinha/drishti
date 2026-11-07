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
/* drishti-risk's chart preset: every chart of the VaR and exposure screens is a <drishti-chart> made the same way, in the terminal
   density, its figures the server's (VaR, ES, EEPE, the backtest's zone come in the spec).

     riskChart(host, spec, {onPick, height})   a <drishti-chart> in host (made once, its spec replaced after), its Chart back

   onPick(row, context) hears a click on a mark that was made of one row (a scenario date's bar: drill into that date). A test reads
   the drawing through host.querySelector('drishti-chart').chart.built (the option, the legend, the figures, the badge). */
import { defineChartElement } from '../../drishti-chart/src/drishti_index.js';

/**
 * Draws a risk chart in host.
 * @param {HTMLElement} host
 * @param {Object} spec a drishti-chart spec (a risk kind or any other)
 * @param {Object} [o] onPick(row, ctx), height (the drawing's, px, default 220), density (default terminal), explain, and any Chart option
 * @returns {Object} the Chart
 */
export function riskChart(host, spec, o) {
  o = Object.assign({ density: 'terminal' }, o);
  o.height = o.height || spec.height || 220;       // the drawing's height: the option's, else the spec's
  const doc = host.ownerDocument, win = doc.defaultView;
  if (win && win.customElements) { defineChartElement(win.customElements, win); }
  let el = host.querySelector(':scope > drishti-chart');
  if (!el) {
    host.textContent = '';
    el = doc.createElement('drishti-chart');
    el.setAttribute('density', o.density);
    el.style.display = 'block';
    host.appendChild(el);
    el.addEventListener('drishti:mark-clicked', (e) => {
      const pick = el._onPick, d = e.detail || {};
      if (pick && d.rows && d.rows.length === 1) { pick(d.rows[0], d); }
    });
  }
  el._onPick = o.onPick || null;
  const opts = Object.assign({}, o);
  delete opts.onPick;                            // height: the drawing's (the card's title, legend and status line add to it)
  if (!el.chart) { el.options = opts; }
  el.spec = Object.assign({ onClick: o.explain ? 'explain' : 'select' }, spec);
  if (!el.chart && el.mount) { el.mount(); }      // a host not in the page yet
  return el.chart;
}
