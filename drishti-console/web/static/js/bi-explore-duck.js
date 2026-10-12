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
/* Wave C (docs/guides/ANALYSE.md "In the browser"): the Slice and SQL tabs of Analyse run in the browser, on the extract the page already holds, with DuckDB-Wasm
   (vendored, installed by tools/fetch-duckdb-wasm.sh, loaded the first time Slice or SQL needs it). The rows never leave the tab for this: they are the rows the server
   built for the person (row rules and masks applied), and DuckDB runs on a copy in memory. The server path stays as the fallback: no DuckDB-Wasm installed, a browser
   without WebAssembly, a failed start, a notebook with further extracts (the page holds only the first), or bi.explore.browser_engine: server. Slice falls back to
   the JavaScript pivot; SQL falls back to the console's SQLite route.

   The page keeps the strict content security policy. Only the DuckDB worker compiles WebAssembly, under the worker policy (script-src 'self' 'wasm-unsafe-eval',
   connect-src to the console's static files only) that the console sets on that one file. Inside DuckDB the extract is the only table, external access is switched off and
   the configuration locked once the table is loaded, so a query cannot read a file or a URL.

   X.duck.usable()            whether the browser engine can be tried
   X.duck.pivot(ex, slice)    Promise of {rowKeys, colKeys, vals, used, cell(rk, ck)} as X.pivot gives
   X.duck.sql(sql)            Promise of {columns, rows, rowCount, truncated, ms, engine}; rejects with Error(.code): DRS-9070 (not valid), 9072 (a limit), 9073 (refused), as the console's route */
