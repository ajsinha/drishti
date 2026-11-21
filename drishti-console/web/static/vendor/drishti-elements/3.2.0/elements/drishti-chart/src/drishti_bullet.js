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
/* The bullet (limit) bar: one figure against its limit and the bands behind it ("within limit", "near", "breach"), as a small SVG, in a
   line of text, a card or a grid cell (drishti-grid's bulletCell and barCell call this: one drawing). Horizontal or vertical. The
   colours are the style sheet's classes (drishti_chart.css: tokens only); the figure, the limit and the use are also in the label a
   screen reader reads, and the host's own text or table is the alternative to the drawing. Nothing is computed beyond the geometry. */

const SVG = 'http://www.w3.org/2000/svg';
const TONES = ['ok', 'warn', 'bad'];

/**
 * @typedef {Object} BulletOptions
 * @property {number} value the figure
 * @property {number} [limit] the limit, drawn as a marker; a figure above it is drawn over-limit
 * @property {number} [min=0] the scale's start
 * @property {number} [max] the scale's end (default: the largest of the figure, the limit and the last band, with room)
 * @property {Array<{to: number, tone: string}>} [bands] ranges behind the bar, by their upper end, in order (tone: ok, warn, bad, or none)
 * @property {('horizontal'|'vertical')} [orientation='horizontal']
 * @property {string} [label] what it measures ("VaR 99% 1d"), for the reader
 * @property {function(number): string} [format] how a figure is written in the label (default: String)
 * @property {string} [prefix='dc-bullet'] the class prefix, to give a host its own look
 */

/**
 * The scale a bullet is drawn on: its start, its end, and a function from a figure to a place 0..100 along the bar.
 * @param {BulletOptions} o
 * @returns {{min: number, max: number, at: function(number): number}}
 */
export function bulletScale(o) {
  const min = typeof o.min === 'number' ? o.min : 0;
  const top = Math.max(typeof o.value === 'number' ? o.value : 0, typeof o.limit === 'number' ? o.limit : 0,
    (o.bands || []).reduce((m, b) => Math.max(m, b.to), 0));
  const max = typeof o.max === 'number' ? o.max : (top > min ? min + (top - min) * (typeof o.limit === 'number' ? 1.15 : 1) : min + 1);
  const span = max - min || 1;
  return { min: min, max: max, at: (v) => Math.max(0, Math.min(100, 100 * (v - min) / span)) };
}

/**
 * The words of a bullet for a screen reader and the title ("VaR 99% 1d: 82 of a limit of 100, 82% used").
 * @param {BulletOptions} o
 * @returns {string}
 */
export function bulletSummary(o) {
  const f = o.format || String;
  let s = (o.label ? o.label + ': ' : '') + f(o.value);
  if (typeof o.limit === 'number') {
    s += ' of a limit of ' + f(o.limit);
    if (o.limit) { s += ', ' + Math.round(100 * o.value / o.limit) + '% used'; }
    if (o.value > o.limit) { s += ', over the limit'; }
  }
  return s;
}

/**
 * A bullet bar as SVG.
 * @param {Document} doc
 * @param {BulletOptions} o
 * @returns {SVGElement}
 * @example bullet(document, {value: 82, limit: 100, bands: [{to: 70, tone: 'ok'}, {to: 90, tone: 'warn'}, {to: 120, tone: 'bad'}]})
 */
export function bullet(doc, o) {
  const P = o.prefix || 'dc-bullet', vert = o.orientation === 'vertical', sc = bulletScale(o);
  const svg = doc.createElementNS(SVG, 'svg');
  svg.setAttribute('viewBox', vert ? '0 0 10 100' : '0 0 100 10');
  svg.setAttribute('preserveAspectRatio', 'none');
  svg.setAttribute('class', P + ' ' + P + (vert ? '-vertical' : '-horizontal'));
  svg.setAttribute('role', 'img');
  const words = bulletSummary(o);
  svg.setAttribute('aria-label', words);
  const t = doc.createElementNS(SVG, 'title');
  t.textContent = words;
  svg.appendChild(t);
  // a rect from a to b along the bar (0..100), thick 0..10 across it; vertical grows upward
  const rect = (a, b, from, thick, cls) => {
    const r = doc.createElementNS(SVG, 'rect');
    if (vert) { r.setAttribute('x', String(from)); r.setAttribute('width', String(thick)); r.setAttribute('y', String(100 - b)); r.setAttribute('height', String(Math.max(0, b - a))); }
    else { r.setAttribute('y', String(from)); r.setAttribute('height', String(thick)); r.setAttribute('x', String(a)); r.setAttribute('width', String(Math.max(0, b - a))); }
    r.setAttribute('class', cls);
    return r;
  };
  let prev = 0;
  (o.bands || []).forEach((b) => {
    const to = sc.at(b.to);
    svg.appendChild(rect(prev, to, 0, 10, P + '-band ' + P + '-band-' + (TONES.indexOf(b.tone) >= 0 ? b.tone : 'none')));
    prev = to;
  });
  if (!(o.bands || []).length) { svg.appendChild(rect(0, 100, 0, 10, P + '-band ' + P + '-band-none')); }
  const over = typeof o.limit === 'number' && o.value > o.limit;
  svg.appendChild(rect(0, sc.at(o.value), 3.3, 3.4, P + '-value' + (over ? ' ' + P + '-over' : '')));
  if (typeof o.limit === 'number') {
    const at = sc.at(o.limit), l = doc.createElementNS(SVG, 'line');
    if (vert) { l.setAttribute('x1', '1'); l.setAttribute('x2', '9'); l.setAttribute('y1', String(100 - at)); l.setAttribute('y2', String(100 - at)); }
    else { l.setAttribute('y1', '1'); l.setAttribute('y2', '9'); l.setAttribute('x1', String(at)); l.setAttribute('x2', String(at)); }
    l.setAttribute('class', P + '-limit');
    svg.appendChild(l);
  }
  return svg;
}
