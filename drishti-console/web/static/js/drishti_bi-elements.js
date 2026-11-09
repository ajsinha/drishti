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
(function (g) { g.Drishti = g.Drishti || {}; })(typeof window !== 'undefined' ? window : globalThis); /* one namespace for everything the console puts on window (tools/dev/asset_prefix.py) */
/* Report visuals that are Drishti Elements (the sunburst and icicle of drishti-tree, the calendar of drishti-chart, the decomposition tree of
   drishti-tree): this file only hands a visual's answer to its element and turns the element's events into the report's selection. It draws
   nothing itself. Linked brushing: what a drag on a line, area, histogram or scatter selects. */
(function () {
  'use strict';
  const B = window.drishtiBi;
  const V = (B.visuals = B.visuals || {});
  const E = () => window.Drishti.elements;

  const none = { dispose() {}, resize() {} };
  const cols = (res) => (res.columns || []).map((c) => c.name);

  /** The rows of a hierarchy: a node per path; inner nodes take the levels' own figures, leaves the answer's. */
  function hierarchy(spec, res) {
    const axis = spec.wells.axis, value = spec.wells.values[0], colour = (spec.wells.colour || [])[0];
    const f = spec.format || {}, warn = f.warnAt === undefined ? 0.9 : f.warnAt, bad = f.badAt === undefined ? 1 : f.badAt;
    const nodes = new Map([['root', { id: 'root', label: spec.title || value, kind: 'root' }]]);
    const status = (v) => (typeof v !== 'number' ? 'none' : v >= bad ? 'over' : v >= warn ? 'near' : 'within');
    const put = (names, row, depth) => {
      const at = (n) => names.indexOf(n);
      let parent = 'root';
      for (let d = 0; d < depth; d++) {
        const id = parent + '|' + String(row[at(axis[d])]);
        if (!nodes.has(id)) { nodes.set(id, { id, parent, label: String(row[at(axis[d])]), kind: axis[d] }); }
        parent = id;
      }
      const n = nodes.get(parent);
      if (at(value) >= 0 && depth === axis.length) { n.size = row[at(value)]; }
      if (colour && at(colour) >= 0) { n.tone = row[at(colour)]; n.status = status(row[at(colour)]); }
    };
    (res.levels || []).forEach((l, d) => { const ns = cols(l); (l.rows || []).forEach((r) => put(ns, r, d + 1)); });
    const ns = cols(res);
    (res.rows || []).forEach((r) => put(ns, r, axis.length));
    const measures = [{ field: 'size', label: value, additive: true }];
    if (colour) { measures.push({ field: 'tone', label: colour, additive: false, childSum: false, bar: { max: 1, status: 'status', tones: { within: 'good', near: 'warn', over: 'bad' } } }); }
    return { rows: Array.from(nodes.values()), measures };
  }

  /** A sunburst or icicle: drishti-tree in that mode, the selection a mark of the report. */
  function partition(host, spec, ctx, res) {
    B.clear(host);
    const box = B.h('div', { class: 'bi-chart' });
    host.appendChild(box);
    const tree = new (E().tree.Tree)(box, { data: hierarchy(spec, res), mode: spec.type, modes: [spec.type], chrome: false, sizeBy: 'size', colourBy: (spec.wells.colour || []).length ? 'tone' : undefined, openDepth: -1 });
    if (ctx.interactive && ctx.onMark) {
      tree.on('selectionChanged', (p) => {
        const id = p.ids[0];
        if (!id || id === 'root') { return; }
        const parts = id.split('|'), depth = parts.length - 2;
        ctx.onMark({ visual: spec.id, field: spec.wells.axis[depth], value: parts[parts.length - 1], label: parts[parts.length - 1] });
      });
    }
    return { dispose() { tree.dispose(); }, resize() { if (tree.resize) { tree.resize(); } else { tree.draw(); } } };
  }

  /** The calendar heatmap: a Chart of kind calendar over the answer's rows. */
  function calendar(host, spec, ctx, res) {
    B.clear(host);
    const box = B.h('div', { class: 'bi-chart' });
    host.appendChild(box);
    const ns = cols(res), xf = spec.wells.axis[0], vf = spec.wells.values[0];
    const rows = (res.rows || []).map((r) => ({ [xf]: r[ns.indexOf(xf)], [vf]: r[ns.indexOf(vf)] }));
    const c = new (E().chart.Chart)(box, { kind: 'calendar', x: { field: xf }, value: { field: vf, format: V.numFmt(spec, ctx, vf) }, title: spec.title, onClick: 'none' }, { rows, height: host.clientHeight || 280 });
    if (ctx.interactive && ctx.onMark) {
      c.on('markClicked', (m) => ctx.onMark({ visual: spec.id, field: xf, value: m.category, label: m.category }));
    }
    return { dispose() { c.destroy ? c.destroy() : c.dispose(); }, resize() { if (c.resize) { c.resize(); } } };
  }

  V.elementVisuals = { sunburst: partition, icicle: partition, calendar };

  /** The decomposition tree: the visual's `format` names a pivot source; every figure is the pivot engine's, per node. */
  V.decomposition = function (host, spec, ctx) {
    const f = spec.format || {};
    if (!f.source || !f.measure || !(f.splits || []).length) {
      B.clear(host).appendChild(B.h('p', { class: 'bi-vis-empty', role: 'status', text: 'Choose a pivot source, a measure and the splits in Format.' }));
      return none;
    }
    B.clear(host);
    const box = B.h('div', { class: 'bi-decomp' });
    host.appendChild(box);
    const d = new (E().tree.Decomposition)(box, {
      title: spec.title || f.measure,
      splits: f.splits.map((s) => ({ field: s, label: B.label(s) })),
      load: (r) => B.api('POST', '/pivot/decompose', Object.assign({ source: f.source, measure: f.measure, options: f.options || {} }, r)),
    });
    return { dispose() { d.dispose(); }, resize() { d.resize(); } };
  };

  /** What a drag selects on a chart that can be brushed: the selection, or null when the chart cannot. (from, to) are the axis values at the band's ends. */
  V.brushOf = function (spec, option, marks) {
    const xa = Array.isArray(option.xAxis) ? option.xAxis[0] : option.xAxis;
    if (!xa || ['line', 'area', 'histogram', 'scatter', 'bubble'].indexOf(spec.type) < 0) { return null; }
    if (spec.type === 'histogram') {
      const field = (spec.wells.axis || [])[0];
      return !field || xa.type !== 'value' ? null : (a, b) => ({ field, values: [a, b], range: true, label: B.compact(a) + ' to ' + B.compact(b) });
    }
    if (spec.type === 'scatter' || spec.type === 'bubble') {
      const data = (option.series[0] && option.series[0].data) || [];
      return !marks.field ? null : (a, b) => {
        const vs = [];
        data.forEach((d, i) => { const x = Array.isArray(d) ? d[0] : (d.value || [])[0]; if (typeof x === 'number' && x >= a && x <= b && marks.items[i]) { vs.push(marks.items[i].value); } });
        return vs.length ? { field: marks.field, values: vs, label: vs.length + ' points' } : null;
      };
    }
    return !marks.field ? null : (a, b) => {
      const lo = Math.max(0, Math.ceil(a)), hi = Math.min(marks.items.length - 1, Math.floor(b));
      return lo > hi ? null : { field: marks.field, values: [marks.items[lo].value, marks.items[hi].value], range: true, label: marks.items[lo].label + ' to ' + marks.items[hi].label };
    };
  };
}());
