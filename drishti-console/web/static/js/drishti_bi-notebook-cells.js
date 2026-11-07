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
/* The Notebook (docs/guides/USER_GUIDE.md › The Notebook): the SQL and Python cells of the page. SQL is Drishti SQL, run by the server over the model
   as the reader. A Python cell runs on the server's compute pool (POST /bi/notebook/python: the server reads the extracts as the reader and runs the
   cell in its strict sandbox); where the server cannot run Python safely, Run is off and says why. Nothing runs in the console or the browser. Output is data drawn as text, a table, a chart (ECharts,
   formatter strings removed) or an image; never markup. */
(function () {
  'use strict';
  // the product's name from configuration (ui.product, on <body data-product>); its SQL is "<product> SQL"
  const PRODUCT = (document.body && document.body.dataset.product) || '', SQL_NAME = (PRODUCT + ' SQL').trim();
  var B = window.drishtiBi, X = B && B.notebook;
  if (!X) { return; }
  var h = B.h, q = B.q, qa = B.qa, clear = B.clear, root = document.getElementById('bx');
  var STARTER = { sql: '', python: "print(df.describe())\nresult = df.head(10)" };
  /** A first statement for a new cell: over the model, a field and a measure grouped (written by the Query panel's printer). */
  function sqlStarter() {
    var m = X.model;
    if (!m || !B.mq || !B.mq.sql) { return STARTER.sql; }
    var field = (X.qual || []).filter(function (f) { var p = f.split('.'); var t = (m.tables || []).filter(function (x) { return x.name === p[0]; })[0]; var c = t && t.columns.filter(function (x) { return x.name === p[1]; })[0]; return c && c.type === 'string' && !c.masked; })[0];
    var measure = ((m.measures || [])[0] || {}).name;
    return B.mq.sql({ mq: 1, select: { fields: field ? [field] : [], measures: measure ? [measure] : [] }, limit: 20 }, m) || STARTER.sql;
  }
  function newCell(kind) { return { source: kind === 'sql' ? sqlStarter() : STARTER[kind], target: 'server' }; }

  function spec() { return X.spec ? { dataset: X.spec.dataset, fields: X.spec.fields, filters: X.spec.filters, dates: X.spec.dates, report: X.spec.report, page: X.spec.page, visual: X.spec.visual, state: X.spec.state } : null; }

  function draw(kind) {
    var host = clear(q('[data-cells=' + kind + ']', root));
    X.cells[kind].forEach(function (cell, i) {
      var area = h('textarea', { class: 'studio-in mono bx-code', rows: kind === 'sql' ? Math.min(10, Math.max(3, String(cell.source || '').split('\n').length)) : 6, spellcheck: 'false', 'aria-label': kind.toUpperCase() + ' cell ' + (i + 1), maxlength: 65536 });
      area.value = cell.source || '';
      area.addEventListener('input', function () { cell.source = area.value; });
      var out = h('div', { class: 'bx-out', 'data-out': '', role: 'region', 'aria-label': 'Output of cell ' + (i + 1), 'aria-live': 'polite' });
      cell.out = out;
      var bar = h('div', { class: 'bx-bar' });
      var run = h('button', { type: 'button', class: 'btn-pill btn-accent', 'data-run': '' }, B.icon('play-fill'), ' Run');
      run.addEventListener('click', function () { runCell(kind, cell); });
      bar.appendChild(run);
      if (kind === 'python') {
        bar.appendChild(h('small', { class: 'text-muted-d', 'data-runs-on': 'server', text: "Runs on the server's compute pool" }));
        if (!X.pythonRuns) {                                                                        // the server cannot run Python safely: it says why
          run.disabled = true; run.title = 'Python does not run on this server: ' + (X.pythonWhy || 'see Help');
          bar.appendChild(h('small', { class: 'text-muted-d', 'data-python-off': '', text: 'Off: ' + (X.pythonWhy || 'Python does not run on this server') }));
        }
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
        bar.appendChild(h('small', { class: 'text-muted-d', 'data-sql-engine': 'server', text: SQL_NAME + ', run by the server over the model' }));
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

  /** One Python cell on the server: what it printed, then its result (a table, a chart option, an SVG or PNG picture, or a value). */
  function runPython(cell) {
    if (!X.pythonRuns) { note(cell.out, 'Python does not run on this server: ' + (X.pythonWhy || ''), true); return Promise.resolve(); }
    note(cell.out, 'Running on the server…');
    return X.post('/bi/notebook/python', { extract: spec(), extracts: X.extras, source: cell.source }).then(function (r) {
      clear(cell.out);
      cell.out.appendChild(h('p', { class: 'bi-result-meta', 'data-engine': 'python', 'data-target': r.target,
        text: 'Ran on the server\'s compute pool in ' + r.ms + ' ms over ' + r.inputRows + ' rows' + (r.truncatedInput ? ' (cut to the extract limit)' : '') + (r.masked && r.masked.length ? ' · masked: ' + r.masked.join(', ') : '') }));
      if (r.stdout) { cell.out.appendChild(h('pre', { class: 'bx-stdout', 'data-stdout': '', text: r.stdout })); }
      var host = h('div', { class: 'bx-result', 'data-result': '' });
      cell.out.appendChild(host);
      if (r.table) {
        X.table(host, r.table.columns, r.table.rows, 200);
      } else if (r.option) {
        B.python.draw(host, { title: 'Cell result' }, { t: {} }, { kind: 'echarts', option: r.option });
      } else if (r.svg || r.png) {
        B.python.draw(host, { title: 'Cell result' }, { t: {} }, r.svg ? { kind: 'svg', svg: r.svg } : { kind: 'png', png: r.png });
      } else if (r.value !== undefined) {
        host.appendChild(h('pre', { class: 'bx-stdout', 'data-value': '', text: JSON.stringify(r.value, null, 1) }));
      }
    }).catch(function (e) {
      note(cell.out, e.message, true);
      if (e.traceback) { cell.out.appendChild(h('pre', { class: 'bx-stdout', 'data-traceback': '', text: e.traceback })); }
    });
  }

  function runCell(kind, cell) {
    if (!X.ex) { note(cell.out, 'Open an extract first.', true); return Promise.resolve(); }
    if (kind === 'python') { return runPython(cell); }                                            // Python runs on the server's compute pool, never here
    note(cell.out, 'Running…');
    return X.post('/bi/notebook/sql', { extract: spec(), sql: cell.source }).then(function (r) {
      clear(cell.out);
      var where = SQL_NAME + ', run by the server over ' + r.model + ' as you' + (r.plan ? ' · plan ' + String(r.plan).slice(0, 12) : '') + (r.masked && r.masked.length ? ' · masked: ' + r.masked.join(', ') : '');
      cell.out.appendChild(h('p', { class: 'bi-result-meta', 'data-engine': 'dsql', text: r.rowCount + ' rows' + (r.truncated ? ' (cut to the SQL row limit)' : '') + ' in ' + r.ms + ' ms · ' + where }));
      var host = h('div', { class: 'tbl-wrap' });
      cell.out.appendChild(host);
      X.table(host, r.columns, r.rows, 200);
    }).catch(function (e) { note(cell.out, e.message, true); });
  }

  function promote(cell, to, anchor) {
    var title = (q('input[name=title]', root).value || 'Notebook cell') + ' (' + to + ')';
    var body = { to: to, name: title, source: cell.source, extracts: X.extras, columns: X.ex ? X.ex.columns.map(function (c) { return c.name; }) : [] };
    X.promoteOnce(anchor, 'python:' + to + ':' + cell.source, function (existing) {
      return X.post('/bi/notebook/promote', body).then(function (d) {
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
    q('[data-run-all=' + kind + ']', root).addEventListener('click', function () { runAll(kind); });   // Python's is off where the server cannot run it safely
  });
  document.addEventListener('drishti:bx-extras', function () { draw('python'); });                        // a cell that reads extract(...) can be promoted to a Transform
  document.addEventListener('drishti:bx-extract', function () {
    ['sql', 'python'].forEach(function (kind) { if (!X.cells[kind].length) { X.cells[kind].push(newCell(kind)); } draw(kind); });
  });
  if (!X.mayPython) { qa('[data-pane=python] [data-run], [data-pane=python] [data-run-all]', root).forEach(function (b) { b.disabled = true; b.title = 'Running Python needs the bi-author power'; }); }
})();
