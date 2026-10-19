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
/* Wave C (2.4.0), the browser Python console (docs/guides/ANALYSE.md section 15). The page asks the console for a masked extract of a model
   (POST /pyconsole/extract: built by the server as you), starts Calc's Web Worker (calc-worker.js: Pyodide, the only script allowed to compile WebAssembly)
   and hands it the extract once; each run gets it as `df`. The worker never fetches data and holds no credential. Promote sends the code to the console
   (POST /pyconsole/promote), which runs it on the backend over the extract rebuilt for you and keeps it as a notebook draft; the two answers are compared.
   Text only, never markup. */
(function () {
  'use strict';
  const B = window.drishtiBi, root = document.getElementById('pc');
  if (!B || !root) { return; }
  const h = B.h, q = (s) => B.q(s, root), qa = (s) => B.qa(s, root);
  const base = root.getAttribute('data-runtime'), mayPromote = root.getAttribute('data-may-promote') === 'true';
  const S = { ex: null, spec: null, extractId: null, sent: null, worker: null, ready: null, running: null, seq: 0, browser: null, charts: [] };
  window.drishtiPyConsole = S;                                       // the browser tests read the state
  const say = (t, bad) => B.say(document.querySelector('[data-msg]'), t, bad);
  const status = (t, bad) => { const el = q('[data-status]'); el.textContent = t; el.classList.toggle('bi-bad', !!bad); };
  const secs = (ms) => (ms >= 1000 ? (ms / 1000).toFixed(1) + ' s' : Math.round(ms) + ' ms');
  function post(path, body) {
    return fetch(path, { method: 'POST', headers: { 'Content-Type': 'application/json', Accept: 'application/json' }, credentials: 'same-origin', body: JSON.stringify(body) })
      .then((r) => r.json().catch(() => ({})).then((d) => {
        if (!r.ok) { const e = new Error((d.code ? d.code + ' ' : '') + (d.detail || r.statusText)); e.code = d.code; e.traceback = d.traceback; throw e; }
        return d;
      }));
  }

  // ---- the editor --------------------------------------------------------------------------------------------------------------------------------
  const ta = q('[data-code]');
  ta.value = '# df is the extract: the rows you may see, masked columns masked\nprint(df.shape)\nshow(df.head(10), "First rows")\n';
  let editor = null;
  if (window.CodeMirror) {
    editor = window.CodeMirror.fromTextArea(ta, { mode: 'python', lineNumbers: true, indentUnit: 4, tabSize: 4, indentWithTabs: false,
      extraKeys: { 'Ctrl-Enter': () => run(), 'Cmd-Enter': () => run(), Tab: (cm) => cm.replaceSelection('    ', 'end') } });
  } else {
    ta.addEventListener('keydown', (e) => { if (e.key === 'Enter' && (e.ctrlKey || e.metaKey)) { e.preventDefault(); run(); } });
  }
  const code = () => (editor ? editor.getValue() : ta.value);
  S.setCode = (t) => { if (editor) { editor.setValue(t); } else { ta.value = t; } };

  // ---- the extract -------------------------------------------------------------------------------------------------------------------------------
  const dsSel = q('[data-dataset]'), fieldsEl = q('[data-fields]'), go = q('[data-open-go]');
  B.layer.groups().then((gs) => B.layer.fillSelect(dsSel, gs, '(choose a model or a dataset)')).catch((e) => say(e.message, true));
  dsSel.addEventListener('change', () => {
    B.clear(fieldsEl); q('[data-fields-box]').hidden = true; go.disabled = true;
    if (!dsSel.value) { return; }
    const ref = B.layer.refOf(dsSel.value);
    B.layer.get('fields?ref=' + encodeURIComponent(ref)).then((m) => {
      (m.tables || []).forEach((t, ti) => {
        const cols = (t.columns || []).filter((c) => c.name !== 'id');
        fieldsEl.appendChild(h('div', { class: 'tx-ftable' }, h('strong', { text: t.name }), cols.map((c, i) => h('label', { class: 'tx-check' },
          h('input', { type: 'checkbox', name: 'f', value: t.name + '.' + c.name, checked: ti === 0 && i < 10 }), ' ' + c.name + (c.masked ? ' (masked)' : '')))));
      });
      q('[data-fields-box]').hidden = false; go.disabled = false;
    }).catch((e) => say(e.message, true));
  });
  q('[data-open]').addEventListener('submit', (ev) => {
    ev.preventDefault();
    const fields = qa('[data-fields] input[name=f]:checked').map((i) => i.value);
    if (!dsSel.value || !fields.length) { say('Choose a model and at least one field.', true); return; }
    const spec = { dataset: dsSel.value, fields };
    if (q('[data-date]').value) { spec.dates = q('[data-date]').value; }
    S.open(spec);
  });
  S.open = function (spec) {
    say('Opening the extract (the server builds it as you)…');
    return B.busy(go, () => post('/pyconsole/extract', { extract: spec }).then((ex) => {
      S.ex = ex; S.spec = spec; S.extractId = 'x' + Date.now(); S.sent = null;
      q('[data-info]').textContent = ex.rowCount.toLocaleString('en-US') + ' rows' + (ex.truncated ? ' of ' + ex.total.toLocaleString('en-US') + ' (cut to ' + ex.maxRows.toLocaleString('en-US') + ')' : '')
        + ', ' + ex.columns.length + ' columns, in your browser.' + (ex.masked.length ? ' Masked for you: ' + ex.masked.join(', ') + '.' : '');
      say('');
      q('[data-run]').disabled = !base;
      q('[data-promote]').disabled = !mayPromote;
      root.setAttribute('data-extract', String(ex.rowCount));
      if (base && !S.worker) { start(); }
    }).catch((e) => say(e.message, true)));
  };

  // ---- the worker: Calc's Pyodide ----------------------------------------------------------------------------------------------------------------
  function start() {
    const t0 = performance.now();
    status('Starting Python in your browser…');
    S.worker = new Worker(root.getAttribute('data-worker'), { type: 'module', name: 'drishti-pyconsole' });
    S.ready = new Promise((resolve, reject) => {
      S.worker.onmessage = (e) => {
        const m = e.data || {};
        if (m.type === 'ready') { status('Python ready · Pyodide ' + m.version + ' · ' + secs(performance.now() - t0)); root.setAttribute('data-ready', 'true'); resolve(m); }
        else if (m.type === 'failed') { status('Python could not start: ' + m.error, true); reject(new Error(m.error)); }
        else { handle(m); }
      };
      S.worker.onerror = (e) => { status('Python could not start: ' + (e.message || 'worker error'), true); reject(e); };
    });
    S.worker.postMessage({ type: 'init', base, module: root.getAttribute('data-module') });
    S.ready.catch(() => {});
  }
  function stop() {
    if (S.worker) { S.worker.terminate(); }
    S.worker = null; S.ready = null; S.sent = null;
    if (S.running) { finish({ ok: false, error: 'Stopped: the Python worker was ended', ms: performance.now() - S.running.t0 }); }
    start();
  }
  q('[data-stop]').addEventListener('click', stop);
  function context() {
    const ctx = { ref: { kind: 'extract', id: S.spec ? B.layer.refOf(S.spec.dataset) : '' }, extractId: S.extractId };
    if (S.sent !== S.extractId) { ctx.extract = { columns: S.ex.columns, rows: S.ex.rows, masked: S.ex.masked }; }   // the rows go to the worker once
    return JSON.stringify(ctx);
  }
  function run() {
    if (!base || !S.ex) { return Promise.resolve(); }
    if (S.running) { say('Still running: Stop ends it'); return Promise.resolve(); }
    if (!S.worker) { start(); }
    const text = code();
    clearOut(q('[data-out]'));
    const me = ++S.seq;
    S.running = { id: me, t0: performance.now(), outputs: [] };
    S.browser = null;
    q('[data-run]').disabled = true; q('[data-stop]').disabled = false;
    root.setAttribute('data-running', 'true');
    status('Running in your browser…');
    return S.ready.then(() => {
      const ctx = context();
      S.sent = S.extractId;
      S.worker.postMessage({ type: 'run', id: me, code: text + '\n# df', context: ctx });     // "df" loads pandas before the code runs
    }).catch((e) => finish({ ok: false, error: String(e && e.message || e), ms: 0 }));
  }
  S.run = run;
  q('[data-run]').addEventListener('click', run);
  function handle(m) {
    if (m.type === 'request') { S.worker.postMessage({ type: 'reply', rid: m.rid, body: JSON.stringify({ ok: false, code: 'DRS-5001', detail: 'the browser console reads only its extract (df)' }) }); return; }
    if (!S.running || (m.id != null && m.id !== S.running.id)) { return; }
    const out = q('[data-out]');
    if (m.type === 'status') { status(m.text); }
    else if (m.type === 'stdout' || m.type === 'stderr') { text(out, m.text, m.type === 'stderr'); }
    else if (m.type === 'output') { let item; try { item = JSON.parse(m.item); } catch (e) { item = { kind: 'text', text: String(m.item) }; } S.running.outputs.push(item); render(out, item); }
    else if (m.type === 'done') { finish(m); }
  }
  function finish(m) {
    const r = S.running;
    S.running = null;
    q('[data-run]').disabled = !base || !S.ex; q('[data-stop]').disabled = true;
    root.setAttribute('data-running', 'false');
    if (!r) { return; }
    status((m.ok ? 'Done in your browser · ' : 'Failed · ') + secs(m.ms || 0) + (m.heapMb ? ' · memory ' + m.heapMb + ' MB' : ''), !m.ok);
    if (!m.ok) {
      const box = h('div', { class: 'pc-err', role: 'alert' }, h('strong', { text: m.error || 'Error' }), m.traceback ? h('pre', { class: 'bx-pre', text: m.traceback }) : null);
      q('[data-out]').appendChild(box);
    }
    const tables = r.outputs.filter((o) => o.kind === 'table');
    S.browser = { ok: !!m.ok, ms: Math.round(m.ms || 0), table: tables.length ? tables[tables.length - 1] : null, outputs: r.outputs.length };
    root.setAttribute('data-ran', String(r.id));
  }

  // ---- output (tables, charts, images, text, notes): data only ----------------------------------------------------------------------------------
  function clearOut(el) { S.charts.forEach((c) => c.dispose()); S.charts = []; B.clear(el); }
  function text(el, t, err) {
    let last = el.lastElementChild;
    if (!last || !last.classList.contains('calc-text') || last.classList.contains('calc-stderr') !== !!err) { last = h('pre', { class: 'calc-text mono' + (err ? ' calc-stderr' : '') }); el.appendChild(last); }
    if (last.textContent.length < 200000) { last.textContent += t; }
  }
  function cell(v) { return v == null ? '' : typeof v === 'number' ? v.toLocaleString('en-US', { maximumFractionDigits: 6 }) : String(v); }
  function table(el, item) {
    if (item.title) { el.appendChild(h('h4', { class: 'calc-h', text: item.title })); }
    el.appendChild(h('div', { class: 'tbl-wrap' }, h('table', { class: 'tbl calc-tbl', 'data-pc-table': '', 'data-plain': '' },
      h('thead', {}, h('tr', {}, item.columns.map((c, i) => h('th', { class: (item.numeric || [])[i] ? 'num' : '', text: c })))),
      h('tbody', {}, item.rows.map((r) => h('tr', {}, r.map((v) => h('td', { class: 'mono' + (typeof v === 'number' ? ' num' : ''), text: cell(v) }))))))));
    el.appendChild(h('p', { class: 'calc-foot text-muted-d', text: item.total.toLocaleString('en-US') + (item.total === 1 ? ' row' : ' rows') }));
  }
  function chart(el, item) {
    if (item.title) { el.appendChild(h('h4', { class: 'calc-h', text: item.title })); }
    const box = h('div', { class: 'calc-chart', 'data-pc-chart': '' });
    el.appendChild(box);
    if (!window.echarts) { box.textContent = 'Charts need ECharts.'; return; }
    const st = getComputedStyle(document.documentElement), tok = (k) => st.getPropertyValue('--d-' + k).trim();
    const type = item.type === 'hist' ? 'bar' : item.type;
    const axis = { axisLine: { lineStyle: { color: tok('border') } }, axisLabel: { color: tok('muted'), fontSize: 10 }, splitLine: { lineStyle: { color: tok('border'), opacity: 0.5 } },
      nameTextStyle: { color: tok('muted') } };                                     // the theme's colours, as Calc draws its charts
    const c = window.echarts.init(box, null, { renderer: 'canvas' });
    c.setOption({ animation: false, color: [tok('link'), tok('accent'), tok('pos'), tok('warn'), tok('neg')], grid: { left: 56, right: 16, top: 24, bottom: 36 },
      tooltip: { trigger: type === 'scatter' ? 'item' : 'axis', backgroundColor: tok('surface'), borderColor: tok('border'), textStyle: { color: tok('ink') } },
      legend: item.series.length > 1 ? { top: 0, textStyle: { color: tok('muted') } } : undefined,
      xAxis: Object.assign({ type: type === 'scatter' ? 'value' : 'category', data: type === 'scatter' ? undefined : item.x, name: item.xLabel || '', nameLocation: 'middle', nameGap: 24 }, axis),
      yAxis: Object.assign({ type: 'value', scale: type === 'line' }, axis),
      series: item.series.map((s) => ({ name: s.name, type, barMaxWidth: 48, data: type === 'scatter' ? s.values.map((v, k) => [item.x[k], v]) : s.values })) });
    S.charts.push(c);
  }
  function render(el, item) {
    if (item.kind === 'table') { table(el, item); }
    else if (item.kind === 'chart') { chart(el, item); }
    else if (item.kind === 'image') { el.appendChild(h('img', { class: 'calc-img', alt: item.title || 'Figure', src: 'data:image/png;base64,' + item.png })); }
    else if (item.kind === 'note') { el.appendChild(h('p', { class: 'calc-note', text: item.text })); }
    else { el.appendChild(h('pre', { class: 'calc-text mono', text: item.text || '' })); }
  }
  window.addEventListener('resize', () => S.charts.forEach((c) => c.resize()));

  // ---- promote: run it on the console backend, keep it as a notebook draft --------------------------------------------------------------------------
  function same(a, b) {
    if (!a || !b) { return false; }
    if (JSON.stringify(a.columns) !== JSON.stringify(b.columns) || a.rows.length !== b.rows.length) { return false; }
    return a.rows.every((r, i) => r.every((v, j) => {
      const w = b.rows[i][j];
      return typeof v === 'number' && typeof w === 'number' ? Math.abs(v - w) <= 1e-9 * Math.max(1, Math.abs(v)) : String(v == null ? '' : v) === String(w == null ? '' : w);
    }));
  }
  q('[data-promote]').addEventListener('click', () => {
    const btn = q('[data-promote]');
    if (!S.ex) { B.near(btn, 'Open an extract first.', true); return; }
    const title = q('[data-ptitle]').value.trim() || 'From the browser console';
    B.busy(btn, () => post('/pyconsole/promote', { extract: S.spec, code: code(), title }).then((p) => {
      const box = q('[data-compare]'), out = q('[data-server-out]');
      box.hidden = false; B.clear(out);
      const a = p.answer || {};
      if (a.stdout) { text(out, a.stdout, false); }
      const t = a.table ? { columns: a.table.columns, rows: a.table.rows, total: a.table.rowCount, numeric: [] } : null;
      if (t) { table(out, t); } else if (a.value != null) { out.appendChild(h('pre', { class: 'calc-text mono', text: JSON.stringify(a.value) })); }
      const browserT = S.browser && S.browser.table;
      const shown = browserT ? { columns: browserT.columns, rows: browserT.rows } : null;
      const match = t && shown ? same(shown, { columns: t.columns, rows: t.rows.slice(0, shown.rows.length) }) : null;
      q('[data-compare-line]').textContent = 'Ran on the console backend in ' + secs(a.ms || 0) + ' over ' + p.inputRows.toLocaleString('en-US') + ' rows rebuilt for you'
        + (p.masked.length ? ' (masked: ' + p.masked.join(', ') + ')' : '') + '. ' + (match === true ? 'The same answer as in your browser.' : match === false ? 'A different answer from your browser\'s: check the code.' : '')
        + ' Saved as the notebook draft "' + title + '".';
      root.setAttribute('data-promoted', match === true ? 'same' : match === false ? 'different' : 'saved');
      q('[data-open-notebook]').href = p.item.href;
      q('[data-open-item]').href = p.item.page;
      S.promoted = p;
    }).catch((e) => B.near(btn, e.message, true)));
  });
})();