(function () {
  'use strict';
  var B = window.drishtiBi, X = B && B.explore, root = document.getElementById('bx');
  if (!X || !root) { return; }
  var BASE = root.getAttribute('data-duckdb') || '';
  var SQL_ROWS = parseInt(root.getAttribute('data-sql-rows'), 10) || 5000;
  var SQL_SECONDS = parseFloat(root.getAttribute('data-sql-seconds')) || 5;
  var SQL_CHARS = parseInt(root.getAttribute('data-sql-chars'), 10) || 20000;
  var START_SECONDS = parseFloat(root.getAttribute('data-duckdb-start')) || 30;
  var D = (X.duck = {});
  var engine = null;            // {db, conn, worker}
  var loading = null;
  var failed = '';
  var ALLOWED = /^(select|with|from|values|describe|summarize|explain)\b/i;

  D.usable = function () { return !!BASE && !failed && typeof WebAssembly === 'object' && typeof Worker === 'function' && X.extras.length === 0; };
  D.failure = function () { return failed; };

  var qi = function (name) { return '"' + String(name).replace(/"/g, '""') + '"'; };
  var err = function (code, message) { return Object.assign(new Error(code + ' ' + message), { code: code }); };

  function start() {
    if (engine) { return Promise.resolve(engine); }
    if (!loading) {
      loading = import(BASE + '/duckdb-browser.mjs').then(function (mod) {
        var worker = new Worker(BASE + '/duckdb-browser-eh.worker.js');
        var db = new mod.AsyncDuckDB(new mod.VoidLogger(), worker);
        return db.instantiate(BASE + '/duckdb-eh.wasm').then(function () { return db.connect(); }).then(function (conn) {
          engine = { db: db, conn: conn, worker: worker };
          document.body.setAttribute('data-duck-ready', 'true');
          return engine;
        });
      });
      loading = Promise.race([loading, new Promise(function (_, reject) {
        setTimeout(function () { reject(new Error('DuckDB-Wasm did not start within ' + START_SECONDS + ' s (bi.explore.browser_start_seconds)')); }, START_SECONDS * 1000);
      })]).catch(function (e) {
        failed = String(e && e.message || e);
        loading = null;
        reset();
        document.body.setAttribute('data-duck-failed', failed.slice(0, 200));
        throw e;
      });
    }
    return loading;
  }

  var TYPES = { integer: 'BIGINT', decimal: 'DOUBLE' };
  var tableFor = null, tablePromise = null;

  /** Puts the extract in DuckDB as the table `extract` (once for each extract the page holds), then closes the doors: no files, no URLs, no further settings. Reading a file counts
      as external access, so a new extract starts a new engine (its worker is ended) instead of loading into one that is locked. */
  function ensure() {
    var ex = X.ex;
    if (tableFor === ex && tablePromise) { return tablePromise; }
    if (tableFor && tableFor !== ex) { reset(); }
    tableFor = ex;
    tablePromise = start().then(function (e) {
      var cast = ex.columns.map(function (c) { return 'CAST(' + qi(c.name) + ' AS ' + (TYPES[c.type] || 'VARCHAR') + ') AS ' + qi(c.name); });
      var objs = ex.rows.map(function (r) { var o = {}; ex.columns.forEach(function (c, i) { o[c.name] = r[i]; }); return o; });
      var file = 'extract.json';
      return e.db.registerFileText(file, JSON.stringify(objs))
        .then(function () { return e.conn.insertJSONFromPath(file, { name: 'extract_raw', schema: 'main' }); })
        .then(function () { return e.conn.query('CREATE TABLE extract AS SELECT ' + cast.join(', ') + ' FROM extract_raw'); })
        .then(function () { return e.conn.query('DROP TABLE extract_raw'); })
        .then(function () { return e.db.dropFile(file); })
        .then(function () { return e.conn.query('SET enable_external_access = false'); })
        .then(function () { return e.conn.query('SET lock_configuration = true'); })
        .then(function () { return e; });
    }).catch(function (e) { tableFor = null; tablePromise = null; throw e; });
    return tablePromise;
  }

  function reset() {
    if (engine) { try { engine.worker.terminate(); } catch (x) { /* gone */ } }
    engine = null; loading = null; tableFor = null; tablePromise = null;
  }

  function plain(v) {
    if (typeof v === 'bigint') { return Number(v); }
    if (v && typeof v === 'object' && typeof v.toISOString === 'function') { return v.toISOString().slice(0, 10); }
    return v;
  }
  function rowsOf(result) { return result.toArray().map(function (r) { var o = r.toJSON(); return Object.keys(o).map(function (k) { return plain(o[k]); }); }); }

  /** Runs `sql` with a time limit: past it the worker is ended (a query cannot be interrupted) and the next call starts a fresh one. */
  function timed(promise) {
    var timer;
    var limit = new Promise(function (_, reject) {
      timer = setTimeout(function () { reset(); reject(err('DRS-9072', 'the SQL ran longer than ' + SQL_SECONDS + ' s and was stopped (bi.explore.sql_max_seconds)')); }, SQL_SECONDS * 1000);
    });
    return Promise.race([promise, limit]).then(function (v) { clearTimeout(timer); return v; }, function (e) { clearTimeout(timer); throw e; });
  }

  // ---- SQL ------------------------------------------------------------------------------------------------------------------------
  D.sql = function (text) {
    var sql = String(text || '').trim().replace(/;+\s*$/, '');
    if (!sql) { return Promise.reject(err('DRS-9070', 'write one SELECT')); }
    if (sql.length > SQL_CHARS) { return Promise.reject(err('DRS-9072', 'the SQL is over ' + SQL_CHARS + ' characters')); }
    if (sql.indexOf(';') >= 0 || !ALLOWED.test(sql)) { return Promise.reject(err('DRS-9073', 'refused: one read-only statement, a SELECT (or WITH, FROM, VALUES, DESCRIBE, SUMMARIZE)')); }
    var t0 = performance.now();
    return timed(ensure().then(function (e) { return e.conn.query(sql); })).then(function (result) {
      var columns = result.schema.fields.map(function (f) { return f.name; });
      var rows = rowsOf(result);
      var truncated = rows.length > SQL_ROWS;
      return { columns: columns, rows: truncated ? rows.slice(0, SQL_ROWS) : rows, rowCount: Math.min(rows.length, SQL_ROWS), truncated: truncated, ms: Math.round(performance.now() - t0), engine: 'duckdb-wasm' };
    }, function (e) {
      if (e && e.code) { throw e; }
      var text = String(e && e.message || e).split('\n')[0];
      throw err(/external access|permission|configuration|not allowed|locked/i.test(text) ? 'DRS-9073' : 'DRS-9070', text);
    });
  };

  // ---- Slice ----------------------------------------------------------------------------------------------------------------------
  /** The pivot as SQL: one GROUP BY over the filtered extract gives count, sum, min and max of every value field for each (row key, column key); the keys are put in order, and
      the aggregates chosen, in the page (the same code as the JavaScript pivot, so the two agree). */
  D.pivot = function (ex, s) {
    var vals = s.values.length ? s.values : [{ field: '', agg: 'count' }];
    var key = function (f, i) { return "coalesce(CAST(" + qi(f) + " AS VARCHAR), '(blank)') AS " + qi('k' + i); };
    var keys = s.rows.concat(s.columns);
    var picks = keys.map(function (f, i) { return key(f, i); });
    vals.forEach(function (v, i) {
      if (!v.field) { picks.push('count(*) AS n' + i, '0 AS s' + i, 'NULL AS lo' + i, 'NULL AS hi' + i); return; }
      var num = 'try_cast(' + qi(v.field) + ' AS DOUBLE)';
      picks.push('count(' + qi(v.field) + ') AS n' + i, 'coalesce(sum(' + num + '), 0) AS s' + i, 'min(' + num + ') AS lo' + i, 'max(' + num + ') AS hi' + i);
    });
    var where = s.filters.filter(function (f) { return f.value; }).map(function (f) { return "coalesce(CAST(" + qi(f.field) + " AS VARCHAR), '') = '" + String(f.value).replace(/'/g, "''") + "'"; });
    var tail = (where.length ? ' WHERE ' + where.join(' AND ') : '') + (keys.length ? ' GROUP BY ' + keys.map(function (_, i) { return qi('k' + i); }).join(', ') : '');
    var count = 'SELECT count(*) FROM extract' + (where.length ? ' WHERE ' + where.join(' AND ') : '');
    return timed(ensure().then(function (e) { return Promise.all([e.conn.query('SELECT ' + picks.join(', ') + ' FROM extract' + tail), e.conn.query(count)]); })).then(function (both) {
      var table = rowsOf(both[0]), used = Number(rowsOf(both[1])[0][0]);
      var rk = {}, ck = {}, cells = {};
      table.forEach(function (r) {
        var a = r.slice(0, s.rows.length).map(String), b = r.slice(s.rows.length, keys.length).map(String);
        var ka = a.join('\u0001'), kb = b.join('\u0001');
        rk[ka] = a; ck[kb] = b;
        cells[ka + '\u0002' + kb] = vals.map(function (_, i) {
          var o = keys.length + i * 4;
          return { n: Number(r[o]), sum: Number(r[o + 1]), min: r[o + 2] === null ? null : Number(r[o + 2]), max: r[o + 3] === null ? null : Number(r[o + 3]) };
        });
      });
      if (used === 0) { rk = {}; ck = {}; cells = {}; }                                                          // no row kept: no cell, as in the JavaScript pivot
      return X.pivotFrom(rk, ck, cells, vals, used);
    });
  };
})();
