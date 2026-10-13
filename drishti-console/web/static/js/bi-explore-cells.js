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
  /** Drishti SQL runs on the server over the model the extract reads (the default, BI_DESIGN.md 3.4); a version 1 dataset only has its extract. */
  function modelOf() { var d = (X.spec && X.spec.dataset) || ''; return d.indexOf('mq:') === 0 ? d.slice(3) : d.indexOf('dataset:') === 0 ? d : ''; }
  function sqlEngine() { return modelOf() ? 'server' : 'extract'; }
  /** A first statement for a new cell: over the model, a field and a measure grouped (written by the Query panel's printer); over the extract, its rows. */
  function sqlStarter(engine) {
    var m = X.model;
    if (engine !== 'server' || !m || !B.mq || !B.mq.sql) { return STARTER.sql; }
    var field = (X.qual || []).filter(function (f) { var p = f.split('.'); var t = (m.tables || []).filter(function (x) { return x.name === p[0]; })[0]; var c = t && t.columns.filter(function (x) { return x.name === p[1]; })[0]; return c && c.type === 'string' && !c.masked; })[0];
    var measure = ((m.measures || [])[0] || {}).name;
    return B.mq.sql({ mq: 1, select: { fields: field ? [field] : [], measures: measure ? [measure] : [] }, limit: 20 }, m) || STARTER.sql;
  }
  function newCell(kind) { var engine = kind === 'sql' ? sqlEngine() : ''; return { source: kind === 'sql' ? sqlStarter(engine) : STARTER[kind], target: 'server', engine: engine }; }

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
        [['measure', 'Promote to a measure'], ['visual', 'Promote to a visual'], ['transform', 'Promote to a Transform']].forEach(function (p) {
          var b = h('button', { type: 'button', class: 'btn-pill btn-ghost', 'data-promote': p[0], text: p[1] });
          if (p[0] === 'transform' && !X.extras.length) {                                          // a Transform joins extracts: it needs a second one
            b.disabled = true; b.setAttribute('aria-describedby', 'bxNoTransform' + i);
            bar.appendChild(b);
            bar.appendChild(h('small', { class: 'text-muted-d', id: 'bxNoTransform' + i, 'data-no-transform': '', text: 'Add a second extract first (More extracts, above).' }));
            return;
          }
          b.addEventListener('click', function () { promote(cell, p[0], b); });
          bar.appendChild(b);
        });
      }
      if (kind === 'sql') {
        var eng = h('select', { class: 'studio-select', 'data-sql-engine': '', 'aria-label': 'Where cell ' + (i + 1) + ' runs' },
          h('option', { value: 'server', text: 'Server: Drishti SQL over the model', disabled: !modelOf() }), h('option', { value: 'extract', text: 'Over the extract (until wave B)' }));
        eng.value = cell.engine || sqlEngine();
        eng.addEventListener('change', function () {
          var was = cell.engine;
          cell.engine = eng.value;
          if (area.value === sqlStarter(was) || area.value === STARTER.sql) { cell.source = area.value = sqlStarter(cell.engine); }       // an untouched starter follows the engine
        });
        bar.appendChild(eng);
        var pb = h('button', { type: 'button', class: 'btn-pill btn-ghost', 'data-promote-report': '' }, 'Promote to a report visual');
        pb.addEventListener('click', function () { X.promoteReport({ sql: cell.source }, 'SQL', pb); });
        bar.appendChild(pb);
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
      var showSql = function (r, engine) {
        clear(cell.out);
        var where = engine === 'dsql' ? 'Drishti SQL, run by the server over ' + r.model + ' as you' + (r.plan ? ' · plan ' + String(r.plan).slice(0, 12) : '') + (r.masked && r.masked.length ? ' · masked: ' + r.masked.join(', ') : '')
          : engine === 'server' ? 'over the extract, run by the console (SQLite)' : 'over the extract, DuckDB in this browser';
        cell.out.appendChild(h('p', { class: 'bi-result-meta', 'data-engine': engine, text: r.rowCount + ' rows' + (r.truncated ? ' (cut to the SQL row limit)' : '') + ' in ' + r.ms + ' ms · ' + where }));
        var host = h('div', { class: 'tbl-wrap' });
        cell.out.appendChild(host);
        X.table(host, r.columns, r.rows, 200);
      };
      // the server is the default engine (Drishti SQL over the model); a statement over the table `extract` (an older notebook) still runs over the extract
      var overExtract = cell.engine === 'extract' || !modelOf() || /\b(from|join)\s+extract\b/i.test(cell.source || '');
      if (!overExtract) {
        return X.post('/bi/explore/dsql', { extract: spec(), sql: cell.source }).then(function (r) { showSql(r, 'dsql'); }).catch(function (e) { note(cell.out, e.message, true); });
      }
      var viaServer = function () { return X.post('/bi/explore/sql', { extract: spec(), extracts: X.extras, sql: cell.source }).then(function (r) { showSql(r, 'server'); }); };
      if (X.duck && X.duck.usable() && X.engine !== 'server') {                                  // DuckDB-Wasm on the extract in this page; the console's SQLite when it cannot start
        return X.duck.sql(cell.source).then(function (r) { showSql(r, 'duckdb-wasm'); }, function (e) {
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

  function promote(cell, to, anchor) {
    var title = (q('input[name=title]', root).value || 'Analyse cell') + ' (' + to + ')';
    var body = { to: to, name: title, source: cell.source, extracts: X.extras, columns: X.ex ? X.ex.columns.map(function (c) { return c.name; }) : [] };
    X.promoteOnce(anchor, 'python:' + to + ':' + cell.source, function (existing) {
      return X.post('/bi/explore/promote', body).then(function (d) {
        var saved = existing ? B.api('PUT', '/items/' + B.id2path(existing), { body: d.yaml }) : X.createItem('python', d.title, d.yaml);
        return saved.then(function (it) { return { item: it, text: 'Python ' + to + (existing ? ' draft updated: ' : ' draft saved in your workspace: ') + it.title + '. ' + d.notes.join(' ') }; });
      });
    });
  }

  function runAll(kind) {
    return X.cells[kind].reduce(function (p, c) { return p.then(function () { return runCell(kind, c); }); }, Promise.resolve());
  }

  ['sql', 'python'].forEach(function (kind) {
    q('[data-add=' + kind + ']', root).addEventListener('click', function () { X.cells[kind].push(newCell(kind)); draw(kind); });
    q('[data-run-all=' + kind + ']', root).addEventListener('click', function () { runAll(kind); });
  });
  document.addEventListener('bx:extras', function () { draw('python'); });                        // a cell that reads extract(...) can be promoted to a Transform
  document.addEventListener('bx:extract', function () {
    ['sql', 'python'].forEach(function (kind) { if (!X.cells[kind].length) { X.cells[kind].push(newCell(kind)); } draw(kind); });
  });
  if (!X.mayPython) { qa('[data-pane=python] button', root).forEach(function (b) { b.disabled = true; b.title = 'Running Python needs the bi-author power'; }); }
})();
