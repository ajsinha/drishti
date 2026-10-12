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
/* Rupaka phase 9, Analyse (docs/guides/ANALYSE.md): the SQL and Python cells of the page. SQL runs on the console over the reader's extract (read-only, limited);
   a Python cell runs on the console's sandboxed worker (server) or in Pyodide (browser), with `df` and, on the server, `quant`. Output is data drawn as text,
   a table, a chart (ECharts, formatter strings removed by the runner) or an image; never markup. */
(function () {
  'use strict';
  var B = window.drishtiBi, X = B && B.explore;
  if (!X) { return; }
  var h = B.h, q = B.q, qa = B.qa, clear = B.clear, root = document.getElementById('bx');
  var STARTER = { sql: 'SELECT * FROM extract LIMIT 20', python: "print(df.describe())\nresult = df.head(10)" };

  function spec() { return X.spec ? { dataset: X.spec.dataset, fields: X.spec.fields, filters: X.spec.filters, dates: X.spec.dates, report: X.spec.report, page: X.spec.page, visual: X.spec.visual, state: X.spec.state } : null; }

  function draw(kind) {
    var host = clear(q('[data-cells=' + kind + ']', root));
    X.cells[kind].forEach(function (cell, i) {
      var area = h('textarea', { class: 'studio-in mono bx-code', rows: kind === 'sql' ? 3 : 6, spellcheck: 'false', 'aria-label': kind.toUpperCase() + ' cell ' + (i + 1), maxlength: 65536 });
      area.value = cell.source || '';
      area.addEventListener('input', function () { cell.source = area.value; });
      var out = h('div', { class: 'bx-out', 'data-out': '', role: 'region', 'aria-label': 'Output of cell ' + (i + 1), 'aria-live': 'polite' });
      cell.out = out;
      var bar = h('div', { class: 'bx-bar' });
      var run = h('button', { type: 'button', class: 'btn-pill btn-accent', 'data-run': '' }, B.icon('play-fill'), ' Run');
      run.addEventListener('click', function () { runCell(kind, cell); });
      bar.appendChild(run);
      if (kind === 'python') {
        var target = h('select', { class: 'studio-select', 'aria-label': 'Where cell ' + (i + 1) + ' runs' }, h('option', { value: 'server', text: 'Server (sandbox)' }), h('option', { value: 'browser', text: 'Browser (Pyodide)' }));
        target.value = cell.target || 'server';
        target.addEventListener('change', function () { cell.target = target.value; });
        bar.appendChild(target);
        [['measure', 'Promote to a measure'], ['visual', 'Promote to a visual']].concat(X.extras.length ? [['transform', 'Promote to a Transform']] : []).forEach(function (p) {
          var b = h('button', { type: 'button', class: 'btn-pill btn-ghost', 'data-promote': p[0], text: p[1] });
          b.addEventListener('click', function () { promote(cell, p[0]); });
          bar.appendChild(b);
        });
      }
      var del = h('button', { type: 'button', class: 'btn-pill btn-ghost', 'aria-label': 'Delete cell ' + (i + 1) }, B.icon('trash'));
      del.addEventListener('click', function () { X.cells[kind].splice(i, 1); draw(kind); });
      bar.appendChild(del);
      host.appendChild(h('div', { class: 'bx-cell', 'data-kind': kind }, area, bar, out));
    });
  }

  function note(out, text, bad) { clear(out).appendChild(h('p', { class: bad ? 'bi-msg bi-bad' : 'text-muted-d', role: bad ? 'alert' : 'status', text: text })); }

  function runCell(kind, cell) {
    if (!X.ex) { note(cell.out, 'Open an extract first.', true); return Promise.resolve(); }
    note(cell.out, 'Running…');
    if (kind === 'sql') {
      var show = function (r, engine) {
        clear(cell.out);
        cell.out.appendChild(h('p', { class: 'bi-result-meta', 'data-engine': engine, text: r.rowCount + ' rows' + (r.truncated ? ' (cut to the SQL row limit)' : '') + ' in ' + r.ms + ' ms · ' + (engine === 'server' ? 'run by the console (SQLite)' : 'DuckDB in this browser') }));
        var host = h('div', { class: 'tbl-wrap' });
        cell.out.appendChild(host);
        X.table(host, r.columns, r.rows, 200);
      };
      var viaServer = function () { return X.post('/bi/explore/sql', { extract: spec(), extracts: X.extras, sql: cell.source }).then(function (r) { show(r, 'server'); }); };
      if (X.duck && X.duck.usable() && X.engine !== 'server') {                                  // DuckDB-Wasm on the extract in this page; the console's SQLite when it cannot start
        return X.duck.sql(cell.source).then(function (r) { show(r, 'duckdb-wasm'); }, function (e) {
          if (e && e.code) { throw e; }
          return viaServer();
        }).catch(function (e) { note(cell.out, e.message, true); });
      }
      return viaServer().catch(function (e) { note(cell.out, e.message, true); });
    }
    var done = function (r) { show(cell.out, r); };
    var fail = function (e) { note(cell.out, e.message, true); if (e.traceback) { cell.out.appendChild(h('pre', { class: 'bx-pre', text: e.traceback })); } };
    if (cell.target === 'browser') { return B.python.runCell(cell.source, X.ex).then(done, fail); }
    return X.post('/bi/explore/python', { extract: spec(), extracts: X.extras, source: cell.source }).then(done, fail);
  }

  function show(out, r) {
    clear(out);
    out.appendChild(h('p', { class: 'bi-result-meta', 'data-py-foot': r.target, text: 'Python · ' + r.target + ' · ' + r.ms + ' ms · ' + r.inputRows + ' rows' }));
    if (r.stdout) { out.appendChild(h('pre', { class: 'bx-pre', 'data-stdout': '', text: r.stdout })); }
    if (r.table) {
      var host = h('div', { class: 'tbl-wrap' }); out.appendChild(host);
      X.table(host, r.table.columns, r.table.rows, 200);
    } else if (r.option || r.svg || r.png) {
      var box = h('div', { class: 'bx-chart' }); out.appendChild(box);
      B.python.draw(box, { title: 'Python result', alt: 'Result of the Python cell' }, { t: B.tokens(box), palette: null }, { kind: r.option ? 'echarts' : r.svg ? 'svg' : 'png', option: r.option, svg: r.svg, png: r.png });
    } else if (r.value !== undefined) { out.appendChild(h('pre', { class: 'bx-pre', 'data-value': '', text: typeof r.value === 'string' ? r.value : JSON.stringify(r.value, null, 1) })); }
  }

  function promote(cell, to) {
    var title = (q('input[name=title]', root).value || 'Analyse cell') + ' (' + to + ')';
    X.post('/bi/explore/promote', { to: to, name: title, source: cell.source, extracts: X.extras, columns: X.ex ? X.ex.columns.map(function (c) { return c.name; }) : [] })
      .then(function (d) { return X.createItem('python', d.title, d.yaml).then(function (it) { X.say('Python ' + to + ' draft saved in your workspace: ' + it.title + '. ' + d.notes.join(' ')); X.link(it); }); })
      .catch(function (e) { X.say(e.message, true); });
  }

  function runAll(kind) {
    return X.cells[kind].reduce(function (p, c) { return p.then(function () { return runCell(kind, c); }); }, Promise.resolve());
  }

  ['sql', 'python'].forEach(function (kind) {
    q('[data-add=' + kind + ']', root).addEventListener('click', function () { X.cells[kind].push({ source: STARTER[kind], target: 'server' }); draw(kind); });
    q('[data-run-all=' + kind + ']', root).addEventListener('click', function () { runAll(kind); });
  });
  document.addEventListener('bx:extras', function () { draw('python'); });                        // a cell that reads extract(...) can be promoted to a Transform
  document.addEventListener('bx:extract', function () {
    ['sql', 'python'].forEach(function (kind) { if (!X.cells[kind].length) { X.cells[kind].push({ source: STARTER[kind], target: 'server' }); } draw(kind); });
  });
  if (!X.mayPython) { qa('[data-pane=python] button', root).forEach(function (b) { b.disabled = true; b.title = 'Running Python needs the bi-author power'; }); }
})();
