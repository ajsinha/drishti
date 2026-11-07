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
/* The editor shell (docs/architecture/EDITORS.md section 1): three panes (the objects with search | the editor with the live YAML below it |
   the live preview with checks), the Visual / YAML / Both toggle remembered per person (the server keeps it), and the header: the status
   with versions, Validate, Preview, History, Impact, Save draft and Propose for review (four eyes). A visual edit sends the document and
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
  E.h = h;
  E.clear = clear;
  E.num = function (v) {
    if (typeof v !== 'number') { return v === null || v === undefined ? '' : String(v); }
    return Math.abs(v) >= 1000 || Number.isInteger(v) ? v.toLocaleString(undefined, { maximumFractionDigits: 2 }) : String(Math.round(v * 10000) / 10000);
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

  var root, S, checkTimer, previewTimer, yamlTimer, seqCheck = 0, seqPreview = 0;

  function say(text, bad) {
    var m = q('[data-msg]');
    m.textContent = text || '';
    m.classList.toggle('ed-bad', !!bad);
  }
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
    focus: function (path) { S.focusPath = path; highlight(); },
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
    var pill = q('[data-status]');
    var title = q('[data-title]');
    var group = q('[data-crumb-group]');
    if (!S) {
      pill.hidden = true; title.textContent = 'Tables & models'; group.textContent = 'Choose an object';
      q('[data-versions]').textContent = '';
      buttons();
      return;
    }
    title.textContent = S.name || 'New ' + (S.python ? 'Python-driven table' : S.kind);
    group.textContent = S.python ? 'Python-driven tables' : S.kind === 'model' ? 'Semantic models' : 'Tables';
    var word = S.origin === 'file' ? 'Shipped' : S.status === 'published' ? 'Approved' : S.status === 'proposed' ? 'Proposed' : 'Draft';
    pill.hidden = false;
    pill.className = 'ed-pill ed-' + (S.status || 'draft');
    pill.textContent = word + (S.dirty ? ' · unsaved' : '');
    q('[data-versions]').textContent = S.origin === 'item' ? (S.version ? 'approved v' + S.version : 'never approved') + (S.status === 'draft' && S.version ? ' · a draft after it' : '') : S.origin === 'file' ? 'from configuration' : '';
    buttons();
  }
  function buttons() {
    var has = !!S;
    var set = function (act, on, why) { var b = q('[data-act="' + act + '"]'); b.disabled = !on; b.title = on ? b.getAttribute('data-title') || b.title : why; };
    root.querySelectorAll('[data-act]').forEach(function (b) { if (!b.getAttribute('data-title')) { b.setAttribute('data-title', b.title); } });
    set('validate', has, 'Open an object first');
    set('preview', has, 'Open an object first');
    set('impact', has && !!S.name, 'Name the object first');
    set('history', has && !!S.item, 'History is kept once the object is saved as a draft');
    set('save', has && !S.readOnly && E.canEdit, S && S.readOnly ? 'Read-only here: copy it as a draft to change it' : 'Saving needs the bi-author power');
    set('propose', has && !!S.item && !S.dirty && S.status === 'draft' && E.canEdit,
      !has ? 'Open an object first' : !S.item ? 'Save a draft first' : S.dirty ? 'Save your changes first' : S.status !== 'draft' ? 'Nothing to propose: it is ' + S.status : 'Proposing needs the bi-author power');
  }

  /* ---- modes: Visual / YAML / Both, remembered per person on the server ------------------------------------------------------- */
  function applyMode() {
    var mode = E.prefs.mode;
    if (S && !S.ok && mode === 'visual') { mode = 'both'; }
    root.querySelectorAll('[data-mode]').forEach(function (b) { b.setAttribute('aria-pressed', b.getAttribute('data-mode') === E.prefs.mode ? 'true' : 'false'); });
    q('[data-visual]').hidden = mode === 'yaml' && !!S;
    var panel = q('[data-yaml-panel]');
    panel.hidden = !S || mode === 'visual';
    panel.classList.toggle('ed-yfull', mode === 'yaml');
    var open = mode === 'yaml' || E.prefs.yamlOpen;
    q('[data-yaml-body]').hidden = !open;
    q('[data-yaml-toggle]').setAttribute('aria-expanded', open ? 'true' : 'false');
    q('[data-yaml-toggle]').textContent = (open ? '▾' : '▸') + ' YAML';
  }
  function setMode(mode) {
    if (mode === 'visual' && S && !S.ok) { say('The visual view waits until the YAML reads: fix the line marked below.', true); return; }
    E.prefs.mode = mode;
    applyMode();
    api('PUT', 'prefs', { mode: mode }).catch(function () { /* kept for this page only */ });
  }

  /* ---- the YAML panel -------------------------------------------------------------------------------------------------------- */
  function lineHeight(ta) { var lh = parseFloat(getComputedStyle(ta).lineHeight); return isFinite(lh) ? lh : 18; }
  function drawYaml(keepCaret) {
    var ta = q('[data-yaml]');
    if (!keepCaret || document.activeElement !== ta) { ta.value = S ? S.yaml || '' : ''; }
    ta.readOnly = !S || S.readOnly;
    var n = (ta.value.match(/\n/g) || []).length + 1;
    var gutter = clear(q('[data-gutter]'));
    var bad = {};
    (S && S.problems || []).forEach(function (p) { if (p.line) { bad[p.line] = p.level; } });
    for (var i = 1; i <= n; i++) { gutter.appendChild(h('div', { class: bad[i] ? 'ed-g-' + bad[i] : '', 'data-line': i, text: String(i) })); }
    q('[data-yaml-state]').textContent = !S ? '' : S.readOnly ? 'read-only' : !S.ok ? 'not valid yet: the visual side keeps the last valid version' : S.valid ? 'live · valid' : 'live · with problems';
    q('[data-yaml-state]').className = 'ed-ystate ' + (!S || !S.ok ? 'ed-bad' : S.valid ? 'ed-good' : 'ed-warn');
    problems();
    highlight();
  }
  function highlight() {
    var ta = q('[data-yaml]'), hl = q('[data-yaml-hl]');
    var line = S && S.focusPath && S.lines ? lineOf(S.lines, S.focusPath) : 0;
    root.querySelectorAll('[data-gutter] .ed-g-on').forEach(function (x) { x.classList.remove('ed-g-on'); });
    if (!line) { hl.hidden = true; return; }
    hl.hidden = false;
    var lh = lineHeight(ta);
    hl.style.top = (6 + (line - 1) * lh - ta.scrollTop) + 'px';
    hl.style.height = lh + 'px';
    var g = q('[data-gutter] [data-line="' + line + '"]');
    if (g) { g.classList.add('ed-g-on'); }
    hl.setAttribute('data-line', String(line));
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
  function problems() {
    var ul = clear(q('[data-problems]'));
    (S && S.problems || []).forEach(function (p) {
      ul.appendChild(h('li', { class: 'ed-p-' + p.level },
        h('button', { type: 'button', class: 'ed-linkbtn', 'data-goto-line': p.line || '', on: { click: function () { gotoLine(p.line); } } }, p.line ? 'line ' + p.line : 'the definition'),
        ' ', h('code', { text: p.code || '' }), ' ', p.message));
    });
  }
  function gotoLine(line) {
    if (!line) { return; }
    var ta = q('[data-yaml]');
    if (E.prefs.mode === 'visual') { setMode('both'); }
    if (!E.prefs.yamlOpen && E.prefs.mode !== 'yaml') { toggleYaml(); }
    var lines = ta.value.split('\n'), pos = 0;
    for (var i = 0; i < line - 1 && i < lines.length; i++) { pos += lines[i].length + 1; }
    ta.focus();
    ta.setSelectionRange(pos, pos + (lines[line - 1] || '').length);
    ta.scrollTop = Math.max(0, (line - 3) * lineHeight(ta));
    S.focusPath = null;
    var hl = q('[data-yaml-hl]');
    hl.hidden = false;
    hl.style.top = (6 + (line - 1) * lineHeight(ta) - ta.scrollTop) + 'px';
  }
  function toggleYaml() {
    E.prefs.yamlOpen = !E.prefs.yamlOpen;
    applyMode();
    api('PUT', 'prefs', { yamlOpen: E.prefs.yamlOpen }).catch(function () { /* kept for this page only */ });
  }
  /** The line the caret is on, as a path: the key on that line (so the visual side can show where you are). */
  function caretLine(ta) { return ta.value.slice(0, ta.selectionStart).split('\n').length; }

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
      applyMode();
      if (r.ok) { schedulePreview(500); }
    }).catch(function (e) { say(e.message, true); }).then(function () { if (mine === seqCheck) { root.setAttribute('aria-busy', 'false'); } });
  }

  /* ---- the preview ------------------------------------------------------------------------------------------------------------ */
  function schedulePreview(wait) { clearTimeout(previewTimer); previewTimer = setTimeout(runPreview, wait); }
  function runPreview() {
    if (!S || !S.ok) { return Promise.resolve(); }
    var mine = ++seqPreview;
    var pane = q('[data-preview]');
    pane.setAttribute('aria-busy', 'true');
    return api('POST', 'preview', { kind: S.kind, yaml: S.yaml, original: S.original || null, date: S.date || null }).then(function (r) {
      if (mine !== seqPreview) { return; }
      S.preview = r;
      drawPreview();
      refreshVisual();
    }).catch(function (e) {
      if (mine !== seqPreview) { return; }
      S.preview = null;
      clear(pane).appendChild(h('p', { class: 'ed-bad', 'data-preview-error': '' }, e.message));
    }).then(function () { if (mine === seqPreview) { pane.setAttribute('aria-busy', 'false'); } });
  }
  function drawPreview() {
    var pane = clear(q('[data-preview]'));
    var r = S.preview;
    if (!r) { return; }
    var ed = editors[S.python ? 'python' : S.kind];
    if (ed && ed.preview && ed.preview(ctx, pane, r)) { return; }
    tablePreview(pane, r);
  }
  function tablePreview(pane, r) {
    pane.appendChild(h('h3', { text: 'Preview · ' + (r.count || 0) + ' rows' + (r.date ? ' · ' + r.date : '') }));
    if (r.dates && r.dates.length > 1) {
      var sel = h('select', { class: 'ed-in', 'aria-label': 'Business date of the preview', 'data-preview-date': '',
        on: { change: function (e) { S.date = e.target.value; runPreview(); } } });
      r.dates.slice(-30).reverse().forEach(function (d) { sel.appendChild(h('option', { value: d, selected: d === r.date, text: d })); });
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
    var host = clear(q('[data-visual]'));
    if (!S) { return; }
    var ro = q('[data-readonly]');
    ro.hidden = !S.readOnly;
    clear(ro);
    if (S.readOnly) {
      ro.appendChild(h('span', {}, S.origin === 'file' ? 'Shipped with the installation (changed through configuration): read-only here. ' : 'You may read this, not change it. '));
      if (E.canEdit) { ro.appendChild(h('button', { type: 'button', class: 'ed-btn', 'data-copy': '', on: { click: copyAsDraft } }, 'Copy as a draft')); }
    }
    var ed = editors[S.python ? 'python' : S.kind];
    if (ed) { ed.render(ctx, host); }
    host.querySelectorAll('input, select, textarea, button[data-edit]').forEach(function (el) { if (S.readOnly && !el.hasAttribute('data-always')) { el.disabled = true; } });
  }
  function refreshVisual() {
    var ed = editors[S.python ? 'python' : S.kind];
    if (ed && ed.refresh) { ed.refresh(ctx, q('[data-visual]')); }
  }
  function copyAsDraft() {
    var copy = clone(S.doc) || {};
    var key = S.kind === 'model' ? 'model' : 'table';
    copy[key] = String(copy[key] || '') + '_copy';
    startNew({ kind: S.kind, python: S.python, doc: copy, pythonFields: S.pythonFields });
  }

  /* ---- objects ---------------------------------------------------------------------------------------------------------------- */
  function drawObjects() {
    var host = clear(q('[data-objects]'));
    var o = E.objects || { tables: [], python: [], models: [] };
    var term = (q('[data-ed-search]').value || '').toLowerCase();
    [['tables', 'Tables'], ['python', 'Python-driven tables'], ['models', 'Semantic models']].forEach(function (g) {
      var list = (o[g[0]] || []).filter(function (x) { return !term || (x.name + ' ' + x.title + ' ' + (x.form || '') + ' ' + (x.connector || '')).toLowerCase().indexOf(term) >= 0; });
      var ul = h('ul', { class: 'ed-list', role: 'list' });
      list.forEach(function (x) {
        var current = S && S.name === x.name && (S.item || '') === (x.item || '');
        ul.appendChild(h('li', {}, h('button', { type: 'button', class: 'ed-obj' + (current ? ' ed-on' : ''), 'aria-current': current ? 'true' : null,
          'data-object': x.name, 'data-object-item': x.item || '', on: { click: function () { openObject(x.kind, x.name, x.item); } } },
          h('span', { class: 'ed-obj-n', text: x.name }),
          h('small', { text: (x.form === 'model' ? (x.tables || 0) + ' tables · ' + (x.measures || 0) + ' measures' : (x.form || '') + (x.connector ? ' · ' + x.connector : '')) + ' · ' + (x.status || '') }))));
      });
      host.appendChild(h('section', { class: 'ed-group', 'data-group': g[0] }, h('h2', {}, g[1] + ' ', h('small', { text: String(list.length) })),
        list.length ? ul : h('p', { class: 'ed-muted ed-small', text: term ? 'none match' : 'none yet' })));
    });
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
    q('[data-empty]') && q('[data-empty]').remove();
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
      q('[data-impact-count]').textContent = '(' + S.impact.length + ')';
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
    q('[data-empty]') && q('[data-empty]').remove();
    status();
    applyMode();
    return runCheck(o.doc ? { doc: o.doc } : {}).then(function () { drawVisual(); drawYaml(false); });
  }

  /* ---- dialogs ---------------------------------------------------------------------------------------------------------------- */
  function dialog(title, body) {
    var d = q('[data-dialog]');
    q('[data-dialog-title]').textContent = title;
    var host = clear(q('[data-dialog-body]'));
    host.appendChild(body);
    return window.Drishti.elements.core.openDialog(d, { within: host });        // modal, its first field focused, focus given back on close
  }
  function closeDialog() { window.Drishti.elements.core.closeDialog(q('[data-dialog]')); }
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
    var ws = h('select', { class: 'ed-in', 'data-save-ws': '', 'aria-label': 'Workspace' }, h('option', { value: 'me', text: 'My workspace (private)' }));
    var msg = h('input', { class: 'ed-in', 'data-save-msg': '', placeholder: 'What changed', 'aria-label': 'What changed' });
    if (!S.item) { form.appendChild(h('label', {}, 'Save in ', ws)); }
    form.appendChild(h('label', {}, 'Note ', msg));
    var go = h('button', { type: 'button', class: 'ed-btn ed-primary', 'data-save-go': '' }, 'Save draft');
    form.appendChild(h('p', {}, go));
    if (!S.item) {
      api('GET', '/build/tables/workspaces').then(function (list) {
        (list || []).filter(function (w) { return !w.personal; }).forEach(function (w) { ws.appendChild(h('option', { value: w.id, text: w.name })); });
        if (ws.options.length > 1) { ws.selectedIndex = 1; }
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
    var note = h('textarea', { class: 'ed-in', rows: '3', 'data-propose-note': '', 'aria-label': 'A note for the reviewer', placeholder: 'What to look at' });
    var go = h('button', { type: 'button', class: 'ed-btn ed-primary', 'data-propose-go': '' }, 'Propose for review');
    go.addEventListener('click', function () {
      api('POST', 'propose', { item: S.item, note: note.value }).then(function (r) {
        S.status = r.status;
        closeDialog();
        say('Proposed for review: another person with the approve power approves it in BI › Reviews (four eyes).');
        status();
        loadObjects();
      }).catch(function (e) { say(e.message, true); });
    });
    dialog('Propose ' + S.name + ' for review', h('div', { class: 'ed-form' },
      h('p', { class: 'ed-small', text: 'A second person approves it before it goes live; you may not approve your own proposal.' }), note, h('p', {}, go)));
  }

  /* ---- boot ------------------------------------------------------------------------------------------------------------------- */
  function boot() {
    root = document.querySelector('[data-editors]');
    if (!root) { return; }
    E.root = root;
    E.prefs = { mode: 'both', yamlOpen: true };
    E.pythonRuns = root.getAttribute('data-python-runs') === 'true';           // the server runs authored Python safely (else data-python-why says why)
    E.pythonWhy = root.getAttribute('data-python-why') || '';
    root.querySelectorAll('[data-mode]').forEach(function (b) { b.addEventListener('click', function () { setMode(b.getAttribute('data-mode')); }); });
    q('[data-yaml-toggle]').addEventListener('click', toggleYaml);
    q('[data-ed-search]').addEventListener('input', drawObjects);
    var ta = q('[data-yaml]');
    ta.addEventListener('input', function () {
      if (!S || S.readOnly) { return; }
      S.yaml = ta.value;
      S.dirty = true;
      S.focusPath = null;
      status();
      drawYaml(true);
      scheduleCheck({}, 300);
    });
    ta.addEventListener('scroll', function () { q('[data-gutter]').scrollTop = ta.scrollTop; highlight(); });
    ta.addEventListener('keyup', function () {
      if (!S || !S.lines) { return; }
      var line = caretLine(ta), path = null;
      Object.keys(S.lines).forEach(function (p) { if (S.lines[p] === line && (!path || p.length > path.length)) { path = p; } });
      root.querySelectorAll('.ed-at').forEach(function (x) { x.classList.remove('ed-at'); });
      if (path) {
        var el = root.querySelector('[data-path="' + path.replace(/"/g, '') + '"]');
        if (el) { el.classList.add('ed-at'); }
      }
    });
    q('[data-act="validate"]').addEventListener('click', function () { runCheck({}).then(function () { say(S.valid ? 'Valid: no problems.' : (S.problems.length + ' problem(s): each is marked at its line.'), !S.valid); }); });
    q('[data-act="preview"]').addEventListener('click', function () { runPreview(); });
    q('[data-act="history"]').addEventListener('click', doHistory);
    q('[data-act="impact"]').addEventListener('click', doImpact);
    q('[data-act="save"]').addEventListener('click', doSave);
    q('[data-act="propose"]').addEventListener('click', doPropose);
    var menu = q('[data-newmenu]'), nb = q('[data-act="new"]');
    nb.addEventListener('click', function () { menu.hidden = !menu.hidden; nb.setAttribute('aria-expanded', menu.hidden ? 'false' : 'true'); if (!menu.hidden) { menu.querySelector('button').focus(); } });
    menu.addEventListener('keydown', function (e) { if (e.key === 'Escape') { menu.hidden = true; nb.setAttribute('aria-expanded', 'false'); nb.focus(); } });
    root.querySelectorAll('[data-new-kind]').forEach(function (b) {
      b.addEventListener('click', function () { menu.hidden = true; nb.setAttribute('aria-expanded', 'false'); E.newSteps(ctx, b.getAttribute('data-new-kind')); });
    });
    q('[data-dialog-close]').addEventListener('click', closeDialog);
    window.Drishti.keys.register({ id: 'editors.save', keys: ['Ctrl+S', 'Meta+S'], scope: 'screen', area: 'editors', group: 'Editor', typing: true,
      description: 'Save', run: function () { if (!S) { return false; } if (!q('[data-act="save"]').disabled) { doSave(); } } });
    window.Drishti.keys.register({ id: 'editors.check', keys: ['Ctrl+Enter', 'Meta+Enter'], scope: 'screen', area: 'editors', group: 'Editor', typing: true,
      description: 'Check it', run: function () { if (!S) { return false; } q('[data-act="validate"]').click(); } });
    root.setAttribute('aria-busy', 'true');
    Promise.all([
      api('GET', 'prefs').then(function (p) { E.prefs = p; }).catch(function () { /* defaults */ }),
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
