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
/* Rupaka phase 6 (docs/guides/BI_GUIDE.md): the python visual of a report. The rows are the ones the person looking may see; the Python item that turns them
   into a number per group or a chart runs on the console's sandboxed runner (POST /bi/python/run: a fork-server worker with no network, a scratch folder,
   CPU, memory, time and output limits). Wave B (docs/architecture/BI_DESIGN.md principle P3): the browser computes nothing, so there is no browser target any
   more; a manifest or a visual that still says `browser` or `both` runs on the server. What comes back is data: a measure's rows (drawn here as bars), an
   ECharts option (drawn with the vendored ECharts, formatter strings removed), a Vega-Lite specification, an SVG or a PNG (shown as an image, which runs no
   script). Never HTML. */
(function () {
  'use strict';
  const B = (window.drishtiBi = window.drishtiBi || {});
  const h = B.h;
  const P = (B.python = B.python || {});
  const DROP = ['formatter', 'extraCssText', 'appendToBody', 'valueFormatter'];

  const failure = (code, detail, traceback) => Object.assign(new Error(code + ' ' + detail), { code, detail, traceback: traceback || '' });

  /** The console's own JSON route (not the /bi/api proxy). */
  function post(path, body) {
    return fetch(path, { method: 'POST', headers: { 'Content-Type': 'application/json', Accept: 'application/json' }, credentials: 'same-origin', body: JSON.stringify(body) })
      .then((r) => r.json().catch(() => ({})).then((data) => {
        if (!r.ok) { throw failure(data.code || 'DRS-9071', data.detail || r.statusText, data.traceback); }
        return data;
      }));
  }

  /** The answer for one python visual: `{kind, ..., ms, target, item, version, rows}`, worked out by the console. `args` is {report, page, state, visual}. */
  P.run = function (spec, args) {
    return post('/bi/python/run', { report: args.report, page: args.page, visual: spec.id, state: args.state });
  };

  // ---- drawing -----------------------------------------------------------------------------------------------------------------
  /** Drops the keys that could carry markup or code (the server's worker does the same; a browser run has no such step). */
  function clean(o) {
    if (Array.isArray(o)) { return o.map(clean); }
    if (o && typeof o === 'object') {
      const out = {};
      Object.keys(o).forEach((k) => { if (DROP.indexOf(k) < 0) { out[k] = clean(o[k]); } });
      return out;
    }
    return o;
  }

  /** Gives what the Python option left unsaid the theme's colours (text, axes, grid lines), so a chart is readable in the dark and the light theme alike. */
  function themed(option, t) {
    if (!t || !t.ink) { return option; }
    const each = (v, fn) => { (Array.isArray(v) ? v : v ? [v] : []).forEach(fn); };
    const text = (o, key, color) => { o[key] = o[key] || {}; if (!o[key].color) { o[key].color = color; } };
    each(option.title, (x) => text(x, 'textStyle', t.ink));
    each(option.legend, (x) => text(x, 'textStyle', t.muted));
    ['xAxis', 'yAxis'].forEach((k) => each(option[k], (a) => {
      text(a, 'axisLabel', t.muted); text(a, 'nameTextStyle', t.muted);
      a.axisLine = a.axisLine || {}; a.axisLine.lineStyle = Object.assign({ color: t.border }, a.axisLine.lineStyle);
      a.splitLine = a.splitLine || {}; a.splitLine.lineStyle = Object.assign({ color: t.border, opacity: 0.5 }, a.splitLine.lineStyle);
    }));
    option.textStyle = Object.assign({ color: t.muted }, option.textStyle);
    return option;
  }

  function chart(host, option, spec, t) {
    if (!window.echarts) { return note(host, 'The chart library did not load.'); }
    B.clear(host);
    const box = h('div', { class: 'bi-chart bi-py-chart', role: 'img', 'aria-label': spec.alt || spec.title || 'Python chart', 'data-py-kind': 'echarts' });
    host.appendChild(box);
    const c = window.echarts.init(box, null, { renderer: 'canvas' });
    c.setOption(themed(Object.assign({ backgroundColor: 'transparent', animation: false }, clean(option)), t));
    box.setAttribute('data-ready', '1');
    return { dispose() { c.dispose(); }, resize() { c.resize(); } };
  }

  function note(host, text) {
    B.clear(host).appendChild(h('p', { class: 'bi-vis-empty', role: 'status', text }));
    return { dispose() {}, resize() {} };
  }

  function image(host, src, spec, kind) {
    B.clear(host);
    host.appendChild(h('img', { class: 'bi-py-img', src, alt: spec.alt || spec.title || 'Python chart', 'data-py-kind': kind }));
    return { dispose() {}, resize() {} };
  }

  /** A measure's rows as bars: one bar per group (the groups joined by " / "), in the theme's first colour. */
  function bars(host, res, spec, ctx) {
    const by = res.by || [];
    const names = res.rows.map((r) => by.length ? r.slice(0, by.length).map((v) => (v == null ? '(blank)' : String(v))).join(' / ') : (res.measure || spec.title || 'value'));
    const values = res.rows.map((r) => r[r.length - 1]);
    const t = ctx.t || {};
    const axis = { axisLine: { lineStyle: { color: t.border } }, axisLabel: { color: t.muted, fontSize: 10 }, nameTextStyle: { color: t.muted, fontSize: 10 }, splitLine: { lineStyle: { color: t.border, opacity: 0.5 } } };
    const label = res.measure || spec.title || 'value';
    return chart(host, {
      grid: { left: 56, right: 16, top: 24, bottom: 36 }, tooltip: { trigger: 'axis' },
      xAxis: Object.assign({ type: 'category', data: names, name: by.join(' / ') }, axis), yAxis: Object.assign({ type: 'value', name: label }, axis),
      series: [{ type: 'bar', name: label, data: values, itemStyle: { color: (ctx.palette && ctx.palette[0]) || '#4f7cff' } }]
    }, spec, t);
  }

  /** Draws an answer of P.run in a frame body; returns the handle the frame disposes. */
  P.draw = function (host, spec, ctx, res) {
    if (res.kind === 'measure') { return bars(host, res, spec, ctx); }
    if (res.kind === 'echarts') { return chart(host, res.option, spec, ctx.t); }
    if (res.kind === 'vega-lite') { return B.vega ? B.vega.drawSpec(host, res.spec, spec, ctx) : note(host, 'The Vega-Lite script did not load.'); }
    if (res.kind === 'svg') { return image(host, 'data:image/svg+xml;charset=utf-8,' + encodeURIComponent(res.svg), spec, 'svg'); }
    if (res.kind === 'png') { return image(host, 'data:image/png;base64,' + res.png, spec, 'png'); }
    return note(host, 'The Python item returned something this page cannot draw.');
  };

  /** The line under a python visual: which item, version, where it ran and how long it took. */
  P.footer = function (foot, res) {
    B.clear(foot);
    foot.appendChild(h('span', { class: 'bi-py-foot', 'data-target': res.target, 'data-ms': String(res.ms), text: 'Python · ' + (res.item || '').replace(/^file:python:/, '') + (res.version ? ' v' + res.version : '') + ' · ' + res.target + ' · ' + res.ms + ' ms · ' + res.inputRows + ' rows' }));
    if (res.masked && res.masked.length) { foot.appendChild(h('span', { class: 'bi-vis-masked', title: 'Masked for you: ' + res.masked.join(', '), text: ' · masked' })); }
  };
})();
