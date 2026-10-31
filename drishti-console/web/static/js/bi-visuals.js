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
/* Rupaka phase 3 (BI_DESIGN.md › Rūpaka: the original design and every phase as built, section 8): the charts and cards of a report, drawn from one visual's query result. Charts are ECharts (vendored), cards are
   plain elements. Every renderer takes the host element, the visual (as the report model has it), the result ({columns, rows, meta, highlight}) and a context
   (the dataset index, the palette, and the callbacks that make a mark interactive), and returns {dispose, resize}. A chart can be driven from the keyboard:
   focus it, Left and Right move between marks, Enter opens the mark's menu (the same one a click opens). Tables, matrices, slicers and the static visuals are
   in bi-grids.js. Everything is built with DOM calls and textContent, never markup strings. */
(function () {
  'use strict';
  const B = window.drishtiBi;
  const h = B.h;
  const V = (B.visuals = B.visuals || {});

  /** Reads a result into helpers: the index of a column by name, the rows, whether a column is masked. */
  V.table = function (res) {
    const names = res.columns.map((c) => c.name);
    const masked = new Set((res.meta && res.meta.masked) || []);
    return { names, rows: res.rows, masked, at: (n) => names.indexOf(n), isMasked: (n) => masked.has(n) };
  };

  /** The colour a conditional format gives a value of a measure, or null. */
  V.conditional = function (spec, measure, value) {
    const list = (spec.format && spec.format.conditional) || [];
    for (let i = 0; i < list.length; i++) {
      const c = list[i];
      if (c.measure !== measure || typeof value !== 'number') { continue; }
      const v = Number(c.value);
      const hit = { lt: value < v, le: value <= v, gt: value > v, ge: value >= v, eq: value === v, ne: value !== v }[c.op];
      if (hit) { return c.colour || null; }
    }
    return null;
  };

  V.formatOf = (ctx, name) => (ctx.formats && ctx.formats[name]) || (ctx.index.measures[name] && ctx.index.measures[name].format) || (ctx.index.columns[name] && ctx.index.columns[name].format) || null;
  V.numFmt = (spec, ctx, name) => (spec.format && spec.format.numberFormat) || V.formatOf(ctx, name);

  /** A live redraw (`ctx.update`) of a number that changed: the new value flashes for a moment (the style sheet turns the flash off for people who ask for less motion). */
  V.flashIfChanged = function (host, el, ctx) {
    if (!ctx.update) { return; }
    const old = host.querySelector('.bi-card-value');
    if (old && old.getAttribute('data-value') !== el.getAttribute('data-value')) { el.classList.add('bi-flash'); }
  };

  var NO_DATA = 'No data for this selection. If you expected rows: the filters may exclude them, or none of your roles has access to the rows here (ask the owner of the data).';
  function emptyNote(host, text) {
    B.clear(host).appendChild(h('p', { class: 'bi-vis-empty', role: 'status', text }));
    return { dispose() {}, resize() {} };
  }

  // ---- ECharts -------------------------------------------------------------------------------------------------------

  function baseOption(spec, ctx, o) {
    const t = ctx.t;
    const f = spec.format || {};
    return Object.assign({
      color: ctx.palette,
      animation: false,
      textStyle: { color: t.ink, fontFamily: t.mono, fontSize: 11 },
      tooltip: { confine: true, renderMode: 'richText', backgroundColor: t.surface, borderColor: t.border, textStyle: { color: t.ink, fontFamily: t.mono, fontSize: 11 } },
      legend: o.legend === false ? { show: false } : { show: true, bottom: 0, textStyle: { color: t.muted, fontSize: 10 }, type: 'scroll' },
      grid: { left: 8, right: 16, top: f.axisTitles && f.axisTitles.y && !o.horizontal ? 28 : 12, bottom: o.legend === false ? 8 : 30, containLabel: true }
    }, o.extra || {});
  }

  function axisStyle(t) {
    return {
      axisLine: { lineStyle: { color: t.border } }, axisTick: { lineStyle: { color: t.border } }, axisLabel: { color: t.muted, fontFamily: t.mono, fontSize: 10 },
      splitLine: { lineStyle: { color: t.border, opacity: 0.5 } }, nameTextStyle: { color: t.muted, fontSize: 10 }
    };
  }

  /** Mounts an ECharts instance in `host` and wires click and the keyboard. `marks` is [{value, label, text}] in the order of the first axis. When the viewer
      redraws a live visual (`ctx.update`) and the chart is already there, its option is replaced in place: no new instance, no flicker, the keyboard cursor stays. */
  function mount(host, option, spec, ctx, marks) {
    if (!window.echarts) { return emptyNote(host, 'The chart library did not load.'); }
    const sig = spec.type + ':' + spec.id;
    const prev = ctx.update ? host.querySelector(':scope > .bi-chart') : null;
    if (prev && prev.__chart && !prev.__chart.isDisposed() && prev.__sig === sig) {
      prev.__state.marks = marks;
      prev.__chart.setOption(option, true);
      return prev.__handle;
    }
    B.clear(host);
    const box = h('div', { class: 'bi-chart', tabindex: ctx.interactive ? '0' : '-1', role: 'img', 'aria-label': spec.alt || spec.title || spec.type });
    host.appendChild(box);
    const chart = window.echarts.init(box, null, { renderer: 'canvas' });
    chart.setOption(option);
    box.__chart = chart;
    box.__sig = sig;
    const state = { marks };
    box.__state = state;
    let cursor = -1;
    const live = h('span', { class: 'visually-hidden', role: 'status', 'aria-live': 'polite' });
    host.appendChild(live);
    const openMark = (m, x, y) => { if (ctx.interactive && ctx.onMark && m) { ctx.onMark({ visual: spec.id, field: state.marks.field, value: m.value, label: m.label }, { x, y, el: box }); } };
    if (ctx.onHover) {                                                                // a tooltip page (2.4.0): the hovered mark, after a short pause
      chart.on('mouseover', (p) => {
        const m = (p.name ? state.marks.byName(p.name) : null) || state.marks.items[p.seriesType === 'heatmap' || p.seriesType === 'custom' ? (p.value || [])[0] : p.dataIndex];
        const ev = p.event && p.event.event;
        if (m) { ctx.onHover({ visual: spec.id, field: state.marks.field, value: m.value, label: m.label }, { x: ev ? ev.clientX : 0, y: ev ? ev.clientY : 0, el: box }); }
      });
      chart.on('mouseout', () => { if (ctx.onHoverEnd) { ctx.onHoverEnd(); } });
      chart.on('globalout', () => { if (ctx.onHoverEnd) { ctx.onHoverEnd(); } });
    }
    chart.on('click', (p) => {
      let m = p.name ? state.marks.byName(p.name) : null;
      if (!m) { m = state.marks.items[p.seriesType === 'heatmap' || p.seriesType === 'custom' ? p.value[0] : p.dataIndex]; }
      const ev = p.event && p.event.event;
      if (m && state.marks.pick && state.marks.pick(m, p)) { return; }              // a map that opens a country's regions on a click (pick: true when that was all)
      openMark(m, ev ? ev.clientX : 0, ev ? ev.clientY : 0);
    });
    box.addEventListener('keydown', (e) => {
      const items = state.marks.items;
      if (!ctx.interactive || !items.length) { return; }
      if (e.key === 'ArrowRight' || e.key === 'ArrowDown' || e.key === 'ArrowLeft' || e.key === 'ArrowUp') {
        e.preventDefault();
        const step = e.key === 'ArrowRight' || e.key === 'ArrowDown' ? 1 : -1;
        if (cursor >= 0) { chart.dispatchAction({ type: 'downplay', seriesIndex: 0, dataIndex: cursor }); }
        cursor = (cursor + step + items.length) % items.length;
        chart.dispatchAction({ type: 'highlight', seriesIndex: 0, dataIndex: cursor });
        live.textContent = items[cursor].text;
      } else if ((e.key === 'Enter' || e.key === ' ') && cursor >= 0) {
        e.preventDefault();
        const r = box.getBoundingClientRect();
        openMark(items[cursor], r.left + r.width / 2, r.top + r.height / 2);
      } else if (e.key === 'Escape') { cursor = -1; }
    });
    const handle = { dispose() { chart.dispose(); }, resize() { chart.resize(); } };
    box.__handle = handle;
    return handle;
  }

  /** Categories, series and values of a category chart: one series per measure, or one per value of the legend field (the first measure). */
  function seriesOf(spec, ctx, res, other) {
    const tb = V.table(res);
    const axis = (spec.wells.axis || spec.wells.rows || [])[0];
    const legend = (spec.wells.legend || [])[0];
    const measures = (spec.wells.values || []).concat(other || []);
    const ai = tb.at(axis);
    const cats = [], labels = [];
    const seen = new Map();
    tb.rows.forEach((r) => { const k = String(r[ai]); if (!seen.has(k)) { seen.set(k, cats.length); cats.push(r[ai]); labels.push(r[ai] == null ? '–' : tb.isMasked(axis) ? '•••' : String(r[ai])); } });
    const series = [];
    if (legend && tb.at(legend) >= 0) {
      const li = tb.at(legend), mi = tb.at(measures[0]);
      const by = new Map();
      tb.rows.forEach((r) => {
        const key = String(r[li]);
        if (!by.has(key)) { by.set(key, { name: key, measure: measures[0], values: new Array(cats.length).fill(null) }); }
        by.get(key).values[seen.get(String(r[ai]))] = r[mi];
      });
      by.forEach((s) => series.push(s));
    } else {
      measures.forEach((m) => {
        const mi = tb.at(m);
        const values = new Array(cats.length).fill(null);
        tb.rows.forEach((r) => { values[seen.get(String(r[ai]))] = r[mi]; });
        series.push({ name: m, measure: m, values });
      });
    }
    return { tb, axis, cats, labels, series, seen };
  }

  function marksFor(field, s, ctx, spec, measureIdx) {
    const items = s.cats.map((v, i) => ({
      value: v, label: s.labels[i],
      text: s.labels[i] + ': ' + s.series.map((x) => (s.series.length > 1 ? x.name + ' ' : '') + B.fmt(x.values[i], V.numFmt(spec, ctx, x.measure), s.tb.isMasked(x.measure))).join(', ')
    }));
    const byName = (n) => items.find((m) => m.label === n);
    return { field, items, byName };
  }

  function valueAxis(spec, ctx, t, measure) {
    const nf = V.numFmt(spec, ctx, measure);
    return Object.assign({ type: 'value', axisLabel: { color: t.muted, fontFamily: t.mono, fontSize: 10, formatter: (v) => B.compact(v) }, name: (spec.format && spec.format.axisTitles && spec.format.axisTitles.y) || '' }, axisStyle(t), { axisLabel: { color: t.muted, fontFamily: t.mono, fontSize: 10, formatter: (v) => (nf === 'pct' ? B.fmt(v, 'pct') : B.compact(v)) } });
  }

  function tip(spec, ctx, s) {
    return (p) => {
      const list = Array.isArray(p) ? p : [p];
      const lines = list.filter((x) => x.seriesName !== '__highlight').map((x) => {
        const sr = s.series.find((q) => q.name === x.seriesName) || s.series[0];
        return x.seriesName + ': ' + B.fmt(typeof x.value === 'number' ? x.value : x.value && x.value[1], V.numFmt(spec, ctx, sr.measure), s.tb.isMasked(sr.measure));
      });
      return list[0].name + '\n' + lines.join('\n');
    };
  }

  function selectedSet(ctx, spec) {
    const s = ctx.selected && ctx.selected.visual === spec.id ? ctx.selected.values.map(String) : [];
    return s.length ? new Set(s) : null;
  }

  function categoryChart(host, spec, ctx, res) {
    const f = spec.format || {};
    const t = ctx.t;
    const type = spec.type;
    const horizontal = type === 'bar';
    let s = seriesOf(spec, ctx, res, type === 'combo' ? spec.wells.secondary || [] : []);
    if (!s.cats.length) { return emptyNote(host, NO_DATA); }
    const percent = !!f.percent && ['bar', 'column', 'area'].indexOf(type) >= 0;
    if (percent) { s = Object.assign({}, s, { series: V.percentOf(s.series, s.cats.length) }); }
    const sel = selectedSet(ctx, spec);
    const hl = res.highlight ? seriesOf(spec, ctx, res.highlight, []) : null;
    const fixed = typeof f.colour === 'string' && /^#[0-9a-fA-F]{6}$/.test(f.colour) ? f.colour : null;
    const count = s.series.length;
    const secondary = type === 'combo' ? new Set(spec.wells.secondary || []) : new Set();
    const styler = percent ? null : V.styler(spec, s.tb, ctx);
    const axisField = s.axis;
    const every = V.labelEvery(f, s.cats.length);
    const series = [];
    s.series.forEach((sr, si) => {
      const isLine = type === 'line' || type === 'area' || secondary.has(sr.measure);
      const base = V.seriesColour(f, sr.name, sr.measure) || (fixed && count === 1 ? fixed : ctx.palette[si % ctx.palette.length]);
      const rows = s.tb.rows;
      const data = sr.values.map((v, i) => {
        const row = rows.find((r) => String(r[s.tb.at(axisField)]) === String(s.cats[i])) || null;
        const cond = styler ? styler.point(sr.measure, v, row) || styler.point(axisField, s.cats[i], row) : null;
        const faded = (sel && !sel.has(String(s.cats[i]))) || !!hl;
        return { value: v, itemStyle: { color: cond || base, opacity: faded ? 0.35 : 1 } };
      });
      series.push({
        name: sr.name, type: isLine ? 'line' : 'bar', data, yAxisIndex: secondary.has(sr.measure) ? 1 : 0, smooth: !!f.smooth, symbol: 'circle', symbolSize: 5,
        areaStyle: type === 'area' ? { opacity: hl ? 0.1 : 0.25 } : undefined, stack: (f.stacked || percent) && !isLine || percent && type === 'area' ? 'total' : undefined,
        lineStyle: { color: base, width: 2, opacity: hl && isLine ? 0.35 : 1 }, emphasis: { focus: 'none' },
        label: { show: !!f.labels, position: V.labelPosition(f, horizontal, isLine), color: t.ink, fontSize: 10, formatter: (p) => (p.dataIndex % every ? '' : percent ? Math.round(p.value * 100) + '%' : B.compact(p.value)) },
        barMaxWidth: 36
      });
      if (hl) {                                                                       // the highlighted part over the faded whole (bars since 2.1, lines and areas since 2.4)
        const hs = hl.series[si] || { values: [] };
        const vals = s.cats.map((c) => { const i = hl.seen.get(String(c)); return i == null || !hs.values ? null : hs.values[i]; });
        series.push(isLine ? { name: '__highlight', type: 'line', data: vals, smooth: !!f.smooth, symbol: 'circle', symbolSize: 6, lineStyle: { color: base, width: 3 }, itemStyle: { color: base },
          areaStyle: type === 'area' ? { opacity: 0.3, color: base } : undefined, silent: true, z: 5, tooltip: { show: false }, yAxisIndex: secondary.has(sr.measure) ? 1 : 0 }
          : { name: '__highlight', type: 'bar', data: vals.map((v) => ({ value: v, itemStyle: { color: base } })), barGap: '-100%', barMaxWidth: 36, silent: true, z: 5, tooltip: { show: false } });
      }
    });
    const cat = V.categoryAxisFormat(Object.assign({ type: 'category', data: s.labels, inverse: horizontal, name: (f.axisTitles && f.axisTitles.x) || '' }, axisStyle(t)), f);
    const val = V.valueAxisFormat(valueAxis(spec, ctx, t, s.series[0].measure), f);
    if (percent) { val.max = 1; val.axisLabel = Object.assign({}, val.axisLabel, { formatter: (v) => Math.round(v * 100) + '%' }); }
    const axes = horizontal ? { xAxis: val, yAxis: cat } : { xAxis: cat, yAxis: type === 'combo' && secondary.size ? [val, V.valueAxisFormat(valueAxis(spec, ctx, t, [...secondary][0]), {})] : val };
    const legendShown = !!(count > 1 || (spec.wells.legend || []).length) && f.legend !== 'none' && f.legend !== false;
    const option = baseOption(spec, ctx, { horizontal, legend: legendShown, extra: Object.assign(axes, { series, tooltip: Object.assign(baseOption(spec, ctx, {}).tooltip, { trigger: 'axis', axisPointer: { type: 'shadow' }, formatter: tip(spec, ctx, s) }) }) });
    if (legendShown) { option.legend = Object.assign({}, option.legend, f.legend === 'right' ? { right: 0, top: 'middle', orient: 'vertical', bottom: undefined } : f.legend === 'top' ? { top: 0, bottom: undefined } : {}); if (f.legend === 'top') { option.grid.top = 28; option.grid.bottom = 8; } }
    const marks = marksFor(s.axis, s, ctx, spec);
    return mount(host, option, spec, ctx, marks);
  }
  V.categoryChart = categoryChart;
  V.mount = (host, option, spec, ctx, marks) => mount(host, option, spec, ctx, marks);
  V.baseOption = (spec, ctx, o) => baseOption(spec, ctx, o);
  V.axisStyle = (t) => axisStyle(t);
  V.valueAxis = (spec, ctx, t, m) => valueAxis(spec, ctx, t, m);
  V.seriesOf = (spec, ctx, res, other) => seriesOf(spec, ctx, res, other);
  V.marksFor = (field, s, ctx, spec) => marksFor(field, s, ctx, spec);

  function pieChart(host, spec, ctx, res) {
    const f = spec.format || {};
    const s = seriesOf(spec, ctx, res, []);
    if (!s.cats.length) { return emptyNote(host, NO_DATA); }
    const sel = selectedSet(ctx, spec);
    const sr = s.series[0];
    const total = sr.values.reduce((a, v) => a + (typeof v === 'number' ? Math.max(v, 0) : 0), 0);
    const data = s.labels.map((l, i) => ({ name: l, value: sr.values[i], itemStyle: Object.assign({ opacity: sel && !sel.has(String(s.cats[i])) ? 0.35 : 1 }, V.seriesColour(f, l, l) ? { color: V.seriesColour(f, l, l) } : {}) }));
    const lab = { percent: '{d}%', value: (p) => B.compact(p.value), name: '{b}', 'name-percent': '{b}: {d}%' }[f.labelStyle || 'name-percent'];
    const option = baseOption(spec, ctx, {
      legend: f.legend !== 'none', extra: {
        tooltip: Object.assign(baseOption(spec, ctx, {}).tooltip, { trigger: 'item', formatter: (p) => p.name + ': ' + B.fmt(p.value, V.numFmt(spec, ctx, sr.measure), s.tb.isMasked(sr.measure)) + ' (' + (total ? Math.round(100 * p.value / total) : 0) + '%)' }),
        series: [{ type: 'pie', radius: spec.type === 'donut' ? ['45%', '70%'] : [0, '70%'], center: ['50%', '45%'], data, label: { show: !!f.labels, color: ctx.t.ink, fontSize: 10, formatter: lab }, itemStyle: { borderColor: ctx.t.surface, borderWidth: 1 } }]
      }
    });
    return mount(host, option, spec, ctx, marksFor(s.axis, s, ctx, spec));
  }

  function waterfall(host, spec, ctx, res) {
    const f = spec.format || {};
    const t = ctx.t;
    const s = seriesOf(spec, ctx, res, []);
    if (!s.cats.length) { return emptyNote(host, NO_DATA); }
    const sr = s.series[0];
    const steps = [];
    let run = 0;
    sr.values.forEach((v, i) => { const d = typeof v === 'number' ? v : 0; steps.push({ label: s.labels[i], from: run, to: run + d, delta: d }); run += d; });
    steps.push({ label: 'Total', from: 0, to: run, delta: run, total: true });
    const labels = steps.map((x) => x.label);
    const item = (params, api) => {
      const i = api.value(0), a = api.coord([i, api.value(1)]), b = api.coord([i, api.value(2)]);
      const w = api.size([1, 0])[0] * 0.6;
      return { type: 'rect', shape: { x: a[0] - w / 2, y: Math.min(a[1], b[1]), width: w, height: Math.max(1, Math.abs(a[1] - b[1])) }, style: api.style({ fill: steps[i].total ? t.link : steps[i].delta >= 0 ? t.pos : t.neg }) };
    };
    const option = baseOption(spec, ctx, {
      legend: false, extra: {
        xAxis: Object.assign({ type: 'category', data: labels }, axisStyle(t)), yAxis: valueAxis(spec, ctx, t, sr.measure),
        tooltip: Object.assign(baseOption(spec, ctx, {}).tooltip, { trigger: 'item', formatter: (p) => { const x = steps[p.value[0]]; return x.label + '\n' + (x.total ? 'Total ' : 'Change ') + B.fmt(x.delta, V.numFmt(spec, ctx, sr.measure), s.tb.isMasked(sr.measure)); } }),
        series: [{ type: 'custom', renderItem: item, data: steps.map((x, i) => [i, x.from, x.to]), encode: { x: 0, y: [1, 2] }, label: { show: !!f.labels, position: 'top' } }]
      }
    });
    const marks = marksFor(s.axis, s, ctx, spec);
    return mount(host, option, spec, ctx, marks);
  }

  function treemap(host, spec, ctx, res) {
    const tb = V.table(res);
    const axes = spec.wells.axis || [];
    const m = spec.wells.values[0], mi = tb.at(m);
    const cols = axes.map((a) => tb.at(a));
    if (!tb.rows.length) { return emptyNote(host, NO_DATA); }
    const roots = new Map();
    tb.rows.forEach((r) => {
      const v = typeof r[mi] === 'number' ? Math.max(r[mi], 0) : 0;
      const k0 = String(r[cols[0]]);
      if (!roots.has(k0)) { roots.set(k0, { name: k0, raw: r[cols[0]], value: 0, children: [] }); }
      const node = roots.get(k0);
      node.value += v;
      if (cols.length > 1) { node.children.push({ name: String(r[cols[1]]), raw: r[cols[1]], value: v }); }
    });
    const data = [...roots.values()].map((n) => (cols.length > 1 ? n : { name: n.name, value: n.value, raw: n.raw }));
    const items = data.map((n) => ({ value: n.raw !== undefined ? n.raw : n.name, label: n.name, text: n.name + ': ' + B.fmt(n.value, V.numFmt(spec, ctx, m), tb.isMasked(m)) }));
    const option = baseOption(spec, ctx, {
      legend: false, extra: {
        tooltip: Object.assign(baseOption(spec, ctx, {}).tooltip, { formatter: (p) => p.name + ': ' + B.fmt(p.value, V.numFmt(spec, ctx, m), tb.isMasked(m)) }),
        series: [{ type: 'treemap', data, roam: false, nodeClick: false, breadcrumb: { show: false }, label: { color: ctx.t.ink, fontSize: 11 }, itemStyle: { borderColor: ctx.t.surface, borderWidth: 1 }, levels: [{ itemStyle: { borderWidth: 2, borderColor: ctx.t.surface, gapWidth: 2 } }] }]
      }
    });
    return mount(host, option, spec, ctx, { field: axes[0], items, byName: (n) => items.find((x) => x.label === n) });
  }

  function heatmap(host, spec, ctx, res) {
    const tb = V.table(res);
    const xf = spec.wells.axis[0], yf = spec.wells.legend[0], m = spec.wells.values[0];
    const xi = tb.at(xf), yi = tb.at(yf), mi = tb.at(m);
    if (!tb.rows.length) { return emptyNote(host, NO_DATA); }
    const xs = [], ys = [];
    tb.rows.forEach((r) => { if (xs.indexOf(String(r[xi])) < 0) { xs.push(String(r[xi])); } if (ys.indexOf(String(r[yi])) < 0) { ys.push(String(r[yi])); } });
    const vals = tb.rows.map((r) => [xs.indexOf(String(r[xi])), ys.indexOf(String(r[yi])), r[mi]]);
    const nums = vals.map((v) => v[2]).filter((v) => typeof v === 'number');
    const t = ctx.t;
    const option = baseOption(spec, ctx, {
      legend: false, extra: {
        grid: { left: 8, right: 16, top: 10, bottom: 46, containLabel: true },
        xAxis: Object.assign({ type: 'category', data: xs, splitArea: { show: true } }, axisStyle(t)), yAxis: Object.assign({ type: 'category', data: ys, splitArea: { show: true } }, axisStyle(t)),
        visualMap: { min: Math.min(0, ...nums), max: Math.max(1, ...nums), calculable: false, orient: 'horizontal', left: 'center', bottom: 0, inRange: { color: [t['surface-2'] || t.surface, t.link] }, textStyle: { color: t.muted, fontSize: 10 } },
        tooltip: Object.assign(baseOption(spec, ctx, {}).tooltip, { formatter: (p) => xs[p.value[0]] + ' / ' + ys[p.value[1]] + ': ' + B.fmt(p.value[2], V.numFmt(spec, ctx, m), tb.isMasked(m)) }),
        series: [{ type: 'heatmap', data: vals, label: { show: !!(spec.format && spec.format.labels), color: t.ink, fontSize: 10, formatter: (p) => B.compact(p.value[2]) } }]
      }
    });
    const items = xs.map((x) => ({ value: x, label: x, text: x }));
    return mount(host, option, spec, ctx, { field: xf, items, byName: (n) => items.find((q) => q.label === n) });
  }

  function scatter(host, spec, ctx, res) {
    const tb = V.table(res);
    const d = spec.wells.details[0], xm = spec.wells.x[0], ym = spec.wells.y[0], sm = (spec.wells.size || [])[0];
    if (!tb.rows.length) { return emptyNote(host, NO_DATA); }
    const di = tb.at(d), xi = tb.at(xm), yi = tb.at(ym), si = sm ? tb.at(sm) : -1;
    const sizes = si >= 0 ? tb.rows.map((r) => Math.abs(Number(r[si]) || 0)) : [];
    const maxS = Math.max(1, ...sizes);
    const t = ctx.t;
    const data = tb.rows.map((r) => ({ name: String(r[di]), value: [r[xi], r[yi]], symbolSize: si >= 0 ? 8 + 28 * Math.sqrt(Math.abs(Number(r[si]) || 0) / maxS) : 10 }));
    const option = baseOption(spec, ctx, {
      legend: false, extra: {
        xAxis: Object.assign({ type: 'value', name: xm, nameLocation: 'middle', nameGap: 26, axisLabel: { formatter: (v) => B.compact(v), color: t.muted, fontSize: 10 } }, axisStyle(t), { axisLabel: { formatter: (v) => B.compact(v), color: t.muted, fontSize: 10 } }),
        yAxis: Object.assign({ type: 'value', name: ym }, axisStyle(t), { axisLabel: { formatter: (v) => B.compact(v), color: t.muted, fontSize: 10 } }),
        grid: { left: 8, right: 16, top: 24, bottom: 34, containLabel: true },
        tooltip: Object.assign(baseOption(spec, ctx, {}).tooltip, { formatter: (p) => p.name + '\n' + xm + ': ' + B.fmt(p.value[0], V.numFmt(spec, ctx, xm)) + '\n' + ym + ': ' + B.fmt(p.value[1], V.numFmt(spec, ctx, ym)) }),
        series: [{ type: 'scatter', data, itemStyle: { color: ctx.palette[0], opacity: 0.8 } }]
      }
    });
    const items = tb.rows.map((r) => ({ value: r[di], label: String(r[di]), text: String(r[di]) + ': ' + B.fmt(r[xi], V.numFmt(spec, ctx, xm)) + ', ' + B.fmt(r[yi], V.numFmt(spec, ctx, ym)) }));
    return mount(host, option, spec, ctx, { field: d, items, byName: (n) => items.find((q) => q.label === n) });
  }

  function gauge(host, spec, ctx, res) {
    const tb = V.table(res);
    const m = spec.wells.values[0], row = tb.rows[0] || [];
    const value = row[tb.at(m)];
    const tm = (spec.wells.target || [])[0];
    const target = tm ? row[tb.at(tm)] : null;
    const f = spec.format || {};
    if (typeof value !== 'number') { return emptyNote(host, tb.isMasked(m) ? '•••' : 'No value.'); }
    const max = typeof f.max === 'number' ? f.max : typeof target === 'number' ? target * 1.25 : Math.max(Math.abs(value) * 1.25, 1);
    const min = typeof f.min === 'number' ? f.min : 0;
    const t = ctx.t;
    const nf = V.numFmt(spec, ctx, m);
    const option = baseOption(spec, ctx, {
      legend: false, extra: {
        series: [{
          type: 'gauge', min, max, startAngle: 200, endAngle: -20, radius: '95%', center: ['50%', '62%'], progress: { show: true, width: 12, itemStyle: { color: t.link } },
          axisLine: { lineStyle: { width: 12, color: [[1, t.border]] } }, pointer: { show: false }, axisTick: { show: false }, splitLine: { show: false },
          axisLabel: { color: t.muted, fontSize: 9, distance: 16, formatter: (v) => B.compact(v) }, anchor: { show: false },
          detail: { valueAnimation: false, formatter: () => B.fmt(value, nf), color: t.ink, fontSize: 20, offsetCenter: [0, '10%'] },
          title: { show: false }, data: [{ value }]
        }]
      }
    });
    option.tooltip = { show: false };
    const out = mount(host, option, Object.assign({}, spec, { alt: spec.alt || (spec.title + ': ' + B.fmt(value, nf) + (typeof target === 'number' ? ' of target ' + B.fmt(target, nf) : '')) }), ctx, { field: null, items: [], byName: () => null });
    return out;
  }

  // ---- cards ---------------------------------------------------------------------------------------------------------

  function card(host, spec, ctx, res) {
    const tb = V.table(res);
    const m = spec.wells.values[0];
    const v = tb.rows.length ? tb.rows[0][tb.at(m)] : null;
    const f = spec.format || {};
    const big = h('div', { class: 'bi-card-value', 'data-value': v == null ? '' : String(v), text: B.fmt(v, V.numFmt(spec, ctx, m), tb.isMasked(m)) });
    cardStyle(big, spec, tb, ctx, m, v, tb.rows[0] || null);
    V.flashIfChanged(host, big, ctx);
    B.clear(host).appendChild(h('div', { class: 'bi-card-body' }, big, (spec.title && f.showLabel !== true) || f.showLabel === false ? null : h('div', { class: 'bi-card-label', text: m })));
    return { dispose() {}, resize() {} };
  }

  /** A card's number as its format says: its size, and the colour (or background and icon) of its conditional formatting. */
  function cardStyle(el, spec, tb, ctx, m, v, row) {
    const f = spec.format || {};
    if (typeof f.calloutSize === 'number') { el.style.fontSize = f.calloutSize + 'px'; }
    const st = V.styler(spec, tb, ctx);
    const c = st ? st.cell(m, v, row) : null;
    if (c && c.colour && !c.background) { el.style.color = c.colour; }
    if (c && c.background) { el.style.backgroundColor = c.background; el.style.color = c.colour; el.classList.add('bi-card-filled'); }
    if (c && c.icon) { V.paintCell(el, { icon: c.icon }); }
    if (c) { el.setAttribute('data-cf', c.background || c.colour || (c.icon && c.icon.words) || ''); }
  }

  function multirow(host, spec, ctx, res) {
    const tb = V.table(res);
    const axis = (spec.wells.axis || [])[0];
    const ms = spec.wells.values;
    const list = h('div', { class: 'bi-multirow', role: 'list' });
    if (axis) {
      const ai = tb.at(axis);
      tb.rows.forEach((r) => list.appendChild(h('div', { class: 'bi-multirow-row', role: 'listitem' }, h('strong', { text: r[ai] == null ? '–' : String(r[ai]) }),
        ms.map((m) => h('span', { class: 'bi-multirow-v', 'data-measure': m }, h('small', { text: m }), ' ', B.fmt(r[tb.at(m)], V.numFmt(spec, ctx, m), tb.isMasked(m)))))));
    } else {
      const r = tb.rows[0] || [];
      ms.forEach((m) => list.appendChild(h('div', { class: 'bi-multirow-row', role: 'listitem' }, h('strong', { text: m }), h('span', { class: 'bi-multirow-v', text: B.fmt(r[tb.at(m)], V.numFmt(spec, ctx, m), tb.isMasked(m)) }))));
    }
    B.clear(host).appendChild(list);
    return { dispose() {}, resize() {} };
  }

  function kpi(host, spec, ctx, res) {
    const tb = V.table(res);
    const m = spec.wells.values[0], tm = (spec.wells.target || [])[0], axis = (spec.wells.axis || [])[0];
    const last = tb.rows[tb.rows.length - 1] || [];
    const v = last[tb.at(m)];
    const target = tm ? last[tb.at(tm)] : null;
    const nf = V.numFmt(spec, ctx, m);
    const body = h('div', { class: 'bi-card-body' });
    const value = h('div', { class: 'bi-card-value', 'data-value': v == null ? '' : String(v), text: B.fmt(v, nf, tb.isMasked(m)) });
    cardStyle(value, spec, tb, ctx, m, v, last);
    V.flashIfChanged(host, value, ctx);
    body.appendChild(value);
    if (!spec.title) { body.appendChild(h('div', { class: 'bi-card-label', text: m })); }
    if (typeof v === 'number' && typeof target === 'number' && target !== 0) {
      const pct = (v - target) / Math.abs(target);
      const good = (spec.format || {}).goodWhen === 'lower' ? pct <= 0 : pct >= 0;          // better when higher (the default) or lower, in the theme's good and bad colours
      const line = h('div', { class: 'bi-kpi-target ' + (pct >= 0 ? 'bi-up' : 'bi-down'), 'data-good': good ? 'yes' : 'no', text: (pct >= 0 ? '▲ ' : '▼ ') + Math.abs(Math.round(pct * 1000) / 10) + '% vs target ' + B.fmt(target, nf) });
      if (ctx.theme && (ctx.theme.good || ctx.theme.bad)) { const gb = B.theme.goodBad(ctx); line.style.color = good ? gb.good : gb.bad; }
      body.appendChild(line);
    }
    B.clear(host).appendChild(body);
    let handle = { dispose() {}, resize() {} };
    if (axis && tb.rows.length > 1 && window.echarts) {
      const spark = h('div', { class: 'bi-spark', 'aria-hidden': 'true' });
      body.appendChild(spark);
      const chart = window.echarts.init(spark, null, { renderer: 'canvas' });
      chart.setOption({ animation: false, grid: { left: 2, right: 2, top: 2, bottom: 2 }, xAxis: { type: 'category', show: false, data: tb.rows.map((r) => r[tb.at(axis)]) }, yAxis: { type: 'value', show: false, scale: true },
        series: [{ type: 'line', data: tb.rows.map((r) => r[tb.at(m)]), showSymbol: false, lineStyle: { color: ctx.palette[0], width: 2 }, areaStyle: { opacity: 0.15, color: ctx.palette[0] } }] });
      handle = { dispose() { chart.dispose(); }, resize() { chart.resize(); } };
    }
    return handle;
  }

  // ---- a rolling line of measures (live-chart) -----------------------------------------------------------------------

  /**
   * Each redraw adds one point per measure (the first row of the answer) and keeps the latest `format.window` (default 120): a line of how the numbers have
   * moved since the page was opened. The points live in the browser (`ctx.rolling[visual id]`); the server holds no history for it.
   */
  function liveChart(host, spec, ctx, res) {
    const tb = V.table(res);
    const f = spec.format || {};
    const limit = Math.max(5, Math.min(1000, Number(f.window) || 120));
    const store = ctx.rolling || (host.__rolling = host.__rolling || {});
    const key = ctx.rollingKey || spec.id;
    const buf = (store[key] = store[key] || { t: [], series: {} });
    const row = tb.rows[0] || [];
    const now = new Date();
    buf.t.push(now.toTimeString().slice(0, 8));
    (spec.wells.values || []).forEach((m) => {
      const v = row[tb.at(m)];
      (buf.series[m] = buf.series[m] || []).push(typeof v === 'number' ? v : null);
    });
    while (buf.t.length > limit) { buf.t.shift(); Object.keys(buf.series).forEach((k) => buf.series[k].shift()); }
    const t = ctx.t;
    const measures = spec.wells.values || [];
    const series = measures.map((m, i) => ({
      name: m, type: 'line', data: buf.series[m] || [], showSymbol: false, smooth: !!f.smooth, lineStyle: { color: ctx.palette[i % ctx.palette.length], width: 2 }, itemStyle: { color: ctx.palette[i % ctx.palette.length] }
    }));
    const option = baseOption(spec, ctx, {
      legend: measures.length > 1, extra: {
        xAxis: Object.assign({ type: 'category', data: buf.t, boundaryGap: false }, axisStyle(t)), yAxis: valueAxis(spec, ctx, t, measures[0]), series,
        tooltip: Object.assign(baseOption(spec, ctx, {}).tooltip, { trigger: 'axis', formatter: (p) => { const l = Array.isArray(p) ? p : [p]; return l[0].name + '\n' + l.map((x) => x.seriesName + ': ' + B.fmt(x.value, V.numFmt(spec, ctx, x.seriesName))).join('\n'); } })
      }
    });
    const first = measures[0];
    const marks = { field: null, items: [], byName: () => null };
    const handle = mount(host, option, Object.assign({}, spec, { alt: spec.alt || (spec.title + ': ' + B.fmt(buf.series[first][buf.series[first].length - 1], V.numFmt(spec, ctx, first), tb.isMasked(first))) }), ctx, marks);
    const box = host.querySelector(':scope > .bi-chart');
    if (box) { box.setAttribute('aria-label', (spec.alt || spec.title || 'Rolling chart') + ': ' + measures.map((m) => m + ' ' + B.fmt((buf.series[m] || []).slice(-1)[0], V.numFmt(spec, ctx, m), tb.isMasked(m))).join(', ')); }
    return handle;
  }

  // ---- funnel, histogram, box plot, bubble and map --------------------------------------------------------------------

  function funnel(host, spec, ctx, res) {
    const f = spec.format || {};
    const s = seriesOf(spec, ctx, res, []);
    if (!s.cats.length) { return emptyNote(host, NO_DATA); }
    const sr = s.series[0];
    const sel = selectedSet(ctx, spec);
    const data = s.labels.map((l, i) => ({ name: l, value: typeof sr.values[i] === 'number' ? sr.values[i] : 0, itemStyle: { opacity: sel && !sel.has(String(s.cats[i])) ? 0.35 : 1 } }));
    const t = ctx.t;
    const nf = V.numFmt(spec, ctx, sr.measure);
    const option = baseOption(spec, ctx, {
      legend: false, extra: {
        tooltip: Object.assign(baseOption(spec, ctx, {}).tooltip, { trigger: 'item', formatter: (p) => p.name + ': ' + B.fmt(p.value, nf, s.tb.isMasked(sr.measure)) }),
        series: [{ type: 'funnel', sort: 'descending', left: '4%', right: '4%', top: 8, bottom: 8, gap: 2, data,
          label: { show: f.labels !== false, color: t.ink, fontSize: 11, position: 'inside', formatter: (p) => p.name + '  ' + B.compact(p.value) }, itemStyle: { borderColor: t.surface, borderWidth: 1 } }]
      }
    });
    return mount(host, option, spec, ctx, marksFor(s.axis, s, ctx, spec));
  }

  /** A histogram arrives binned by the server (columns bin, count, from, to): bars of how many members fall in each band. */
  function histogram(host, spec, ctx, res) {
    const tb = V.table(res);
    const bi = tb.at('bin'), ci = tb.at('count');
    const measure = (spec.wells.values || [])[0];
    if (tb.isMasked('count') || (res.meta && (res.meta.masked || []).indexOf(measure) >= 0)) { return emptyNote(host, '•••'); }
    if (!tb.rows.length) { return emptyNote(host, NO_DATA); }
    const t = ctx.t;
    const f = spec.format || {};
    const fi = tb.at('from');
    const bands = tb.rows.map((r) => String(r[bi]));
    const labels = tb.rows.map((r) => (fi >= 0 && typeof r[fi] === 'number' ? B.compact(r[fi]) : String(r[bi])));          // the axis names where a bar starts; the tip says the whole band
    const option = baseOption(spec, ctx, {
      legend: false, extra: {
        xAxis: Object.assign({ type: 'category', data: labels, name: (f.axisTitles && f.axisTitles.x) || (measure ? measure + ' (where a bar starts)' : ''), nameLocation: 'middle', nameGap: 26, axisLabel: { color: t.muted, fontFamily: t.mono, fontSize: 10, hideOverlap: true } }, axisStyle(t), { axisLabel: { color: t.muted, fontFamily: t.mono, fontSize: 10, hideOverlap: true } }),
        yAxis: Object.assign({ type: 'value', name: (f.axisTitles && f.axisTitles.y) || 'count' }, axisStyle(t)),
        grid: { left: 8, right: 16, top: 24, bottom: 38, containLabel: true },
        tooltip: Object.assign(baseOption(spec, ctx, {}).tooltip, { trigger: 'axis', axisPointer: { type: 'shadow' }, formatter: (p) => { const x = Array.isArray(p) ? p[0] : p; return bands[x.dataIndex] + '\n' + B.compact(x.value) + ' in this band'; } }),
        series: [{ type: 'bar', data: tb.rows.map((r) => r[ci]), barCategoryGap: '4%', itemStyle: { color: ctx.palette[0] }, label: { show: !!f.labels, position: 'top', color: t.ink, fontSize: 10 } }]
      }
    });
    return mount(host, option, spec, ctx, { field: null, items: [], byName: () => null });
  }

  /** A box plot arrives summarised by the server (columns: the category, min, q1, median, q3, max, count, outliers). */
  function box(host, spec, ctx, res) {
    const tb = V.table(res);
    const measure = (spec.wells.values || [])[0];
    const field = (spec.wells.axis || [])[0] || null;
    if (res.meta && (res.meta.masked || []).indexOf(measure) >= 0) { return emptyNote(host, '•••'); }
    if (!tb.rows.length) { return emptyNote(host, NO_DATA); }
    const t = ctx.t;
    const cat = 0, nf = V.numFmt(spec, ctx, measure);
    const names = tb.rows.map((r) => String(r[cat]));
    const boxes = tb.rows.map((r) => [r[1], r[2], r[3], r[4], r[5]]);
    const outliers = [];
    tb.rows.forEach((r, i) => String(r[7] || '').split(',').map((x) => x.trim()).filter((x) => x !== '' && !isNaN(Number(x))).forEach((x) => outliers.push([i, Number(x)])));
    const sel = selectedSet(ctx, spec);
    const option = baseOption(spec, ctx, {
      legend: false, extra: {
        xAxis: Object.assign({ type: 'category', data: names, boundaryGap: true }, axisStyle(t)),
        yAxis: valueAxis(spec, ctx, t, measure),
        tooltip: Object.assign(baseOption(spec, ctx, {}).tooltip, { trigger: 'item', formatter: (p) => {
          if (p.seriesType === 'scatter') { return names[p.value[0]] + '\noutlier ' + B.fmt(p.value[1], nf); }
          const r = tb.rows[p.dataIndex];
          return String(r[cat]) + '\nmax ' + B.fmt(r[5], nf) + '\nQ3 ' + B.fmt(r[4], nf) + '\nmedian ' + B.fmt(r[3], nf) + '\nQ1 ' + B.fmt(r[2], nf) + '\nmin ' + B.fmt(r[1], nf) + '\nn ' + r[6];
        } }),
        series: [
          { type: 'boxplot', data: boxes.map((b, i) => ({ value: b, itemStyle: { opacity: sel && !sel.has(names[i]) ? 0.35 : 1 } })), itemStyle: { color: t['surface-2'] || t.surface, borderColor: ctx.palette[0], borderWidth: 2 }, boxWidth: ['20%', '60%'] },
          { type: 'scatter', data: outliers, symbolSize: 6, itemStyle: { color: ctx.palette[1] || ctx.palette[0] } }
        ]
      }
    });
    const items = names.map((n, i) => ({ value: tb.rows[i][cat], label: n, text: n + ': median ' + B.fmt(tb.rows[i][3], nf) + ', ' + B.fmt(tb.rows[i][1], nf) + ' to ' + B.fmt(tb.rows[i][5], nf) + ', ' + tb.rows[i][6] + ' members' }));
    return mount(host, option, spec, ctx, { field: field && tb.names[cat] === field ? field : null, items: field ? items : [], byName: (n) => (field ? items.find((q) => q.label === n) : null) });
  }

  // the map: countries, and below a country its regions; vendored shapes (no tiles, no CDN), loaded by geomap.js on first use
  // (a <drishti-report> element loads geomap.js from /embed/v1/js/ and the shapes from /embed/v1/ on the same server).
  // Drilling is the visual's own zoom: a click on a country selects it (the cross-filter, as ever) and opens its regions; the breadcrumb
  // "World > Australia" goes back. The level is kept per visual while the report is open.
  const ISO_REGION = /^([A-Za-z]{2})-[A-Za-z0-9]{1,3}$/;
  const mapLevel = {};                                    // visual id -> the iso3 of the country its map has opened; null: the world

  /** What a location value means: {c: the country, region: its upper-case ISO 3166-2 code or null}; null when no country matches. */
  function place(w, raw) {
    if (raw == null) { return null; }
    const G = window.drishtiGeo;
    const s = String(raw).trim();
    const m = ISO_REGION.exec(s);
    if (m) {
      const c = G.find(w, m[1]);
      if (c) { return { c, region: s.toUpperCase() }; }
    }
    const c = G.find(w, s);
    return c ? { c, region: null } : null;
  }

  function startLevel(w, spec) {
    const f = spec.format || {};
    if (!f.level || String(f.level).toLowerCase() === 'world') { return null; }
    const c = window.drishtiGeo.find(w, f.level);
    return c ? c.iso3 : null;
  }

  function drawMap(host, spec, ctx, res, w) {
    const G = window.drishtiGeo;
    const tb = V.table(res);
    const f = spec.format || {};
    const measure = (spec.wells.values || [])[0];
    const mi = tb.at(measure);
    const t = ctx.t;
    const nf = V.numFmt(spec, ctx, measure);
    const maskedValue = tb.isMasked(measure);
    host.querySelectorAll(':scope > .bi-map-note, :scope > .bi-map-crumb').forEach((n) => n.remove());
    if (!tb.rows.length) { return emptyNote(host, NO_DATA); }
    const loc = (spec.wells.location || [])[0];
    if (loc) { return drawPlaces(host, spec, ctx, tb, w, { loc, mi, nf, maskedValue, t, f }); }
    const lat = tb.at((spec.wells.latitude || [])[0]), lon = tb.at((spec.wells.longitude || [])[0]);
    const label = (spec.wells.details || [])[0];
    const di = label ? tb.at(label) : -1;
    const pts = tb.rows.filter((r) => typeof r[lat] === 'number' && typeof r[lon] === 'number');
    const mx = Math.max(1, ...pts.map((r) => Math.abs(Number(r[mi]) || 0)));
    const data = pts.map((r) => ({ name: di >= 0 ? String(r[di]) : r[lat] + ', ' + r[lon], value: [r[lon], r[lat], r[mi]], symbolSize: 7 + 22 * Math.sqrt(Math.abs(Number(r[mi]) || 0) / mx) }));
    const option = baseOption(spec, ctx, {
      legend: false, extra: {
        tooltip: Object.assign(baseOption(spec, ctx, {}).tooltip, { trigger: 'item', formatter: (p) => p.name + ': ' + B.fmt(p.value[2], nf, maskedValue) }),
        geo: { map: w.name, roam: false, left: 4, right: 4, top: 4, bottom: 4, silent: true, itemStyle: { areaColor: t['surface-2'] || t.surface, borderColor: t.border, borderWidth: 0.5 } },
        series: [{ type: 'scatter', coordinateSystem: 'geo', data, itemStyle: { color: ctx.palette[0], opacity: 0.8 } }]
      }
    });
    const items = data.map((d, i) => ({ value: di >= 0 ? pts[i][di] : d.name, label: d.name, text: d.name + ': ' + B.fmt(d.value[2], nf, maskedValue) }));
    return mount(host, option, spec, ctx, { field: label || null, items: label ? items : [], byName: (n) => (label ? items.find((x) => x.label === n) : null) });
  }

  /** The countries (or one country's regions) shaded by the measure; `o` carries the well names and formats. */
  function drawPlaces(host, spec, ctx, tb, w, o) {
    const G = window.drishtiGeo;
    const { loc, mi, nf, maskedValue, t, f } = o;
    const li = tb.at(loc);
    const drill = f.drill !== false;
    if (!(spec.id in mapLevel)) { mapLevel[spec.id] = startLevel(w, spec); }
    const level = mapLevel[spec.id];                       // an iso3, or null for the world
    const parsed = tb.rows.map((r) => ({ raw: r[li], p: place(w, r[li]), v: typeof r[mi] === 'number' ? r[mi] : null })).filter((x) => x.raw != null);
    const missing = parsed.filter((x) => !x.p).map((x) => String(x.raw));
    const sel = selectedSet(ctx, spec);
    let inner = { dispose() {}, resize() {} };
    let dead = false;
    const redraw = () => { inner.dispose(); inner = drawPlaces(host, spec, ctx, tb, w, o) || inner; };
    const crumb = (country) => {
      if (!drill && !country) { return; }
      const nav = h('nav', { class: 'bi-map-crumb', 'aria-label': 'Map level' });
      const world = h('button', { type: 'button', class: 'bi-vis-btn bi-map-up', 'data-map-up': '', text: 'World' });
      world.addEventListener('click', () => { mapLevel[spec.id] = null; redraw(); });
      if (!country) { nav.appendChild(h('span', { class: 'bi-map-here', 'aria-current': 'location', text: 'World' })); }
      else { nav.appendChild(world); nav.appendChild(h('span', { 'aria-hidden': 'true', text: ' › ' })); nav.appendChild(h('span', { class: 'bi-map-here', 'aria-current': 'location', text: country.name })); }
      host.insertBefore(nav, host.firstChild);
    };
    const finish = (handle) => {
      inner = handle || inner;
      if (missing.length) { host.appendChild(h('p', { class: 'bi-map-note', 'data-map-missing': [...new Set(missing)].join(','), text: 'Not on the map: ' + [...new Set(missing)].join(', ') + ' (a country or region code is expected).' })); }
    };
    const mk = (geo, lvl, items, marks) => {
      const keep = sel ? marks.items.filter((m) => m.value != null && sel.has(String(m.value))).map((m) => m.iso) : null;
      const g = G.option({ level: lvl, geo, t, items, clickable: items.map((i) => i.code), linked: !!ctx.interactive, numText: (v) => B.compact(v), selected: keep,
        openText: 'click to select' + (drill && lvl === 'world' ? ' and open its regions' : ''), closedText: 'no figure' });
      return mount(host, Object.assign(baseOption(spec, ctx, { legend: false }), g), spec, ctx, marks);
    };

    if (!level) {
      // the world: one figure per country (a country with only regional rows shows their sum), a dot for the countries the shapes are too small for
      const by = new Map();
      parsed.filter((x) => x.p).forEach((x) => {
        const k = x.p.c.iso3;
        const e = by.get(k) || { c: x.p.c, v: null, raw: null, regional: true };
        if (typeof x.v === 'number') { e.v = (e.v || 0) + x.v; }
        if (!x.p.region && e.raw == null) { e.raw = x.raw; e.regional = false; }
        by.set(k, e);
      });
      const items = [], list = [];
      by.forEach((e, k) => {
        items.push({ code: k, value: e.v, text: B.fmt(e.v, nf, maskedValue) });
        list.push({ value: e.raw, label: e.c.name, iso: k, text: e.c.name + ': ' + B.fmt(e.v, nf, maskedValue) + (e.regional ? ' (all regions)' : '') });
      });
      const marks = { field: loc, items: list, byName: (n) => list.find((x) => x.iso === n) };
      marks.pick = (mark) => {
        if (!drill || !mark) { return false; }
        mapLevel[spec.id] = mark.iso;
        const selects = mark.value != null && ctx.interactive && ctx.onMark;          // a selection redraws the visual itself; otherwise redraw here
        if (!selects) { redraw(); }
        return !selects;
      };
      finish(mk(w, 'world', items, marks));
      crumb(null);
      return { dispose() { dead = true; inner.dispose(); }, resize() { inner.resize(); } };
    }

    // one country's regions: a figure per region code; a click selects the region
    const country = w.by.iso3[level];
    B.clear(host).appendChild(h('p', { class: 'bi-vis-empty', role: 'status', text: 'Loading the regions of ' + (country ? country.name : level) + '…' }));
    G.region(level).then((geo) => {
      if (dead) { return; }
      B.clear(host);
      const seen = new Map();
      parsed.filter((x) => x.p && x.p.c.iso3 === level && x.p.region).forEach((x) => {
        const code = x.p.region;
        if (!geo.by.code[code]) { missing.push(String(x.raw)); return; }
        const e = seen.get(code) || { v: null, raw: x.raw };
        if (typeof x.v === 'number') { e.v = (e.v || 0) + x.v; }
        seen.set(code, e);
      });
      const items = [], list = [];
      seen.forEach((e, code) => {
        const name = geo.by.code[code].name;
        items.push({ code, value: e.v, text: B.fmt(e.v, nf, maskedValue) });
        list.push({ value: e.raw, label: name, iso: code, text: name + ': ' + B.fmt(e.v, nf, maskedValue) });
      });
      finish(mk(geo, 'region', items, { field: loc, items: list, byName: (n) => list.find((x) => x.iso === n) }));
      crumb(country);
      if (!items.length) { host.appendChild(h('p', { class: 'bi-map-note', 'data-map-noregions': level, text: 'No figures at region level for ' + (country ? country.name : level) + ': the location field holds countries, not their regions.' })); }
    }).catch(() => {
      if (dead) { return; }
      mapLevel[spec.id] = null;                             // a country with no region file: back to the world, and say so
      redraw();
      host.appendChild(h('p', { class: 'bi-map-note', 'data-map-noregions': level, text: 'No regions are available for ' + (country ? country.name : level) + '.' }));
    });
    return { dispose() { dead = true; inner.dispose(); }, resize() { inner.resize(); } };
  }

  function map(host, spec, ctx, res) {
    if (!window.echarts) { return emptyNote(host, 'The chart library did not load.'); }
    if (!window.drishtiGeo) { return emptyNote(host, 'The map data did not load.'); }
    B.clear(host).appendChild(h('p', { class: 'bi-vis-empty', role: 'status', text: 'Loading the map…' }));
    let inner = { dispose() {}, resize() {} };
    let dead = false;
    window.drishtiGeo.world().then((w) => { if (!dead) { inner = drawMap(host, spec, ctx, res, w) || inner; } })
      .catch(() => { if (!dead) { emptyNote(host, 'The map data did not load.'); } });
    return { dispose() { dead = true; inner.dispose(); }, resize() { inner.resize(); } };
  }

  const splitOr = (host, spec, ctx, res) => ((spec.wells.split || []).length && V.multiples ? V.multiples(host, spec, ctx, res) : categoryChart(host, spec, ctx, res));      // small multiples (2.4.0)
  const CHARTS = { 'live-chart': liveChart, bar: splitOr, column: splitOr, line: splitOr, area: splitOr, combo: categoryChart, pie: pieChart, donut: pieChart, waterfall, treemap, heatmap, scatter,
    bubble: scatter, funnel, histogram, box, map, gauge };
  const CARDS = { card, 'multirow-card': multirow, kpi };

  /** Draws the visual if it is a chart or a card; returns the handle, or null when the type is not one of these (bi-grids.js draws the rest). */
  V.drawChartOrCard = function (host, spec, ctx, res) {
    const fn = CHARTS[spec.type] || CARDS[spec.type];
    return fn ? fn(host, spec, ctx, res) : null;
  };
  V.isChart = (type) => !!CHARTS[type];

  /**
   * The data-as-of line of an answer (2.6.7 patch): each table reads its latest date on or before the asked one, so a fact and a dimension
   * may stand at different dates. One date: "Data as of D", with "(asked A: no later data)" when the asked date A is later. Several:
   * "Data as of: trades D1, prices D2 (asked A)". meta: the answer's meta ({dataAsOf: {asked, tables}, dates}); asked: the page's date,
   * used when the answer does not say it. Empty when nothing dated was read.
   */
  V.asOfLine = function (meta, asked) {
    const m = meta || {};
    const da = m.dataAsOf || {};
    const tables = da.tables || {};
    const ask = da.asked || asked || '';
    const days = Array.from(new Set(Object.values(tables))).sort();
    if (days.length > 1) {
      const each = Object.keys(tables).sort().map((t) => t + ' ' + tables[t]).join(', ');
      return 'Data as of: ' + each + (ask ? ' (asked ' + ask + ')' : '');
    }
    const ds = m.dates || [];
    const day = days[0] || ds[ds.length - 1];
    if (!day) { return ''; }
    return 'Data as of ' + day + (ask && ask > day ? ' (asked ' + ask + ': no later data)' : '');
  };
})();
