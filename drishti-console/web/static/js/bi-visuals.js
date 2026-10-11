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
/* Rupaka phase 3 (RUPAKA.md section 8): the charts and cards of a report, drawn from one visual's query result. Charts are ECharts (vendored), cards are
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

  V.formatOf = (ctx, name) => (ctx.index.measures[name] && ctx.index.measures[name].format) || (ctx.index.columns[name] && ctx.index.columns[name].format) || null;
  V.numFmt = (spec, ctx, name) => (spec.format && spec.format.numberFormat) || V.formatOf(ctx, name);

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

  /** Mounts an ECharts instance in `host` and wires click and the keyboard. `marks` is [{value, label, text}] in the order of the first axis. */
  function mount(host, option, spec, ctx, marks) {
    if (!window.echarts) { return emptyNote(host, 'The chart library did not load.'); }
    B.clear(host);
    const box = h('div', { class: 'bi-chart', tabindex: ctx.interactive ? '0' : '-1', role: 'img', 'aria-label': spec.alt || spec.title || spec.type });
    host.appendChild(box);
    const chart = window.echarts.init(box, null, { renderer: 'canvas' });
    chart.setOption(option);
    box.__chart = chart;
    let cursor = -1;
    const live = h('span', { class: 'visually-hidden', role: 'status', 'aria-live': 'polite' });
    host.appendChild(live);
    const openMark = (m, x, y) => { if (ctx.interactive && ctx.onMark && m) { ctx.onMark({ visual: spec.id, field: marks.field, value: m.value, label: m.label }, { x, y, el: box }); } };
    chart.on('click', (p) => {
      let m = p.name ? marks.byName(p.name) : null;
      if (!m) { m = marks.items[p.seriesType === 'heatmap' || p.seriesType === 'custom' ? p.value[0] : p.dataIndex]; }
      const ev = p.event && p.event.event;
      openMark(m, ev ? ev.clientX : 0, ev ? ev.clientY : 0);
    });
    box.addEventListener('keydown', (e) => {
      if (!ctx.interactive || !marks.items.length) { return; }
      if (e.key === 'ArrowRight' || e.key === 'ArrowDown' || e.key === 'ArrowLeft' || e.key === 'ArrowUp') {
        e.preventDefault();
        const step = e.key === 'ArrowRight' || e.key === 'ArrowDown' ? 1 : -1;
        if (cursor >= 0) { chart.dispatchAction({ type: 'downplay', seriesIndex: 0, dataIndex: cursor }); }
        cursor = (cursor + step + marks.items.length) % marks.items.length;
        chart.dispatchAction({ type: 'highlight', seriesIndex: 0, dataIndex: cursor });
        live.textContent = marks.items[cursor].text;
      } else if ((e.key === 'Enter' || e.key === ' ') && cursor >= 0) {
        e.preventDefault();
        const r = box.getBoundingClientRect();
        openMark(marks.items[cursor], r.left + r.width / 2, r.top + r.height / 2);
      } else if (e.key === 'Escape') { cursor = -1; }
    });
    return { dispose() { chart.dispose(); }, resize() { chart.resize(); } };
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
    const s = seriesOf(spec, ctx, res, type === 'combo' ? spec.wells.secondary || [] : []);
    if (!s.cats.length) { return emptyNote(host, 'No data for this selection.'); }
    const sel = selectedSet(ctx, spec);
    const hl = res.highlight ? seriesOf(spec, ctx, res.highlight, []) : null;
    const dimmed = !!(sel || hl);
    const fixed = typeof f.colour === 'string' && /^#[0-9a-fA-F]{6}$/.test(f.colour) ? f.colour : null;
    const count = s.series.length;
    const secondary = type === 'combo' ? new Set(spec.wells.secondary || []) : new Set();
    const series = [];
    s.series.forEach((sr, si) => {
      const isLine = type === 'line' || type === 'area' || secondary.has(sr.measure);
      const base = fixed && count === 1 ? fixed : ctx.palette[si % ctx.palette.length];
      const data = sr.values.map((v, i) => {
        const cond = V.conditional(spec, sr.measure, v);
        const faded = (sel && !sel.has(String(s.cats[i]))) || (hl && !isLine);
        return { value: v, itemStyle: { color: cond || base, opacity: faded ? 0.35 : 1 } };
      });
      series.push({
        name: sr.name, type: isLine ? 'line' : 'bar', data, yAxisIndex: secondary.has(sr.measure) ? 1 : 0, smooth: !!f.smooth, symbol: 'circle', symbolSize: 5,
        areaStyle: type === 'area' ? { opacity: 0.25 } : undefined, stack: f.stacked && !isLine ? 'total' : undefined, lineStyle: { color: base, width: 2 }, emphasis: { focus: 'none' },
        label: { show: !!f.labels, position: horizontal ? 'right' : isLine ? 'top' : 'top', color: t.ink, fontSize: 10, formatter: (p) => B.compact(p.value) },
        barMaxWidth: 36
      });
      if (hl && !isLine) {
        const hs = hl.series[si] || { values: [] };
        const vals = s.cats.map((c) => { const i = hl.seen.get(String(c)); return i == null || !hs.values ? null : hs.values[i]; });
        series.push({ name: '__highlight', type: 'bar', data: vals.map((v) => ({ value: v, itemStyle: { color: base } })), barGap: '-100%', barMaxWidth: 36, silent: true, z: 5, tooltip: { show: false } });
      }
    });
    const cat = Object.assign({ type: 'category', data: s.labels, inverse: horizontal, name: (f.axisTitles && f.axisTitles.x) || '' }, axisStyle(t));
    const val = valueAxis(spec, ctx, t, s.series[0].measure);
    const axes = horizontal ? { xAxis: val, yAxis: cat } : { xAxis: cat, yAxis: type === 'combo' && secondary.size ? [val, valueAxis(spec, ctx, t, [...secondary][0])] : val };
    const legendShown = !!(count > 1 || (spec.wells.legend || []).length) && f.legend !== 'none' && f.legend !== false;
    const option = baseOption(spec, ctx, { horizontal, legend: legendShown, extra: Object.assign(axes, { series, tooltip: Object.assign(baseOption(spec, ctx, {}).tooltip, { trigger: 'axis', axisPointer: { type: 'shadow' }, formatter: tip(spec, ctx, s) }) }) });
    if (legendShown) { option.legend = Object.assign({}, option.legend, f.legend === 'right' ? { right: 0, top: 'middle', orient: 'vertical', bottom: undefined } : f.legend === 'top' ? { top: 0, bottom: undefined } : {}); if (f.legend === 'top') { option.grid.top = 28; option.grid.bottom = 8; } }
    const marks = marksFor(s.axis, s, ctx, spec);
    return mount(host, option, spec, ctx, marks);
  }

  function pieChart(host, spec, ctx, res) {
    const f = spec.format || {};
    const s = seriesOf(spec, ctx, res, []);
    if (!s.cats.length) { return emptyNote(host, 'No data for this selection.'); }
    const sel = selectedSet(ctx, spec);
    const sr = s.series[0];
    const total = sr.values.reduce((a, v) => a + (typeof v === 'number' ? Math.max(v, 0) : 0), 0);
    const data = s.labels.map((l, i) => ({ name: l, value: sr.values[i], itemStyle: { opacity: sel && !sel.has(String(s.cats[i])) ? 0.35 : 1 } }));
    const option = baseOption(spec, ctx, {
      legend: f.legend !== 'none', extra: {
        tooltip: Object.assign(baseOption(spec, ctx, {}).tooltip, { trigger: 'item', formatter: (p) => p.name + ': ' + B.fmt(p.value, V.numFmt(spec, ctx, sr.measure), s.tb.isMasked(sr.measure)) + ' (' + (total ? Math.round(100 * p.value / total) : 0) + '%)' }),
        series: [{ type: 'pie', radius: spec.type === 'donut' ? ['45%', '70%'] : [0, '70%'], center: ['50%', '45%'], data, label: { show: !!f.labels, color: ctx.t.ink, fontSize: 10, formatter: '{b}: {d}%' }, itemStyle: { borderColor: ctx.t.surface, borderWidth: 1 } }]
      }
    });
    return mount(host, option, spec, ctx, marksFor(s.axis, s, ctx, spec));
  }

  function waterfall(host, spec, ctx, res) {
    const f = spec.format || {};
    const t = ctx.t;
    const s = seriesOf(spec, ctx, res, []);
    if (!s.cats.length) { return emptyNote(host, 'No data for this selection.'); }
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
    if (!tb.rows.length) { return emptyNote(host, 'No data for this selection.'); }
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
    if (!tb.rows.length) { return emptyNote(host, 'No data for this selection.'); }
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
    if (!tb.rows.length) { return emptyNote(host, 'No data for this selection.'); }
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
    const colour = V.conditional(spec, m, v);
    const big = h('div', { class: 'bi-card-value', 'data-value': v == null ? '' : String(v), text: B.fmt(v, V.numFmt(spec, ctx, m), tb.isMasked(m)) });
    if (colour) { big.style.color = colour; }
    B.clear(host).appendChild(h('div', { class: 'bi-card-body' }, big, spec.title ? null : h('div', { class: 'bi-card-label', text: m })));
    return { dispose() {}, resize() {} };
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
    const colour = V.conditional(spec, m, v);
    if (colour) { value.style.color = colour; }
    body.appendChild(value);
    if (!spec.title) { body.appendChild(h('div', { class: 'bi-card-label', text: m })); }
    if (typeof v === 'number' && typeof target === 'number' && target !== 0) {
      const pct = (v - target) / Math.abs(target);
      body.appendChild(h('div', { class: 'bi-kpi-target ' + (pct >= 0 ? 'bi-up' : 'bi-down'), text: (pct >= 0 ? '▲ ' : '▼ ') + Math.abs(Math.round(pct * 1000) / 10) + '% vs target ' + B.fmt(target, nf) }));
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

  const CHARTS = { bar: categoryChart, column: categoryChart, line: categoryChart, area: categoryChart, combo: categoryChart, pie: pieChart, donut: pieChart, waterfall, treemap, heatmap, scatter, gauge };
  const CARDS = { card, 'multirow-card': multirow, kpi };

  /** Draws the visual if it is a chart or a card; returns the handle, or null when the type is not one of these (bi-grids.js draws the rest). */
  V.drawChartOrCard = function (host, spec, ctx, res) {
    const fn = CHARTS[spec.type] || CARDS[spec.type];
    return fn ? fn(host, spec, ctx, res) : null;
  };
  V.isChart = (type) => !!CHARTS[type];
})();
