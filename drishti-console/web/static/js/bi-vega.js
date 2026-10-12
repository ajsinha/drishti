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
/* Wave C (docs/guides/REPORTS.md "Vega-Lite visuals", PYTHON_BI.md "Vega-Lite output"): draws a Vega-Lite specification in a frame.

   Two sources, one drawing path:
     * a report visual of `type: vega-lite`: the specification is in the visual's `format.spec`; its data is the rows of the visual's own query (the wells), put
       in as `data.values`, so what the person sees is what the server let them see (row rules and masks already applied);
     * a Python visual whose item says `output: vega-lite`: the function returns a Vega-Lite dict, its data inline.

   The libraries (vega, vega-lite, vega-embed and vega-interpreter, vendor/vega/) are loaded the first time a page draws one. They are drawn under the page's
   strict policy: no eval anywhere, because vega-embed is told to build the expression syntax tree itself (`ast: true`) and to run it with the vendored
   vega-interpreter, which walks that tree instead of compiling it to a function. Nothing is fetched: the loader refuses every URL (data, images, links), and a
   specification that names one is refused before it is drawn. Canvas renderer, no actions menu, no editor link. */
(function () {
  'use strict';
  const B = (window.drishtiBi = window.drishtiBi || {});
  const h = B.h;
  const V = (B.vega = B.vega || {});
  const BASE = '/static/vendor/vega/6.4.0/';
  const LIBS = ['vega.min.js', 'vega-lite.min.js', 'vega-embed.min.js', 'vega-interpreter.min.js'];
  let loading = null;

  const failure = (detail) => Object.assign(new Error('DRS-9077 ' + detail), { code: 'DRS-9077', detail });

  /** Loads the four scripts once, in order (each needs the one before); scripts of this origin, so the strict policy allows them. */
  function libs() {
    if (window.vegaEmbed && window.vegaInterpreter) { return Promise.resolve(); }
    if (!loading) {
      loading = LIBS.reduce((p, file) => p.then(() => new Promise((resolve, reject) => {
        const s = document.createElement('script');
        s.src = BASE + file;
        s.onload = resolve;
        s.onerror = () => reject(failure('The Vega-Lite library did not load (' + file + ').'));
        document.head.appendChild(s);
      })), Promise.resolve());
      loading.catch(() => { loading = null; });
    }
    return loading;
  }

  /** The first place a specification names a URL or a loader, or null: `data.url`, `url` or `loader` as a plain value anywhere. */
  V.forbidden = function (node, path) {
    path = path || '$';
    if (Array.isArray(node)) {
      for (let i = 0; i < node.length; i++) { const f = V.forbidden(node[i], path + '[' + i + ']'); if (f) { return f; } }
      return null;
    }
    if (node && typeof node === 'object') {
      for (const k of Object.keys(node)) {
        if ((k === 'url' || k === 'loader') && typeof node[k] !== 'object') { return path + '.' + k; }
        if (k === 'data' && node[k] && typeof node[k] === 'object' && 'url' in node[k]) { return path + '.data.url'; }
        const f = V.forbidden(node[k], path + '.' + k);
        if (f) { return f; }
      }
    }
    return null;
  };

  /** The rows of a query result as objects. A field keeps the name after the table (`trades.desk` is `desk`); the table is put back (`trades_desk`) when two share a name. */
  V.records = function (res) {
    const names = res.columns.map((c) => c.name);
    const short = names.map((n) => n.slice(n.lastIndexOf('.') + 1));
    const keys = names.map((n, i) => (short.indexOf(short[i]) !== short.lastIndexOf(short[i]) ? n.replace(/\./g, '_') : short[i]));
    return res.rows.map((r) => { const o = {}; keys.forEach((k, i) => { o[k] = r[i]; }); return o; });
  };

  /** What the theme and the report palette say, under whatever the specification's own `config` says. */
  function themeConfig(ctx) {
    const t = (ctx && ctx.t) || {};
    const c = { background: 'transparent', view: { stroke: 'transparent' } };
    if (t.ink) {
      c.title = { color: t.ink };
      c.axis = { labelColor: t.muted, titleColor: t.muted, domainColor: t.border, tickColor: t.border, gridColor: t.border, gridOpacity: 0.5 };
      c.legend = { labelColor: t.muted, titleColor: t.muted };
      c.header = { labelColor: t.muted, titleColor: t.muted };
    }
    if (ctx && ctx.palette && ctx.palette.length) { c.range = { category: ctx.palette }; }
    return c;
  }

  function note(host, text) {
    B.clear(host).appendChild(h('p', { class: 'bi-vis-empty', role: 'status', 'data-vega-error': '', text }));
    return { dispose() {}, resize() {} };
  }

  /** Draws `vl` (a Vega-Lite specification already holding its data) in `host`; returns the handle the frame disposes. */
  V.drawSpec = function (host, vl, spec, ctx) {
    B.clear(host);
    const bad = V.forbidden(vl);
    if (bad) { return note(host, 'A Vega-Lite visual may not load anything from a URL or name a loader (' + bad + '); its data is the rows of its wells.'); }
    const box = h('div', { class: 'bi-chart bi-vega', role: 'img', 'aria-label': spec.alt || spec.title || 'Vega-Lite chart', 'data-py-kind': 'vega-lite' });
    host.appendChild(box);
    let view = null;
    let gone = false;
    const full = Object.assign({ width: 'container', height: 'container', autosize: { type: 'fit', contains: 'padding' } }, vl,
      { config: Object.assign({}, themeConfig(ctx), vl.config || {}) });
    full.$schema = full.$schema || 'https://vega.github.io/schema/vega-lite/v6.json';
    const done = libs().then(() => {
      if (gone) { return null; }
      const loader = window.vega.loader();
      const refuse = () => Promise.reject(new Error('a Vega-Lite visual may not load anything from a URL'));
      loader.load = refuse;
      loader.http = refuse;
      loader.sanitize = refuse;
      return window.vegaEmbed(box, full, { actions: false, renderer: 'canvas', ast: true, expr: window.vegaInterpreter, loader, tooltip: false, defaultStyle: false, mode: 'vega-lite' });
    }).then((r) => {
      if (!r) { return; }
      if (gone) { r.finalize(); return; }
      view = r;
      box.setAttribute('data-ready', '1');
    }, (e) => {
      note(host, 'Vega-Lite could not draw this: ' + (e && e.message ? e.message : e));
    });
    return {
      dispose() { gone = true; if (view) { view.finalize(); view = null; } },
      resize() { if (view) { view.view.resize().runAsync(); } },
      ready: done
    };
  };

  /** A `type: vega-lite` visual: the specification of `format.spec`, its data the rows of the visual's query. */
  V.draw = function (host, spec, ctx, res) {
    const vl = spec.format && spec.format.spec;
    if (!vl || typeof vl !== 'object') { return note(host, 'This visual has no Vega-Lite specification (format: { spec: ... }).'); }
    const copy = B.clone(vl);
    copy.data = { values: V.records(res) };
    return V.drawSpec(host, copy, spec, ctx);
  };
})();
