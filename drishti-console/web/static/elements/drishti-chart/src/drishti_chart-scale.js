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
/* The colour scale API (one scale for a heatmap, a density chart, a map and a grid cell): sequential or diverging with a midpoint, its
   colour for a value and its legend as an element (a gradient with the low, the midpoint and the high). The ramps are the theme's
   (drishti-core's visualTheme: sequential and diverging), so every theme and dark and light work; a scale never invents a colour. */
import { rampColour } from '../../drishti-core/src/drishti_index.js';

const SVG = 'http://www.w3.org/2000/svg';

/**
 * @typedef {Object} ScaleOptions
 * @property {('sequential'|'diverging')} [type='sequential']
 * @property {number[]} domain the lowest and the highest figure
 * @property {number} [midpoint=0] diverging: the figure drawn in the middle colour; the scale is symmetric about it
 * @property {string[]} ramp the colours, low to high (diverging: negative, middle, positive); the theme's ramp (ChartTheme.sequential, .diverging)
 * @property {function(number): string} [format] how a figure is written in the legend
 */

/**
 * A colour scale: colour(v) for a figure, the domain it covers and a legend element.
 * @param {ScaleOptions} o
 * @returns {{type: string, lo: number, hi: number, mid: ?number, colour: function(number): string, legend: function(Document): SVGElement}}
 */
export function colourScale(o) {
  if (!o || !Array.isArray(o.domain) || !Array.isArray(o.ramp) || o.ramp.length < 2) { throw new Error('colourScale: give a domain [lo, hi] and a ramp of colours'); }
  const div = o.type === 'diverging', mid = div ? (typeof o.midpoint === 'number' ? o.midpoint : 0) : null, f = o.format || String;
  let lo = o.domain[0], hi = o.domain[1];
  if (div) { const m = Math.max(Math.abs(lo - mid), Math.abs(hi - mid)); lo = mid - m; hi = mid + m; }
  const colour = (v) => rampColour(o.ramp, Math.max(lo, Math.min(hi, v)), lo, hi);
  const legend = (doc) => {
    const svg = doc.createElementNS(SVG, 'svg'), id = 'dc-scale-' + Math.round(Math.random() * 1e9);
    svg.setAttribute('viewBox', '0 0 100 14');
    svg.setAttribute('class', 'dc-scale');
    svg.setAttribute('role', 'img');
    svg.setAttribute('aria-label', (div ? 'Diverging' : 'Sequential') + ' scale from ' + f(lo) + (div ? ', middle ' + f(mid) : '') + ' to ' + f(hi));
    const defs = doc.createElementNS(SVG, 'defs'), g = doc.createElementNS(SVG, 'linearGradient');
    g.setAttribute('id', id);
    o.ramp.forEach((c, i) => { const s = doc.createElementNS(SVG, 'stop'); s.setAttribute('offset', String(100 * i / (o.ramp.length - 1)) + '%'); s.setAttribute('stop-color', c); g.appendChild(s); });
    defs.appendChild(g);
    svg.appendChild(defs);
    const r = doc.createElementNS(SVG, 'rect');
    ['x', 'y'].forEach((k) => r.setAttribute(k, '0'));
    r.setAttribute('width', '100'); r.setAttribute('height', '8'); r.setAttribute('fill', 'url(#' + id + ')'); r.setAttribute('class', 'dc-scale-bar');
    svg.appendChild(r);
    [[0, f(lo), 'start'], div ? [50, f(mid), 'middle'] : null, [100, f(hi), 'end']].filter(Boolean).forEach((t) => {
      const x = doc.createElementNS(SVG, 'text');
      x.setAttribute('x', String(t[0])); x.setAttribute('y', '13.5'); x.setAttribute('text-anchor', t[2]); x.setAttribute('class', 'dc-scale-label');
      x.textContent = t[1];
      svg.appendChild(x);
    });
    return svg;
  };
  return { type: div ? 'diverging' : 'sequential', lo: lo, hi: hi, mid: mid, colour: colour, legend: legend };
}
