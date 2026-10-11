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
/* Rupaka phase 9, Analyse (docs/guides/ANALYSE.md): the page. It opens a dataset as the reader's masked extract (POST /bi/explore/extract), pivots it in the
   browser (Slice: DuckDB-Wasm is not vendored, so the pivot is plain JavaScript over the extract the page holds), keeps the notebook, and saves or promotes
   through the BI item API. The SQL and Python cells are in bi-explore-cells.js. Text only, never markup. */
(function () {
  'use strict';
  var B = window.drishtiBi, root = document.getElementById('bx');
  if (!B || !root) { return; }
  var h = B.h, q = B.q, qa = B.qa, clear = B.clear;
  var MAX_SHOWN = 500;
  var X = (B.explore = {
    ex: null,                                   // the extract the console sent: {columns[{name,type,masked}], rows, rowCount, total, truncated, masked}
    spec: null,                                 // what to send to rebuild it: {dataset, fields}
    qual: [],                                   // the qualified field names asked for, in the order of the extract's columns
    slice: { rows: [], columns: [], values: [], filters: [] },
    mayPython: root.getAttribute('data-may-python') === 'true',
    item: null,                                 // the notebook item being edited
    model: null
  });

  function say(text, bad) { B.say(q('[data-msg]'), text, bad); }
  X.say = say;
  X.post = function (path, body) {
    return fetch(path, { method: 'POST', headers: { 'Content-Type': 'application/json', Accept: 'application/json' }, credentials: 'same-origin', body: JSON.stringify(body) })
      .then(function (r) {
        return r.json().catch(function () { return {}; }).then(function (d) {
          if (!r.ok) { var e = new Error((d.code ? d.code + ' ' : '') + (d.detail || r.statusText)); e.code = d.code; e.traceback = d.traceback; throw e; }
          return d;
        });
      });
  };

  /** A table of columns and rows (text only), cut to `max` rows. */
  X.table = function (host, columns, rows, max) {
    clear(host);
    var shown = rows.slice(0, max || MAX_SHOWN);
    host.appendChild(h('table', { class: 'tbl bi-tbl', 'data-plain': '' },
      h('thead', {}, h('tr', {}, columns.map(function (c) { return h('th', { scope: 'col', text: String(c) }); }))),
      h('tbody', {}, shown.map(function (r) { return h('tr', {}, r.map(function (v) { return h('td', { class: typeof v === 'number' ? 'num' : '', text: v == null ? '' : String(v) }); })); }))));
    if (rows.length > shown.length) { host.appendChild(h('p', { class: 'text-muted-d', text: 'Showing the first ' + shown.length + ' of ' + rows.length + ' rows.' })); }
  };

  // ---- opening an extract -----------------------------------------------------------------------------------------------------
  var dsSel = q('[data-dataset]', root), fieldsBox = q('[data-fields-box]', root), fieldsEl = q('[data-fields]', root), go = q('[data-open-go]', root);

  function loadDatasets() {
    return B.api('GET', '/datasets').then(function (d) {
      var list = Array.isArray(d) ? d : (d.datasets || []);
      clear(dsSel);
      dsSel.appendChild(h('option', { value: '', text: '(choose a dataset)' }));
      list.forEach(function (d) { dsSel.appendChild(h('option', { value: d.id, text: d.title || d.id })); });
    });
  }

  function loadModel(id) {
    X.model = null; clear(fieldsEl); fieldsBox.hidden = true; go.disabled = true;
    if (!id) { return Promise.resolve(); }
    return B.api('GET', '/datasets/' + B.id2path(id)).then(function (m) {
      X.model = m;
      (m.tables || []).forEach(function (t) {
        var cols = (t.columns || []).filter(function (c) { return c.name !== 'id'; });
        if (!cols.length) { return; }
        fieldsEl.appendChild(h('div', { class: 'bx-table' }, h('strong', { text: t.name }), cols.map(function (c) {
          var q2 = t.name + '.' + c.name;
          return h('label', { class: 'bx-check' }, h('input', { type: 'checkbox', name: 'f', value: q2, checked: !c.masked && cols.indexOf(c) < 8 }),
            ' ' + c.name + (c.masked ? ' (masked)' : ''));
        })));
      });
      fieldsBox.hidden = false; go.disabled = false;
    });
  }
  dsSel.addEventListener('change', function () { loadModel(dsSel.value).catch(function (e) { say(e.message, true); }); });

  /** Opens (or reopens) the extract for `spec` and redraws the tabs. */
  X.open = function (spec) {
    say('Opening the extract…');
    return X.post('/bi/explore/extract', spec).then(function (ex) {
      X.ex = ex; X.spec = spec; X.qual = spec.fields || [];
      var masked = ex.masked.length ? ' Masked for you: ' + ex.masked.join(', ') + '.' : '';
      q('[data-info]', root).textContent = ex.rowCount + ' rows' + (ex.truncated ? ' of ' + ex.total + ' (cut to the row limit)' : '') + ', ' + ex.columns.length + ' columns.' + masked;
      say('');
      X.slice = sliceFits() ? X.slice : { rows: [], columns: [], values: [], filters: [] };
      drawChips(); drawSlice();
      q('[data-save-go]', root).disabled = false;
      document.dispatchEvent(new CustomEvent('bx:extract'));
    }).catch(function (e) { say(e.message, true); throw e; });
  };
  function sliceFits() {
    var names = X.ex.columns.map(function (c) { return c.name; });
    var all = X.slice.rows.concat(X.slice.columns, X.slice.values.map(function (v) { return v.field; }), X.slice.filters.map(function (f) { return f.field; }));
    return all.every(function (n) { return names.indexOf(n) >= 0; });
  }
  q('[data-open]', root).addEventListener('submit', function (e) {
    e.preventDefault();
    var fields = qa('input[name=f]:checked', fieldsEl).map(function (i) { return i.value; });
    if (!dsSel.value || !fields.length) { say('Choose a dataset and at least one field.', true); return; }
    X.open({ dataset: dsSel.value, fields: fields }).catch(function () {});
  });

  // ---- slice ---------------------------------------------------------------------------------------------------------------------
  var AGGS = ['sum', 'avg', 'count', 'min', 'max'];
  function colType(name) { var c = X.ex.columns.filter(function (x) { return x.name === name; })[0]; return c ? c.type : 'string'; }

  function drawChips() {
    var box = q('[data-chips]', root); clear(box);
    X.ex.columns.forEach(function (c) {
      var chip = h('span', { class: 'bx-chip', draggable: 'true', 'data-field': c.name, title: c.type + (c.masked ? ', masked' : '') }, h('span', { text: c.name }));
      [['rows', 'R', 'Rows'], ['columns', 'C', 'Columns'], ['values', 'V', 'Values'], ['filters', 'F', 'Filters']].forEach(function (z) {
        chip.appendChild(h('button', { type: 'button', class: 'bx-mini', 'data-to': z[0], 'aria-label': 'Add ' + c.name + ' to ' + z[2], title: 'Add to ' + z[2], text: z[1] }));
      });
      chip.addEventListener('dragstart', function (e) { e.dataTransfer.setData('text/plain', c.name); e.dataTransfer.effectAllowed = 'copy'; });
      box.appendChild(chip);
    });
  }
  q('[data-chips]', root).addEventListener('click', function (e) {
    var b = e.target.closest('[data-to]');
    if (b) { addTo(b.getAttribute('data-to'), b.parentNode.getAttribute('data-field')); }
  });
  qa('[data-zone]', root).forEach(function (z) {
    z.addEventListener('dragover', function (e) { e.preventDefault(); z.classList.add('bx-over'); });
    z.addEventListener('dragleave', function () { z.classList.remove('bx-over'); });
    z.addEventListener('drop', function (e) { e.preventDefault(); z.classList.remove('bx-over'); addTo(z.getAttribute('data-zone'), e.dataTransfer.getData('text/plain')); });
  });

  function addTo(zone, field) {
    if (!X.ex || X.ex.columns.every(function (c) { return c.name !== field; })) { return; }
    var s = X.slice;
    if (zone === 'values') { s.values.push({ field: field, agg: colType(field) === 'string' ? 'count' : 'sum' }); } else if (zone === 'filters') {
      if (s.filters.every(function (f) { return f.field !== field; })) { s.filters.push({ field: field, value: '' }); }
    } else if (s[zone].indexOf(field) < 0) { s[zone].push(field); }
    drawSlice();
  }

  function drawSlice() {
    var s = X.slice;
    qa('[data-zone]', root).forEach(function (z) {
      var name = z.getAttribute('data-zone'), body = clear(q('[data-zone-body]', z));
      s[name].forEach(function (item, i) {
        var label = typeof item === 'string' ? item : item.field;
        var row = h('span', { class: 'bx-item' }, h('span', { text: label }));
        if (name === 'values') {
          var sel = h('select', { class: 'studio-select', 'aria-label': 'Aggregation of ' + label }, AGGS.map(function (a) { return h('option', { value: a, text: a, selected: a === item.agg }); }));
          sel.addEventListener('change', function () { item.agg = sel.value; drawPivot(); });
          row.appendChild(sel);
        } else if (name === 'filters') {
          var sel2 = h('select', { class: 'studio-select', 'aria-label': 'Keep rows where ' + label + ' is' }, [h('option', { value: '', text: '(all)' })].concat(distinct(label).map(function (v) { return h('option', { value: v, text: v, selected: v === item.value }); })));
          sel2.addEventListener('change', function () { item.value = sel2.value; drawPivot(); });
          row.appendChild(sel2);
        }
        row.appendChild(h('button', { type: 'button', class: 'bx-mini', 'aria-label': 'Remove ' + label + ' from ' + name, text: '×' }));
        row.lastChild.addEventListener('click', function () { s[name].splice(i, 1); drawSlice(); });
        body.appendChild(row);
      });
    });
    drawPivot();
  }
  function distinct(field) {
    var i = X.ex.columns.findIndex(function (c) { return c.name === field; }), seen = {}, out = [];
    X.ex.rows.forEach(function (r) { var v = r[i] == null ? '' : String(r[i]); if (!seen[v] && out.length < 200) { seen[v] = 1; out.push(v); } });
    return out.sort();
  }

  /** The pivot of the extract under the slice: `{rowKeys, colKeys, cell(r, c) -> [values]}` (exposed for tests and the notebook). */
  X.pivot = function (ex, s) {
    var idx = {};
    ex.columns.forEach(function (c, i) { idx[c.name] = i; });
    var rows = ex.rows.filter(function (r) { return s.filters.every(function (f) { return !f.value || String(r[idx[f.field]] == null ? '' : r[idx[f.field]]) === f.value; }); });
    var vals = s.values.length ? s.values : [{ field: '', agg: 'count' }];
    var key = function (r, fields) { return fields.map(function (f) { return r[idx[f]] == null ? '(blank)' : String(r[idx[f]]); }); };
    var rk = {}, ck = {}, cells = {};
    rows.forEach(function (r) {
      var a = key(r, s.rows), b = key(r, s.columns), ka = a.join('\u0001'), kb = b.join('\u0001');
      rk[ka] = a; ck[kb] = b;
      var cell = cells[ka + '\u0002' + kb] = cells[ka + '\u0002' + kb] || vals.map(function () { return { n: 0, sum: 0, min: null, max: null }; });
      vals.forEach(function (v, i) {
        var x = v.field ? r[idx[v.field]] : 1, c = cell[i];
        if (x == null) { return; }
        c.n += 1;
        var num = Number(x);
        if (isFinite(num)) { c.sum += num; c.min = c.min === null ? num : Math.min(c.min, num); c.max = c.max === null ? num : Math.max(c.max, num); }
      });
    });
    var sortKeys = function (o) { return Object.keys(o).sort(function (x, y) { return x.localeCompare(y, undefined, { numeric: true }); }); };
    var out = function (c, v) { return !c ? '' : v.agg === 'count' ? c.n : v.agg === 'sum' ? c.sum : v.agg === 'avg' ? (c.n ? c.sum / c.n : '') : v.agg === 'min' ? c.min : c.max; };
    return {
      rowKeys: sortKeys(rk).map(function (k) { return rk[k]; }), colKeys: sortKeys(ck).map(function (k) { return ck[k]; }), vals: vals, used: rows.length,
      cell: function (a, b) { var c = cells[a.join('\u0001') + '\u0002' + b.join('\u0001')]; return vals.map(function (v, i) { return out(c && c[i], v); }); }
    };
  };

  function drawPivot() {
    var host = clear(q('[data-pivot]', root)), s = X.slice, meta = q('[data-pivot-meta]', root);
    q('[data-promote-slice]', root).disabled = !(s.rows.length || s.columns.length);
    if (!X.ex || !(s.rows.length || s.columns.length || s.values.length)) { meta.textContent = 'Add fields to Rows, Columns or Values.'; return; }
    var p = X.pivot(X.ex, s), many = p.vals.length > 1;
    var head = s.rows.length ? s.rows.slice() : ['(all rows)'];
    var cols = p.colKeys.length && s.columns.length ? p.colKeys : [[]];
    var header = head.slice();
    cols.forEach(function (c) { p.vals.forEach(function (v) { header.push([c.join(' / '), many || !c.length ? (v.field ? v.agg + ' ' + v.field : 'rows') : ''].filter(Boolean).join(' · ') || 'rows'); }); });
    var body = (p.rowKeys.length ? p.rowKeys : [[]]).map(function (rk) {
      var row = s.rows.length ? rk.slice() : ['(all)'];
      cols.forEach(function (c) { p.cell(rk, c.length ? c : []).forEach(function (v) { row.push(typeof v === 'number' ? Math.round(v * 1e6) / 1e6 : v); }); });
      return row;
    });
    meta.textContent = p.used + ' of ' + X.ex.rowCount + ' rows used; ' + body.length + ' pivot rows.';
    X.table(host, header, body, MAX_SHOWN);
  }

  // ---- promote a slice -------------------------------------------------------------------------------------------------------------
  X.qualified = function (name) { var i = X.ex.columns.findIndex(function (c) { return c.name === name; }); return X.qual[i] || name; };
  X.createItem = function (kind, title, body) { return B.api('POST', '/workspaces/me/items', { kind: kind, title: title, description: 'Started in Analyse.', body: body }); };
  q('[data-promote-slice]', root).addEventListener('click', function () {
    var s = X.slice, title = (q('input[name=title]', root).value || 'Slice of ' + (X.spec && X.spec.dataset || 'data')).slice(0, 80);
    var map = function (a) { return a.map(X.qualified); };
    X.post('/bi/explore/promote', { to: 'report', name: title, dataset: X.spec.dataset, slice: { rows: map(s.rows), columns: map(s.columns), values: s.values.map(function (v) { return { field: X.qualified(v.field), agg: v.agg }; }) } })
      .then(function (d) { return X.createItem('report', d.title, d.yaml).then(function (it) { say('Report draft saved in your workspace: ' + it.title + '. ' + d.notes.join(' ')); link(it); }); })
      .catch(function (e) { say(e.message, true); });
  });
  function link(it) { var a = q('[data-saved]', root); a.href = '/bi/i/' + B.id2path(it.id); a.hidden = false; }
  X.link = link;

  // ---- notebooks -----------------------------------------------------------------------------------------------------------------
  X.cells = { sql: [], python: [] };            // [{source, target?}], kept by bi-explore-cells.js
  X.notebook = function () {
    var cells = [{ type: 'slice', slice: JSON.parse(JSON.stringify(X.slice)) }];
    X.cells.sql.forEach(function (c) { cells.push({ type: 'sql', source: c.source }); });
    X.cells.python.forEach(function (c) { cells.push({ type: 'python', source: c.source, target: c.target || 'server' }); });
    return { rachana: 1, notebook: B.slug(q('input[name=title]', root).value || 'analysis') || 'analysis', title: q('input[name=title]', root).value || 'Analysis',
      dataset: X.spec.dataset, fields: X.spec.fields, cells: cells };
  };
  q('[data-save]', root).addEventListener('submit', function (e) {
    e.preventDefault();
    if (!X.spec) { say('Open an extract first.', true); return; }
    var nb = X.notebook();
    X.post('/bi/explore/notebook/check', { notebook: nb }).then(function (c) {
      if (!c.valid) { throw new Error(c.problems.map(function (p) { return p.code + ' ' + p.message; }).join('; ')); }
      var save = X.item && X.item.can && X.item.can.edit ? B.api('PUT', '/items/' + B.id2path(X.item.id), { title: nb.title, body: c.yaml }) : X.createItem('notebook', nb.title, c.yaml);
      return save.then(function (it) { X.item = it; say('Saved as a notebook: ' + nb.title + (it.version ? '' : '.')); link(it); });
    }).catch(function (er) { say(er.message, true); });
  });

  X.restore = function (nb) {
    X.slice = { rows: [], columns: [], values: [], filters: [] };
    X.cells = { sql: [], python: [] };
    (nb.cells || []).forEach(function (c) {
      if (c.type === 'slice') { X.slice = Object.assign({ rows: [], columns: [], values: [], filters: [] }, c.slice); } else if (c.type === 'sql') { X.cells.sql.push({ source: c.source }); } else { X.cells.python.push({ source: c.source, target: c.target || 'server' }); }
    });
    q('input[name=title]', root).value = nb.title || '';
    return X.open({ dataset: nb.dataset, fields: nb.fields });
  };

  var notebookSel = q('[data-notebooks]', root);
  function loadNotebooks() {
    return B.api('GET', '/catalogue?kind=notebook').then(function (d) {
      (d.items || []).forEach(function (it) { notebookSel.appendChild(h('option', { value: it.id, text: it.title })); });
    }).catch(function () {});
  }
  function openNotebook(id) {
    return B.api('GET', '/items/' + B.id2path(id)).then(function (it) {
      return X.post('/bi/explore/notebook/check', { yaml: it.body || '' }).then(function (c) {
        if (!c.valid) { throw new Error(c.problems.map(function (p) { return p.code + ' ' + p.message; }).join('; ')); }
        X.item = it;
        return X.restore(c.notebook);
      });
    });
  }
  notebookSel.addEventListener('change', function () { if (notebookSel.value) { openNotebook(notebookSel.value).catch(function (e) { say(e.message, true); }); } });

  // ---- tabs --------------------------------------------------------------------------------------------------------------------------
  var tabs = qa('[data-tab]', root);
  function showTab(name) {
    tabs.forEach(function (t) { var on = t.getAttribute('data-tab') === name; t.setAttribute('aria-selected', on ? 'true' : 'false'); t.tabIndex = on ? 0 : -1; t.classList.toggle('btn-accent', on); });
    qa('[data-pane]', root).forEach(function (p) { p.hidden = p.getAttribute('data-pane') !== name; });
  }
  tabs.forEach(function (t, i) {
    t.addEventListener('click', function () { showTab(t.getAttribute('data-tab')); });
    t.addEventListener('keydown', function (e) {
      var n = e.key === 'ArrowRight' ? i + 1 : e.key === 'ArrowLeft' ? i - 1 : -1;
      if (n >= 0) { var t2 = tabs[(n + tabs.length) % tabs.length]; showTab(t2.getAttribute('data-tab')); t2.focus(); e.preventDefault(); }
    });
  });
  showTab('slice');

  loadDatasets().then(loadNotebooks).then(function () {
    var want = new URLSearchParams(window.location.search).get('item');
    if (want) { notebookSel.value = want; return openNotebook(want); }
    return null;
  }).catch(function (e) { say(e.message, true); });
})();
