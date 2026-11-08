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
(function (g) { g.Drishti = g.Drishti || {}; })(typeof window !== 'undefined' ? window : globalThis); /* one namespace for everything the console puts on window (tools/dev/asset_prefix.py) */
/* The Notebook (docs/guides/USER_GUIDE.md › The Notebook): the page. It opens a model as the reader's masked extract (POST /bi/notebook/extract), keeps the
   notebook, and saves or promotes through the BI item API. The SQL and Python cells are in drishti_bi-notebook-cells.js; slicing is the Pivot's.
   Text only, never markup. */
(function () {
  'use strict';
  var B = window.drishtiBi, root = document.getElementById('bx');
  if (!B || !root) { return; }
  var h = B.h, q = B.q, qa = B.qa, clear = B.clear;
  var MAX_SHOWN = 500;
  var X = (B.notebook = {
    ex: null,                                   // the extract the console sent: {columns[{name,type,masked}], rows, rowCount, total, truncated, masked}
    spec: null,                                 // what to send to rebuild it: {dataset, fields}
    qual: [],                                   // the qualified field names asked for, in the order of the extract's columns
    extras: [],                                 // the notebook's other extracts: [{name, dataset, fields}], read by a cell as extract("name")
    mayPython: root.getAttribute('data-may-python') === 'true',
    pythonRuns: root.getAttribute('data-python-runs') === 'true',   // false: the server cannot run Python safely (data-python-why says why)
    pythonWhy: root.getAttribute('data-python-why') || '',
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

  /** A table of columns and rows as the cell answered them, cut to `max` rows, drawn by drishti-grid (Drishti Elements: sortable, the
      first column pinned; it sums nothing), as tall as its rows up to 15 before it scrolls. */
  X.table = function (host, columns, rows, max) {
    clear(host);
    var E = window.Drishti && window.Drishti.elements;
    if (!E || !(E.grid || E.load)) { host.appendChild(h('p', { class: 'text-muted-d', text: 'The table needs the Elements grid (drishti-grid), which this page did not load.' })); return; }
    if (!E.grid) { E.load('drishti-grid').then(function () { X.table(host, columns, rows, max); }); return; }
    var shown = rows.slice(0, max || MAX_SHOWN);
    var cols = columns.map(function (c, i) {
      var num = shown.some(function (r) { return typeof r[i] === 'number'; });
      return { id: 'c' + i, field: 'c' + i, header: String(c), type: num ? 'number' : 'text', pinned: i === 0 ? 'left' : null, enableRowGroup: false, enablePivot: false,
        enableValue: false, format: function (v) { return v == null ? '' : String(v); } };
    });
    var data = shown.map(function (r, k) { var o = { __k: String(k) }; r.forEach(function (v, i) { o['c' + i] = v; }); return o; });
    var box = h('div', { class: 'bi-nb-grid', 'data-grid': '' });
    box.style.height = (Math.min(Math.max(data.length, 1), 15) + 2) * 28 + 6 + 'px';
    host.appendChild(box);
    new E.grid.Grid(box, { columns: cols, rows: data, getRowId: function (r) { return r.__k; }, layout: 'wells', density: 'compact', chrome: false,
      checkboxes: false, rowSelection: 'none', clientAggregation: false, label: 'The cell\u2019s result' });
    if (rows.length > shown.length) { host.appendChild(h('p', { class: 'text-muted-d', text: 'Showing the first ' + shown.length + ' of ' + rows.length + ' rows.' })); }
  };

  // ---- opening an extract -----------------------------------------------------------------------------------------------------
  var dsSel = q('[data-dataset]', root), fieldsBox = q('[data-fields-box]', root), fieldsEl = q('[data-fields]', root), go = q('[data-open-go]', root);

  /** The sources a person can open, grouped: models, their datasets, each pack's datasets, then the version 1 datasets. Shared with the extra extracts (drishti_bi-notebook-extracts.js). */
  X.sources = function () {
    return B.layer.groups({ legacy: function () { return B.api('GET', '/datasets').then(function (d) { return Array.isArray(d) ? d : (d.datasets || []); }); } });
  };
  /** The fields of a source in the extract's shape ({title, tables[{name, columns[]}]}): a model or a dataset through its model, a version 1 dataset as it is. */
  X.fieldsOf = function (value) {
    if (/^(mq|dataset):/.test(value)) {
      var ref = value.indexOf('mq:') === 0 ? value.slice(3) : value;
      return B.layer.get('fields?ref=' + encodeURIComponent(ref));
    }
    return B.api('GET', '/datasets/' + B.id2path(value));
  };
  function loadDatasets() {
    return X.sources().then(function (groups) { B.layer.fillSelect(dsSel, groups, '(choose a model or a dataset)'); });
  }

  var NUMERIC = /^(int|integer|long|short|float|double|decimal|number|numeric|bigint)/i;
  /** Loads the dataset's fields as the checklist. `ticked` (the qualified names a notebook was saved with) decides the ticks; else the first eight columns of a table and every numeric one (the measures: notional, mtm, pnl1d) are ticked. */
  function loadModel(id, ticked) {
    X.model = null; clear(fieldsEl); fieldsBox.hidden = true; go.disabled = true;
    if (!id) { return Promise.resolve(); }
    return X.fieldsOf(id).then(function (m) {
      X.model = m;
      // a model with two facts (trades and netting sets): one extract is one grain, so only the first fact (and the dimensions) is ticked to begin with
      var facts = (m.tables || []).filter(function (t) { return (t.kind || 'fact') === 'fact'; });
      var startFact = facts.length > 1 ? facts[0].name : '';
      (m.tables || []).forEach(function (t) {
        var cols = (t.columns || []).filter(function (c) { return c.name !== 'id'; });
        if (!cols.length) { return; }
        var otherFact = startFact && (t.kind || 'fact') === 'fact' && t.name !== startFact;
        fieldsEl.appendChild(h('div', { class: 'bx-table', 'data-bx-table': t.name }, h('strong', { text: t.name }), otherFact ? h('small', { class: 'text-muted-d', 'data-bx-other-fact': t.name, text: ' another fact: tick its fields to analyse it instead (one fact per extract)' }) : null, cols.map(function (c) {
          var q2 = t.name + '.' + c.name;
          var on = ticked ? ticked.indexOf(q2) >= 0 : !otherFact && !c.masked && (cols.indexOf(c) < 8 || NUMERIC.test(c.type || ''));
          return h('label', { class: 'bx-check' }, h('input', { type: 'checkbox', name: 'f', value: q2, checked: on }),
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
    return X.post('/bi/notebook/extract', spec).then(function (ex) {
      X.ex = ex; X.spec = spec; X.qual = spec.fields || [];
      var masked = ex.masked.length ? ' Masked for you: ' + ex.masked.join(', ') + '.' : '';
      q('[data-info]', root).textContent = ex.rowCount + ' rows' + (ex.truncated ? ' of ' + ex.total + ' (cut to the row limit)' : '') + ', ' + ex.columns.length + ' columns.' + masked;
      say('');
      q('[data-save-go]', root).disabled = false;
      document.dispatchEvent(new CustomEvent('drishti:bx-extract'));
    }).catch(function (e) { say(e.message, true); throw e; });
  };
  X.time = B.consume && q('[data-time]', root) ? B.consume.timePicker(q('[data-time]', root), function () {}) : null;
  q('[data-open]', root).addEventListener('submit', function (e) {
    e.preventDefault();
    var fields = qa('input[name=f]:checked', fieldsEl).map(function (i) { return i.value; });
    if (!dsSel.value || !fields.length) { say('Choose a dataset and at least one field.', true); return; }
    var t = X.time ? X.time.value() : { mode: 'now' };                // wave B: the extract is asked now, as of a date or over a window (one plan answers all three)
    var spec = { dataset: dsSel.value, fields: fields };
    if (t.mode === 'asof') { spec.dates = t.asOf; } else if (t.mode === 'window') { spec.dates = { from: t.from, to: t.to }; }
    X.open(spec).catch(function () {});
  });

  // ---- promote a SQL cell -------------------------------------------------------------------------------------------------------------
  X.qualified = function (name) { var i = X.ex.columns.findIndex(function (c) { return c.name === name; }); return X.qual[i] || name; };
  X.createItem = function (kind, title, body) { return B.api('POST', '/workspaces/me/items', { kind: kind, title: title, description: 'Started in the Notebook.', body: body }); };
  var targetSel = q('[data-promote-target]', root), pageIn = q('[data-promote-page]', root);
  X.promotedItems = {};                           // what was promoted already, by the cell: promoting it again offers to update that draft

  /** The answer beside the button that asked for it (one message, never also at the top), with a link to the draft. */
  X.tell = function (anchor, text, bad, item) {
    say('');
    var box = B.near(anchor, text, bad);
    if (box && item && !bad) { box.appendChild(document.createTextNode(' ')); box.appendChild(h('a', { class: 'lnk', href: '/bi/i/' + B.id2path(item.id), 'data-promoted-link': '', text: 'Open the draft' })); }
  };
  /** Runs `make(existingItemId | null)` (resolves with {item, text}). When `key` was promoted before, asks beside the button: update that draft, or save another. */
  X.promoteOnce = function (anchor, key, make) {
    var old = root.querySelector('[data-promote-again]');
    if (old) { old.remove(); }
    var run = function (existing) {
      return make(existing).then(function (r) { if (r) { X.promotedItems[key] = r.item.id; X.tell(anchor, r.text, false, r.item); } }).catch(function (e) { X.tell(anchor, e.message, true); });
    };
    var prior = X.promotedItems[key];
    if (!prior) { return run(null); }
    var upd = h('button', { type: 'button', class: 'btn-pill btn-accent', 'data-promote-update': '', text: 'Update that draft' });
    var other = h('button', { type: 'button', class: 'btn-pill btn-ghost', 'data-promote-another': '', text: 'Save another' });
    var no = h('button', { type: 'button', class: 'btn-pill btn-ghost', text: 'Cancel' });
    var box = h('span', { class: 'bi-ask', 'data-promote-again': '', role: 'group', 'aria-label': 'Already promoted' }, 'This is already a draft in your workspace. ', upd, other, no);
    anchor.after(box);
    upd.addEventListener('click', function () { box.remove(); run(prior); });
    other.addEventListener('click', function () { box.remove(); run(null); });
    no.addEventListener('click', function () { box.remove(); });
    return Promise.resolve();
  };
  /** The title of the open dataset in words (the model's), not its id. */
  X.datasetTitle = function () { var o = dsSel.component.getOption(); return (X.model && X.model.title) || (o && o.value && o.label) || X.spec.dataset || 'data'; };
  /** A SQL cell (`extra`: {sql}) as a visual in a new report draft, or added to the report chosen above (its draft is saved with the new visual). */
  X.promoteReport = function (extra, label, anchor) {
    if (!X.spec || !X.ex) { X.tell(anchor, 'Open an extract first.', true); return Promise.resolve(); }
    var typed = q('input[name=title]', root).value;
    var title = (typed || X.datasetTitle() + ' ' + label).slice(0, 80);
    var target = targetSel.value;
    var body = Object.assign({ to: 'report', name: title, dataset: X.spec.dataset, columns: X.ex.columns.map(function (c) { return c.name; }), qualified: X.qual, target: target || undefined, page: pageIn.value.trim() || undefined }, extra);
    var make = function (existing) {
      return X.post('/bi/notebook/promote', body).then(function (d) {
        var saved = target ? B.api('PUT', '/items/' + B.id2path(target), { body: d.yaml }) : existing ? B.api('PUT', '/items/' + B.id2path(existing), { body: d.yaml }) : X.createItem('report', d.title, d.yaml);
        return saved.then(function (it) {
          X.lastPromoted = { item: it.id, visual: d.visual || 'sql' };
          return { item: it, text: (target ? 'Visual ' + d.visual + ' added to the report draft: ' : existing ? 'Report draft updated: ' : 'Report draft saved in your workspace: ') + it.title + '. ' + d.notes.join(' ') };
        });
      });
    };
    return target ? make(null).then(function (r) { X.tell(anchor, r.text, false, r.item); }).catch(function (e) { X.tell(anchor, e.message, true); })
      : X.promoteOnce(anchor, 'report:' + JSON.stringify([X.spec.dataset, extra]), make);
  };
  B.api('GET', '/workspaces').then(function (list) {                                              // the report drafts you may edit: your workspace and the shared ones you contribute to
    var mine = list.filter(function (w) { return !w.shipped && (w.personal || ['contributor', 'member', 'owner', 'admin'].indexOf(w.role) >= 0); }).slice(0, 8);
    return Promise.all(mine.map(function (w) { return B.api('GET', '/workspaces/' + B.id2path(w.personal ? 'me' : w.id) + '/items').catch(function () { return []; }); }));
  }).then(function (lists) {
    var reports = [].concat.apply([], lists.map(function (l) { return Array.isArray(l) ? l : (l.items || []); })).filter(function (it) { return it.kind === 'report'; });
    targetSel.component.setOptions(targetSel.component.options.concat(reports.map(function (it) { return { value: it.id, label: 'the report: ' + it.title }; })));      // drishti-inputs' Select
  }).catch(function () {});
  function link(it) { var a = q('[data-saved]', root); a.href = '/bi/i/' + B.id2path(it.id); a.hidden = false; }
  X.link = link;

  // ---- notebooks -----------------------------------------------------------------------------------------------------------------
  X.cells = { sql: [], python: [] };            // [{source, target?}], kept by drishti_bi-notebook-cells.js
  X.body = function () {
    var cells = [];
    X.cells.sql.forEach(function (c) { cells.push({ type: 'sql', source: c.source }); });
    X.cells.python.forEach(function (c) { cells.push({ type: 'python', source: c.source, target: c.target || 'server' }); });
    var nb = { rachana: 1, notebook: B.slug(q('input[name=title]', root).value || 'analysis') || 'analysis', title: q('input[name=title]', root).value || 'Analysis',
      dataset: X.spec.dataset, fields: X.spec.fields, cells: cells };
    if (X.extras.length) { nb.extracts = X.extras.map(function (e) { return { name: e.name, dataset: e.dataset, fields: e.fields }; }); }
    return nb;
  };
  q('[data-save]', root).addEventListener('submit', function (e) {
    e.preventDefault();
    if (!X.spec) { say('Open an extract first.', true); return; }
    var nb = X.body();
    X.post('/bi/notebook/check', { notebook: nb }).then(function (c) {
      if (!c.valid) { throw new Error(c.problems.map(function (p) { return p.code + ' ' + p.message; }).join('; ')); }
      var save = X.item && X.item.can && X.item.can.edit ? B.api('PUT', '/items/' + B.id2path(X.item.id), { title: nb.title, body: c.yaml }) : X.createItem('notebook', nb.title, c.yaml);
      return save.then(function (it) { X.item = it; say('Saved as a notebook: ' + nb.title + (it.version ? '' : '.')); link(it); });
    }).catch(function (er) { say(er.message, true); });
  });

  X.restore = function (nb) {
    X.cells = { sql: [], python: [] };
    (nb.cells || []).forEach(function (c) {
      if (c.type === 'sql') { X.cells.sql.push({ source: c.source }); } else { X.cells.python.push({ source: c.source, target: 'server' }); }
    });
    q('input[name=title]', root).value = nb.title || '';
    X.extras = (nb.extracts || []).map(function (e) { return { name: e.name, dataset: e.dataset, fields: e.fields }; });
    document.dispatchEvent(new CustomEvent('drishti:bx-extras'));
    var dsPick = dsSel.component;                                                                   // drishti-inputs' Select
    if (!dsPick.options.some(function (o) { return o.value === nb.dataset; })) { dsPick.setOptions(dsPick.options.concat([{ value: nb.dataset, label: nb.dataset }])); }
    dsSel.value = nb.dataset;                                                                       // the Dataset select and its fields show what the notebook was saved with
    return loadModel(nb.dataset, nb.fields).catch(function () {}).then(function () { return X.open({ dataset: nb.dataset, fields: nb.fields }); });
  };

  var notebookSel = q('[data-notebooks]', root);
  function loadNotebooks() {
    return B.api('GET', '/catalogue?kind=notebook').then(function (d) {
      notebookSel.component.setOptions(notebookSel.component.options.concat((d.items || []).map(function (it) { return { value: it.id, label: it.title }; })));
    }).catch(function () {});
  }
  function openNotebook(id) {
    return B.api('GET', '/items/' + B.id2path(id)).then(function (it) {
      return X.post('/bi/notebook/check', { yaml: it.body || '' }).then(function (c) {
        if (!c.valid) { throw new Error(c.problems.map(function (p) { return p.code + ' ' + p.message; }).join('; ')); }
        X.item = it;
        if (!notebookSel.component.options.some(function (o) { return o.value === it.id; })) { notebookSel.component.setOptions(notebookSel.component.options.concat([{ value: it.id, label: it.title }])); }   // a draft is not in the catalogue
        notebookSel.value = it.id;
        return X.restore(c.notebook).then(function (r) {
          q('input[name=title]', root).value = it.title; link(it);                                   // the item's title is the notebook's name
          if (c.notes && c.notes.length) { say(c.notes.join(' ') + ' Open the Pivot to slice.'); }  // an older notebook's slice: left out, said
          return r;
        });
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
  showTab('sql');

  loadDatasets().then(loadNotebooks).then(function () {
    var want = new URLSearchParams(window.location.search).get('item');
    if (want) { notebookSel.value = want; return openNotebook(want); }
    return null;
  }).catch(function (e) { say(e.message, true); });
})();
