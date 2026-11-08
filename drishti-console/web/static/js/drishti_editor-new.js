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
/* + New (docs/architecture/EDITORS.md section 1, option B): guided steps in plain language that land in the three-pane editor.
   A table: its name, its source (a kind or another table), the columns suggested from a sample of the source (the server's), check and
   open. A model: the four questions of its Overview (what it is about, what people look at it by, what numbers it gives, who may see what).
   A Python-driven table: its name and where to start (a template, a notebook, or empty). Built with DOM calls only. */
(function () {
  'use strict';
  var E = window.Drishti.editors;

  function stepper(host, titles, at) {
    var h = E.h;
    host.appendChild(h('ol', { class: 'ed-steps', 'aria-label': 'Steps' }, titles.map(function (t, i) {
      return h('li', { class: i < at ? 'ed-done' : i === at ? 'ed-on' : '', 'aria-current': i === at ? 'step' : null, text: (i + 1) + ' ' + t });
    })));
  }
  function nav(host, back, next, nextLabel) {
    var h = E.h;
    host.appendChild(h('p', { class: 'ed-stepnav' },
      back ? h('button', { type: 'button', class: 'ed-btn', 'data-step-back': '', on: { click: back } }, 'Back') : null, ' ',
      h('button', { type: 'button', class: 'ed-btn ed-primary', 'data-step-next': '', on: { click: next } }, nextLabel || 'Next')));
  }
  function visual() {
    var v = E.root.querySelector('[data-visual]');
    E.root.querySelector('[data-yaml-panel]').hidden = true;
    v.hidden = false;
    return E.clear(v);
  }
  function nameOk(n, model) { return model ? /^[a-z][a-z0-9_-]*$/.test(n) : /^[a-z][a-z0-9_]*\.[a-z][a-z0-9_]*$/.test(n); }

  E.newSteps = function (ctx, kind) {
    if (!E.canEdit) { E.say('Making tables and models needs the bi-author power (or an administrator).', true); return null; }
    if (kind === 'model') { return model(ctx); }
    if (kind === 'python') { return python(ctx); }
    return table(ctx);
  };

  /* ---- a table ----------------------------------------------------------------------------------------------------------------- */
  function table(ctx) {
    var h = E.h, st = { name: '', title: '', source: '', over: '', doc: null, at: 0 };
    var titles = ['Name', 'Source', 'Columns', 'Check and open'];
    function draw() {
      var host = visual();
      host.setAttribute('data-new-steps', 'table');
      host.appendChild(h('h2', { text: 'A new table' }));
      stepper(host, titles, st.at);
      if (st.at === 0) {
        var n = h('input', { class: 'ed-in', 'data-new-name': '', value: st.name, placeholder: 'domain.name, e.g. trading.trade_view', 'aria-label': 'Name: domain.name' });
        var t = h('input', { class: 'ed-in', 'data-new-title': '', value: st.title, placeholder: 'Trades as the desk sees them', 'aria-label': 'Title' });
        host.appendChild(h('div', { class: 'ed-form' }, h('label', {}, 'What is it called? ', n), h('small', { text: 'Lower case, the domain first: names are unique across every table and model.' }),
          h('label', {}, 'A title people read ', t)));
        nav(host, null, function () {
          st.name = n.value.trim(); st.title = t.value.trim();
          if (!nameOk(st.name)) { E.say('A table is named domain.name: lower case letters, digits and underscores (trading.trade_view).', true); return; }
          E.say(''); st.at = 1; draw();
        });
      } else if (st.at === 1) {
        var src = ctx.sources();
        var choices = [{ value: '', label: 'Choose…' }]
          .concat((src.kinds || []).map(function (k) { return { value: 'kind:' + k.kind, label: k.kind + ' · ' + (k.connector || '') + (k.title ? ' · ' + k.title : ''), group: 'Kinds (any connector)' }; }))
          .concat((src.tables || []).map(function (x) { return { value: 'table:' + x.table, label: x.table, group: 'Tables (a table over a table)' }; }));
        var sel = E.pick({ class: 'ed-pick', 'data-new-source': '', 'aria-label': 'Where its rows come from' }, choices,
          choices.some(function (c) { return c.value === st.source; }) ? st.source : '');
        host.appendChild(h('div', { class: 'ed-form' }, h('label', {}, 'Where do its rows come from? ', sel),
          h('small', { text: 'Several tables may read one source, each with its own columns; dates follow the source.' })));
        nav(host, function () { st.at = 0; draw(); }, function () {
          st.source = sel.value || '';
          if (!st.source) { E.say('Choose a source.', true); return; }
          E.say('Reading a sample of the source on the server…');
          if (st.source.indexOf('kind:') === 0) {
            ctx.api('POST', 'suggest', { kind: st.source.slice(5), name: st.name }).then(function (r) { st.doc = r.doc; st.grains = r.grains; E.say(''); st.at = 2; draw(); })
              .catch(function (e) { E.say(e.message, true); });
          } else {
            st.doc = { rachana: 3, table: st.name, from: { table: st.source.slice(6) }, columns: {} };
            E.say(''); st.at = 2; draw();
          }
        });
      } else if (st.at === 2) {
        var cols = st.doc.columns || {};
        var list = h('ul', { class: 'ed-picks', 'data-new-columns': '' });
        st.keep = st.keep || {};
        Object.keys(cols).forEach(function (c) {
          if (st.keep[c] === undefined) { st.keep[c] = true; }
          list.appendChild(h('li', {}, h('label', {}, h('input', { type: 'checkbox', checked: st.keep[c], 'data-new-col': c, on: { change: function (e) { st.keep[c] = e.target.checked; } } }),
            ' ' + c + ' ', h('small', { text: (cols[c].path || cols[c].expr || '') + ' · ' + (cols[c].type || '') }))));
        });
        host.appendChild(h('p', { text: Object.keys(cols).length ? 'Tick the columns to keep (suggested from a sample of the source; you can add computed columns in the editor).'
          : 'A table over a table has its base\'s columns; add your own in the editor.' }));
        host.appendChild(list);
        nav(host, function () { st.at = 1; draw(); }, function () { st.at = 3; draw(); });
      } else {
        var doc = JSON.parse(JSON.stringify(st.doc));
        doc.table = st.name;
        if (st.title) { doc.title = st.title; }
        var kept = {};
        Object.keys(doc.columns || {}).forEach(function (c) { if (st.keep[c] !== false) { kept[c] = doc.columns[c]; } });
        doc.columns = kept;
        host.appendChild(h('p', { text: st.name + ': ' + Object.keys(kept).length + ' column(s) from ' + st.source.replace(/^(kind|table):/, '') + '. It opens in the editor, where the server checks it and shows a live preview.' }));
        nav(host, function () { st.at = 2; draw(); }, function () { ctx.start({ kind: 'table', doc: doc }); }, 'Open in the editor');
      }
    }
    draw();
  }

  /* ---- a model: the four questions --------------------------------------------------------------------------------------------- */
  function model(ctx) {
    var h = E.h, st = { at: 0, name: '', title: '', description: '', fact: '', dims: [], measures: [], rules: [] };
    var titles = ['What is it about?', 'What do people look at it by?', 'What numbers does it give?', 'Who may see what?'];
    function alias(t) { return String(t).split('.').pop(); }
    function draw() {
      var host = visual();
      host.setAttribute('data-new-steps', 'model');
      host.appendChild(h('h2', { text: 'A new semantic model' }));
      stepper(host, titles, st.at);
      var tables = ctx.sources().tables || [];
      if (st.at === 0) {
        var n = h('input', { class: 'ed-in', 'data-new-name': '', value: st.name, placeholder: 'risk-by-desk', 'aria-label': 'Name' });
        var t = h('input', { class: 'ed-in', 'data-new-title': '', value: st.title, placeholder: 'Risk by desk', 'aria-label': 'Title' });
        var d = h('textarea', { class: 'ed-in', rows: '2', 'data-new-about': '', 'aria-label': 'What it is about', placeholder: 'MTM and limits of every desk' }); d.value = st.description;
        var f = E.pick({ class: 'ed-pick', 'data-new-fact': '', 'aria-label': 'The table of the things counted (the fact)' },
          [{ value: '', label: 'Choose…' }].concat(tables.map(function (x) { return x.table; })), tables.some(function (x) { return x.table === st.fact; }) ? st.fact : '');
        host.appendChild(h('div', { class: 'ed-form' }, h('label', {}, 'Name ', n), h('label', {}, 'Title ', t), h('label', {}, 'What is it about? ', d),
          h('label', {}, 'Which table holds the things it counts? ', f)));
        nav(host, null, function () {
          st.name = n.value.trim(); st.title = t.value.trim(); st.description = d.value.trim(); st.fact = f.value || '';
          if (!nameOk(st.name, true)) { E.say('A model is named in lower case with hyphens (risk-by-desk).', true); return; }
          if (!st.fact) { E.say('Choose the table it counts.', true); return; }
          E.say(''); st.at = 1; draw();
        });
      } else if (st.at === 1) {
        var picks = h('ul', { class: 'ed-picks', 'data-new-dims': '' });
        tables.filter(function (x) { return x.table !== st.fact; }).forEach(function (x) {
          picks.appendChild(h('li', {}, h('label', {}, h('input', { type: 'checkbox', checked: st.dims.indexOf(x.table) >= 0, 'data-new-dim': x.table,
            on: { change: function (e) { if (e.target.checked) { st.dims.push(x.table); } else { st.dims = st.dims.filter(function (y) { return y !== x.table; }); } } } }), ' ' + x.table)));
        });
        host.appendChild(h('p', { text: 'People look at the numbers by the columns of ' + st.fact + ' and of the tables you tick here (desks, books, counterparties). Relationships are added in the editor.' }));
        host.appendChild(picks);
        nav(host, function () { st.at = 0; draw(); }, function () { st.at = 2; draw(); });
      } else if (st.at === 2) {
        var fact = tables.filter(function (x) { return x.table === st.fact; })[0] || { columns: [] };
        var nums = fact.columns.filter(function (c) { return c.type === 'decimal' || c.type === 'integer'; });
        var list = h('ul', { class: 'ed-picks', 'data-new-measures': '' });
        nums.forEach(function (c) {
          var on = st.measures.indexOf(c.name) >= 0;
          list.appendChild(h('li', {}, h('label', {}, h('input', { type: 'checkbox', checked: on, 'data-new-measure': c.name,
            on: { change: function (e) { if (e.target.checked) { st.measures.push(c.name); } else { st.measures = st.measures.filter(function (y) { return y !== c.name; }); } } } }),
            ' the sum of ' + c.name)));
        });
        host.appendChild(h('p', { text: 'A count of rows is always given. Tick the sums to start with; every other formula is written in the Measures tab.' }));
        host.appendChild(list);
        nav(host, function () { st.at = 1; draw(); }, function () { st.at = 3; draw(); });
      } else {
        var roles = ctx.sources().roles || [];
        var role = E.pick({ class: 'ed-pick', 'data-new-role': '', 'aria-label': 'Role' }, [{ value: '', label: 'Everyone sees every row' }].concat(roles), '');
        var where = h('input', { class: 'ed-in', 'data-new-where': '', placeholder: alias(st.fact) + '.DESK in user.desks', 'aria-label': 'Which rows the role sees (Rachana)' });
        E.formula(where, function () { return []; });
        host.appendChild(h('div', { class: 'ed-form' }, h('p', { text: 'The tables\' own rules always apply. You may narrow what a role sees in this model (more in the Row rules, Masks and Access tabs).' }),
          h('label', {}, 'Role ', role), h('label', {}, 'sees the rows where ', where)));
        nav(host, function () { st.at = 2; draw(); }, function () {
          var fa = alias(st.fact);
          var doc = { rachana: 3, model: st.name, tables: {}, measures: {} };
          if (st.title) { doc.title = st.title; }
          if (st.description) { doc.description = st.description; }
          doc.tables[fa] = { table: st.fact, role: 'fact' };
          st.dims.forEach(function (d) { doc.tables[alias(d)] = { table: d, role: 'dimension' }; });
          doc.measures.Rows = { expr: 'count(' + fa + ')' };
          st.measures.forEach(function (m) { doc.measures[m] = { expr: 'sum(' + fa + '.' + m + ')' }; });
          if (role.value && where.value.trim()) { doc.security = { rows: {} }; doc.security.rows[fa] = [{ role: role.value, where: where.value.trim() }]; }
          ctx.start({ kind: 'model', doc: doc });
        }, 'Open in the editor');
      }
    }
    draw();
  }

  /* ---- a Python-driven table ---------------------------------------------------------------------------------------------------- */
  function python(ctx) {
    var h = E.h, st = { at: 0, name: '', start: 'template' };
    function draw() {
      var host = visual();
      host.setAttribute('data-new-steps', 'python');
      host.appendChild(h('h2', { text: 'A new Python-driven table' }));
      stepper(host, ['Name', 'Start from'], st.at);
      if (st.at === 0) {
        var n = h('input', { class: 'ed-in', 'data-new-name': '', value: st.name, placeholder: 'market.fx_daily', 'aria-label': 'Name: domain.name' });
        host.appendChild(h('div', { class: 'ed-form' }, h('label', {}, 'What is it called? ', n),
          h('small', { text: 'Its code runs on the server\'s compute pool and must return the columns of its output contract.' })));
        nav(host, null, function () {
          st.name = n.value.trim();
          if (!nameOk(st.name)) { E.say('A table is named domain.name (market.fx_daily).', true); return; }
          E.say(''); st.at = 1; draw();
        });
      } else {
        var opts = [['template', 'A template: join and derive, resample, statistics per group, an approved feed'], ['notebook', 'A notebook: cells you explored, turned into a table'], ['blank', 'An empty build function']];
        var group = h('div', { role: 'radiogroup', class: 'ed-form', 'aria-label': 'Start from' }, opts.map(function (o) {
          return h('label', {}, h('input', { type: 'radio', name: 'edpystart', value: o[0], checked: st.start === o[0], 'data-new-start': o[0], on: { change: function () { st.start = o[0]; } } }), ' ' + o[1]);
        }));
        host.appendChild(group);
        nav(host, function () { st.at = 0; draw(); }, function () {
          var doc = { rachana: 3, table: st.name, from: { python: st.name.replace('.', '-'), params: { pool: 'default', runs: 'on-demand' } }, columns: {} };
          var fields = { source: 'def build(inputs, params):\n    # inputs by alias, each a table as you may see it; return one table\n    return None\n', inputs: [], timeout: 60, memory: 512 };
          ctx.start({ kind: 'table', python: true, doc: doc, pythonFields: fields }).then(function () {
            if (st.start === 'template' && E.pythonTemplates) { E.pythonTemplates(ctx); }
            if (st.start === 'notebook' && E.pythonNotebook) { E.pythonNotebook(ctx); }
          });
        }, 'Open in the editor');
      }
    }
    draw();
  }
})();
