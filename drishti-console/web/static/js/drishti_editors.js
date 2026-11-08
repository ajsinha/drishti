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
/* The editors of the table layer (docs/architecture/EDITORS.md sections 1 to 4) on drishti-designer's DesignerShell: the objects with
   search | the editor with the live YAML (the kit's YamlPane over drishti-inputs' CodeEditor) below it | the live preview with checks, the
   Visual / YAML / Both toggle and the layout remembered per person (the server keeps them), and the header: the status with versions,
   Validate, Preview, History, Impact, Save draft and Propose for review (four eyes). A visual edit sends the document and
   the server writes the YAML back; a YAML edit sends the text and the server answers the document, so either side shows the other's
   edit. Every check, total and figure is the server's. The editors themselves (drishti_editor-table.js, -model.js, -python.js) register
   here; the + New steps are drishti_editor-new.js. One page's state lives in this closure, never in a shared global. */
(function () {
  'use strict';
  var E = (window.Drishti.editors = window.Drishti.editors || {});
  var editors = {};
  E.register = function (key, editor) { editors[key] = editor; };

  /* ---- DOM ------------------------------------------------------------------------------------------------------------------- */
  function h(tag, attrs) {
    var el = document.createElement(tag);
    Object.keys(attrs || {}).forEach(function (k) {
      var v = attrs[k];
      if (v === null || v === undefined || v === false) { return; }
      if (k === 'text') { el.textContent = v; }
      else if (k === 'on') { Object.keys(v).forEach(function (ev) { el.addEventListener(ev, v[ev]); }); }
      else if (k === 'class') { el.className = v; }
      else if (k === 'value') { el.value = v; }
      else if (k === 'checked' || k === 'disabled' || k === 'hidden' || k === 'readOnly' || k === 'selected') { el[k] = !!v; }
      else { el.setAttribute(k, v === true ? '' : v); }
    });
    for (var i = 2; i < arguments.length; i++) {
      var c = arguments[i];
      if (c === null || c === undefined || c === false) { continue; }
      if (Array.isArray(c)) { c.forEach(function (x) { if (x) { el.appendChild(typeof x === 'string' ? document.createTextNode(x) : x); } }); }
      else { el.appendChild(typeof c === 'string' ? document.createTextNode(c) : c); }
    }
    return el;
  }
  function clear(el) { while (el && el.firstChild) { el.removeChild(el.firstChild); } return el; }
  /** drishti-inputs' Select (<drishti-select>): attrs as h's (aria-label names its control; on.change hears its drishti:change), options
      [{value, label, group}] or plain values, value the one picked (else the first). */
  function pick(attrs, options, value) {
    var a = Object.assign({}, attrs), label = a['aria-label'], on = a.on || {};
    delete a['aria-label']; delete a.on;
    var s = h('drishti-select', a);
    s.options = { label: label, options: options, value: value };
    Object.keys(on).forEach(function (ev) { s.addEventListener(ev === 'change' ? 'drishti:change' : ev, on[ev]); });
    return s;
  }
  E.h = h;
  E.pick = pick;
  E.clear = clear;
  E.num = function (v) {
    if (typeof v !== 'number') { return v === null || v === undefined ? '' : String(v); }
    return Math.abs(v) >= 1000 || Number.isInteger(v) ? window.Drishti.elements.core.formatNumber(v, { maxDecimals: 2, minus: '-' }) : String(Math.round(v * 10000) / 10000);
  };

  function api(method, path, body) {
    var opt = { method: method, headers: { Accept: 'application/json' }, credentials: 'same-origin' };
    if (body !== undefined && method !== 'GET') { opt.headers['Content-Type'] = 'application/json'; opt.body = JSON.stringify(body); }
    var url = path.charAt(0) === '/' ? path : '/build/tables/api/' + path;
    return fetch(url, opt).then(function (r) {
      return r.json().catch(function () { return {}; }).then(function (d) {
        if (!r.ok) { var e = new Error((d.code ? d.code + ' ' : '') + (d.detail || r.statusText)); e.status = r.status; e.code = d.code; throw e; }
        return d;
      });
    });
  }
  function q(sel) { return root.querySelector(sel); }
  function clone(o) { return o === undefined ? undefined : JSON.parse(JSON.stringify(o)); }

  var root, S, shell, pane, checkTimer, previewTimer, seqCheck = 0, seqPreview = 0;
  var K = window.Drishti.elements.designer;

  function say(text, bad) { shell.say(text, bad); }
  E.say = function (t, bad) { say(t, bad); };

  /* ---- the context an editor draws with ------------------------------------------------------------------------------------- */
  var ctx = {
    h: h, clear: clear, api: api, say: say,
    get S() { return S; },
    /** A visual edit: {@code fn(doc)} changes the document; {@code path} is the key being edited (its YAML line is highlighted). */
    change: function (fn, path) {
      if (!S || S.readOnly) { return; }
      var doc = clone(S.doc) || {};
      fn(doc);
      S.doc = doc;
      S.dirty = true;
      if (path) { S.focusPath = path; }
      scheduleCheck({ doc: doc }, 120);
    },
    focus: function (path) { S.focusPath = path; highlight(true); },
    names: function () { return names(); },
    dialog: dialog,
    redraw: function () { drawVisual(); },
    sources: function () { return E.sourcesCache || { kinds: [], tables: [], roles: [] }; },
    markDirty: function () { S.dirty = true; status(); },
    setYaml: function (yaml) { S.yaml = yaml; scheduleCheck({}, 0); },
    open: function (kind, name, item) { openObject(kind, name, item); },
    start: function (o) { return startNew(o); }
  };
  E.ctx = ctx;

  function names() {
    var out = [];
    if (!S || !S.doc) { return out; }
    var d = S.doc;
    if (S.kind === 'table') {
      Object.keys(d.columns || {}).forEach(function (c) { out.push({ name: c, note: 'a column of this table' }); });
      var src = d.from && d.from.kind && (ctx.sources().kinds || []).filter(function (k) { return k.kind === d.from.kind; })[0];
      (src ? src.columns : []).forEach(function (c) { out.push({ name: '$.' + c.name, note: c.type + ' · ' + (c.description || d.from.kind) }); });
      (ctx.sources().tables || []).forEach(function (t) { out.push({ name: t.table, note: 'a table (for related)' }); });
    } else {
      Object.keys(d.tables || {}).forEach(function (a) {
        out.push({ name: a, note: 'alias of ' + (d.tables[a].table || '') });
        var t = (ctx.sources().tables || []).filter(function (x) { return x.table === String(d.tables[a].table || '').replace(/@.*$/, ''); })[0];
        (t ? t.columns : []).forEach(function (c) { out.push({ name: a + '.' + c.name, note: c.type }); });
      });
      Object.keys(d.measures || {}).forEach(function (m) { out.push({ name: "measure('" + m + "')", note: 'a measure of this model' }); });
    }
    return out;
  }

  /* ---- the header ----------------------------------------------------------------------------------------------------------- */
  function status() {
    if (!S) {
      shell.setStatus(null); shell.setTitle('Tables & models'); shell.setCrumbs(['Build', { label: 'Tables & models', href: '/build/tables' }, 'Choose an object']);
      buttons();
      return;
    }
    shell.setTitle(S.name || 'New ' + (S.python ? 'Python-driven table' : S.kind));
    shell.setCrumbs(['Build', { label: 'Tables & models', href: '/build/tables' }, S.python ? 'Python-driven tables' : S.kind === 'model' ? 'Semantic models' : 'Tables']);
    shell.setStatus({ status: S.origin === 'file' ? 'shipped' : S.status, version: S.version, origin: S.origin, dirty: S.dirty });
    buttons();
  }
  function buttons() {
    var has = !!S;
    shell.setActions({
      validate: has || 'Open an object first', preview: has || 'Open an object first',
      impact: (has && !!S.name) || 'Name the object first',
      history: (has && !!S.item) || 'History is kept once the object is saved as a draft',
      save: (has && !S.readOnly && E.canEdit) || (S && S.readOnly ? 'Read-only here: copy it as a draft to change it' : 'Saving needs the bi-author power'),
      propose: (has && !!S.item && !S.dirty && S.status === 'draft' && E.canEdit) ||
        (!has ? 'Open an object first' : !S.item ? 'Save a draft first' : S.dirty ? 'Save your changes first' : S.status !== 'draft' ? 'Nothing to propose: it is ' + S.status : 'Proposing needs the bi-author power')
    });
  }

  /* ---- modes: Visual / YAML / Both and the layout, remembered per person on the server ------------------------------------------- */
  function applyMode() {
    shell.setOpen(!!S);
    if (shell.mode !== E.prefs.mode) { shell.setMode(E.prefs.mode); }
    if (shell.yamlOpen !== (E.prefs.yamlOpen !== false)) { shell.toggleYaml(E.prefs.yamlOpen !== false); }
    if (S && !S.ok && E.prefs.mode === 'visual') { shell.setMode('both'); }
    pane.refresh();
  }

  /* ---- the YAML panel: drishti-designer's YamlPane; the server reads it, writes it and says each problem at its line ----------------- */
  function drawYaml(keepCaret) {
    if (!keepCaret || !pane.editor.cm || !pane.editor.cm.hasFocus()) { pane.setText(S ? S.yaml || '' : ''); }
    pane.editor.setReadOnly(!S || S.readOnly);
    shell.setYamlState(!S ? '' : S.readOnly ? 'read-only' : !S.ok ? 'not valid yet: the visual side keeps the last valid version' : S.valid ? 'live · valid' : 'live · with problems',
      !S || !S.ok ? 'bad' : S.valid ? 'good' : 'warn');
    pane.editor.setProblems((S && S.problems || []).map(function (p) {
      var lv = String(p.level || p.severity || 'error').toLowerCase();             // a problem without a level (the YAML that does not read) is an error
      return { line: p.line || 1, severity: lv === 'warning' || lv === 'warn' ? 'warning' : lv === 'info' ? 'info' : 'error', message: (p.code ? p.code + ' ' : '') + p.message };
    }));
    highlight(false);
  }
  /** The line of the key being edited on the visual side, marked in the YAML (the server's line of each path, else its parent's). */
  function highlight(scroll) {
    var line = S && S.focusPath && S.lines ? lineOf(S.lines, S.focusPath) : 0;
    if (line) { pane.editor.highlight(line - 1, line - 1, { scroll: scroll !== false }); } else { pane.editor.highlight(null); }
  }
  function lineOf(lines, path) {
    var at = path;
    while (at) {
      if (lines[at]) { return lines[at]; }
      var cut = Math.max(at.lastIndexOf('.'), at.lastIndexOf('['));
      at = cut <= 0 ? '' : at.slice(0, cut);
    }
    return 0;
  }

  /* ---- checking as typed ------------------------------------------------------------------------------------------------------ */
  function scheduleCheck(extra, wait) {
    clearTimeout(checkTimer);
    seqCheck++;                       // an answer still on its way is for an older text: it must not overwrite this edit
    root.setAttribute('aria-busy', 'true');
    checkTimer = setTimeout(function () { runCheck(extra || {}); }, wait);
  }
  function runCheck(extra) {
    if (!S) { root.setAttribute('aria-busy', 'false'); return Promise.resolve(); }
    var mine = ++seqCheck;
    var body = { kind: S.kind, yaml: S.yaml || '', original: S.original || null };
    if (extra.doc) { body.doc = extra.doc; }
    return api('POST', 'check', body).then(function (r) {
      if (mine !== seqCheck) { return; }
      var fromDoc = !!extra.doc;
      S.yaml = r.yaml;
      S.lines = r.lines || {};
      S.problems = r.problems || [];
      S.ok = !!r.ok;
      S.valid = !!r.valid;
      S.statement = r.statement || null;
      S.statementRefused = r.statementRefused || null;
      S.measureFacts = r.measures || S.measureFacts;
      if (r.ok) {
        var before = JSON.stringify(S.doc);
        S.doc = r.doc;
        if (r.name) { S.name = r.name; }
        if (!fromDoc && JSON.stringify(r.doc) !== before) { drawVisual(); }
        else if (fromDoc) { refreshVisual(); }
      }
      drawYaml(!fromDoc);
      status();
      shell.setOpen(true);
      if (r.ok) { schedulePreview(500); }
    }).catch(function (e) { say(e.message, true); }).then(function () { if (mine === seqCheck) { root.setAttribute('aria-busy', 'false'); } });
  }

  /* ---- the preview ------------------------------------------------------------------------------------------------------------ */
  function schedulePreview(wait) { clearTimeout(previewTimer); previewTimer = setTimeout(runPreview, wait); }
  function runPreview() {
    if (!S || !S.ok) { return Promise.resolve(); }
    var mine = ++seqPreview;
    var prev = shell.preview;
    prev.setAttribute('aria-busy', 'true');
    return api('POST', 'preview', { kind: S.kind, yaml: S.yaml, original: S.original || null, date: S.date || null }).then(function (r) {
      if (mine !== seqPreview) { return; }
      S.preview = r;
      drawPreview();
      refreshVisual();
    }).catch(function (e) {
      if (mine !== seqPreview) { return; }
      S.preview = null;
      clear(prev).appendChild(h('p', { class: 'ed-bad', 'data-preview-error': '' }, e.message));
    }).then(function () { if (mine === seqPreview) { prev.setAttribute('aria-busy', 'false'); } });
  }
  function drawPreview() {
    var pane = clear(shell.preview);
    var r = S.preview;
    if (!r) { return; }
    var ed = editors[S.python ? 'python' : S.kind];
    if (ed && ed.preview && ed.preview(ctx, pane, r)) { return; }
    tablePreview(pane, r);
  }
  function tablePreview(pane, r) {
    pane.appendChild(h('h3', { text: 'Preview · ' + (r.count || 0) + ' rows' + (r.date ? ' · ' + r.date : '') }));
    if (r.dates && r.dates.length > 1) {
      var sel = pick({ class: 'ed-pick', 'aria-label': 'Business date of the preview', 'data-preview-date': '',
        on: { change: function (e) { S.date = e.target.value; runPreview(); } } }, r.dates.slice(-30).reverse(), r.date);
      pane.appendChild(h('label', { class: 'ed-small' }, 'Business date ', sel));
    }
    var cols = r.columns || [];
    var table = h('table', { class: 'ed-grid-t ed-prev', 'data-resize': 'columns', 'data-resize-key': 'editors.preview-columns' });
    table.appendChild(h('thead', {}, h('tr', {}, cols.map(function (c) { return h('th', { scope: 'col', text: c }); }))));
    var tb = h('tbody');
    (r.rows || []).forEach(function (row) { tb.appendChild(h('tr', {}, row.map(function (v) { return h('td', { text: v === null ? '' : typeof v === 'object' ? JSON.stringify(v) : E.num(v) }); }))); });
    table.appendChild(tb);
    if (r.totals && Object.keys(r.totals).length) {
      table.appendChild(h('tfoot', {}, h('tr', { 'data-totals': '' }, cols.map(function (c, i) {
        return h('td', { title: r.totals[c] !== undefined ? 'the sum of every row read, by the pivot engine' : '', text: r.totals[c] !== undefined ? E.num(r.totals[c]) : i === 0 ? 'Total' : '' });
      }))));
    }
    pane.appendChild(h('div', { class: 'ed-scroll' }, table));
    checks(pane, r.checks);
    if (r.cost) {
      pane.appendChild(h('p', { class: 'ed-cost ' + (r.cost.refused ? 'ed-bad' : r.cost.crossConnector ? 'ed-warn' : 'ed-muted'), 'data-cost': '' }, (r.cost.crossConnector ? '! ' : '') + r.cost.text));
    }
    usedBy(pane);
  }
  function checks(pane, list) {
    if (!list || !list.length) { return; }
    pane.appendChild(h('h3', { text: 'Checks' }));
    pane.appendChild(h('ul', { class: 'ed-checks', 'data-checks': '' }, list.map(function (c) {
      return h('li', { class: 'ed-c-' + c.level }, (c.level === 'ok' ? '✓ ' : c.level === 'info' ? 'i ' : '! ') + c.text);
    })));
  }
  E.checks = checks;
  function usedBy(pane) {
    if (!S.impact) { return; }
    pane.appendChild(h('h3', { text: 'Used by' }));
    pane.appendChild(h('p', { class: 'ed-small', 'data-usedby': '' }, S.impact.length ? S.impact.map(function (u) { return u.kind + ' ' + u.name; }).join(' · ') : 'nothing yet'));
  }
  E.usedBy = usedBy;
  E.tablePreview = tablePreview;

  /* ---- drawing ---------------------------------------------------------------------------------------------------------------- */
  function drawVisual() {
    var host = clear(shell.visual);
    if (!S) { return; }
    shell.setReadOnly(S.readOnly ? { text: S.origin === 'file' ? 'Shipped with the installation (changed through configuration): read-only here.' : 'You may read this, not change it.',
      button: E.canEdit ? { label: 'Copy as a draft', run: copyAsDraft } : null } : null);
    var ed = editors[S.python ? 'python' : S.kind];
    if (ed) { ed.render(ctx, host); }
    host.querySelectorAll('input, textarea, button[data-edit]').forEach(function (el) { if (S.readOnly && !el.hasAttribute('data-always')) { el.disabled = true; } });
    host.querySelectorAll('drishti-select').forEach(function (el) { if (S.readOnly && !el.hasAttribute('data-always') && el.component) { el.component.setDisabled(true); } });
  }
  function refreshVisual() {
    var ed = editors[S.python ? 'python' : S.kind];
    if (ed && ed.refresh) { ed.refresh(ctx, shell.visual); }
  }
  function copyAsDraft() {
    var copy = clone(S.doc) || {};
    var key = S.kind === 'model' ? 'model' : 'table';
    copy[key] = String(copy[key] || '') + '_copy';
    startNew({ kind: S.kind, python: S.python, doc: copy, pythonFields: S.pythonFields });
  }

  /* ---- objects ---------------------------------------------------------------------------------------------------------------- */
  function drawObjects() {
    var o = E.objects || { tables: [], python: [], models: [] };
    shell.setObjects([['tables', 'Tables'], ['python', 'Python-driven tables'], ['models', 'Semantic models']].map(function (g) {
      return { id: g[0], label: g[1], items: (o[g[0]] || []).map(function (x) {
        return { id: x.name, label: x.name, data: x, current: !!(S && S.name === x.name && (S.item || '') === (x.item || '')), search: x.title + ' ' + (x.form || '') + ' ' + (x.connector || ''),
          detail: (x.form === 'model' ? (x.tables || 0) + ' tables · ' + (x.measures || 0) + ' measures' : (x.form || '') + (x.connector ? ' · ' + x.connector : '')) + ' · ' + (x.status || '') };
      }) };
    }));
  }
  function loadObjects() {
    return api('GET', 'objects').then(function (o) { E.objects = o; E.canEdit = !!o.canEdit; drawObjects(); buttons(); });
  }

  /* ---- opening ---------------------------------------------------------------------------------------------------------------- */
  function fresh(kind) {
    return { kind: kind, python: false, name: '', original: null, item: null, origin: 'new', readOnly: false, status: 'draft', version: 0,
      yaml: '', doc: null, lines: {}, problems: [], ok: false, valid: false, dirty: true, focusPath: null, preview: null, impact: null };
  }
  function openObject(kind, name, item) {
    say('');
    root.setAttribute('aria-busy', 'true');
    var params = 'kind=' + encodeURIComponent(kind) + (item ? '&item=' + encodeURIComponent(item) : '&name=' + encodeURIComponent(name || ''));
    return api('GET', 'object?' + params).then(function (r) {
      S = fresh(kind);
      S.name = r.name || name;
      S.original = S.name;
      S.item = r.item || null;
      S.origin = r.origin;
      S.readOnly = !!r.readOnly;
      S.status = r.status;
      S.version = r.version || 0;
      S.yaml = r.yaml;
      S.doc = r.doc;
      S.lines = r.lines || {};
      S.problems = r.problems || [];
      S.ok = !!r.ok;
      S.valid = !!r.valid;
      S.statement = r.statement || null;
      S.statementRefused = r.statementRefused || null;
      S.measureFacts = r.measures || null;
      S.dirty = false;
      S.python = kind === 'table' && !!(r.doc && r.doc.from && r.doc.from.python);
      S.pythonFields = r.python || null;
      history.replaceState(null, '', '/build/tables?kind=' + encodeURIComponent(kind) + (S.item ? '&item=' + encodeURIComponent(S.item) : '&name=' + encodeURIComponent(S.name)));
      return shown();
    }).catch(function (e) { say(e.message, true); }).then(function () { root.setAttribute('aria-busy', 'false'); });
  }
  function shown() {
    drawVisual();
    drawYaml(false);
    status();
    applyMode();
    drawObjects();
    loadImpact();
    return runPreview();
  }
  function loadImpact() {
    if (!S || !S.name) { return; }
    var kind = S.kind;
    api('GET', 'impact?kind=' + encodeURIComponent(kind) + '&name=' + encodeURIComponent(S.name)).then(function (r) {
      S.impact = r.usedBy || [];
      shell.setImpactCount(S.impact.length);
      if (S.preview) { drawPreview(); }
    }).catch(function () { /* impact is shown when it can be read */ });
  }
  /** Lands an object made by the + New steps (or a copy) in the three-pane editor: {kind, python, doc, yaml, pythonFields}. */
  function startNew(o) {
    S = fresh(o.kind);
    S.python = !!o.python;
    S.pythonFields = o.pythonFields || null;
    S.doc = o.doc || null;
    S.yaml = o.yaml || '';
    history.replaceState(null, '', '/build/tables?new=' + (o.python ? 'python' : o.kind));
    status();
    applyMode();
    return runCheck(o.doc ? { doc: o.doc } : {}).then(function () { drawVisual(); drawYaml(false); });
  }

  /* ---- dialogs ---------------------------------------------------------------------------------------------------------------- */
  function dialog(title, body) { return shell.dialog(title, body); }       // modal, its first field focused, focus given back on close
  function closeDialog() { shell.closeDialog(); }
  E.closeDialog = closeDialog;

  function doHistory() {
    api('GET', 'history?item=' + encodeURIComponent(S.item)).then(function (r) {
      var rows = (r.versions || []).map(function (v) {
        return h('tr', {}, h('td', { text: 'v' + (v.version || v.n || '') }), h('td', { text: v.createdBy || v.author || '' }), h('td', { text: v.createdAt || v.at || '' }), h('td', { text: v.note || v.message || '' }));
      });
      dialog('History of ' + S.name, rows.length ? h('table', { class: 'ed-grid-t', 'data-history': '' }, h('thead', {}, h('tr', {}, ['Version', 'By', 'When', 'Note'].map(function (x) { return h('th', { text: x }); }))), h('tbody', {}, rows))
        : h('p', { 'data-history': '', text: 'No approved version yet: the draft is saved; versions are made when it is approved.' }));
    }).catch(function (e) { say(e.message, true); });
  }
  function doImpact() {
    api('GET', 'impact?kind=' + encodeURIComponent(S.kind) + '&name=' + encodeURIComponent(S.name)).then(function (r) {
      S.impact = r.usedBy || [];
      dialog('What uses ' + S.name, S.impact.length ? h('ul', { 'data-impact': '' }, S.impact.map(function (u) { return h('li', {}, h('b', { text: u.kind + ' ' + u.name }), ' ' + u.how); }))
        : h('p', { 'data-impact': '', text: 'Nothing uses it yet: a change affects no model, table, report or board.' }));
    }).catch(function (e) { say(e.message, true); });
  }
  function doSave() {
    var form = h('div', { class: 'ed-form' });
    var ws = pick({ class: 'ed-pick', 'data-save-ws': '', 'aria-label': 'Workspace' }, [{ value: 'me', label: 'My workspace (private)' }]);
    var msg = h('input', { class: 'ed-in', 'data-save-msg': '', placeholder: 'What changed', 'aria-label': 'What changed' });
    if (!S.item) { form.appendChild(h('label', {}, 'Save in ', ws)); }
    form.appendChild(h('label', {}, 'Note ', msg));
    var go = h('button', { type: 'button', class: 'ed-btn ed-primary', 'data-save-go': '' }, 'Save draft');
    form.appendChild(h('p', {}, go));
    if (!S.item) {
      api('GET', '/build/tables/workspaces').then(function (list) {
        var shared = (list || []).filter(function (w) { return !w.personal; }).map(function (w) { return { value: w.id, label: w.name }; });
        ws.component.setOptions(ws.component.options.concat(shared), shared.length ? shared[0].value : 'me');
      }).catch(function () { /* the personal workspace is always there */ });
    }
    go.addEventListener('click', function () { save(ws.value, msg.value).then(closeDialog); });
    dialog(S.item ? 'Save a new draft of ' + S.name : 'Save ' + (S.name || 'the new object') + ' as a draft', form);
  }
  function save(workspace, message) {
    var body = { kind: S.kind, yaml: S.yaml, item: S.item, workspace: workspace || 'me', original: S.original, message: message || '' };
    if (S.python) { body.python = S.pythonFields || {}; body.python.name = S.name; }
    return api('POST', 'save', body).then(function (r) {
      S.item = r.item; S.status = r.status; S.version = r.version || 0; S.dirty = false; S.original = S.name;
      if (S.python && r.pythonItem) { S.pythonFields = S.pythonFields || {}; S.pythonFields.item = r.pythonItem; }
      if (S.origin !== 'item') { S.origin = 'item'; }
      say('Saved as a draft. Propose it for review when it is ready: nothing goes live until another person approves it.');
      status();
      history.replaceState(null, '', '/build/tables?kind=' + S.kind + '&item=' + encodeURIComponent(S.item));
      return loadObjects();
    }).catch(function (e) { say(e.message, true); throw e; });
  }
  function doPropose() {
    shell.propose(S.name, function (note) {
      return api('POST', 'propose', { item: S.item, note: note }).then(function (r) {
        S.status = r.status;
        say('Proposed for review: another person with the approve power approves it in BI › Reviews (four eyes).');
        status();
        loadObjects();
      });
    });
  }

  /* ---- boot ------------------------------------------------------------------------------------------------------------------- */
  function boot() {
    root = document.querySelector('[data-editors]');
    if (!root) { return; }
    E.root = root;
    E.prefs = { mode: 'both', yamlOpen: true, layout: 'studio' };
    E.pythonRuns = root.getAttribute('data-python-runs') === 'true';           // the server runs authored Python safely (else data-python-why says why)
    E.pythonWhy = root.getAttribute('data-python-why') || '';
    shell = E.shell = new K.DesignerShell(root, { crumbs: ['Build', { label: 'Tables & models', href: '/build/tables' }, 'Choose an object'], title: 'Tables & models',
      search: 'Search tables and models', newItems: [{ id: 'table', label: 'Table or virtual table' }, { id: 'model', label: 'Semantic model' }, { id: 'python', label: 'Python-driven table' }],
      canMode: function (m) { return m === 'visual' && S && !S.ok ? 'The visual view waits until the YAML reads: fix the line marked below.' : true; } });
    root.classList.add('ed');
    shell.visual.appendChild(h('div', { class: 'ed-empty', 'data-empty': '' }, h('h2', { text: 'Tables, virtual tables, models and Python-driven tables' }),
      h('p', {}, 'Choose an object on the left, or ', h('strong', { text: '+ New' }), ' to make one in guided steps. Every change is a draft until another person approves it.')));
    shell.preview.appendChild(h('p', { class: 'ed-muted', text: 'The preview is read on the server as you may see the data.' }));
    var yhost = h('div', { 'data-yaml': '' });
    shell.yamlBody.appendChild(yhost);
    pane = new K.YamlPane(yhost, { label: 'YAML of the definition', height: 340, delay: 300,
      fromYaml: function (text) {                      // the server reads the YAML (its problems at their lines); the visual side keeps the last valid version
        if (!S || S.readOnly) { return text; }
        S.yaml = text; S.dirty = true; S.focusPath = null;
        status();
        return runCheck({}).then(function () { return text; });
      } });
    E.yamlText = function () { return pane.getText(); };                     // the YAML as it stands (the page's own scripts, the tests)
    pane.editor.on('cursor', function (c) {               // the line the caret is on, as a path: the key on that line is marked on the visual side
      if (!S || !S.lines) { return; }
      var line = c.line + 1, path = null;
      Object.keys(S.lines).forEach(function (p) { if (S.lines[p] === line && (!path || p.length > path.length)) { path = p; } });
      root.querySelectorAll('.ed-at').forEach(function (x) { x.classList.remove('ed-at'); });
      if (path) { var el = root.querySelector('[data-path="' + path.replace(/"/g, '') + '"]'); if (el) { el.classList.add('ed-at'); } }
    });
    shell.on('mode', function (m) { E.prefs.mode = m.mode; pane.refresh(); api('PUT', 'prefs', { mode: m.mode }).catch(function () { /* kept for this page only */ }); });
    shell.on('yaml', function (y) { E.prefs.yamlOpen = y.open; pane.refresh(); api('PUT', 'prefs', { yamlOpen: y.open }).catch(function () { /* kept for this page only */ }); });
    shell.on('layout', function (l) { if (l.user) { E.prefs.layout = l.layout; api('PUT', 'prefs', { layout: l.layout }).catch(function () { /* kept for this page only */ }); } });
    shell.on('open', function (o) { openObject(o.item.data.kind, o.item.data.name, o.item.data.item); });
    shell.on('new', function (n) { E.newSteps(ctx, n.id); });
    var acts = { validate: function () { runCheck({}).then(function () { say(S.valid ? 'Valid: no problems.' : (S.problems.length + ' problem(s): each is marked at its line.'), !S.valid); }); },
      preview: function () { runPreview(); }, history: doHistory, impact: doImpact, save: doSave, propose: doPropose };
    shell.on('action', function (a) { if (acts[a.id]) { acts[a.id](); } });
    window.Drishti.keys.register({ id: 'editors.save', keys: ['Ctrl+S', 'Meta+S'], scope: 'screen', area: 'editors', group: 'Editor', typing: true,
      description: 'Save', run: function () { if (!S) { return false; } if (!shell.buttons.save.disabled) { doSave(); } } });
    window.Drishti.keys.register({ id: 'editors.check', keys: ['Ctrl+Enter', 'Meta+Enter'], scope: 'screen', area: 'editors', group: 'Editor', typing: true,
      description: 'Check it', run: function () { if (!S) { return false; } shell.buttons.validate.click(); } });
    root.setAttribute('aria-busy', 'true');
    Promise.all([
      api('GET', 'prefs').then(function (p) { E.prefs = Object.assign(E.prefs, p); if (p.layout) { shell.setLayout(p.layout); } }).catch(function () { /* defaults */ }),
      api('GET', 'sources').then(function (s) { E.sourcesCache = s; E.functions = s.functions || []; }).catch(function () { E.sourcesCache = { kinds: [], tables: [], roles: [] }; }),
      loadObjects().catch(function (e) { say(e.message, true); })
    ]).then(function () {
      applyMode();
      status();
      var kind = root.getAttribute('data-kind'), name = root.getAttribute('data-name'), item = root.getAttribute('data-item'), nw = root.getAttribute('data-new');
      if (nw) { return E.newSteps(ctx, nw); }
      if (kind && (name || item)) { return openObject(kind, name, item); }
      return null;
    }).then(function () { root.setAttribute('aria-busy', 'false'); }, function () { root.setAttribute('aria-busy', 'false'); });
  }
  if (document.readyState === 'loading') { document.addEventListener('DOMContentLoaded', boot); } else { setTimeout(boot, 0); }
})();
