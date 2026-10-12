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
/* Rupaka phase 6 (docs/guides/PYTHON_BI.md): the python visual of a report. The rows are the ones the person looking may see; the Python item that turns them
   into a number per group or a chart runs in one of two places:

     server    the console's sandboxed worker (POST /bi/python/run: no network, a scratch folder, CPU, memory, time and output limits);
     browser   Pyodide (the runtime Calc ships) in a Web Worker of this page, loaded the first time it is needed, on the rows this page already received
               (the JSON of the visual's own query). The worker is Calc's (calc-worker.js), which has the narrow 'wasm-unsafe-eval' policy of its own response;
               this page keeps the strict policy.

   format.run in the report says which (server, browser, auto). auto asks the server first and falls back to the browser when the server cannot run the
   item (no runner, or the item is for the browser only) and the browser can. What comes back is data: a measure's rows (drawn here as bars), an ECharts
   option (drawn with the vendored ECharts, formatter strings removed), an SVG or a PNG (shown as an image, which runs no script). Never HTML. */
(function () {
  'use strict';
  const B = (window.drishtiBi = window.drishtiBi || {});
  const h = B.h;
  const root = document.getElementById('biCanvas');
  const P = (B.python = B.python || {});
  const RUNTIME = (root && root.getAttribute('data-py-runtime')) || '';
  const WORKER = (root && root.getAttribute('data-py-worker')) || '';
  const MODULE = (root && root.getAttribute('data-py-module')) || '';
  const DRIVER = (root && root.getAttribute('data-py-driver')) || '';
  const AUTHOR = !!root && root.getAttribute('data-author') === 'true';
  const MARK = '\u0001BIRESULT';
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

  // ---- the browser target ----------------------------------------------------------------------------------------------------
  let worker = null;
  let ready = null;
  let chain = Promise.resolve();
  let driver = null;

  P.browserReady = () => !!RUNTIME && !!WORKER && typeof WebAssembly === 'object' && typeof Worker === 'function';

  function boot() {
    if (ready) { return ready; }
    ready = new Promise((resolve, reject) => {
      worker = new Worker(WORKER, { type: 'module', name: 'drishti-bi-python' });
      worker.onmessage = (e) => {
        const m = e.data || {};
        if (m.type === 'ready') { resolve(m); } else if (m.type === 'failed') { reject(failure('DRS-9074', 'The browser Python could not start: ' + m.error)); }
      };
      worker.onerror = (e) => reject(failure('DRS-9074', 'The browser Python could not start: ' + (e.message || 'worker error')));
      worker.postMessage({ type: 'init', base: RUNTIME, module: MODULE });
    });
    ready.catch(() => { ready = null; worker = null; });
    return ready;
  }
  P.stopWorker = () => { if (worker) { worker.terminate(); } worker = null; ready = null; };

  function loadDriver() {
    if (!driver) { driver = fetch(DRIVER, { credentials: 'same-origin' }).then((r) => { if (!r.ok) { throw failure('DRS-9074', 'The browser driver did not load'); } return r.text(); }); }
    return driver;
  }

  const b64 = (obj) => {
    const bytes = new TextEncoder().encode(JSON.stringify(obj));
    let s = '';
    for (let i = 0; i < bytes.length; i += 0x8000) { s += String.fromCharCode.apply(null, bytes.subarray(i, i + 0x8000)); }
    return btoa(s);
  };

  /** Runs `payload` ({columns, rows, source, entry, interface, inputs, by, params}) in Pyodide; serialised, one run at a time (the worker has one interpreter). */
  function runInBrowser(manifest, payload) {
    const job = chain.then(() => Promise.all([boot(), loadDriver()]).then(([, drv]) => new Promise((resolve, reject) => {
      const id = 'bi' + Date.now() + Math.random().toString(16).slice(2);
      let out = '';
      const limit = Math.max(1, manifest.timeout || 10) * 1000 + 15000;                         // a first run also loads numpy and pandas
      const timer = setTimeout(() => { P.stopWorker(); reject(failure('DRS-9072', "'" + manifest.name + "' ran longer than its " + (manifest.timeout || 10) + ' s limit in the browser and was stopped')); }, limit);
      const prior = worker.onmessage;
      worker.onmessage = (e) => {
        const m = e.data || {};
        if (m.type === 'stdout' && m.id === id) { out += m.text; } else if (m.type === 'done' && m.id === id) {
          clearTimeout(timer);
          worker.onmessage = prior;
          const at = out.indexOf(MARK);
          if (m.ok === false) { reject(failure('DRS-9071', m.error || 'the Python item failed', AUTHOR ? (m.traceback || '') : '')); } else if (at < 0) { reject(failure('DRS-9071', 'the Python item returned nothing')); } else { resolve(JSON.parse(out.slice(at + MARK.length).split('\n')[0])); }
        }
      };
      const imports = (manifest.packages || []).filter((p) => /^[A-Za-z_][A-Za-z0-9_]*$/.test(p)).map((p) => 'import ' + p + '\n').join('');
      worker.postMessage({ type: 'run', id, code: imports + drv + "\n_bi_main('" + b64(payload) + "')\n", context: '{}' });
    })));
    chain = job.catch(() => {});
    return job;
  }

  /** A notebook cell of Analyse (ANALYSE.md) in Pyodide: `ex` is the extract as the console sent it ({columns[{name}], rows}); no report involved. */
  P.runCell = function (source, ex, params) {
    if (!P.browserReady()) { return Promise.reject(failure('DRS-9074', 'The browser Python runtime is not installed on this console (tools/fetch-pyodide.sh), so cells can run on the server only')); }
    const packages = ['pandas'].concat(['numpy', 'scipy', 'statsmodels'].filter((p) => new RegExp('^\\s*(import|from)\\s+' + p + '\\b', 'm').test(source)));
    const payload = { columns: ex.columns.map((c) => c.name), rows: ex.rows, source, interface: 'cell', params: params || {}, entry: '', inputs: [], by: null, output: '' };
    const t0 = performance.now();
    return runInBrowser({ name: 'analyse-cell', timeout: 30, packages }, payload).then((out) => Object.assign(out, { ms: Math.round(performance.now() - t0), target: 'browser', inputRows: ex.rows.length }));
  };

  // ---- asking ------------------------------------------------------------------------------------------------------------------
  const wantsBrowser = (m) => m.target === 'browser' || m.target === 'both';

  /** The answer for one python visual: `{kind, ..., ms, target, item, version, rows}`. `args` is {report, page, state, visual}. */
  P.run = function (spec, args) {
    const mode = (spec.format && spec.format.run) || 'auto';
    const server = () => post('/bi/python/run', { report: args.report, page: args.page, visual: spec.id, state: args.state });
    const browser = () => {
      const path = '/reports/' + B.id2path(args.report);
      return B.enqueue(() => B.api('POST', path + '/python', { page: args.page, visual: spec.id })).then((desc) => {
        if (!P.browserReady()) { throw failure('DRS-9074', 'The browser Python runtime is not installed on this console (tools/fetch-pyodide.sh), so this visual cannot run in the browser'); }
        if (!wantsBrowser(desc.manifest)) { throw failure('DRS-9070', "'" + desc.manifest.name + "' is for the server only (target: server)"); }
        return B.enqueue(() => B.api('POST', path + '/query', { page: args.page, visual: spec.id, state: args.state })).then((res) => {
          const t0 = performance.now();
          const m = desc.measure;
          const short = (c) => String(c).slice(String(c).indexOf('.') + 1);
          const payload = {
            columns: res.columns.map((c) => c.name), rows: res.rows, source: desc.manifest.source, entry: desc.manifest.entry, interface: desc.manifest.interface,
            inputs: m ? (m.inputs || []).map(short) : desc.manifest.inputs, by: m ? (m.by || []).map(short) : null, params: Object.assign({}, desc.manifest.params, desc.params || {}), output: desc.manifest.output
          };
          return runInBrowser(desc.manifest, payload).then((out) => Object.assign(out, {
            ms: Math.round(performance.now() - t0), target: 'browser', item: desc.id, version: desc.version, measure: m ? m.name : null, inputRows: res.rowCount, masked: (res.meta && res.meta.masked) || []
          }));
        });
      });
    };
    if (mode === 'browser') { return browser(); }
    if (mode === 'server') { return server(); }
    return server().catch((e) => {
      const fallback = e.code === 'DRS-9074' || (e.code === 'DRS-9070' && /browser only/.test(e.detail || ''));
      if (fallback && P.browserReady()) { return browser(); }
      throw e;
    });
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
