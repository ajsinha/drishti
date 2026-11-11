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
(function (g) { g.Drishti = g.Drishti || {}; })(typeof window !== 'undefined' ? window : globalThis);
/* The Python-driven table editor (HOW_IT_FITS.md section S4: option A as the editor, B the Notebook, C the templates; all end
   in A). The build function beside its output contract (the columns, types, keys and rules it must return); its inputs (tables read as
   the person may see them); when it runs (on demand and cached per business date, or on each load of an input); its compute pool and
   limits. Sample run reads the inputs as the person may see them and runs the code on the server's compute pool (POST python/run), and the rows
   are checked against the contract; where the server cannot run Python safely the button is off and says why. The code is kept in the table's
   build-function item (a BI Python item) and saved with the table. */
(function () {
  'use strict';
  var E = window.Drishti.editors;
  var TYPES = ['string', 'integer', 'decimal', 'boolean', 'date', 'timestamp'];

  function fields(ctx) {
    var S = ctx.S;
    S.pythonFields = S.pythonFields || { source: '', inputs: [], timeout: 60, memory: 512 };
    return S.pythonFields;
  }
  /** The table's inputs (alias to table) kept in the table (from.inputs) and in the build function's item (its list of tables). */
  function setInputs(ctx, d, inputs) {
    d.from = d.from || {};
    if (Object.keys(inputs).length) { d.from.inputs = inputs; } else { delete d.from.inputs; }
    fields(ctx).inputs = Object.keys(inputs).map(function (a) { return inputs[a]; });
  }

  function render(ctx, host) {
    var h = ctx.h, S = ctx.S, doc = S.doc || {}, f = fields(ctx);
    host.setAttribute('data-editor', 'python');
    var from = doc.from || {}, params = from.params || {};
    var code = h('textarea', { class: 'ed-in ed-code', rows: '16', spellcheck: 'false', 'data-python-code': '', 'aria-label': 'The build function (Python)' });
    code.value = f.source || '';
    code.addEventListener('input', function () { f.source = code.value; ctx.markDirty(); });
    code.addEventListener('keydown', function (e) {
      if (e.key === 'Tab' && !e.shiftKey && !e.ctrlKey) {
        e.preventDefault();
        var s = code.selectionStart;
        code.value = code.value.slice(0, s) + '    ' + code.value.slice(code.selectionEnd);
        code.setSelectionRange(s + 4, s + 4);
        f.source = code.value; ctx.markDirty();
      }
    });
    var left = h('section', { class: 'ed-card ed-codecard' }, h('h3', { text: 'Build function' }),
      h('p', { class: 'ed-small', text: 'build(inputs, params): inputs by alias, each a table as you may see it; return one table with the contract\'s columns. Tab indents; Escape then Tab leaves.' }),
      code,
      h('p', { class: 'ed-row-btns' },
        h('button', { type: 'button', class: 'ed-btn', 'data-edit': '', 'data-py-templates': '', on: { click: function () { templates(ctx); } } }, 'Templates'), ' ',
        h('button', { type: 'button', class: 'ed-btn', 'data-edit': '', 'data-py-notebook': '', on: { click: function () { notebook(ctx); } } }, 'Notebook → Turn into table'), ' ',
        h('button', { type: 'button', class: 'ed-btn', 'data-edit': '', 'data-py-propose-contract': '', on: { click: function () { proposeContract(ctx); } } }, 'Contract from the code')));
    var right = h('div', { class: 'ed-pyside' }, contract(ctx, doc), inputs(ctx, doc), runs(ctx, params, f));
    host.appendChild(h('div', { class: 'ed-row2' },
      h('label', { class: 'ed-field' }, h('span', { text: 'Name' }), h('input', { class: 'ed-in', value: doc.table || '', 'data-path': 'table', 'aria-label': 'Name',
        on: { input: function (e) { var v = e.target.value.trim(); ctx.change(function (d) { d.table = v; d.from = d.from || {}; d.from.python = v.replace('.', '-'); }, 'table'); } } })),
      h('label', { class: 'ed-field' }, h('span', { text: 'Title' }), h('input', { class: 'ed-in', value: doc.title || '', 'data-path': 'title', 'aria-label': 'Title',
        on: { input: function (e) { var v = e.target.value; ctx.change(function (d) { if (v.trim()) { d.title = v; } else { delete d.title; } }, 'title'); } } }))));
    host.appendChild(h('div', { class: 'ed-pygrid' }, left, right));
  }

  function contract(ctx, doc) {
    var h = ctx.h, cols = doc.columns || {}, tests = doc.tests || [];
    var notNull = {};
    tests.forEach(function (t) { if (t['not-null']) { [].concat(t['not-null']).forEach(function (c) { notNull[c] = true; }); } });
    var rows = Object.keys(cols).map(function (n) {
      var c = cols[n], p = 'columns.' + n;
      return h('tr', { 'data-path': p, 'data-contract-col': n }, h('td', { text: n }),
        h('td', {}, E.pick({ class: 'ed-pick ed-narrow', 'data-contract-type': n, 'aria-label': 'Type of ' + n,
          on: { change: function (e) { var v = e.target.value; ctx.change(function (d) { d.columns[n].type = v; }, p + '.type'); } } },
          TYPES, TYPES.indexOf(c.type || 'string') >= 0 ? c.type || 'string' : undefined)),
        h('td', {}, h('input', { type: 'checkbox', checked: !!c.key, 'data-contract-key': n, 'aria-label': n + ' is a key',
          on: { change: function (e) { var on = e.target.checked; ctx.change(function (d) { if (on) { d.columns[n].key = true; } else { delete d.columns[n].key; } }, p); } } })),
        h('td', {}, h('input', { type: 'checkbox', checked: !!notNull[n], 'data-contract-notnull': n, 'aria-label': n + ' is never empty',
          on: { change: function (e) {
            var on = e.target.checked;
            ctx.change(function (d) {
              d.tests = (d.tests || []).filter(function (t) { return !(t['not-null'] === n); });
              if (on) { d.tests.push({ 'not-null': n }); }
              if (!d.tests.length) { delete d.tests; }
            }, 'tests');
          } } })),
        h('td', {}, h('button', { type: 'button', class: 'ed-linkbtn', 'data-edit': '', 'data-contract-remove': n, 'aria-label': 'Remove ' + n,
          on: { click: function () { ctx.change(function (d) { delete d.columns[n]; }, 'columns'); ctx.redraw(); } } }, 'Remove')));
    });
    var name = h('input', { class: 'ed-in ed-narrow', 'data-contract-new': '', placeholder: 'COLUMN', 'aria-label': 'A new column of the contract' });
    return h('section', { class: 'ed-card', 'aria-labelledby': 'edConH' }, h('h3', { id: 'edConH', text: 'Output contract' }),
      h('p', { class: 'ed-small', text: 'The columns, types, keys and rules the code must return; a run that returns anything else is refused.' }),
      h('div', { class: 'ed-scroll' }, h('table', { class: 'ed-grid-t', 'data-contract': '', 'data-resize': 'columns', 'data-resize-key': 'editors.python-contract' },
        h('thead', {}, h('tr', {}, ['Column', 'Type', 'Key', 'Never empty', ''].map(function (x) { return h('th', { scope: 'col', text: x }); }))), h('tbody', {}, rows))),
      h('p', {}, name, ' ', h('button', { type: 'button', class: 'ed-btn', 'data-edit': '', 'data-contract-add': '', on: { click: function () {
        var n = name.value.trim();
        if (!/^[A-Za-z_][A-Za-z0-9_]*$/.test(n)) { E.say('A column is letters, digits and underscores.', true); return; }
        ctx.change(function (d) { d.columns = d.columns || {}; d.columns[n] = { type: 'decimal' }; }, 'columns.' + n);
        ctx.redraw();
      } } }, '+ Column')));
  }

  function inputs(ctx, doc) {
    var h = ctx.h, src = ctx.sources().tables || [], ins = (doc.from || {}).inputs || {};
    var rows = Object.keys(ins).map(function (a) {
      return h('tr', { 'data-input': a }, h('td', { text: a }), h('td', { text: String(ins[a]) }),
        h('td', {}, h('button', { type: 'button', class: 'ed-linkbtn', 'data-edit': '', 'data-input-remove': a, 'aria-label': 'Remove the input ' + a,
          on: { click: function () { ctx.change(function (d) { var x = Object.assign({}, d.from.inputs || {}); delete x[a]; setInputs(ctx, d, x); }, 'from.inputs'); ctx.redraw(); } } }, 'Remove')));
    });
    var pick = E.pick({ class: 'ed-pick', 'data-input-pick': '', 'aria-label': 'A table to read' }, src.map(function (x) { return x.table; }));
    return h('section', { class: 'ed-card' }, h('h3', { text: 'Inputs' }),
      h('div', { class: 'ed-scroll' }, h('table', { class: 'ed-grid-t', 'data-inputs': '' }, h('thead', {}, h('tr', {}, ['Alias', 'Table', ''].map(function (x) { return h('th', { scope: 'col', text: x }); }))), h('tbody', {}, rows))),
      h('p', {}, pick, ' ', h('button', { type: 'button', class: 'ed-btn', 'data-edit': '', 'data-input-add': '', on: { click: function () {
        if (!pick.value) { E.say('No table to read: make one first.', true); return; }
        var a = pick.value.split('.').pop();
        ctx.change(function (d) { var x = Object.assign({}, (d.from || {}).inputs || {}); x[a] = pick.value; setInputs(ctx, d, x); }, 'from.inputs');
        ctx.redraw();
      } } }, '+ Input')));
  }

  function runs(ctx, params, f) {
    var h = ctx.h;
    function param(key, value) { ctx.change(function (d) { d.from = d.from || {}; d.from.params = Object.assign({}, d.from.params || {}); d.from.params[key] = value; }, 'from.params.' + key); }
    var when = E.pick({ class: 'ed-pick', 'data-py-runs': '', 'aria-label': 'When it runs', on: { change: function (e) { param('runs', e.target.value); } } },
      [{ value: 'on-demand', label: 'on demand, kept per business date' }, { value: 'on-load', label: 'on each load of an input' }], params.runs === 'on-load' ? 'on-load' : 'on-demand');
    var pool = h('input', { class: 'ed-in ed-narrow', value: params.pool || 'default', 'data-py-pool': '', 'aria-label': 'Compute pool', on: { change: function (e) { param('pool', e.target.value.trim() || 'default'); } } });
    var timeout = h('input', { class: 'ed-in ed-narrow', type: 'number', min: '1', max: '3600', value: String(f.timeout || 60), 'data-py-timeout': '', 'aria-label': 'Time limit in seconds',
      on: { change: function (e) { f.timeout = parseInt(e.target.value, 10) || 60; ctx.markDirty(); } } });
    var memory = h('input', { class: 'ed-in ed-narrow', type: 'number', min: '64', max: '65536', value: String(f.memory || 512), 'data-py-memory': '', 'aria-label': 'Memory limit in MB',
      on: { change: function (e) { f.memory = parseInt(e.target.value, 10) || 512; ctx.markDirty(); } } });
    var off = !E.pythonRuns;
    var run = h('button', { type: 'button', class: 'ed-btn', 'data-py-run': '', 'data-always': '', disabled: off, 'aria-describedby': off ? 'edRunOff' : null,
      on: { click: function () { sampleRun(ctx); } } }, 'Sample run');
    return h('section', { class: 'ed-card' }, h('h3', { text: 'When it runs, where, and its limits' }),
      h('label', { class: 'ed-field' }, h('span', { text: 'Runs' }), when),
      h('div', { class: 'ed-row3' }, h('label', { class: 'ed-field' }, h('span', { text: 'Compute pool' }), pool),
        h('label', { class: 'ed-field' }, h('span', { text: 'Time (s)' }), timeout), h('label', { class: 'ed-field' }, h('span', { text: 'Memory (MB)' }), memory)),
      h('p', {}, run, ' ', off ? h('small', { id: 'edRunOff', class: 'ed-small', 'data-python-off': '', text: 'Off: ' + (E.pythonWhy || 'Python does not run on this server') }) : null),
      h('p', { class: 'ed-small', text: 'The sample run reads the inputs as you may see them, runs the code on the server\'s compute pool, and checks the rows against the contract.' }));
  }

  /** The sample run: the server reads the inputs as this person, runs the build function on its pool and checks the rows against the contract. */
  function sampleRun(ctx, inputs) {
    var f = fields(ctx), S = ctx.S;
    if (!f.source.trim()) { E.say('Write the build function first.', true); return Promise.resolve(); }
    E.say('Running on the server\'s compute pool…');
    return ctx.api('POST', 'python/run', { yaml: S.yaml || '', source: f.source, timeout: f.timeout || 60, memory: f.memory || 512, date: S.date || null, inputs: inputs || null })
      .then(function (r) {
        S.preview = r;
        S.sampleRun = { source: f.source, answer: r };              // kept: a later preview of the same code shows this run, not the prompt again
        var pane = document.querySelector('[data-preview]');
        if (pane) { ctx.clear(pane); preview(ctx, pane, r); }
        E.say(r.matches ? 'The sample run matches the contract: ' + r.count + ' rows in ' + r.ms + ' ms.' : 'The sample run does not match the contract: see the checks.', !r.matches);
        return r;
      }).catch(function (e) { E.say(e.message, true); });
  }

  function proposeContract(ctx) {
    var cols = {};
    var ins = ((ctx.S.doc || {}).from || {}).inputs || {};
    Object.keys(ins).forEach(function (a) {
      var t = (ctx.sources().tables || []).filter(function (x) { return x.table === ins[a]; })[0];
      (t ? t.columns : []).forEach(function (c) { cols[c.name] = c.type; });
    });
    ctx.api('POST', 'python/contract', { source: fields(ctx).source, columns: cols }).then(function (r) {
      var got = r.columns || {};
      if (!Object.keys(got).length) { E.say('No columns found in the code: name them in the contract.', true); return; }
      ctx.change(function (d) {
        d.columns = d.columns || {};
        Object.keys(got).forEach(function (n) { if (!d.columns[n]) { d.columns[n] = got[n]; } });
      }, 'columns');
      ctx.redraw();
      E.say('The contract lists ' + Object.keys(got).length + ' column(s) found in the code: confirm their types and keys.');
    }).catch(function (e) { E.say(e.message, true); });
  }

  function templates(ctx) {
    ctx.api('GET', 'python/templates').then(function (r) {
      var h = ctx.h;
      var list = h('ul', { class: 'ed-templates', 'data-templates': '' }, (r.templates || []).map(function (t) {
        return h('li', {}, h('button', { type: 'button', class: 'ed-btn', 'data-template': t.id, on: { click: function () { useTemplate(ctx, t); } } }, t.title), ' ', h('small', { text: t.says }));
      }));
      ctx.dialog('Start from a template', h('div', {}, h('p', { class: 'ed-small', text: 'A template writes the build function, its inputs and its contract; the inputs are examples: choose your own tables.' }), list));
    }).catch(function (e) { E.say(e.message, true); });
  }
  E.pythonTemplates = templates;
  function useTemplate(ctx, t) {
    var f = fields(ctx);
    f.source = t.code || '';
    ctx.change(function (d) {
      d.from = d.from || {};
      d.from.params = Object.assign({ pool: 'default', runs: 'on-demand' }, d.from.params || {}, t.params || {});
      setInputs(ctx, d, Object.assign({}, t.inputs || {}));
      d.columns = JSON.parse(JSON.stringify(t.contract || {}));
    }, 'columns');
    E.closeDialog();
    ctx.redraw();
    E.say('The template "' + t.title + '" is in: choose your input tables and check the contract.');
  }

  function notebook(ctx) {
    var h = ctx.h;
    var cells = h('div', { class: 'ed-cells', 'data-cells': '' });
    function add(text) {
      var ta = h('textarea', { class: 'ed-in ed-code', rows: '4', spellcheck: 'false', 'data-cell': '', 'aria-label': 'Notebook cell ' + (cells.children.length + 1) });
      ta.value = text || '';
      cells.appendChild(ta);
      return ta;
    }
    add('import pandas as pd\nlegs = drishti.table("trading.trade_legs")');
    add('legs["ABS_NOTIONAL"] = legs["NOTIONAL"].abs()\nlegs');
    var addBtn = h('button', { type: 'button', class: 'ed-btn', 'data-cell-add': '', on: { click: function () { add('').focus(); } } }, '+ Cell');
    var off = !E.pythonRuns;
    var shown = h('div', { class: 'ed-scroll', 'data-cells-result': '' });
    var runBtn = h('button', { type: 'button', class: 'ed-btn', 'data-cell-run': '', disabled: off, title: off ? 'Off: ' + E.pythonWhy : null, on: { click: function () {
      var texts = Array.prototype.map.call(cells.querySelectorAll('textarea'), function (x) { return x.value; });
      ctx.api('POST', 'python/notebook', { cells: texts }).then(function (r) {               // the cells as one build function, run as a sample run
        fields(ctx).source = r.source;
        return sampleRun(ctx, r.inputs || {});
      }).then(function (r) {
        ctx.clear(shown);
        if (r) { shown.appendChild(rowsTable(ctx, r)); }
      }).catch(function (e) { E.say(e.message, true); });
    } } }, 'Run cells on the server');
    var go = h('button', { type: 'button', class: 'ed-btn ed-primary', 'data-turn-into-table': '', on: { click: function () {
      var texts = Array.prototype.map.call(cells.querySelectorAll('textarea'), function (x) { return x.value; });
      ctx.api('POST', 'python/notebook', { cells: texts }).then(function (r) {
        fields(ctx).source = r.source;
        ctx.change(function (d) { var x = Object.assign({}, (d.from || {}).inputs || {}, r.inputs || {}); setInputs(ctx, d, x); }, 'from.inputs');
        E.closeDialog();
        ctx.redraw();
        proposeContract(ctx);
      }).catch(function (e) { E.say(e.message, true); });
    } } }, 'Turn into table');
    ctx.dialog('Notebook', h('div', { class: 'ed-form' },
      h('p', { class: 'ed-small', text: 'Explore in cells; drishti.table("domain.name") reads a table as you may see it. Turn into table makes the cells a build function and fills in its inputs and contract.' }),
      cells, h('p', {}, addBtn, ' ', runBtn, off ? h('small', { class: 'ed-small', 'data-python-off': '', text: ' Off: ' + E.pythonWhy }) : null), shown, h('p', {}, go)));
  }
  E.pythonNotebook = notebook;

  /** The rows a sample run returned (its first rows), as a table. */
  function rowsTable(ctx, r) {
    var h = ctx.h;
    return h('table', { class: 'ed-grid-t', 'data-sample-rows': '' }, h('thead', {}, h('tr', {}, (r.columns || []).map(function (c) { return h('th', { scope: 'col', text: c }); }))),
      h('tbody', {}, (r.rows || []).map(function (row) { return h('tr', {}, row.map(function (v) { return h('td', { text: v == null ? '' : String(v) }); })); })));
  }

  function preview(ctx, pane, r) {
    var h = ctx.h, kept = ctx.S.sampleRun;
    if (!(r && r.ran) && kept && kept.source === fields(ctx).source) { r = kept.answer; }
    pane.appendChild(h('h3', { text: 'Sample run' }));
    if (r && r.ran) {
      pane.appendChild(h('p', { class: 'ed-small', 'data-sample-run': 'ran', text: r.count + ' rows in ' + r.ms + ' ms on the server\'s compute pool' +
        (r.inputs || []).map(function (i) { return ' · ' + i.name + ': ' + i.rows + ' rows' + (i.masked && i.masked.length ? ' (masked: ' + i.masked.join(', ') + ')' : ''); }).join('') }));
      pane.appendChild(h('div', { class: 'ed-scroll' }, rowsTable(ctx, r)));
      E.checks(pane, r.checks || []);
      E.usedBy(pane);
      return true;
    }
    var cols = Object.keys((ctx.S.doc || {}).columns || {});
    pane.appendChild(h('p', { class: 'ed-small', 'data-sample-run': '', text: E.pythonRuns ? 'Press Sample run: the rows it returns are checked against the contract below.' : 'Python does not run on this server: ' + E.pythonWhy + '. The contract below is what a run must return.' }));
    pane.appendChild(h('div', { class: 'ed-scroll' }, h('table', { class: 'ed-grid-t' }, h('thead', {}, h('tr', {}, cols.map(function (c) { return h('th', { scope: 'col', text: c }); }))))));
    E.checks(pane, r.checks || []);
    E.usedBy(pane);
    return true;
  }

  E.register('python', { render: render, preview: preview });
})();
