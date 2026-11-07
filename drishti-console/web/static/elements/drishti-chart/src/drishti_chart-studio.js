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
/* The chart's studio mode (g2 B): the kinds in a rail (general, maps, then each domain library's, drishti-risk's for the banking
   module), the chart large with its data table under it, and the engine-neutral spec on the right, what a developer writes (or a Sutra,
   a report or the AI produces), with options that change both. Picking a kind draws the studio's sample of it (options.samples, the
   gallery's), or the same spec under the other kind. */
import { h } from '../../drishti-core/src/drishti_index.js';
import { kindList, kindOf, specText } from './drishti_chart-spec.js';
import { dataTable } from './drishti_chart-parts.js';

/** @private the spec's JSON with its keys, strings and numbers marked for the style sheet (elements, no markup strings) */
function coloured(doc, text) {
  const out = doc.createDocumentFragment(), re = /("(?:[^"\\]|\\.)*")(\s*:)?|(-?\d[\d.e+-]*|true|false|null)/g;
  let at = 0, m;
  while ((m = re.exec(text))) {
    if (m.index > at) { out.appendChild(doc.createTextNode(text.slice(at, m.index))); }
    if (m[1]) { out.appendChild(h(doc, 'span', { cls: m[2] ? 'dc-j-key' : 'dc-j-str', text: m[1] })); if (m[2]) { out.appendChild(doc.createTextNode(m[2])); } } else { out.appendChild(h(doc, 'span', { cls: 'dc-j-num', text: m[3] })); }
    at = re.lastIndex;
  }
  out.appendChild(doc.createTextNode(text.slice(at)));
  return out;
}

/**
 * The studio mode's frame around a Chart: the kind rail, the table under the chart, the spec and its options.
 */
export class Studio {
  /**
   * @param {Object} chart the Chart (its card goes in the middle)
   */
  constructor(chart) {
    this.c = chart;
    const doc = chart.doc;
    this.rail = h(doc, 'nav', { cls: 'dc-rail', attrs: { 'aria-label': 'Chart kinds' } });
    this.under = h(doc, 'div', { cls: 'dc-studio-table' });
    this.mid = h(doc, 'div', { cls: 'dc-mid' }, [chart.card, this.under]);
    this.pre = h(doc, 'pre', { cls: 'dc-spec', attrs: { tabindex: '0', 'aria-label': 'The spec' } });
    this.opts = h(doc, 'div', { cls: 'dc-opts' });
    this.tab = 'spec';
    const tabBtn = (id, text) => h(doc, 'button', { cls: 'dc-tab', text: text, attrs: { type: 'button', role: 'tab', 'data-tab': id, 'aria-selected': String(this.tab === id) }, on: { click: () => this.showTab(id) } });
    this.tabs = h(doc, 'div', { cls: 'dc-tabs', attrs: { role: 'tablist' } }, [tabBtn('spec', 'Spec'), tabBtn('options', 'Options')]);
    this.copy = h(doc, 'button', { cls: 'dc-btn', text: 'Copy the spec', attrs: { type: 'button', 'data-act': 'copy-spec' }, on: { click: () => this.c.export('spec') } });
    this.note = h(doc, 'p', { cls: 'dc-note', text: 'The spec names no drawing engine: a page, a Sutra panel, a BI visual and the AI write the same one. Options change the chart and the spec together.' });
    this.right = h(doc, 'div', { cls: 'dc-specpane' }, [this.tabs, this.opts, this.pre, h(doc, 'div', { cls: 'dc-spec-foot' }, [this.copy]), this.note]);
    this.root = h(doc, 'div', { cls: 'dc-studio' }, [this.rail, this.mid, this.right]);
    chart.el.appendChild(this.root);
    this.showTab('spec');
  }

  /** @private the tab shown: the spec, or the options and the spec */
  showTab(id) {
    this.tab = id;
    Array.prototype.forEach.call(this.tabs.children, (b) => { b.setAttribute('aria-selected', String(b.getAttribute('data-tab') === id)); b.classList.toggle('dc-on', b.getAttribute('data-tab') === id); });
    this.opts.hidden = id !== 'options';
  }

