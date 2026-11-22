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
/* Rupaka phase 6 (docs/guides/bi/README.md): the python visual of a report. The rows are the ones the person looking may see; the Python item that turns them
   into a number per group or a chart runs on the server's compute pool (POST /bi/python/run: a strict sandbox with no network, its own folder,
   CPU, memory, time and output limits). Wave B (docs/architecture/ARCHITECTURE.md §28 principle P3): the browser computes nothing, so there is no browser target any
   more; a manifest or a visual that still says `browser` or `both` runs on the server. What comes back is data: a measure's rows (drawn here as bars), a
   drishti-chart spec (the preferred form, drawn by drishti-chart), an ECharts option (drawn by drishti-chart's report part, formatter strings removed), a Vega-Lite
   specification (translated and drawn by drishti-chart, or refused in words), an SVG or a PNG (shown as an image, which runs no script). Never HTML. */
(function () {
  'use strict';
  const B = (window.drishtiBi = window.drishtiBi || {});
  const h = B.h;
  const P = (B.python = B.python || {});

  const failure = (code, detail, traceback) => Object.assign(new Error(code + ' ' + detail), { code, detail, traceback: traceback || '' });

  /** The console's own JSON route (not the /bi/api proxy). */
  function post(path, body) {
    return fetch(drishti_url(path), { method: 'POST', headers: { 'Content-Type': 'application/json', Accept: 'application/json' }, credentials: 'same-origin', body: JSON.stringify(body) })
      .then((r) => r.json().catch(() => ({})).then((data) => {
        if (!r.ok) { throw failure(data.code || 'DRS-9071', data.detail || r.statusText, data.traceback); }
        return data;
      }));
  }

  /** The answer for one python visual: `{kind, ..., ms, target, item, version, rows}`, worked out by the console. `args` is {report, page, state, visual}. */
  P.run = function (spec, args) {
    return post(drishti_url('/bi/python/run'), { report: args.report, page: args.page, visual: spec.id, state: args.state });
  };

  // ---- drawing -----------------------------------------------------------------------------------------------------------------
  const R = () => window.Drishti.elements && window.Drishti.elements.chart_report;   // drishti-chart's report part draws every chart here

  /** An ECharts option the item returned, made safe (formatter strings and the like removed) and given the theme's colours by drishti-chart. */
  function chart(host, option, spec, t) {
    if (!R()) { return note(host, 'The chart library did not load.'); }
    return R().drawOption(host, R().pythonOption(option, t), { cls: 'bi-chart bi-py-chart', label: spec.alt || spec.title || 'Python chart', attrs: { 'data-py-kind': 'echarts' } });
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
    if (!R()) { return note(host, 'The chart library did not load.'); }
    return chart(host, R().measureBarsOption(res, spec.title, ctx.t || {}, ctx.palette && ctx.palette[0]), spec, ctx.t || {});
  }

  /** Draws an answer of P.run in a frame body; returns the handle the frame disposes. */
  P.draw = function (host, spec, ctx, res) {
    if (res.kind === 'measure') { return bars(host, res, spec, ctx); }
    if (res.kind === 'echarts') { return chart(host, res.option, spec, ctx.t); }
    if (res.kind === 'drishti-chart') { return B.visuals.drishtiChart(host, res.spec, spec); }
    if (res.kind === 'vega-lite') { return B.visuals.vegaLite(host, res.spec, spec); }
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
