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
/* The Playground page of an element (mock-up bi-depth/elements-playground, option A): one column, the live visual on top, its data and options
   below, then the HTML and JavaScript that draw it, editable: every edit redraws, after a short pause, in a NEW frame with sandbox="allow-scripts"
   (no same origin) under a policy with no network (drishti_playground-frame.html); the frame is swapped in only if the code ran cleanly. An error
   names its line and keeps the last good drawing, greyed. Reset restores the example. */
(function (root) {
  'use strict';
  const NS = (root.Drishti = root.Drishti || {});
  const GAL = (NS.gallery = NS.gallery || {});
  const PAUSE_MS = 300;
  const DATA_START = '// ---- data ----', DATA_END = '// ---- end of data ----';
  const JS_LIB = { 'drishti-chart': 'chart', 'drishti-tree': 'tree', 'drishti-graph': 'graph', 'drishti-grid': 'grid', 'drishti-inputs': 'inputs', 'drishti-risk': 'risk' };

  /** A deep copy with the functions and DOM nodes left out, so it can be written as text. */
  function plain(x, drop) {
    return JSON.parse(JSON.stringify(x, (k, v) => (typeof v === 'function' || (v && v.nodeType) || (drop && drop.has(v)) ? undefined : v)));
  }

  /** The rows of a component's first data, and where they are. */
  function rowsIn(b) {
    if (b.spec && b.spec.data && Array.isArray(b.spec.data.rows)) { return b.spec.data.rows; }
    if (b.options && b.options.data && Array.isArray(b.options.data.rows)) { return b.options.data.rows; }
    if (b.options && Array.isArray(b.options.rows)) { return b.options.rows; }
    return null;
  }

  /**
   * What a page edits: the demo's own {html, js, rows}, or written from what the demo gives the component (b: its build).
   * @returns {?{html: string, js: string}}
   */
  GAL.pageSource = function (d, values, ctx) {
    if (d.page) { return d.page(values, ctx); }
    return d.build ? GAL.writePage(d.build(values, ctx)) : null;
  };

  /**
   * A page written from a component's build: the rows first (the Data grid edits them), then its spec and options, then the component.
   * @param {Object} b the build ({library, ctor, spec?, options?, jsOptions?})
   * @param {{rows?: Object[], code?: Object<string, string>, before?: string}} [o] rows to use instead of the build's; options written as
   *   code (a datasource, a function), by key; lines put before the component
   * @returns {?{html: string, js: string, rows: ?Object[]}}
   */
  GAL.writePage = function (b, o) {
    o = o || {};
    const short = JS_LIB[b.tag] || JS_LIB[b.library];
    if (!short || !b.ctor) { return null; }
    const found = rowsIn(b), rows = o.rows || found, drop = new Set(found ? [found] : []);
    const spec = b.spec ? plain(b.spec, drop) : null, options = plain(Object.assign({}, b.options || {}, b.jsOptions || {}), drop);
    const code = o.code || {};
    Object.keys(code).forEach((k) => { options[k] = '__CODE_' + k + '__'; });
    if (rows && !o.rows) {                                         // the rows go back where the build had them
      if (spec && spec.data && Array.isArray(b.spec.data.rows)) { spec.data.rows = '__ROWS__'; } else if (options.data && b.options.data && Array.isArray(b.options.data.rows)) { options.data.rows = '__ROWS__'; } else { options.rows = '__ROWS__'; }
    }
    const put = (name, x) => 'const ' + name + ' = ' + JSON.stringify(x, null, 2) + ';\n';
    let js = [DATA_START, 'const rows = ' + JSON.stringify(rows || [], null, 2) + ';', DATA_END, '', o.before ? o.before + '\n' : '',
      (spec ? put('spec', spec) : '') + put('options', options),
      'new Drishti.elements.' + short + '.' + b.ctor + "(document.getElementById('visual'), " + (spec ? 'spec, options' : 'options') + ');'].join('\n').replace(/"__ROWS__"/g, 'rows');
    Object.keys(code).forEach((k) => { js = js.split('"__CODE_' + k + '__"').join(code[k]); });
    return { html: '<div id="visual" style="height: 360px"></div>', js: js.replace(/\n{3,}/g, '\n\n'), rows: rows, uses: 'drishti-' + short };
  };

  /** The libraries a page needs, deepest first (their bundles inlined in the sandbox: it can load nothing). */
  function closure(manifest, libs) {
    const out = [];
    function add(n) {
      if (out.indexOf(n) >= 0 || !manifest.libraries[n]) { return; }
      (manifest.libraries[n].requires || []).forEach(add);
      out.push(n);
    }
    libs.forEach(add);
    return ['drishti-core'].concat(out.filter((n) => n !== 'drishti-core'));
  }

  const FETCHED = new Map();
  function text(u) {
    if (!FETCHED.has(u)) { FETCHED.set(u, fetch(u).then((r) => (r.ok ? r.text() : ''))); }
    return FETCHED.get(u);
  }

  /** The page written in the sandbox: tokens and bundles inlined, the error reporter, then the person's code; the code is named drishti-playground-code.js, so an error thrown in a library still finds its line in it. */
  async function compose(g, libs, html, js) {
    const M = g.E.core.manifest(), base = new URL('../elements/', location.href).href, stat = new URL('../', location.href).href;
    const names = closure(M, libs), css = [text(stat + 'css/drishti_tokens.css')], scripts = [];
    names.forEach((n) => {
      const L = M.libraries[n];
      (L.css || []).forEach((c) => css.push(text(new URL(c, stat).href)));
      (L.scripts || []).forEach((c) => scripts.push(text(new URL(c, stat).href)));
      scripts.push(text(base + n + '/drishti_' + n.replace('drishti-', '') + '.js'));
    });
    const [sheets, codes] = await Promise.all([Promise.all(css), Promise.all(scripts)]);
    const headTop = '<!doctype html><html data-theme="' + (g.doc.documentElement.getAttribute('data-theme') || 'terminal') + '"><head><meta charset="utf-8"><style>' + sheets.join('\n') + '</style></head><body>' + html +
      '\n<script>' + codes.join('\n;\n').replace(/<\/script/gi, '<\\/script') + '\n<\/script>\n<script>var failed=false;function at(x){var m=/drishti-playground-code\.js:(\d+)/.exec(x&&x.stack||"");return m?+m[1]:0;}function rep(m,own,l){failed=true;parent.postMessage({type:"drishti-playground-error",message:String(m),own:own,line:l},"*");}window.addEventListener("error",function(e){var own=/drishti-playground-code\.js$/.test(e.filename||"");rep(e.message,own,own?e.lineno:at(e.error));});window.addEventListener("unhandledrejection",function(e){rep(e.reason&&e.reason.message||e.reason,false,at(e.reason));});<\/script>\n<script>';
    const html2 = headTop + js + '\n//# sourceURL=drishti-playground-code.js\n<\/script>\n<script>setTimeout(function(){if(!failed){parent.postMessage({type:"drishti-playground-ok"},"*");}},60);<\/script></body></html>';
    return { html: html2 };
  }

  /**
   * Draws the page of a component in the panel.
   * @param {Object} g the gallery
   * @param {Object} d the demo
   * @param {HTMLElement} panel
   * @returns {void}
   */
  GAL.renderPage = function (g, d, panel) {
    const doc = g.doc, h = g.h, values = g.values[d.id] || g.initial(d);
    const src = GAL.pageSource(d, values, g.makeContext());
    if (!src) { panel.appendChild(h(doc, 'p', { cls: 'gx-note', text: 'This component has no editable page yet: see Examples.' })); return; }
    const libs = [d.library].concat(src.uses ? [src.uses] : []);
    const original = { html: src.html, js: src.js };
    let rows = src.rows ? src.rows.map((r) => Object.assign({}, r)) : null;
    const live = h(doc, 'div', { cls: 'gx-page-visual gx-stage', attrs: { 'data-page-live': d.id } });
    const status = h(doc, 'p', { cls: 'gx-page-status', attrs: { role: 'status', 'data-page-status': '' } });
    const errBox = h(doc, 'div', { cls: 'gx-page-error', attrs: { role: 'alert', hidden: 'hidden', 'data-page-error': '' } });
    panel.appendChild(h(doc, 'section', { cls: 'gx-page' }, [live, status, errBox]));

    let dataHost = null, gridRef = null;
    if (rows) {
      dataHost = h(doc, 'div', { cls: 'gx-page-data', attrs: { 'data-page-data': '' } });
      panel.appendChild(h(doc, 'h2', { text: 'Data' }));
      panel.appendChild(dataHost);
      const columns = Object.keys(rows[0] || {}).map((f) => ({ field: f, editable: true }));
      gridRef = new (g.E.grid.Grid)(dataHost, { columns, rows, height: 240, density: 'compact' });
      gridRef.on('cellValueChanged', (e) => {
        const r = rows.find((x, i) => String((x.id !== undefined ? x.id : i)) === String(e.rowId)) || rows[Number(e.rowId)];
        if (r) { r[e.colId] = isNaN(Number(e.value)) || e.value === '' ? e.value : Number(e.value); writeRows(); }
      });
    }
    panel.appendChild(h(doc, 'h2', { text: 'Options' }));
    panel.appendChild(g.optionsPanel(d, values));

    panel.appendChild(h(doc, 'h2', { text: 'HTML' }));
    const htmlHost = h(doc, 'div', { attrs: { 'data-page-code': 'html' } });
    panel.appendChild(htmlHost);
    panel.appendChild(h(doc, 'h2', { text: 'JavaScript' }));
    const jsHost = h(doc, 'div', { attrs: { 'data-page-code': 'js' } });
    panel.appendChild(jsHost);
    const reset = h(doc, 'button', { cls: 'gx-btn', text: 'Reset', attrs: { type: 'button', 'data-action': 'page-reset' } });
    panel.appendChild(reset);
    const CE = g.E.inputs.CodeEditor;
    const ed = { html: new CE(htmlHost, { mode: 'html', value: original.html, height: 80 }), js: new CE(jsHost, { mode: 'javascript', value: original.js, height: 320 }) };

    let frame = null, timer = 0, seq = 0, good = false, mark = null, draws = 0;
    function setError(msg, line) {
      errBox.hidden = false;
      errBox.textContent = '';
      const go = h(doc, 'button', { cls: 'gx-btn', text: 'Go to the line', attrs: { type: 'button' }, on: { click: () => { const cm = ed.js.cm; if (cm && line) { cm.focus(); cm.setCursor({ line: line - 1, ch: 0 }); } } } });
      errBox.appendChild(h(doc, 'span', { attrs: { 'data-page-error-text': '' }, text: (line ? 'Line ' + line + ': ' : '') + msg }));
      errBox.appendChild(go);
      status.textContent = 'The code failed; the last good drawing is kept.';
      if (mark && ed.js.cm) { ed.js.cm.removeLineClass(mark, 'background', 'gx-page-badline'); }
      if (ed.js.cm && line) { mark = line - 1; ed.js.cm.addLineClass(mark, 'background', 'gx-page-badline'); }
      if (frame) { frame.classList.add('gx-page-stale'); }
    }
    function clearError() { errBox.hidden = true; if (mark !== null && ed.js.cm) { ed.js.cm.removeLineClass(mark, 'background', 'gx-page-badline'); } mark = null; }

    function writeRows() {
      if (!rows) { return; }
      const v = ed.js.getValue(), a = v.indexOf(DATA_START), b = v.indexOf(DATA_END);
      if (a < 0 || b < 0) { schedule(); return; }
      ed.js.setValue(v.slice(0, a) + DATA_START + '\nconst rows = ' + JSON.stringify(rows, null, 2) + ';\n' + DATA_END + v.slice(b + DATA_END.length));
      schedule();
    }

    async function run() {
      const my = ++seq;
      const page = await compose(g, libs, ed.html.getValue(), ed.js.getValue());
      if (my !== seq) { return; }
      const f = doc.createElement('iframe');
      f.setAttribute('sandbox', 'allow-scripts');
      f.setAttribute('title', 'The element, drawn from the code below');
      f.className = 'gx-page-frame';
      f.style.visibility = 'hidden';
      f.style.position = 'absolute';
      f.src = new URL('drishti_playground-frame.html', location.href).href;
      const off = () => window.removeEventListener('message', on);
      function on(e) {
        if (e.source !== f.contentWindow) { return; }
        const t = e.data && e.data.type;
        if (t === 'drishti-playground-ready') { f.contentWindow.postMessage({ type: 'drishti-playground-page', html: page.html }, '*'); return; }
        if (my !== seq) { off(); f.remove(); return; }
        if (t === 'drishti-playground-ok') {
          off();
          clearError();
          if (frame) { frame.remove(); }
          frame = f; f.style.visibility = ''; f.style.position = '';
          status.textContent = 'Drawn from the code below.';
          good = true;
          draws += 1;
        } else if (t === 'drishti-playground-error') {
          off();
          setError(e.data.own ? e.data.message : e.data.message + ' (in a library, called from the code below)', e.data.line || null);
          f.remove();
          good = false;
        }
      }
      window.addEventListener('message', on);
      live.appendChild(f);
    }
    function schedule() { clearTimeout(timer); timer = setTimeout(run, PAUSE_MS); status.textContent = 'Redrawing…'; }

    ed.js.on('change', (x) => { if (x.origin !== 'setValue') { schedule(); } });
    ed.html.on('change', (x) => { if (x.origin !== 'setValue') { schedule(); } });
    reset.addEventListener('click', () => { ed.html.setValue(original.html); ed.js.setValue(original.js); if (rows && gridRef) { rows = src.rows.map((r) => Object.assign({}, r)); gridRef.setRows(rows); } clearError(); run(); });
    run();
    g.pageState = () => ({ good, error: !errBox.hidden, seq, draws });
    g.pageGrid = gridRef;
    g.pageRows = () => rows;
    g.pageEditors = ed;
  };
}(typeof window !== 'undefined' ? window : null));
