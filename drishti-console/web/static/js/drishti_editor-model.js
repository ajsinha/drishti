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
/* The semantic model editor (docs/architecture/EDITORS.md section 3: option A as the editor, B as the Overview, C for a measure's details
   and the Access tab). Tabs with counts: Overview (the four questions), Tables (with their relationships), Fields, Measures, Hierarchies,
   Row rules, Masks, Access (roles against rows, columns and editing). A measure's ? opens its card: the formula, a plain explanation, a
   worked test value and where it is used. "Adds up?" is the engine's answer (the server's), never the author's. */
(function () {
  'use strict';
  var E = window.Drishti.editors;
  var TABS = ['overview', 'tables', 'fields', 'measures', 'hierarchies', 'rules', 'masks', 'access'];
  var LABELS = { overview: 'Overview', tables: 'Tables', fields: 'Fields', measures: 'Measures', hierarchies: 'Hierarchies', rules: 'Row rules', masks: 'Masks', access: 'Access' };
  var tab = 'overview';

  function tableCols(ctx, ref) {
    var t = (ctx.sources().tables || []).filter(function (x) { return x.table === String(ref || '').replace(/@.*$/, ''); })[0];
    return t ? t.columns : [];
  }
  function fields(ctx, doc) {
    var out = [];
    Object.keys(doc.tables || {}).forEach(function (a) { tableCols(ctx, doc.tables[a].table).forEach(function (c) { out.push({ name: a + '.' + c.name, type: c.type, alias: a }); }); });
    return out;
  }
  function counts(doc, ctx) {
    var sec = doc.security || {};
    var rules = 0;
    Object.keys(sec.rows || {}).forEach(function (a) { rules += (sec.rows[a] || []).length; });
    return { overview: '', tables: Object.keys(doc.tables || {}).length, fields: fields(ctx, doc).length, measures: Object.keys(doc.measures || {}).length,
      hierarchies: Object.keys(doc.hierarchies || {}).length, rules: rules, masks: (sec.masked || []).length, access: (ctx.sources().roles || []).length };
  }

  function render(ctx, host) {
    var h = ctx.h, doc = ctx.S.doc || {};
    host.setAttribute('data-editor', 'model');
    var c = counts(doc, ctx);
    var list = h('div', { class: 'ed-tabs', role: 'tablist', 'aria-label': 'Parts of the model' });
    TABS.forEach(function (t, i) {
      var b = h('button', { type: 'button', role: 'tab', id: 'edTab-' + t, 'aria-selected': tab === t ? 'true' : 'false', 'aria-controls': 'edPanel', tabindex: tab === t ? '0' : '-1',
        'data-tab': t, 'data-always': '', on: { click: function () { tab = t; ctx.redraw(); }, keydown: function (e) {
          var j = e.key === 'ArrowRight' ? i + 1 : e.key === 'ArrowLeft' ? i - 1 : -2;
          if (j !== -2) { e.preventDefault(); tab = TABS[(j + TABS.length) % TABS.length]; ctx.redraw(); var nb = E.root.querySelector('[data-tab="' + tab + '"]'); if (nb) { nb.focus(); } }
        } } }, LABELS[t], c[t] !== '' ? h('small', { text: ' ' + c[t] }) : null);
      list.appendChild(b);
    });
    host.appendChild(list);
    var panel = h('div', { role: 'tabpanel', id: 'edPanel', 'aria-labelledby': 'edTab-' + tab, class: 'ed-panel', 'data-panel': tab });
    host.appendChild(panel);
    ({ overview: overview, tables: tables, fields: fieldsTab, measures: measures, hierarchies: hierarchies, rules: rules, masks: masks, access: access })[tab](ctx, panel, doc);
  }

  function input(ctx, value, path, label, set, formula) {
    var el = ctx.h('input', { class: 'ed-in', value: value || '', 'data-path': path, 'aria-label': label,
      on: { input: function (e) { var v = e.target.value; ctx.change(function (d) { set(d, v); }, path); }, focus: function () { ctx.focus(path); } } });
    if (formula) { E.formula(el, ctx.names); }
    return el;
  }
  function addButton(ctx, label, attr, fn) {
    var a = { type: 'button', class: 'ed-btn', 'data-edit': '' };
    a[attr] = '';
    a.on = { click: fn };
    return ctx.h('p', {}, ctx.h('button', a, label));
  }
  function remove(ctx, label, attr, fn) {
    var a = { type: 'button', class: 'ed-linkbtn', 'data-edit': '', 'aria-label': label };
    a[attr] = '';
    a.on = { click: fn };
    return ctx.h('button', a, 'Remove');
  }
  function grid(ctx, heads, rows, key) {
    var h = ctx.h;
    return h('div', { class: 'ed-scroll' }, h('table', { class: 'ed-grid-t', 'data-resize': 'columns', 'data-resize-key': 'editors.model-' + key },
      h('thead', {}, h('tr', {}, heads.map(function (x) { return h('th', { scope: 'col', text: x }); }))), h('tbody', {}, rows)));
  }

  /* ---- Overview: the four questions -------------------------------------------------------------------------------------------- */
  function overview(ctx, panel, doc) {
    var h = ctx.h, facts = ctx.S.measureFacts || [];
    var dims = Object.keys(doc.tables || {}).filter(function (a) { return doc.tables[a].role === 'dimension'; });
    var sec = doc.security || {};
    panel.appendChild(h('div', { class: 'ed-four' },
      h('section', { class: 'ed-card', 'data-question': 'about' }, h('h3', { text: 'What is it about?' }),
        E.liveForm(ctx, 'ed-about', 'What is it about?', {
          model: { type: 'string', title: 'Name', hook: { 'data-path': 'model' } }, title: { type: 'string', title: 'Title', hook: { 'data-path': 'title' } },
          description: { type: 'string', title: 'In words', hook: { 'data-path': 'description', 'aria-label': 'Description' } } },
        { model: doc.model, title: doc.title, description: doc.description },
        function (d, path, v) { if (path === 'model') { d.model = (v || '').trim(); } else if ((v || '').trim()) { d[path] = v; } else { delete d[path]; } })),
      h('section', { class: 'ed-card', 'data-question': 'by' }, h('h3', { text: 'What do people look at it by?' }),
        h('p', { text: dims.length ? dims.join(', ') + (Object.keys(doc.hierarchies || {}).length ? '; hierarchies: ' + Object.keys(doc.hierarchies).join(', ') : '') : 'the fact table\'s own columns (add dimension tables in Tables)' })),
      h('section', { class: 'ed-card', 'data-question': 'numbers' }, h('h3', { text: 'What numbers does it give?' }),
        h('ul', {}, Object.keys(doc.measures || {}).map(function (m) {
          var f = facts.filter(function (x) { return x.name === m; })[0];
          return h('li', {}, h('b', { text: m }), f ? ' · adds up? ' + f.word : '');
        }))),
      h('section', { class: 'ed-card', 'data-question': 'who' }, h('h3', { text: 'Who may see what?' }),
        h('p', { text: (Object.keys(sec.rows || {}).length ? 'row rules on ' + Object.keys(sec.rows).join(', ') : 'no row rules of its own (the tables\' rules apply)') + '; ' +
          ((sec.masked || []).length ? (sec.masked.length + ' masked column(s)') : 'no masks of its own') }),
        h('button', { type: 'button', class: 'ed-linkbtn', 'data-always': '', 'data-goto-access': '', on: { click: function () { tab = 'access'; ctx.redraw(); } } }, 'See the Access matrix'))));
  }

  /* ---- Tables and relationships ------------------------------------------------------------------------------------------------- */
  function tables(ctx, panel, doc) {
    var h = ctx.h, src = ctx.sources().tables || [];
    var rows = Object.keys(doc.tables || {}).map(function (a) {
      var u = doc.tables[a], p = 'tables.' + a;
      var sel = h('select', { class: 'ed-in', 'data-path': p + '.table', 'data-model-table': a, 'aria-label': 'Table of ' + a,
        on: { change: function (e) { var v = e.target.value; ctx.change(function (d) { d.tables[a].table = v; }, p + '.table'); } } },
        src.map(function (x) { return h('option', { value: x.table, selected: String(u.table).replace(/@.*$/, '') === x.table, text: x.table + (x.connector ? ' · ' + x.connector : '') }); }));
      if (!src.some(function (x) { return x.table === String(u.table).replace(/@.*$/, ''); })) { sel.appendChild(h('option', { value: u.table, selected: true, text: u.table + ' (not found)' })); }
      var role = h('select', { class: 'ed-in ed-narrow', 'data-path': p + '.role', 'data-model-role': a, 'aria-label': 'Role of ' + a,
        on: { change: function (e) { var v = e.target.value; ctx.change(function (d) { d.tables[a].role = v; }, p + '.role'); } } },
        ['fact', 'dimension'].map(function (r) { return h('option', { value: r, selected: (u.role || 'fact') === r, text: r }); }));
      return h('tr', { 'data-path': p }, h('td', { text: a }), h('td', {}, sel), h('td', {}, role),
        h('td', {}, remove(ctx, 'Remove ' + a, 'data-remove-alias', function () { ctx.change(function (d) { delete d.tables[a]; }, 'tables'); ctx.redraw(); })));
    });
    panel.appendChild(grid(ctx, ['Alias', 'Table', 'Role', ''], rows, 'tables'));
    panel.appendChild(addButton(ctx, '+ Table', 'data-add-table', function () {
      var first = src[0];
      if (!first) { E.say('No table to add: make one first.', true); return; }
      var a = first.table.split('.').pop(), i = 1;
      while ((ctx.S.doc.tables || {})[a]) { a = first.table.split('.').pop() + '_' + (++i); }
      ctx.change(function (d) { d.tables = d.tables || {}; d.tables[a] = { table: first.table, role: 'dimension' }; }, 'tables.' + a);
      ctx.redraw();
    }));
    panel.appendChild(h('h3', { text: 'Relationships (from the many side)' }));
    var all = fields(ctx, doc).map(function (f) { return f.name; });
    var rels = (doc.relationships || []).map(function (r, i) {
      var p = 'relationships[' + i + ']';
      function end(key) {
        var s = h('select', { class: 'ed-in', 'data-path': p + '.' + key, 'data-rel': key + '-' + i, 'aria-label': key + ' of relationship ' + (i + 1),
          on: { change: function (e) { var v = e.target.value; ctx.change(function (d) { d.relationships[i][key] = v; }, p + '.' + key); } } },
          all.map(function (f) { return h('option', { value: f, selected: r[key] === f, text: f }); }));
        if (all.indexOf(r[key]) < 0) { s.appendChild(h('option', { value: r[key] || '', selected: true, text: r[key] || '' })); }
        return s;
      }
      return h('tr', { 'data-path': p }, h('td', {}, end('from')), h('td', {}, end('to')),
        h('td', {}, h('input', { type: 'checkbox', checked: r.active !== false, 'data-rel-active': i, 'aria-label': 'Relationship ' + (i + 1) + ' is active',
          on: { change: function (e) { var on = e.target.checked; ctx.change(function (d) { if (on) { delete d.relationships[i].active; } else { d.relationships[i].active = false; } }, p); } } })),
        h('td', {}, remove(ctx, 'Remove relationship ' + (i + 1), 'data-remove-rel', function () { ctx.change(function (d) { d.relationships.splice(i, 1); if (!d.relationships.length) { delete d.relationships; } }, 'relationships'); ctx.redraw(); })));
    });
    panel.appendChild(grid(ctx, ['From', 'To', 'Active', ''], rels, 'relationships'));
    panel.appendChild(addButton(ctx, '+ Relationship', 'data-add-rel', function () {
      ctx.change(function (d) { d.relationships = d.relationships || []; d.relationships.push({ from: all[0] || '', to: all[1] || all[0] || '' }); }, 'relationships');
      ctx.redraw();
    }));
    panel.appendChild(h('div', { 'data-model-cost': '' }));
    refresh(ctx, panel.parentNode);
  }

  /* ---- Fields ------------------------------------------------------------------------------------------------------------------- */
  function fieldsTab(ctx, panel, doc) {
    var h = ctx.h;
    var used = JSON.stringify(doc.measures || {}) + JSON.stringify(doc.hierarchies || {}) + JSON.stringify(doc.security || {});
    var rows = fields(ctx, doc).map(function (f) {
      return h('tr', {}, h('td', { text: f.name }), h('td', { text: f.type }), h('td', { text: used.indexOf(f.name) >= 0 ? 'used' : '' }));
    });
    panel.appendChild(h('p', { class: 'ed-small', text: 'Every column of the model\'s tables, as alias.COLUMN: what formulas, hierarchies and rules name. A column is added or renamed in its table.' }));
    panel.appendChild(grid(ctx, ['Field', 'Type', 'In this model'], rows, 'fields'));
  }

  /* ---- Measures ----------------------------------------------------------------------------------------------------------------- */
  function measures(ctx, panel, doc) {
    var h = ctx.h;
    var rows = Object.keys(doc.measures || {}).map(function (m) {
      var x = doc.measures[m] || {}, p = 'measures.' + m;
      var f = (ctx.S.measureFacts || []).filter(function (y) { return y.name === m; })[0] || {};
      return h('tr', { 'data-path': p, 'data-measure': m },
        h('td', { text: m }),
        h('td', {}, x.python ? h('span', { class: 'ed-small', 'data-python-measure': m, text: 'Python: ' + (typeof x.python === 'string' ? x.python : JSON.stringify(x.python)) + ' (edit it in the YAML)' }) : input(ctx, x.expr, p + '.expr', 'Formula of ' + m, function (d, v) { d.measures[m].expr = v; }, true)),
        h('td', {}, input(ctx, x.format, p + '.format', 'Format of ' + m, function (d, v) { if (v.trim()) { d.measures[m].format = v; } else { delete d.measures[m].format; } })),
        h('td', { 'data-adds-up': m, title: f.why || '', text: f.word ? (f.word === 'yes' ? 'yes ✓' : f.word === 'ratio' ? 'ratio · per total' : 'no · per total') : '' }),
        h('td', {}, h('button', { type: 'button', class: 'ed-linkbtn', 'data-always': '', 'data-measure-card': m, 'aria-label': 'About ' + m, on: { click: function () { card(ctx, m); } } }, '?'),
          ' ', remove(ctx, 'Remove ' + m, 'data-remove-measure', function () { ctx.change(function (d) { delete d.measures[m]; }, 'measures'); ctx.redraw(); })));
    });
    panel.appendChild(grid(ctx, ['Name', 'Formula (Rachana)', 'Format', 'Adds up?', ''], rows, 'measures'));
    var name = h('input', { class: 'ed-in ed-narrow', 'data-new-measure-name': '', placeholder: 'Gross MTM', 'aria-label': 'Name of the new measure' });
    panel.appendChild(h('p', {}, name, ' ', h('button', { type: 'button', class: 'ed-btn', 'data-edit': '', 'data-add-measure': '', on: { click: function () {
      var n = name.value.trim();
      if (!n || (ctx.S.doc.measures || {})[n]) { E.say(n ? 'There is a measure ' + n + ' already.' : 'Name the measure first.', true); return; }
      var fact = Object.keys(ctx.S.doc.tables || {})[0] || 't';
      ctx.change(function (d) { d.measures = d.measures || {}; d.measures[n] = { expr: 'count(' + fact + ')' }; }, 'measures.' + n);
      ctx.redraw();
    } } }, '+ Measure')));
    panel.appendChild(h('p', { class: 'ed-small', text: 'Type a formula: the functions and each field appear as you type. Whether a total adds up is the engine\'s answer: VaR, ES and ratios are computed per total.' }));
  }
  function card(ctx, m) {
    var h = ctx.h, f = (ctx.S.measureFacts || []).filter(function (y) { return y.name === m; })[0] || {};
    var pv = ctx.S.preview && (ctx.S.preview.measures || []).filter(function (y) { return y.name === m; })[0];
    ctx.dialog(m, h('dl', { class: 'ed-card-dl', 'data-card': m },
      h('dt', { text: 'Formula' }), h('dd', {}, h('code', { text: f.expr || '' })),
      h('dt', { text: 'In words' }), h('dd', { text: f.explain || '' }),
      h('dt', { text: 'Worked test value' }), h('dd', { 'data-card-value': '', text: pv && pv.value !== undefined && pv.value !== null ? E.num(pv.value) + ' — every row you may see' + (ctx.S.preview.date ? ' on ' + ctx.S.preview.date : '') : 'read in the preview' }),
      h('dt', { text: 'Adds up?' }), h('dd', { text: (f.word || '') + (f.why ? ' — ' + f.why : '') }),
      h('dt', { text: 'Used by' }), h('dd', { text: (f.usedBy || []).length ? f.usedBy.join(', ') : 'no other measure of this model' })));
  }

  /* ---- Hierarchies -------------------------------------------------------------------------------------------------------------- */
  function hierarchies(ctx, panel, doc) {
    var h = ctx.h;
    var rows = Object.keys(doc.hierarchies || {}).map(function (n) {
      var p = 'hierarchies.' + n;
      return h('tr', { 'data-path': p }, h('td', { text: n }),
        h('td', {}, input(ctx, (doc.hierarchies[n] || []).join(', '), p, 'Levels of ' + n, function (d, v) { d.hierarchies[n] = v.split(',').map(function (s) { return s.trim(); }).filter(Boolean); }, true)),
        h('td', {}, remove(ctx, 'Remove ' + n, 'data-remove-hierarchy', function () { ctx.change(function (d) { delete d.hierarchies[n]; }, 'hierarchies'); ctx.redraw(); })));
    });
    panel.appendChild(grid(ctx, ['Hierarchy', 'Levels, top first (alias.COLUMN, comma separated)', ''], rows, 'hierarchies'));
    var name = h('input', { class: 'ed-in ed-narrow', 'data-new-hierarchy-name': '', placeholder: 'Desk and book', 'aria-label': 'Name of the new hierarchy' });
    panel.appendChild(h('p', {}, name, ' ', h('button', { type: 'button', class: 'ed-btn', 'data-edit': '', 'data-add-hierarchy': '', on: { click: function () {
      var n = name.value.trim();
      if (!n) { E.say('Name the hierarchy first.', true); return; }
      var f = fields(ctx, ctx.S.doc)[0];
      ctx.change(function (d) { d.hierarchies = d.hierarchies || {}; d.hierarchies[n] = f ? [f.name] : []; }, 'hierarchies.' + n);
      ctx.redraw();
    } } }, '+ Hierarchy')));
  }

  /* ---- Row rules ---------------------------------------------------------------------------------------------------------------- */
  function rules(ctx, panel, doc) {
    var h = ctx.h, roles = ctx.sources().roles || [];
    var rowsBy = (doc.security || {}).rows || {};
    var rows = [];
    Object.keys(rowsBy).forEach(function (a) {
      (rowsBy[a] || []).forEach(function (r, i) {
        var p = 'security.rows.' + a + '[' + i + ']';
        var role = h('select', { class: 'ed-in ed-narrow', 'data-path': p + '.role', 'data-rule-role': a + i, 'aria-label': 'Role of rule ' + (i + 1) + ' on ' + a,
          on: { change: function (e) { var v = e.target.value; ctx.change(function (d) { d.security.rows[a][i].role = v; }, p + '.role'); } } },
          roles.map(function (x) { return h('option', { value: x, selected: String(r.role) === x, text: x }); }));
        if (roles.indexOf(String(r.role)) < 0) { role.appendChild(h('option', { value: r.role, selected: true, text: String(r.role) })); }
        rows.push(h('tr', { 'data-path': p }, h('td', { text: a }), h('td', {}, role),
          h('td', {}, input(ctx, r.where, p + '.where', 'Rows of rule ' + (i + 1) + ' on ' + a, function (d, v) { d.security.rows[a][i].where = v; }, true)),
          h('td', {}, remove(ctx, 'Remove rule ' + (i + 1) + ' on ' + a, 'data-remove-rule', function () {
            ctx.change(function (d) { d.security.rows[a].splice(i, 1); if (!d.security.rows[a].length) { delete d.security.rows[a]; } }, 'security'); ctx.redraw();
          }))));
      });
    });
    panel.appendChild(h('p', { class: 'ed-small', text: 'A model\'s rules only narrow what the tables\' own rules allow. A role no rule names sees no rows of that table.' }));
    panel.appendChild(grid(ctx, ['Table (alias)', 'Role', 'Sees the rows where (Rachana)', ''], rows, 'rules'));
    panel.appendChild(addButton(ctx, '+ Row rule', 'data-add-rule', function () {
      var a = Object.keys(ctx.S.doc.tables || {})[0];
      if (!a) { E.say('Add a table first.', true); return; }
      ctx.change(function (d) {
        d.security = d.security || {}; d.security.rows = d.security.rows || {}; d.security.rows[a] = d.security.rows[a] || [];
        d.security.rows[a].push({ role: roles[0] || 'viewer', where: a + '.DESK in user.desks' });
      }, 'security.rows');
      ctx.redraw();
    }));
  }

  /* ---- Masks -------------------------------------------------------------------------------------------------------------------- */
  function masks(ctx, panel, doc) {
    var h = ctx.h, masked = ((doc.security || {}).masked || []);
    var names = fields(ctx, doc).map(function (f) { return f.name; });
    var list = E.liveForm(ctx, 'ed-picks', 'Masked columns', {             // drishti-inputs' Form: a row of checkboxes, each with its data-mask hook
      masked: { type: 'array', title: 'Masked columns', widget: 'checkboxes', optionHook: 'mask', items: { type: 'string', 'enum': names } } },
    { masked: masked.filter(function (x) { return names.indexOf(x) >= 0; }) },
    function (d, path, v) {
      d.security = d.security || {};
      var keep = (d.security.masked || []).filter(function (x) { return names.indexOf(x) < 0; }), m = keep.concat(v || []);   // a mask of a column not listed here stays
      if (m.length) { d.security.masked = m; } else { delete d.security.masked; if (!Object.keys(d.security).length) { delete d.security; } }
    });
    list.setAttribute('data-masks', '');
    panel.appendChild(h('p', { class: 'ed-small', text: 'A masked column shows ••• to every reader without the raw power, and cannot filter, sort or group.' }));
    panel.appendChild(list);
  }

  /* ---- Access: roles × rows, columns, editing (the server works it out) ----------------------------------------------------------- */
  function access(ctx, panel) {
    var h = ctx.h;
    var host = h('div', { 'data-access': '', 'aria-busy': 'true' }, h('p', { class: 'ed-muted', text: 'Working out who sees what…' }));
    panel.appendChild(host);
    ctx.api('POST', 'access', { yaml: ctx.S.yaml, original: ctx.S.original }).then(function (r) {
      E.clear(host);
      host.appendChild(grid(ctx, ['Role', 'Rows', 'Columns', 'May edit'], (r.roles || []).map(function (x) {
        return h('tr', { 'data-access-role': x.role }, h('td', { text: x.role }), h('td', { text: x.rows }), h('td', { text: x.columns }), h('td', { text: x.edit ? 'yes' : 'no' }));
      }), 'access'));
    }).catch(function (e) { E.clear(host).appendChild(h('p', { class: 'ed-bad', text: e.message })); })
      .then(function () { host.setAttribute('aria-busy', 'false'); });
  }

  /* ---- after a check or a preview ------------------------------------------------------------------------------------------------- */
  function refresh(ctx, host) {
    if (!host) { return; }
    (ctx.S.measureFacts || []).forEach(function (f) {
      var cell = host.querySelector('[data-adds-up="' + f.name.replace(/"/g, '') + '"]');
      if (cell) { cell.textContent = f.word === 'yes' ? 'yes ✓' : f.word === 'ratio' ? 'ratio · per total' : 'no · per total'; cell.title = f.why || ''; }
    });
    var cost = host.querySelector('[data-model-cost]');
    var pv = ctx.S.preview;
    if (cost && pv) {
      E.clear(cost);
      (pv.warnings || []).forEach(function (w) { cost.appendChild(ctx.h('p', { class: 'ed-cost ed-warn', text: '! ' + w })); });
      if (!(pv.warnings || []).length && (pv.sides || []).length) {
        var conns = {};
        pv.sides.forEach(function (s) { conns[s.connector || '?'] = 1; });
        cost.appendChild(ctx.h('p', { class: 'ed-cost ed-muted', text: Object.keys(conns).length > 1 ? 'Joins across connectors: each side within ' + E.num(pv.limit) + ' rows or filtered.' : 'One connector: no join across connectors.' }));
      }
    }
  }

  /* ---- the preview pane: each measure's total, with the engine's "adds up?" -------------------------------------------------------- */
  function preview(ctx, pane, r) {
    var h = ctx.h;
    pane.appendChild(h('h3', { text: 'Live answer' + (r.date ? ' · ' + r.date : '') }));
    if (r.error) { pane.appendChild(h('p', { class: 'ed-bad', 'data-preview-error': '', text: r.error })); }
    pane.appendChild(h('div', { class: 'ed-scroll' }, h('table', { class: 'ed-grid-t', 'data-measures-preview': '' },
      h('thead', {}, h('tr', {}, ['Measure', 'Total', 'Adds up?'].map(function (x) { return h('th', { scope: 'col', text: x }); }))),
      h('tbody', {}, (r.measures || []).map(function (m) {
        return h('tr', {}, h('td', { text: m.name }), h('td', { class: 'ed-num', text: m.value === undefined ? '' : E.num(m.value) }), h('td', { title: m.why || '', text: m.word || '' }));
      })))));
    var checks = [];
    (r.warnings || []).forEach(function (w) { checks.push({ level: 'warning', text: w }); });
    (r.sides || []).forEach(function (s) { checks.push({ level: 'ok', text: s.alias + ': ' + s.table + (s.connector ? ' · ' + s.connector : '') + (s.rows >= 0 ? ' · ' + E.num(s.rows) + ' rows' : '') }); });
    (r.masked || []).forEach(function (m) { checks.push({ level: 'info', text: m + ' is masked for you' }); });
    E.checks(pane, checks);
    E.usedBy(pane);
    return true;
  }

  E.register('model', { render: render, refresh: refresh, preview: preview });
})();