  /**
   * Draws the rail, the options and the spec for what the chart shows now.
   * @param {Object} built the kind's answer (its table goes under the chart)
   * @returns {void}
   */
  update(built) {
    const c = this.c, doc = c.doc, kinds = kindList(), groups = {};
    kinds.forEach((k) => { (groups[k.family] = groups[k.family] || []).push(k); });
    const words = { general: 'General · drishti-chart', map: 'Maps · drishti-chart', risk: 'Risk · drishti-risk' };
    this.rail.textContent = '';
    ['general', 'map'].filter((f) => groups[f]).concat(Object.keys(groups).filter((f) => f !== 'general' && f !== 'map')).forEach((f) => {
      this.rail.appendChild(h(doc, 'div', { cls: 'dc-rail-g', text: words[f] || f }));
      groups[f].forEach((k) => this.rail.appendChild(h(doc, 'button', { cls: 'dc-rail-k' + (c.spec.kind === k.name ? ' dc-on' : ''), text: k.label,
        attrs: { type: 'button', 'data-kind': k.name, 'aria-current': c.spec.kind === k.name ? 'true' : 'false' }, on: { click: () => this.pick(k.name) } })));
    });
    this.pre.textContent = '';
    this.pre.appendChild(coloured(doc, specText(c.rawSpec)));
    this.under.textContent = '';
    if (built && built.table) { this.under.appendChild(dataTable(doc, built.table, built.summary || '', { limit: 200 })); }
    this.options();
  }

  /** @private the options that apply to the kind shown */
  options() {
    const c = this.c, doc = c.doc, s = c.rawSpec, k = c.spec.kind;
    this.opts.textContent = '';
    const row = (label, el) => this.opts.append(h(doc, 'label', { text: label, attrs: { for: el.id } }), el);
    const select = (id, values, cur, fn) => {
      const el = h(doc, 'select', { attrs: { id: 'dc-o-' + id + '-' + (Studio.seq = (Studio.seq || 0) + 1), 'data-opt': id }, on: { change: () => fn(el.value) } }, values.map((v) => h(doc, 'option', { text: v, attrs: { value: v } })));
      el.value = cur;
      return el;
    };
    const change = (patch) => { const next = Object.assign({}, s, patch); Object.keys(patch).forEach((x) => { if (patch[x] === undefined) { delete next[x]; } }); c.setSpec(next); };
    row('Legend', select('legend', ['bottom', 'right', 'top', 'none'], s.legend === false ? 'none' : (s.legend || 'bottom'), (v) => change({ legend: v })));
    row('Labels', select('labels', ['off', 'on'], s.labels ? 'on' : 'off', (v) => change({ labels: v === 'on' })));
    if (k === 'bar' || k === 'area' || k === 'line') {
      row('Stack', select('stack', ['none', 'stacked', 'percent'], s.stack === 'percent' ? 'percent' : s.stack ? 'stacked' : 'none', (v) => change({ stack: v === 'none' ? undefined : v === 'percent' ? 'percent' : true })));
    }
    if (k === 'bar') { row('Bars', select('orient', ['vertical', 'horizontal'], s.orient === 'horizontal' ? 'horizontal' : 'vertical', (v) => change({ orient: v }))); }
    const offers = (kindOf(k) || {}).studio || [];                   // a domain library's kind says which of the studio's options it takes
    if (offers.indexOf('log') >= 0) { row('Log x', select('log', ['on', 'off'], s.log === false ? 'off' : 'on', (v) => change({ log: v === 'on' }))); }
    if (c.spec.kind !== 'map' && (['line', 'area', 'bar'].indexOf(k) >= 0 || offers.indexOf('zoom') >= 0)) { row('Zoom', select('zoom', ['off', 'on'], s.zoom ? 'on' : 'off', (v) => change({ zoom: v === 'on' ? (s.zoom || true) : undefined }))); }
    row('Density', select('density', ['comfortable', 'compact', 'terminal'], c.density, (v) => c.setDensity(v)));
  }

  /** @private draws a kind: the studio's sample of it, or the spec under the other kind */
  pick(kind) {
    const samples = this.c.o.samples || {};
    const next = samples[kind] ? samples[kind] : Object.assign({}, this.c.rawSpec, { kind: kind });
    this.c.setSpec(next);
  }

  /**
   * Leaves the studio (the chart goes back to a card).
   * @returns {void}
   */
  destroy() { this.root.remove(); }
}
