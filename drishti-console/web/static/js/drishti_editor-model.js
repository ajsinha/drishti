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
/* The semantic model editor (HOW_IT_FITS.md section S3: option A as the editor, B as the Overview, C for a measure's details
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

  function addButton(ctx, label, attr, fn) {
    var a = { type: 'button', class: 'ed-btn', 'data-edit': '' };
    a[attr] = '';
    a.on = { click: fn };
    return ctx.h('p', {}, ctx.h('button', a, label));
  }
  function remove(ctx, label, attr, value, fn) {
    var a = { type: 'button', class: 'ed-linkbtn', 'data-edit': '', 'aria-label': label, disabled: !!ctx.S.readOnly };
    a[attr] = value;
    a.on = { click: fn };
    return ctx.h('button', a, 'Remove');
  }
  /** A select's options from a list, the values the rows hold now kept when they are not among them ({@code note} after their words). */
  function choices(list, held, note) {
    var out = list.map(function (x) { return typeof x === 'object' ? x : { value: x, label: x }; });
    held.forEach(function (cur) {
      if (cur !== undefined && cur !== null && cur !== '' && !out.some(function (x) { return x.value === cur; })) { out.push({ value: cur, label: String(cur) + (note || '') }); }
    });
    return out;
  }
  function addsUp(f) { return f && f.word ? (f.word === 'yes' ? 'yes ✓' : f.word === 'ratio' ? 'ratio · per total' : 'no · per total') : ''; }
  /** A read-only grid of rows (fields, the access matrix): drishti-grid without editing. */
  function grid(ctx, key, label, cols, rows, rowData) {
    return E.rowGrid(ctx, key, { label: label, rows: rows, rowData: rowData, columns: cols.map(function (c) { return { field: c[0], header: c[1], width: c[2] || 180 }; }) });
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
      var u = doc.tables[a] || {};
      var bare = String(u.table || '').replace(/@.*$/, '');
      return { alias: a, table: src.some(function (x) { return x.table === bare; }) ? bare : u.table, role: u.role || 'fact' };
    });
    var known = src.map(function (x) { return { value: x.table, label: x.table + (x.connector ? ' · ' + x.connector : '') }; });
    panel.appendChild(E.rowGrid(ctx, 'tables', { label: 'The model\'s tables', rows: rows, id: function (r) { return r.alias; },
      rowData: function (r) { return { path: 'tables.' + r.alias, modelTable: r.alias }; },
      columns: [
        { field: 'alias', header: 'Alias', width: 120 },
        { field: 'table', header: 'Table', width: 260, options: choices(known, rows.map(function (r) { return r.table; }), ' (not found)'),
          set: function (d, r, v) { d.tables[r.alias].table = v; } },
        { field: 'role', header: 'Role', width: 120, options: ['fact', 'dimension'], set: function (d, r, v) { d.tables[r.alias].role = v; } },
        { field: 'remove', header: '', width: 90, render: function (r) {
          return remove(ctx, 'Remove ' + r.alias, 'data-remove-alias', r.alias, function () { ctx.change(function (d) { delete d.tables[r.alias]; }, 'tables'); ctx.redraw(); });
        } }] }));
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
    var rels = (doc.relationships || []).map(function (r, i) { return { i: i, name: r.name || '', from: r.from || '', to: r.to || '', active: r.active !== false, direction: r.direction || 'single' }; });
    var ends = choices(all, rels.map(function (r) { return r.from; }).concat(rels.map(function (r) { return r.to; })));
    panel.appendChild(E.rowGrid(ctx, 'relationships', { label: 'Relationships', rows: rels, id: function (r) { return r.i; },
      rowData: function (r) { return { path: 'relationships[' + r.i + ']', rel: r.i }; },
      columns: [
        { field: 'name', header: 'Name', width: 130, set: function (d, r, v) { if (v) { d.relationships[r.i].name = v; } else { delete d.relationships[r.i].name; } } },
        { field: 'from', header: 'From', width: 200, options: ends, set: function (d, r, v) { d.relationships[r.i].from = v; } },
        { field: 'to', header: 'To', width: 200, options: ends, set: function (d, r, v) { d.relationships[r.i].to = v; } },
        { field: 'direction', header: 'Filter', width: 110, options: [{ value: 'single', label: 'One way' }, { value: 'both', label: 'Both ways' }], set: function (d, r, v) { if (v === 'both') { d.relationships[r.i].direction = 'both'; } else { delete d.relationships[r.i].direction; } } },
        { field: 'active', header: 'Active', width: 80, type: 'boolean', set: function (d, r, v) { if (v) { delete d.relationships[r.i].active; } else { d.relationships[r.i].active = false; } } },
        { field: 'remove', header: '', width: 90, render: function (r) {
          return remove(ctx, 'Remove relationship ' + (r.i + 1), 'data-remove-rel', String(r.i), function () {
            ctx.change(function (d) { d.relationships.splice(r.i, 1); if (!d.relationships.length) { delete d.relationships; } }, 'relationships'); ctx.redraw();
          });
        } }] }));
    panel.appendChild(addButton(ctx, '+ Relationship', 'data-add-rel', function () {
      ctx.change(function (d) { d.relationships = d.relationships || []; d.relationships.push({ from: all[0] || '', to: all[1] || all[0] || '' }); }, 'relationships');
      ctx.redraw();
    }));
    bridges(ctx, panel, doc, all);
    panel.appendChild(h('div', { 'data-model-cost': '' }));
    refresh(ctx, panel.parentNode);
    refusal(ctx, panel, doc);
    daxBox(ctx, panel);
  }

  /* ---- Many-to-many bridges: table first; the paths are drawn on a refusal or when asked ------------------------------------------- */
  function bridges(ctx, panel, doc, all) {
    var h = ctx.h;
    panel.appendChild(h('h3', { text: 'Many-to-many bridges' }));
    panel.appendChild(h('p', { class: 'ed-small', text: 'Dimensions filter across a bridge by a semi-join: a fact is never counted twice, and group rows may not add to the total because a book can sit in two groups.' }));
    var rows = (doc.bridges || []).map(function (b, i) { return { i: i, name: b.name || '', left: b.left || '', right: b.right || '', through: b.through || '', leftFilter: b['left-filter'] || 'both' }; });
    var aliases = Object.keys(doc.tables || {});
    var ends = choices(all, rows.map(function (r) { return r.left; }).concat(rows.map(function (r) { return r.right; })));
    panel.appendChild(E.rowGrid(ctx, 'bridges', { label: 'Bridges', rows: rows, id: function (r) { return r.i; },
      rowData: function (r) { return { path: 'bridges[' + r.i + ']', bridge: r.i }; },
      columns: [
        { field: 'name', header: 'Name', width: 140, set: function (d, r, v) { d.bridges[r.i].name = v; } },
        { field: 'left', header: 'Left key', width: 180, options: ends, set: function (d, r, v) { d.bridges[r.i].left = v; } },
        { field: 'right', header: 'Right key', width: 180, options: ends, set: function (d, r, v) { d.bridges[r.i].right = v; } },
        { field: 'through', header: 'Bridge table', width: 160, options: aliases, set: function (d, r, v) { d.bridges[r.i].through = v; } },
        { field: 'leftFilter', header: 'Right filters left', width: 130, options: [{ value: 'both', label: 'Yes' }, { value: 'one_way', label: 'No' }], set: function (d, r, v) { if (v === 'one_way') { d.bridges[r.i]['left-filter'] = 'one_way'; } else { delete d.bridges[r.i]['left-filter']; } } },
        { field: 'remove', header: '', width: 90, render: function (r) {
          return remove(ctx, 'Remove bridge ' + (r.i + 1), 'data-remove-bridge', String(r.i), function () { ctx.change(function (d) { d.bridges.splice(r.i, 1); if (!d.bridges.length) { delete d.bridges; } }, 'bridges'); ctx.redraw(); });
        } }] }));
    panel.appendChild(addButton(ctx, '+ Bridge', 'data-add-bridge', function () {
      ctx.change(function (d) { d.bridges = d.bridges || []; d.bridges.push({ name: 'bridge_' + ((d.bridges.length || 0) + 1), left: all[0] || '', right: all[1] || all[0] || '', through: aliases[0] || '' }); }, 'bridges');
      ctx.redraw();
    }));
  }

  /* an ambiguous path comes back from the server's check with both paths and the fix: shown with the fixes one click away and the paths drawn */
  function refusal(ctx, panel, doc) {
    var h = ctx.h;
    var p = (ctx.S.problems || []).filter(function (x) { return String(x.message).indexOf('Two filter paths') === 0; })[0];
    var draw = h('div', { class: 'ed-paths', 'data-model-paths': '' });
    var shown = !!p;
    function paint() {
      E.clear(draw);
      draw.classList.toggle('ed-paths-on', shown);
      if (shown) { paths(ctx, draw, doc); }
    }
    if (p) {
      var msg = String(p.message);
      var off = /make '([^']+)' inactive/.exec(msg), both = /turn off both ways on '([^']+)'/.exec(msg);
      panel.appendChild(h('div', { class: 'ed-cost ed-warn', 'data-model-refusal': '' },
        h('p', { text: msg }),
        off ? h('button', { type: 'button', class: 'ed-linkbtn', 'data-always': '', 'data-fix-inactive': off[1], on: { click: function () {
          ctx.change(function (d) { (d.relationships || []).forEach(function (r) { if (r.name === off[1] || r.id === off[1]) { r.active = false; } }); }, 'relationships'); ctx.redraw(); } }, text: 'Make ' + off[1] + ' inactive' }) : null,
        both ? h('button', { type: 'button', class: 'ed-linkbtn', 'data-always': '', 'data-fix-oneway': both[1], on: { click: function () {
          ctx.change(function (d) {
            (d.relationships || []).forEach(function (r) { if (r.name === both[1] || r.id === both[1]) { delete r.direction; } });
            (d.bridges || []).forEach(function (b) { var leftAlias = String(b.left || '').split('.')[0]; if ((b['left-name'] || ('bridge_' + leftAlias)) === both[1]) { b['left-filter'] = 'one_way'; } });
          }, 'relationships'); ctx.redraw(); } }, text: 'Turn off both ways on ' + both[1] }) : null));
    }
    panel.appendChild(addButton(ctx, shown ? 'Hide the paths' : 'Show the paths', 'data-show-paths', function () { shown = !shown; paint(); }));
    panel.appendChild(draw);
    paint();
  }

  /* drawn only on a refusal or when asked: the same relationships as the grid, through drishti-graph */
  function paths(ctx, host, doc) {
    var nodes = Object.keys(doc.tables || {}).map(function (a) { return { id: a, label: a, group: doc.tables[a].role || '' }; });
    var edges = (doc.relationships || []).filter(function (r) { return r.active !== false; }).map(function (r) { return { from: String(r.from).split('.')[0], to: String(r.to).split('.')[0], label: r.direction === 'both' ? 'both ways' : '' }; });
    (doc.bridges || []).forEach(function (b) {
      edges.push({ from: b.through, to: String(b.left).split('.')[0], label: 'bridge' });
      edges.push({ from: b.through, to: String(b.right).split('.')[0], label: 'bridge' });
    });
    window.Drishti.elements.core.load('drishti-graph').then(function (G) {
      (G.drawGraphPanel || window.Drishti.elements.graph.drawGraphPanel)(host, { nodes: nodes, edges: edges }, { density: 'compact' });
    });
  }

  /* DAX to Rachana: the server converts and names exactly what it could not */
  function daxBox(ctx, panel) {
    var h = ctx.h;
    panel.appendChild(h('h3', { text: 'Convert DAX to Rachana' }));
    var input = h('input', { type: 'text', 'data-dax-input': '', 'aria-label': 'DAX expression', placeholder: 'CALCULATE(SUM(trades[MTM]), ALL(desks))', class: 'ed-in' });
    var out = h('div', { 'data-dax-output': '' });
    panel.appendChild(input);
    panel.appendChild(addButton(ctx, 'Convert', 'data-dax-convert', function () {
      ctx.api('POST', 'dax', { dax: input.value }).then(function (r) {
        E.clear(out);
        if (r.rachana) { out.appendChild(h('p', { class: 'ed-small', text: r.rachana })); }
        (r.notes || []).forEach(function (n) { out.appendChild(h('p', { class: 'ed-small', text: 'Note: ' + n })); });
        (r.notConverted || []).forEach(function (n) { out.appendChild(h('p', { class: 'ed-cost ed-warn', text: 'Not converted: ' + n })); });
      });
    }));
    panel.appendChild(out);
  }

  /* ---- Fields ------------------------------------------------------------------------------------------------------------------- */
  function fieldsTab(ctx, panel, doc) {
    var h = ctx.h;
    var used = JSON.stringify(doc.measures || {}) + JSON.stringify(doc.hierarchies || {}) + JSON.stringify(doc.security || {});
    var rows = fields(ctx, doc).map(function (f) { return { name: f.name, type: f.type, used: used.indexOf(f.name) >= 0 ? 'used' : '' }; });
    panel.appendChild(h('p', { class: 'ed-small', text: 'Every column of the model\'s tables, as alias.COLUMN: what formulas, hierarchies and rules name. A column is added or renamed in its table.' }));
    panel.appendChild(grid(ctx, 'fields', 'Fields', [['name', 'Field', 240], ['type', 'Type', 120], ['used', 'In this model', 120]], rows, function (r) { return { field: r.name }; }));
  }

  /* ---- Measures ----------------------------------------------------------------------------------------------------------------- */
  function measures(ctx, panel, doc) {
    var h = ctx.h;
    var rows = Object.keys(doc.measures || {}).map(function (m) {
      var x = doc.measures[m] || {};
      return { name: m, expr: x.python ? 'Python: ' + (typeof x.python === 'string' ? x.python : JSON.stringify(x.python)) + ' (edit it in the YAML)' : x.expr || '', python: !!x.python, format: x.format || '' };
    });
    function fact(m) { return (ctx.S.measureFacts || []).filter(function (y) { return y.name === m; })[0]; }
    panel.appendChild(E.rowGrid(ctx, 'measures', { label: 'Measures', rows: rows, id: function (r) { return r.name; },
      rowData: function (r) { return { path: 'measures.' + r.name, measure: r.name, pythonMeasure: r.python ? r.name : null }; },
      columns: [
        { field: 'name', header: 'Name', width: 140 },
        { field: 'expr', header: 'Formula (Rachana)', width: 300, editor: 'code', editable: function (r) { return !r.python; }, set: function (d, r, v) { d.measures[r.name].expr = v || ''; } },
        { field: 'format', header: 'Format', width: 110, set: function (d, r, v) { if (String(v || '').trim()) { d.measures[r.name].format = v; } else { delete d.measures[r.name].format; } } },
        { field: 'addsUp', header: 'Adds up?', width: 130, render: function (r) {           // the engine's answer, read as the row is drawn (refresh draws it again)
          var f = fact(r.name) || {};
          return h('span', { 'data-adds-up': r.name, title: f.why || '', text: addsUp(f) });
        } },
        { field: 'actions', header: '', width: 120, render: function (r) {
          return h('span', {}, h('button', { type: 'button', class: 'ed-linkbtn', 'data-always': '', 'data-measure-card': r.name, 'aria-label': 'About ' + r.name, on: { click: function () { card(ctx, r.name); } } }, '?'),
            ' ', remove(ctx, 'Remove ' + r.name, 'data-remove-measure', r.name, function () { ctx.change(function (d) { delete d.measures[r.name]; }, 'measures'); ctx.redraw(); }));
        } }] }));
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
    var rows = Object.keys(doc.hierarchies || {}).map(function (n) { return { name: n, levels: (doc.hierarchies[n] || []).join(', ') }; });
    panel.appendChild(E.rowGrid(ctx, 'hierarchies', { label: 'Hierarchies', rows: rows, id: function (r) { return r.name; },
      rowData: function (r) { return { path: 'hierarchies.' + r.name, hierarchy: r.name }; },
      columns: [
        { field: 'name', header: 'Hierarchy', width: 160 },
        { field: 'levels', header: 'Levels, top first (alias.COLUMN, comma separated)', width: 380, editor: 'code',
          set: function (d, r, v) { d.hierarchies[r.name] = String(v || '').split(',').map(function (s) { return s.trim(); }).filter(Boolean); } },
        { field: 'remove', header: '', width: 90, render: function (r) {
          return remove(ctx, 'Remove ' + r.name, 'data-remove-hierarchy', r.name, function () { ctx.change(function (d) { delete d.hierarchies[r.name]; }, 'hierarchies'); ctx.redraw(); });
        } }] }));
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
      (rowsBy[a] || []).forEach(function (r, i) { rows.push({ alias: a, i: i, role: r.role === undefined || r.role === null ? '' : String(r.role), where: r.where || '' }); });
    });
    var offered = choices(roles, rows.map(function (r) { return r.role; }));
    panel.appendChild(h('p', { class: 'ed-small', text: 'A model\'s rules only narrow what the tables\' own rules allow. A role no rule names sees no rows of that table.' }));
    panel.appendChild(E.rowGrid(ctx, 'rules', { label: 'Row rules', rows: rows, id: function (r) { return r.alias + '|' + r.i; },
      rowData: function (r) { return { path: 'security.rows.' + r.alias + '[' + r.i + ']', rule: r.alias + r.i }; },
      columns: [
        { field: 'alias', header: 'Table (alias)', width: 130 },
        { field: 'role', header: 'Role', width: 140, options: offered, set: function (d, r, v) { d.security.rows[r.alias][r.i].role = v; } },
        { field: 'where', header: 'Sees the rows where (Rachana)', width: 340, editor: 'code', set: function (d, r, v) { d.security.rows[r.alias][r.i].where = v || ''; } },
        { field: 'remove', header: '', width: 90, render: function (r) {
          return remove(ctx, 'Remove rule ' + (r.i + 1) + ' on ' + r.alias, 'data-remove-rule', r.alias + r.i, function () {
            ctx.change(function (d) { d.security.rows[r.alias].splice(r.i, 1); if (!d.security.rows[r.alias].length) { delete d.security.rows[r.alias]; } }, 'security'); ctx.redraw();
          });
        } }] }));
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
      host.appendChild(grid(ctx, 'access', 'Who sees what', [['role', 'Role', 140], ['rows', 'Rows', 260], ['columns', 'Columns', 260], ['edit', 'May edit', 90]],
        (r.roles || []).map(function (x) { return { role: x.role, rows: x.rows, columns: x.columns, edit: x.edit ? 'yes' : 'no' }; }), function (x) { return { accessRole: x.role }; }));
    }).catch(function (e) { E.clear(host).appendChild(h('p', { class: 'ed-bad', text: e.message })); })
      .then(function () { host.setAttribute('aria-busy', 'false'); });
  }

  /* ---- after a check or a preview ------------------------------------------------------------------------------------------------- */
  function refresh(ctx, host) {
    if (!host) { return; }
    var ms = host.querySelector('[data-rowgrid="measures"]');
    if (ms && ms.grid) { ms.grid.refresh(); }                           // the "adds up?" cells read the engine's answer as they are drawn
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
