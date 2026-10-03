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
/* Calc: Python on any view, in the browser (docs/guides/PYTHON_CALC.md). Alt+C opens a drawer like F9's with an editor
   (CodeMirror, Python mode), Run (Ctrl+Enter), Stop, the output (text, tables paged like Drishti's, charts in the
   theme, images, errors with their traceback) and a history of runs. The code runs in Pyodide inside a Web Worker
   (calc-worker.js) started on first open; Stop ends the worker and starts a fresh one. When Python reads data, the
   worker asks this page, and this page fetches with the user's session (/api/view, /api/raw, /api/series and
   /api/calc/*): the same reads as the screen, with the user's roles. Nothing is run on a server. */
(function () {
  'use strict';
  var drawer = document.getElementById('calcDrawer');
  if (!drawer) { return; }
  var kind = drawer.getAttribute('data-kind'), id = drawer.getAttribute('data-id');
  var base = drawer.getAttribute('data-runtime');
  var $ = function (sel) { return drawer.querySelector(sel); };
  var ta = $('[data-calc-code]'), out = $('[data-calc-out]'), hist = $('[data-calc-history]'), statusEl = $('[data-calc-status]');
  var runBtn = $('[data-calc-run]'), stopBtn = $('[data-calc-stop]'), pick = $('[data-calc-snippets]'), msg = $('[data-calc-msg]');
  var delBtn = $('[data-calc-delete]');
  var HKEY = 'drishti.calc.history', DKEY = 'drishti.calc.draft.' + kind;
  var editor = null, worker = null, ready = null, runSeq = 0, running = null, charts = [], snippets = { pack: [], mine: [] };
  var mine = null, errLine = null, stats = null;

  // ---- small helpers ------------------------------------------------------------------------------------------------
  function el(tag, cls, text) { var e = document.createElement(tag); if (cls) { e.className = cls; } if (text != null) { e.textContent = text; } return e; }
  function store(key, value) { try { if (value == null) { localStorage.removeItem(key); } else { localStorage.setItem(key, value); } } catch (e) { /* private window */ } }
  function load(key) { try { return localStorage.getItem(key); } catch (e) { return null; } }
  function status(text, bad) { statusEl.textContent = text; statusEl.classList.toggle('t-bad', !!bad); }
  function say(text, bad) { msg.textContent = text || ''; msg.classList.toggle('t-bad', !!bad); }
  function secs(ms) { return ms >= 1000 ? (ms / 1000).toFixed(1) + ' s' : Math.round(ms) + ' ms'; }
  function code() { return editor ? editor.getValue() : ta.value; }
  function setCode(text) { if (editor) { editor.setValue(text); editor.focus(); } else { ta.value = text; } }
  function json(r) { return r.json().catch(function () { return {}; }).then(function (b) { return { ok: r.ok, status: r.status, b: b }; }); }

  // ---- the drawer ---------------------------------------------------------------------------------------------------
  function open() {
    drawer.hidden = false;
    document.body.classList.add('calc-open');
    if (!editor) { setup(); }
    if (editor) { editor.refresh(); editor.focus(); }
    if (base && !worker) { start(); }
  }
  function close() { drawer.hidden = true; document.body.classList.remove('calc-open'); }
  function toggle() { if (drawer.hidden) { open(); } else { close(); } }
  document.querySelectorAll('[data-calc-open]').forEach(function (b) { b.addEventListener('click', toggle); });
  $('[data-calc-close]').addEventListener('click', close);
  document.addEventListener('keydown', function (e) {
    if (e.altKey && !e.ctrlKey && !e.metaKey && (e.code === 'KeyC' || e.key === 'c' || e.key === 'C')) { e.preventDefault(); toggle(); return; }
    if (e.key === 'Escape' && !drawer.hidden && !e.defaultPrevented) { close(); }
  });

  function setup() {
    var draft = load(DKEY);
    ta.value = draft != null ? draft : '# ' + kind + ' ' + id + ': Python in your browser. Ctrl+Enter runs.\n'
      + '# view is this screen: view.doc (the document), view.tables (its tables as DataFrames).\n'
      + 'print(view)\nlist(view.tables)\n';
    if (window.CodeMirror) {
      editor = window.CodeMirror.fromTextArea(ta, {
        mode: 'python', lineNumbers: true, indentUnit: 4, tabSize: 4, indentWithTabs: false, matchBrackets: true,
        extraKeys: {
          'Ctrl-Enter': function () { run(); }, 'Cmd-Enter': function () { run(); },
          Tab: function (cm) { if (cm.somethingSelected()) { cm.indentSelection('add'); } else { cm.replaceSelection('    ', 'end'); } },
          'Shift-Tab': function (cm) { cm.indentSelection('subtract'); },
          Esc: function () { close(); }
        }
      });
      var t = null;
      editor.on('change', function () {
        clearTimeout(t);
        t = setTimeout(function () { store(DKEY, editor.getValue()); }, 400);
        if (errLine != null) { editor.removeLineClass(errLine, 'background', 'calc-err-line'); errLine = null; }
      });
    } else {
      ta.addEventListener('keydown', function (e) { if (e.key === 'Enter' && (e.ctrlKey || e.metaKey)) { e.preventDefault(); run(); } });
      ta.addEventListener('input', function () { store(DKEY, ta.value); });
    }
    loadSnippets();
    drawHistory();
  }

  // ---- the worker ---------------------------------------------------------------------------------------------------
  function start() {
    var t0 = performance.now();
    status('Starting Python…');
    worker = new Worker(drawer.getAttribute('data-worker'), { type: 'module', name: 'drishti-calc' });
    ready = new Promise(function (resolve, reject) {
      worker.onmessage = function (e) {
        var m = e.data || {};
        if (m.type === 'ready') {
          status('Python ready · Pyodide ' + m.version + ' · ' + secs(performance.now() - t0) + (m.sync ? '' : ' · reads need await (see help)'));
          drawer.setAttribute('data-calc-sync', m.sync ? 'true' : 'false');
          // without JavaScript Promise Integration Python cannot pause for a read: say so where the user looks, before a run fails
          var nosync = drawer.querySelector('[data-calc-nosync]');
          if (nosync) { nosync.hidden = !!m.sync; }
          if (!m.sync) { say('This browser cannot pause Python for a read: write await drishti.get_async(…), search_async, columns_async or history_async. view works as it is.'); }
          drawer.setAttribute('data-calc-ready', String(Math.round(performance.now() - t0)));
          resolve(m);
        } else if (m.type === 'failed') {
          status('Python could not start: ' + m.error, true);
          reject(new Error(m.error));
        } else { handle(m); }
      };
      worker.onerror = function (e) { status('Python could not start: ' + (e.message || 'worker error'), true); reject(e); };
    });
    worker.postMessage({ type: 'init', base: base, module: drawer.getAttribute('data-module') });
    ready.catch(function () { /* shown in the status */ });
  }

  function stop() {
    if (worker) { worker.terminate(); }
    worker = null; ready = null;
    if (running) {
      finish({ ok: false, error: 'Stopped: the Python worker was ended (variables from earlier runs are gone)', ms: performance.now() - running.t0 });
    }
    start();
  }

  function context() {
    var path = encodeURIComponent(kind) + '/' + encodeURIComponent(id);
    return Promise.all([fetch('/api/view/' + path).then(json), fetch('/api/raw/' + path).then(json)]).then(function (res) {
      var vm = res[0].ok ? res[0].b : {}, raw = res[1].ok ? res[1].b : {};
      return JSON.stringify({ ref: vm.ref || { kind: kind, id: id }, title: vm.title, strip: vm.strip, panels: vm.panels || [],
        provenance: vm.provenance || raw.provenance || {}, doc: raw.data || {} });
    });
  }

  function run() {
    if (!base) { status('Python runtime not installed: run tools/fetch-pyodide.sh', true); return; }
    if (running) { say('Still running: Stop ends it'); return; }
    var text = code();
    if (!text.trim()) { return; }
    if (!worker) { start(); }
    clearOutput();
    say('');
    var me = ++runSeq;
    running = { id: me, t0: performance.now(), code: text, reads: 0, bytes: 0 };
    runBtn.disabled = true; stopBtn.disabled = false;
    status(ready ? 'Starting…' : 'Starting Python…');
    Promise.all([ready, context()]).then(function (got) {
      if (!running || running.id !== me) { return; }
      worker.postMessage({ type: 'run', id: me, code: text, context: got[1] });
    }).catch(function (e) { finish({ ok: false, error: String(e && e.message || e), ms: performance.now() - running.t0 }); });
  }

  function handle(m) {
    if (m.type === 'request') { answer(m); return; }
    if (!running || (m.id != null && m.id !== running.id)) { return; }
    if (m.type === 'status') { status(m.text); }
    else if (m.type === 'stdout' || m.type === 'stderr') { text(m.text, m.type === 'stderr'); }
    else if (m.type === 'output') { try { render(JSON.parse(m.item)); } catch (e) { text(String(m.item), true); } }
    else if (m.type === 'done') { finish(m); }
  }

  // ---- reads the Python asks for: fetched here, with this page's session -------------------------------------------------
  function urlFor(op, a) {
    var q = [];
    var add = function (k, v) { if (v != null && v !== '') { q.push(encodeURIComponent(k) + '=' + encodeURIComponent(v)); } };
    var path;
    if (op === 'get') { path = '/api/raw/' + encodeURIComponent(a.kind) + '/' + encodeURIComponent(a.id); }
    else if (op === 'search') { path = '/api/calc/search'; add('q', a.q); }
    else if (op === 'columns') { path = '/api/calc/columns/' + encodeURIComponent(a.kind); add('paths', a.paths); add('limit', a.limit); }
    else if (op === 'history') { path = '/api/series/' + encodeURIComponent(a.kind) + '/' + encodeURIComponent(a.id); add('path', a.path); add('days', a.days); }
    else { return null; }
    add('asOf', a.asOf);
    return path + (q.length ? '?' + q.join('&') : '');
  }
  function answer(m) {
    var a = {};
    try { a = JSON.parse(m.args || '{}'); } catch (e) { a = {}; }
    var url = urlFor(m.op, a);
    var reply = function (body) { if (worker) { worker.postMessage({ type: 'reply', rid: m.rid, body: JSON.stringify(body) }); } };
    if (!url) { reply({ ok: false, code: 'DRS-5001', detail: 'unknown read ' + m.op }); return; }
    if (running) { running.reads++; status('Reading ' + m.op + '…'); }
    fetch(url, { credentials: 'same-origin' }).then(function (r) {
      return r.text().then(function (t) {
        if (running) { running.bytes += t.length; }
        var b = {};
        try { b = JSON.parse(t); } catch (e) { b = { detail: t.slice(0, 200) }; }
        if (!r.ok) { reply({ ok: false, status: r.status, code: b.code || ('HTTP-' + r.status), detail: b.detail || r.statusText }); return; }
        reply({ ok: true, data: m.op === 'get' ? b.data : b });
      });
    }).catch(function (e) { reply({ ok: false, code: 'DRS-5003', detail: 'the console could not be reached: ' + e }); });
  }

  function finish(m) {
    var r = running;
    running = null;
    runBtn.disabled = !base; stopBtn.disabled = true;
    if (!r) { return; }
    var total = m.ms != null ? m.ms : performance.now() - r.t0;
    var parts = [m.ok ? 'Done' : 'Failed', secs(total)];
    if (m.loadMs) { parts.push('packages ' + secs(m.loadMs)); }
    if (m.runMs != null) { parts.push('Python ' + secs(m.runMs)); }
    if (r.reads) { parts.push(r.reads + (r.reads === 1 ? ' read, ' : ' reads, ') + Math.round(r.bytes / 1024) + ' KB'); }
    if (m.heapMb) { parts.push('memory ' + m.heapMb + ' MB'); }
    status(parts.join(' · '), !m.ok);
    stats = { ok: !!m.ok, ms: Math.round(total), loadMs: m.loadMs || 0, runMs: m.runMs, reads: r.reads, heapMb: m.heapMb || null };
    drawer.setAttribute('data-calc-last', JSON.stringify(stats));
    if (!m.ok) {
      var box = el('div', 'calc-err');
      box.appendChild(el('strong', null, m.error || 'Error'));
      if (m.traceback) { box.appendChild(el('pre', 'mono', m.traceback)); }
      if (m.details) {                                  // every frame, the runtime's too, behind a toggle (UX-19)
        var more = el('details', 'calc-details');
        more.appendChild(el('summary', null, 'Details'));
        more.appendChild(el('pre', 'mono', m.details));
        box.appendChild(more);
      }
      out.appendChild(box);
      if (m.line && editor) { errLine = m.line - 1; editor.addLineClass(errLine, 'background', 'calc-err-line'); }
    }
    remember({ at: new Date().toISOString(), kind: kind, id: id, code: r.code, ok: !!m.ok, ms: Math.round(total) });
  }

  // ---- output -------------------------------------------------------------------------------------------------------
  function clearOutput() {
    charts.forEach(function (c) { c.dispose(); });
    charts = [];
    out.innerHTML = '';
  }
  function text(t, err) {
    var last = out.lastElementChild;
    if (!last || !last.classList.contains('calc-text') || last.classList.contains('calc-stderr') !== !!err) {
      last = el('pre', 'calc-text mono' + (err ? ' calc-stderr' : ''));
      out.appendChild(last);
    }
    if (last.textContent.length < 200000) { last.textContent += t; }
    else if (!last.hasAttribute('data-capped')) { last.setAttribute('data-capped', ''); last.textContent += '\n… output capped at 200,000 characters\n'; }
  }
  function heading(t) { if (t) { out.appendChild(el('h4', 'calc-h', t)); } }
  function fmt(v) {
    if (v == null) { return ''; }
    if (typeof v === 'number') {
      if (!isFinite(v)) { return ''; }
      var a = Math.abs(v);
      return v.toLocaleString('en-US', { maximumFractionDigits: a >= 1e5 ? 0 : a >= 1 ? 4 : 6 }).replace('-', '−');
    }
    if (typeof v === 'boolean') { return v ? 'true' : 'false'; }
    return String(v);
  }
  function table(item) {
    heading(item.title);
    var wrap = el('div', 'tbl-wrap'), t = el('table', 'tbl calc-tbl');
    t.setAttribute('aria-label', item.title || 'Result');
    var head = el('thead'), tr = el('tr');
    item.columns.forEach(function (c, i) { var th = el('th', item.numeric[i] ? 'num' : null, c); tr.appendChild(th); });
    head.appendChild(tr); t.appendChild(head);
    var body = el('tbody');
    item.rows.forEach(function (row) {
      var r = el('tr');
      row.forEach(function (v, i) {
        var td = el('td', 'mono' + (item.numeric[i] ? ' num' : '') + (typeof v === 'number' && v < 0 ? ' t-neg' : ''), fmt(v));
        r.appendChild(td);
      });
      body.appendChild(r);
    });
    t.appendChild(body); wrap.appendChild(t);
    var foot = el('p', 'calc-foot text-muted-d', item.total.toLocaleString('en-US') + (item.total === 1 ? ' row' : ' rows'));
    out.appendChild(wrap); out.appendChild(foot);
  }
  function tokens() {
    var s = getComputedStyle(document.documentElement), t = {};
    ['ink', 'muted', 'border', 'link', 'accent', 'neg', 'pos', 'surface', 'warn'].forEach(function (k) { t[k] = s.getPropertyValue('--d-' + k).trim(); });
    t.mono = s.getPropertyValue('--d-font-mono').trim();
    return t;
  }
  function chart(item) {
    heading(item.title);
    var box = el('div', 'calc-chart');
    out.appendChild(box);
    if (!window.echarts) { box.textContent = 'Charts need ECharts, which this page did not load.'; return; }
    var t = tokens(), palette = [t.link, t.accent, t.pos, t.warn, t.neg, t.muted];
    var axis = { axisLine: { lineStyle: { color: t.border } }, axisLabel: { color: t.muted, fontFamily: t.mono, fontSize: 10 },
      splitLine: { lineStyle: { color: t.border, opacity: .5 } }, nameTextStyle: { color: t.muted } };
    var type = item.type === 'hist' ? 'bar' : item.type;
    var series = item.series.map(function (s, i) {
      var o = { name: s.name, type: type, color: palette[i % palette.length], symbolSize: type === 'scatter' ? 6 : 4, showSymbol: s.values.length <= 60 };
      o.data = type === 'scatter' ? s.values.map(function (v, k) { return [item.x[k], v]; }) : s.values;
      if (item.type === 'hist') { o.barCategoryGap = '4%'; }
      return o;
    });
    var numericX = type === 'scatter' || item.x.every(function (v) { return typeof v === 'number'; }) && type === 'line';
    var c = window.echarts.init(box, null, { renderer: 'canvas' });
    c.setOption({
      animation: false, color: palette, grid: { left: 56, right: 16, top: series.length > 1 ? 28 : 12, bottom: 36 },
      legend: series.length > 1 ? { top: 0, textStyle: { color: t.muted } } : undefined,
      tooltip: { trigger: type === 'scatter' ? 'item' : 'axis', backgroundColor: t.surface, borderColor: t.border, textStyle: { color: t.ink, fontFamily: t.mono, fontSize: 11 } },
      xAxis: Object.assign({ type: numericX ? 'value' : 'category', data: numericX ? undefined : item.x, name: item.xLabel || '', nameLocation: 'middle', nameGap: 24, scale: true }, axis),
      yAxis: Object.assign({ type: 'value', scale: item.type !== 'hist' && item.type !== 'bar' }, axis),
      series: numericX && type === 'line' ? series.map(function (s) { s.data = s.data.map(function (v, k) { return [item.x[k], v]; }); return s; }) : series
    });
    charts.push(c);
  }
  function render(item) {
    if (item.kind === 'table') { table(item); }
    else if (item.kind === 'chart') { chart(item); }
    else if (item.kind === 'image') {
      heading(item.title);
      var img = el('img', 'calc-img');
      img.alt = item.title || 'Figure';
      img.src = 'data:image/png;base64,' + item.png;
      out.appendChild(img);
    } else if (item.kind === 'note') { out.appendChild(el('p', 'calc-note', item.text)); }
    else { heading(item.title); out.appendChild(el('pre', 'calc-text mono', item.text)); }
  }
  window.addEventListener('resize', function () { charts.forEach(function (c) { c.resize(); }); });

  // ---- history of runs (this browser) ----------------------------------------------------------------------------------
  function remember(entry) {
    var list = [];
    try { list = JSON.parse(load(HKEY) || '[]'); } catch (e) { list = []; }
    if (entry.code.length > 20000) { entry.code = entry.code.slice(0, 20000); }
    list.unshift(entry);
    store(HKEY, JSON.stringify(list.slice(0, 30)));
    drawHistory();
  }
  function drawHistory() {
    var list = [];
    try { list = JSON.parse(load(HKEY) || '[]'); } catch (e) { list = []; }
    hist.innerHTML = '';
    if (!list.length) { hist.appendChild(el('li', 'text-muted-d', 'No runs yet in this browser.')); return; }
    list.forEach(function (h) {
      var li = el('li', 'calc-run');
      var b = el('button', 'calc-run-b');
      b.type = 'button';
      b.title = 'Load this code into the editor';
      var first = (h.code.split('\n').filter(function (l) { return l.trim() && l.trim().charAt(0) !== '#'; })[0] || h.code.split('\n')[0] || '').slice(0, 90);
      b.appendChild(el('span', 'calc-run-when mono', h.at.slice(5, 16).replace('T', ' ')));
      b.appendChild(el('span', h.ok ? 't-ok' : 't-bad', h.ok ? '✓' : '✗'));
      b.appendChild(el('span', 'mono calc-run-code', first));
      b.appendChild(el('span', 'text-muted-d calc-run-where', h.kind + ' ' + h.id + ' · ' + secs(h.ms)));
      b.addEventListener('click', function () { setCode(h.code); tab('out'); });
      li.appendChild(b);
      hist.appendChild(li);
    });
  }
  function tab(which) {
    drawer.querySelectorAll('[data-calc-tab]').forEach(function (b) {
      var on = b.getAttribute('data-calc-tab') === which;
      b.classList.toggle('on', on); b.setAttribute('aria-selected', on ? 'true' : 'false');
    });
    out.hidden = which !== 'out'; hist.hidden = which !== 'history';
  }
  drawer.querySelectorAll('[data-calc-tab]').forEach(function (b) { b.addEventListener('click', function () { tab(b.getAttribute('data-calc-tab')); }); });

  // ---- snippets: the packs' and the user's own ------------------------------------------------------------------------
  function loadSnippets() {
    return fetch('/api/calc/snippets?kind=' + encodeURIComponent(kind)).then(json).then(function (res) {
      if (!res.ok) { say(res.b.detail || 'Snippets could not be read', true); return; }
      snippets = res.b;
      pick.innerHTML = '';
      pick.appendChild(new Option('Snippets…', ''));
      var group = function (label, list, prefix) {
        if (!list.length) { return; }
        var g = document.createElement('optgroup');
        g.label = label;
        list.forEach(function (s, i) { var o = new Option(s.title || s.name, prefix + i); o.title = s.description || ''; g.appendChild(o); });
        pick.appendChild(g);
      };
      group('From the packs', snippets.pack || [], 'p');
      group('Mine', snippets.mine || [], 'm');
      if (mine) { var i = (snippets.mine || []).findIndex(function (s) { return s.name === mine; }); if (i >= 0) { pick.value = 'm' + i; } }
      delBtn.hidden = !mine;
    });
  }
  pick.addEventListener('change', function () {
    var v = pick.value;
    if (!v) { return; }
    var s = v.charAt(0) === 'p' ? snippets.pack[+v.slice(1)] : snippets.mine[+v.slice(1)];
    if (!s) { return; }
    if (code().trim() && !window.confirm('Replace the code in the editor with "' + (s.title || s.name) + '"?')) { pick.value = ''; return; }
    setCode(s.code);
    mine = v.charAt(0) === 'm' ? s.name : null;
    delBtn.hidden = !mine;
    say(s.description || '');
  });
  $('[data-calc-save]').addEventListener('click', function () {
    var name = window.prompt('Save this code as (your snippets, on this server):', mine || '');
    if (name == null || !name.trim()) { return; }
    var body = { code: code(), kind: kind, description: '' };
    fetch('/api/calc/snippets/' + encodeURIComponent(name.trim()), { method: 'PUT', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body) })
      .then(json).then(function (res) {
        if (!res.ok) { say(drsMessage(res.b, 'not saved'), true); return; }
        mine = res.b.name;
        say('Saved as ' + mine + '.');
        loadSnippets();
      });
  });
  delBtn.addEventListener('click', function () {
    if (!mine || !window.confirm('Delete your snippet "' + mine + '"?')) { return; }
    fetch('/api/calc/snippets/' + encodeURIComponent(mine), { method: 'DELETE' }).then(json).then(function (res) {
      if (!res.ok) { say(res.b.detail || 'not deleted', true); return; }
      say('Deleted ' + mine + '.');
      mine = null;
      loadSnippets();
    });
  });

  runBtn.addEventListener('click', run);
  stopBtn.addEventListener('click', stop);
  $('[data-calc-clear]').addEventListener('click', function () { clearOutput(); say(''); });
  window.drishtiCalc = { open: open, run: run, stop: stop, stats: function () { return stats; } };
})();
