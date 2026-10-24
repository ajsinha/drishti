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
/* Forms on a view page (SUTRA_NEXT.md 10). A panel whose Sutra has form: carries it as data-form, already as this reader may use it (the
   server marks masked fields and locks other roles' fields). Three ways in:
   * edit: beside   click a row and a form opens beside the table: typed fields, locked ones say why, warnings and errors in words under
                    the field, "Draft · not saved", Save draft and Discard;
   * edit: cells    a pencil marks the editable columns; a cell opens on a click, Enter or F2; Enter keeps the value in the draft, Esc
                    undoes it, Tab moves on; a changed cell is tinted and says "was …"; a bad value has a red border and the reason under
                    the row; "N changes not saved", Undo all, Review N changes (then Save draft); a masked cell says Hidden for you;
   * new: drawer    New opens a drawer over the dimmed page, in steps (Who ✓ → What → Check), with Back, Next and Save as draft, and the
                    hints computed as people type (a limit's headroom from the chosen counterparty).
   Saving keeps a draft per person on the server (/api/drafts, checked again there with the same rules); 2.8.0 sends it through the
   connector. "You have N drafts on this page" lets a person resume or discard one; leaving with changes not saved asks first.
   Field types beyond text, numbers, dates and choices: money (an amount typed as 1.5m, 250k or 1,000,000, shown grouped, with its
   currency beside it: fixed, or the one another field holds), datetime (a date and a time, the time zone shown beside it) and entity (a
   linked record picked by search: its id kept, its name shown, Open beside it). A field's default starts a new record: a value, or
   =<expression> worked out as people type until they type over it.
   window.drishtiForms. */
(function () {
  'use strict';
  var view = document.querySelector('[data-view]');
  var X = window.drishtiFormExpr;
  if (!view || !X) { return; }
  var page = { kind: view.dataset.kind, id: view.dataset.id, sutra: view.dataset.chosenSutra || view.dataset.viewSutra || '', design: view.dataset.design || '' };
  var forms = {};                                    // panel id -> state
  var drafts = [];                                   // this page's drafts (the server's)
  var live = document.createElement('p');
  live.className = 'visually-hidden'; live.setAttribute('role', 'status'); live.setAttribute('aria-live', 'polite');
  view.appendChild(live);

  // ---- small helpers ------------------------------------------------------------------------------------------------------
  function el(tag, cls, text) { var n = document.createElement(tag); if (cls) { n.className = cls; } if (text !== undefined) { n.textContent = text; } return n; }
  function btn(cls, text, on) { var b = el('button', cls, text); b.type = 'button'; if (on) { b.addEventListener('click', on); } return b; }
  function say(t) { live.textContent = t; }
  function numeric(f) { return f.type === 'number' || f.type === 'money'; }
  /* a money field's currency: a fixed code, or what the field it names holds in vals */
  function ccy(f, vals) { var c = f.currency || ''; return /^[A-Z]{3}$/.test(c) ? c : (vals && !X.empty(vals[c]) ? String(vals[c]) : ''); }
  function fmt(f, v, vals) {
    if (X.empty(v)) { return ''; }
    if (numeric(f)) {
      var n = X.typed(f, v);
      if (isNaN(n)) { return String(v); }
      var s = n.toLocaleString('en-US', { maximumFractionDigits: /0$/.test(f.fmt || '') ? 0 : f.type === 'money' ? 2 : 6 }), c = f.type === 'money' && vals ? ccy(f, vals) : '';
      return c ? s + ' ' + c : s;
    }
    if (f.type === 'datetime') { return String(v).replace('T', ' ').slice(0, 16) + ' ' + (f.tz || 'UTC'); }
    if (f.type === 'bool') { return v === true || v === 'true' ? 'Yes' : 'No'; }
    if (f.type === 'enum' || f.type === 'lookup' || f.type === 'entity') {
      var o = (f.options || []).filter(function (x) { return String(x.value) === String(v); })[0];
      if (!o) { return String(v); }
      return f.type === 'enum' || o.label === o.value ? o.label : f.type === 'entity' ? o.label + ' · ' + o.value : o.value + ' · ' + o.label;
    }
    return String(v);
  }
  function same(f, a, b) {
    if (X.empty(a) && X.empty(b)) { return true; }
    if (numeric(f)) { return X.typed(f, a) === X.typed(f, b); }
    return String(a) === String(b);
  }
  function valueOf(f, n) { return f.type === 'bool' ? n.checked : f.type === 'entity' ? (n.getAttribute('data-value') || '') : n.value; }
  function paintCcy(st, root, vals) {
    root.querySelectorAll('[data-ccy-of]').forEach(function (t) { var f = fieldOf(st, t.getAttribute('data-ccy-of')); if (f) { t.textContent = ccy(f, vals) || '—'; } });
  }
  function api(method, path, body) {
    return fetch('/api/drafts' + (path || ''), { method: method, credentials: 'same-origin',
      headers: body ? { 'Content-Type': 'application/json', Accept: 'application/json' } : { Accept: 'application/json' }, body: body ? JSON.stringify(body) : undefined })
      .then(function (r) {
        if (r.status === 204) { return null; }
        return r.json().catch(function () { return {}; }).then(function (j) {
          if (!r.ok) { var e = new Error(j.detail || ('HTTP ' + r.status)); e.issues = j.issues || []; e.status = r.status; throw e; }
          return j;
        });
      });
  }
  function carry(st, f, v) {
    var o = (f.options || []).filter(function (x) { return String(x.value) === String(v); })[0];
    return o && o.carry ? o.carry : null;
  }
  function carries(st, values) {
    var out = {};
    st.form.fields.forEach(function (f) { if (f.type === 'lookup' || f.type === 'entity') { var c = carry(st, f, values[f.id]); if (c) { out[f.id] = c; } } });
    return out;
  }
  function clock() { var d = new Date(); return ('0' + d.getHours()).slice(-2) + ':' + ('0' + d.getMinutes()).slice(-2); }
  function editable(f, isNew) { return !f.masked && !f.locked && (isNew ? f.onNew !== false : !f.readonly); }
  function why(f) { return f.masked ? 'Hidden for you' : f.locked ? f.locked : f.readonly ? (f.reason ? 'read-only: ' + f.reason : 'locked: you can view, not change') : ''; }

  // ---- one field (beside and drawer) --------------------------------------------------------------------------------------
  /* An entity field: a search box over the records it may link to (as the viewer may see them), the id kept and the name shown. */
  function entityInput(st, f, value, onChange, id) {
    var opts = f.options || [], pick = -1, shown = [];
    var wrap = el('span', 'fm-entity');
    var n = el('input', 'fm-in fm-entity-in'); n.type = 'search'; n.id = id; n.name = f.id; n.autocomplete = 'off';
    n.setAttribute('data-field', f.id); n.setAttribute('role', 'combobox'); n.setAttribute('aria-autocomplete', 'list'); n.setAttribute('aria-expanded', 'false');
    n.placeholder = f.placeholder || 'Search by name or id…';
    if (f.required) { n.setAttribute('aria-required', 'true'); }
    var list = el('ul', 'fm-entity-list'); list.id = id + '-list'; list.setAttribute('role', 'listbox'); list.hidden = true;
    n.setAttribute('aria-controls', list.id);
    var open = el('a', 'fm-entity-open', 'Open'); open.target = '_blank'; open.rel = 'noopener'; open.setAttribute('data-fm-open', f.id);
    function label(v) { var o = opts.filter(function (x) { return String(x.value) === String(v); })[0]; return o ? (o.label !== o.value ? o.label + ' · ' + o.value : o.value) : (X.empty(v) ? '' : String(v)); }
    function set(v) {
      n.setAttribute('data-value', X.empty(v) ? '' : String(v)); n.value = label(v);
      open.hidden = X.empty(v) || !f.entity;
      if (!open.hidden) { open.href = '/v/' + encodeURIComponent(f.entity.kind) + '/' + encodeURIComponent(v); open.setAttribute('aria-label', 'Open ' + label(v)); }
    }
    function draw() {
      var q = n.value.trim().toLowerCase();
      if (q === label(n.getAttribute('data-value')).toLowerCase()) { q = ''; }
      shown = opts.filter(function (o) { return !q || String(o.value).toLowerCase().indexOf(q) >= 0 || String(o.label).toLowerCase().indexOf(q) >= 0; }).slice(0, 8);
      pick = Math.min(Math.max(pick, 0), shown.length - 1);
      list.textContent = '';
      shown.forEach(function (o, k) {
        var li = el('li', k === pick ? 'on' : ''); li.id = id + '-o' + k; li.setAttribute('role', 'option'); li.setAttribute('aria-selected', k === pick ? 'true' : 'false');
        li.setAttribute('data-value', o.value);
        li.appendChild(el('b', '', o.label)); if (o.label !== o.value) { li.appendChild(el('span', 'fm-entity-id', ' ' + o.value)); }
        li.addEventListener('mousedown', function (e) { e.preventDefault(); choose(o); });
        list.appendChild(li);
      });
      if (!shown.length) { list.appendChild(el('li', 'fm-entity-none', opts.length ? 'Nothing matches “' + n.value.trim() + '”' : 'Nothing you may link to')); }
      list.hidden = false; n.setAttribute('aria-expanded', 'true');
      n.setAttribute('aria-activedescendant', pick >= 0 ? id + '-o' + pick : '');
    }
    function close() { list.hidden = true; n.setAttribute('aria-expanded', 'false'); n.removeAttribute('aria-activedescendant'); }
    function choose(o) { set(o.value); close(); onChange(o.value); }
    n.addEventListener('input', function () { pick = 0; draw(); });
    n.addEventListener('click', function () { if (list.hidden) { draw(); } });
    n.addEventListener('keydown', function (e) {
      if (e.key === 'ArrowDown' || e.key === 'ArrowUp') {
        e.preventDefault();
        if (list.hidden) { draw(); return; }
        pick = shown.length ? (pick + (e.key === 'ArrowDown' ? 1 : shown.length - 1)) % shown.length : -1; draw();
      } else if (e.key === 'Enter' && !list.hidden && shown[pick]) {
        e.preventDefault(); e.stopImmediatePropagation(); choose(shown[pick]);
      } else if (e.key === 'Escape' && !list.hidden) {
        e.preventDefault(); e.stopImmediatePropagation(); close(); set(n.getAttribute('data-value'));
      }
    });
    n.addEventListener('blur', function () {
      close();
      if (!n.value.trim() && n.getAttribute('data-value')) { set(''); onChange(''); } else { set(n.getAttribute('data-value')); }
    });
    set(value);
    wrap.appendChild(n); wrap.appendChild(open); wrap.appendChild(list);
    n.fmWrap = wrap;
    return n;
  }

  function input(st, f, value, onChange) {
    var n, id = 'fm-' + st.id + '-' + f.id + '-' + (++st.seq);
    if (f.type === 'entity') { return entityInput(st, f, value, onChange, id); }
    if (f.type === 'enum' || f.type === 'lookup') {
      n = el('select', 'fm-in');
      n.appendChild(new Option(f.type === 'lookup' ? 'Choose…' : '', ''));
      (f.options || []).forEach(function (o) { n.appendChild(new Option(f.type === 'lookup' && o.label !== o.value ? o.value + ' · ' + o.label : o.label, o.value)); });
      n.value = X.empty(value) ? '' : String(value);
    } else if (f.type === 'textarea') {
      n = el('textarea', 'fm-in'); n.rows = 3; n.value = X.empty(value) ? '' : String(value);
    } else if (f.type === 'bool') {
      n = el('input', 'fm-check'); n.type = 'checkbox'; n.checked = value === true || value === 'true';
    } else {
      n = el('input', 'fm-in');
      n.type = f.type === 'date' ? 'date' : f.type === 'datetime' ? 'datetime-local' : 'text';
      if (numeric(f)) { n.inputMode = 'decimal'; n.value = X.empty(value) ? '' : fmt(f, value); } else { n.value = X.empty(value) ? '' : String(value).slice(0, f.type === 'datetime' ? 16 : undefined); }
    }
    if (f.placeholder && 'placeholder' in n) { n.placeholder = f.placeholder; }
    n.id = id; n.name = f.id; n.setAttribute('data-field', f.id);
    if (f.required) { n.setAttribute('aria-required', 'true'); }
    n.addEventListener(f.type === 'bool' || n.tagName === 'SELECT' ? 'change' : 'input', function () {
      onChange(f.type === 'bool' ? n.checked : n.value);
    });
    if (f.type === 'money' || f.type === 'datetime') {         // the currency or the time zone beside the value
      var w = el('span', 'fm-adorn'), unit = el('span', 'fm-unit', f.type === 'money' ? (ccy(f, st.vals) || '—') : (f.tz || 'UTC'));
      unit.title = f.type === 'money' ? 'Currency' : 'Time zone';
      unit.id = id + '-unit'; n.setAttribute('aria-describedby', unit.id);
      if (f.type === 'money') {
        unit.setAttribute('data-ccy-of', f.id);
        n.placeholder = n.placeholder || '1.5m, 250k or 1,000,000';
        n.addEventListener('blur', function () { var t = X.typed(f, n.value); if (n.value.trim() && typeof t === 'number' && !isNaN(t)) { n.value = fmt(f, t); } });
      }
      w.appendChild(n); w.appendChild(unit);
      n.fmWrap = w;
    }
    return n;
  }

  function fieldBox(st, f, value, isNew, onChange) {
    var box = el('div', 'fm-field'); box.setAttribute('data-fm-field', f.id);
    var lab = el('label', 'fm-label', f.label);
    if (f.required && editable(f, isNew)) { lab.appendChild(el('span', 'fm-req', ' *')); }
    box.appendChild(lab);
    if (f.masked) {
      box.classList.add('fm-masked');
      lab.appendChild(el('span', 'fm-why', ' (Hidden for you)'));
      box.appendChild(el('div', 'fm-ro fm-hidden', 'Hidden for you'));
      return box;
    }
    if (!editable(f, isNew)) {
      box.classList.add('fm-locked');
      lab.appendChild(el('span', 'fm-why', ' (' + why(f) + ')'));
      var ro = el('div', 'fm-ro mono', fmt(f, value, st.vals) || '—'); ro.setAttribute('aria-readonly', 'true'); ro.setAttribute('data-field', f.id);
      box.appendChild(ro);
      return box;
    }
    var n = input(st, f, value, onChange);
    lab.htmlFor = n.id;
    box.appendChild(n.fmWrap || n);
    var msg = el('div', 'fm-msgs'); msg.id = n.id + '-msg'; msg.setAttribute('data-msgs', f.id);
    n.setAttribute('aria-describedby', ((n.getAttribute('aria-describedby') || '') + ' ' + msg.id).trim());
    box.appendChild(msg);
    return box;
  }

  function showIssues(root, issues, generalBox) {
    root.querySelectorAll('[data-msgs]').forEach(function (m) { m.textContent = ''; });
    root.querySelectorAll('.fm-field').forEach(function (b) { b.classList.remove('fm-bad', 'fm-warned'); var i = b.querySelector('.fm-in'); if (i) { i.removeAttribute('aria-invalid'); } });
    if (generalBox) { generalBox.textContent = ''; }
    issues.forEach(function (is) {
      var m = is.field ? root.querySelector('[data-msgs="' + is.field + '"]') : null;
      var target = m || generalBox;
      if (!target) { return; }
      var p = el('p', 'fm-msg fm-' + (is.severity === 'warn' ? 'warn' : 'error'));
      p.appendChild(el('i', 'bi ' + (is.severity === 'warn' ? 'bi-exclamation-triangle' : 'bi-x-octagon')));
      p.appendChild(document.createTextNode(' ' + is.message));
      target.appendChild(p);
      var box = m && m.closest('.fm-field');
      if (box) {
        box.classList.add(is.severity === 'warn' ? 'fm-warned' : 'fm-bad');
        if (is.severity !== 'warn') { var i = box.querySelector('.fm-in'); if (i) { i.setAttribute('aria-invalid', 'true'); } }
      }
    });
  }

  function badge(text, kind) { var b = el('span', 'fm-badge fm-badge-' + (kind || 'draft'), text); b.setAttribute('data-fm-badge', ''); return b; }

  // ---- saving drafts ---------------------------------------------------------------------------------------------------------
  function payload(st, mode, records, extra) {
    var b = { kind: page.kind, id: page.id, sutra: page.sutra, design: page.design, panel: st.id, mode: mode, title: st.form.title || st.title, records: records };
    Object.keys(extra || {}).forEach(function (k) { b[k] = extra[k]; });
    return b;
  }
  function save(st, which, body) {
    var id = st.draftIds[which];
    return (id ? api('PUT', '/' + encodeURIComponent(id), body) : api('POST', '', body)).then(function (d) {
      st.draftIds[which] = d.draft;
      drafts = drafts.filter(function (x) { return x.draft !== d.draft; }).concat([d]);
      bar(st);
      return d;
    });
  }
  function discard(st, which) {
    var id = st.draftIds[which];
    delete st.draftIds[which];
    if (!id) { return Promise.resolve(); }
    return api('DELETE', '/' + encodeURIComponent(id)).then(function () {
      drafts = drafts.filter(function (x) { return x.draft !== id; });
      bar(st);
    });
  }

  // ---- "You have N drafts on this page" --------------------------------------------------------------------------------------
  function bar(st) {
    var mine = drafts.filter(function (d) { return d.panel === st.id; });
    var b = st.sec.querySelector('[data-fm-drafts]');
    if (!mine.length) { if (b) { b.remove(); } return; }
    if (!b) { b = el('div', 'fm-drafts'); b.setAttribute('data-fm-drafts', ''); b.setAttribute('role', 'region'); b.setAttribute('aria-label', 'Your drafts'); st.sec.querySelector('.pnl-b').prepend(b); }
    b.textContent = '';
    b.appendChild(el('i', 'bi bi-journal-text'));
    b.appendChild(el('b', '', ' You have ' + mine.length + ' draft' + (mine.length === 1 ? '' : 's') + ' on this page'));
    mine.forEach(function (d) {
      var it = el('span', 'fm-draft-item');
      var what = d.mode === 'new' ? 'New' : d.mode === 'cells' ? (d.changes + ' cell change' + (d.changes === 1 ? '' : 's')) : 'Edit ' + ((d.records[0] || {}).record || '');
      it.appendChild(el('span', 'fm-draft-what', what));
      it.appendChild(btn('fm-link', 'Resume', function () { resume(st, d); }));
      it.appendChild(btn('fm-link fm-link-quiet', 'Discard', function () {
        api('DELETE', '/' + encodeURIComponent(d.draft)).then(function () {
          drafts = drafts.filter(function (x) { return x.draft !== d.draft; });
          Object.keys(st.draftIds).forEach(function (k) { if (st.draftIds[k] === d.draft) { delete st.draftIds[k]; } });
          bar(st); say('Draft discarded');
        });
      }));
      b.appendChild(it);
    });
  }

  function resume(st, d) {
    var r = d.records || [];
    if (d.mode === 'new') { drawer(st, (r[0] || {}).values || {}, d.step, d.draft); return; }
    if (d.mode === 'cells') {
      st.draftIds.cells = d.draft;
      r.forEach(function (rec) {
        var i = st.rowOf(rec.record);
        if (i < 0) { return; }
        Object.keys(rec.values || {}).forEach(function (fid) { st.cells.changes[i] = st.cells.changes[i] || {}; st.cells.changes[i][fid] = rec.values[fid]; });
      });
      st.cells.saved = true;
      paintCells(st);
      say('Draft resumed');
      return;
    }
    var rec = r[0] || {}, i = st.rowOf(rec.record);
    st.draftIds['row:' + rec.record] = d.draft;
    beside(st, i, rec.values || {}, rec.record);
  }

  // ---- A: a form beside the table ------------------------------------------------------------------------------------------------
  function confirmLeave() { return window.confirm('You have changes not saved as a draft. Leave them?'); }

  function beside(st, row, given, recordKey) {
    var cur = st.side;
    if (cur && cur.dirty && !confirmLeave()) { return; }
    closeBeside(st);
    var base = row >= 0 ? (st.form.rows[row] || {}) : { values: {}, masked: [] };
    var was = {};
    Object.keys(base.values || {}).forEach(function (k) { was[k] = base.values[k]; });
    var values = {};
    Object.keys(was).forEach(function (k) { values[k] = was[k]; });
    Object.keys(given || {}).forEach(function (k) { values[k] = given[k]; });
    var key = recordKey || base.key || '';
    var s = { row: row, key: key, was: was, values: values, dirty: false, saved: !!given };
    st.side = s;
    st.vals = values;
    st.sec.classList.add('fm-beside-open');
    st.sec.setAttribute('data-editing', '');
    var aside = el('aside', 'fm-side'); aside.setAttribute('aria-label', 'Edit ' + key); aside.setAttribute('data-fm-side', key);
    var head = el('header', 'fm-head');
    head.appendChild(el('h4', '', (st.form.title || 'Edit') + (key ? ' ' + key : '')));
    var bdg = el('span', 'fm-badges'); head.appendChild(bdg);
    head.appendChild(btn('raw-x fm-x', '×', function () { if (!s.dirty || confirmLeave()) { s.dirty = false; closeBeside(st); } })).setAttribute('aria-label', 'Close the form');
    aside.appendChild(head);
    var body = el('div', 'fm-body'), general = el('div', 'fm-general');
    st.form.fields.forEach(function (f) {
      var masked = f.masked || (base.masked || []).indexOf(f.id) >= 0;
      var ff = masked ? Object.assign({}, f, { masked: true }) : f;
      body.appendChild(fieldBox(st, ff, values[f.id], false, function (v) { values[f.id] = v; s.dirty = true; s.saved = false; refresh(); }));
    });
    aside.appendChild(body); aside.appendChild(general);
    var foot = el('footer', 'fm-foot');
    var saveB = btn('fm-btn fm-primary fm-save', 'Save draft', function () {
      var issues = issuesOf();
      if (issues.some(function (i) { return i.severity !== 'warn'; })) { showIssues(aside, issues, general); focusBad(aside); say('Fix the marked fields first'); return; }
      var changed = {};
      st.form.fields.forEach(function (f) { if (editable(f, false) && (base.masked || []).indexOf(f.id) < 0 && !same(f, values[f.id], was[f.id])) { changed[f.id] = typed(f, values[f.id]); } });
      save(st, 'row:' + key, payload(st, 'edit', [{ record: key, values: changed, was: was }])).then(function (d) {
        s.dirty = false; s.saved = true; s.at = clock(); paint(d.warnings || []); say('Draft saved');
      }).catch(function (e) { showIssues(aside, (e.issues || []).length ? e.issues : [{ field: null, message: e.message, severity: 'error' }], general); });
    });
    foot.appendChild(saveB);
    foot.appendChild(btn('fm-btn fm-discard', 'Discard', function () {
      discard(st, 'row:' + key).then(function () { s.dirty = false; closeBeside(st); say('Changes discarded'); });
    }));
    foot.appendChild(el('span', 'fm-note', 'Sending to the source arrives in 2.8.0'));
    aside.appendChild(foot);
    st.sec.querySelector('.pnl-b').appendChild(aside);
    function typed(f, v) { var t = X.typed(f, v); return f.type === 'number' && !isNaN(t) ? t : t; }
    function issuesOf() {
      var checked = {};
      st.form.fields.forEach(function (f) { if (editable(f, false) && !same(f, values[f.id], was[f.id])) { checked[f.id] = true; } });
      return X.check(st.form, values, checked, carries(st, values));
    }
    function paint(extra) {
      bdg.textContent = '';
      if (s.dirty) { bdg.appendChild(badge('Draft · not saved')); } else if (s.saved) { bdg.appendChild(badge('Draft saved' + (s.at ? ' ' + s.at : ''), 'saved')); }
      if (extra && extra.length) { showIssues(aside, issuesOf(), general); }
    }
    function refresh() { showIssues(aside, issuesOf(), general); paintCcy(st, aside, values); paint(); }
    s.paint = paint;
    refresh();
    var first = aside.querySelector('.fm-in, .fm-check');
    if (first) { first.focus(); }
  }
  function focusBad(root) { var b = root.querySelector('.fm-bad .fm-in'); if (b) { b.focus(); } }
  function closeBeside(st) {
    var a = st.sec.querySelector('[data-fm-side]');
    if (a) { a.remove(); }
    st.sec.classList.remove('fm-beside-open');
    st.side = null;
    sync(st);
  }

  // ---- B: editing in the cells -----------------------------------------------------------------------------------------------------
  function cellsSetup(st) {
    var c = st.cells = { changes: {}, saved: false };
    var tb = el('div', 'fm-toolbar'); tb.setAttribute('data-fm-toolbar', '');
    c.count = el('span', 'fm-badges');
    tb.appendChild(c.count);
    tb.appendChild(el('span', 'fm-tip', '✎ marks the columns you can edit'));
    c.undo = btn('fm-btn fm-undo', 'Undo all', function () { c.changes = {}; c.saved = false; discard(st, 'cells'); paintCells(st); say('All changes undone'); });
    c.review = btn('fm-btn fm-primary fm-review-open', 'Review changes', function () { review(st); });
    tb.appendChild(c.undo); tb.appendChild(c.review);
    var wrap = st.sec.querySelector('.tbl-wrap');
    if (!wrap) { return; }
    wrap.before(tb);
    var keys = el('p', 'fm-keys', 'Enter keeps the cell in your draft · Esc undoes it · Tab moves on · masked and read-only columns can\'t be edited');
    wrap.after(keys);
    var ths = st.sec.querySelectorAll('thead th');
    st.form.fields.forEach(function (f) {
      if (f.col === undefined || !ths[f.col]) { return; }
      if (editable(f, false)) { var p = el('span', 'fm-pencil', ' ✎'); p.title = 'You can edit this column'; p.setAttribute('aria-label', '(editable)'); ths[f.col].appendChild(p); }
    });
    st.rowsEl.forEach(function (tr, i) {
      var r = st.form.rows[i] || { values: {}, masked: [] };
      st.form.fields.forEach(function (f) {
        var td = f.col === undefined ? null : tr.children[f.col];
        if (!td) { return; }
        td.setAttribute('data-fcell', f.id);
        td.setAttribute('data-orig', td.textContent);
        if (f.masked || (r.masked || []).indexOf(f.id) >= 0) { td.classList.add('fm-hidden'); td.textContent = 'Hidden for you'; td.title = 'Hidden for you: it can\'t be edited'; return; }
        if (!editable(f, false)) { return; }
        td.classList.add('fm-editable'); td.tabIndex = 0;
        td.setAttribute('aria-label', f.label + ' ' + (td.textContent || '').trim() + ', editable');
      });
    });
    paintCells(st);
  }

  function fieldOf(st, id) { return st.form.fields.filter(function (f) { return f.id === id; })[0]; }
  function rowIndex(st, tr) { return Number(tr.getAttribute('data-frow')); }

  function editCell(st, td) {
    var f = fieldOf(st, td.getAttribute('data-fcell')), tr = td.closest('tr'), i = rowIndex(st, tr);
    var r = st.form.rows[i] || { values: {} }, ch = st.cells.changes[i] || {};
    var cur = f.id in ch ? ch[f.id] : r.values[f.id];
    st.vals = Object.assign({}, r.values, ch);
    var n = input(st, f, cur, function () {});
    n.classList.add('fm-cell-in');
    n.setAttribute('aria-label', f.label);
    td.textContent = ''; td.appendChild(n.fmWrap || n); n.focus(); if (n.select) { n.select(); }
    var done = false;
    function finish(keep, move) {
      if (done) { return; }
      done = true;
      if (keep) {
        var v = valueOf(f, n);
        st.cells.changes[i] = st.cells.changes[i] || {};
        if (same(f, v, r.values[f.id])) { delete st.cells.changes[i][f.id]; } else { st.cells.changes[i][f.id] = v; st.cells.saved = false; }
        if (!Object.keys(st.cells.changes[i]).length) { delete st.cells.changes[i]; }
      }
      paintCells(st);
      var cells = Array.prototype.slice.call(st.sec.querySelectorAll('td.fm-editable'));
      var at = cells.indexOf(st.sec.querySelector('tr[data-frow="' + i + '"] td[data-fcell="' + f.id + '"]'));
      var next = move ? cells[at + move] : cells[at];
      if (next) { next.focus(); }
    }
    n.addEventListener('keydown', function (e) {
      if (e.key === 'Enter') { e.preventDefault(); finish(true, 0); }
      else if (e.key === 'Escape') { e.preventDefault(); e.stopPropagation(); finish(false, 0); }
      else if (e.key === 'Tab') { e.preventDefault(); finish(true, e.shiftKey ? -1 : 1); }
    });
    n.addEventListener('blur', function () { setTimeout(function () { finish(true, null); }, 0); });
  }

  function cellIssues(st, i) {
    var r = st.form.rows[i] || { values: {} }, ch = st.cells.changes[i] || {}, values = {}, checked = {};
    Object.keys(r.values || {}).forEach(function (k) { values[k] = r.values[k]; });
    Object.keys(ch).forEach(function (k) { values[k] = ch[k]; checked[k] = true; });
    return X.check(st.form, values, checked, {});
  }

  function paintCells(st) {
    var c = st.cells, n = 0, bad = 0;
    st.sec.querySelectorAll('tr.fm-why').forEach(function (x) { x.remove(); });
    st.rowsEl.forEach(function (tr, i) {
      var r = st.form.rows[i] || { values: {} }, ch = c.changes[i] || {};
      var issues = Object.keys(ch).length ? cellIssues(st, i) : [];
      tr.querySelectorAll('td.fm-editable').forEach(function (td) {
        var f = fieldOf(st, td.getAttribute('data-fcell'));
        td.classList.remove('fm-changed', 'fm-bad', 'fm-warned');
        if (f.id in ch) {
          n++;
          td.textContent = fmt(f, ch[f.id], Object.assign({}, r.values, ch));
          var w = el('small', 'fm-was', 'was ' + (fmt(f, r.values[f.id], r.values) || '—'));
          td.appendChild(w);
          td.classList.add('fm-changed');
          var mine = issues.filter(function (is) { return is.field === f.id; });
          if (mine.some(function (is) { return is.severity !== 'warn'; })) { td.classList.add('fm-bad'); bad++; } else if (mine.length) { td.classList.add('fm-warned'); }
        } else if (!td.querySelector('input,select,textarea')) {
          td.textContent = td.getAttribute('data-orig');
        }
      });
      if (issues.length) {
        var why = el('tr', 'fm-why'), cell = el('td');
        cell.colSpan = tr.children.length;
        issues.forEach(function (is) { var p = el('p', 'fm-msg fm-' + (is.severity === 'warn' ? 'warn' : 'error')); p.appendChild(el('i', 'bi ' + (is.severity === 'warn' ? 'bi-exclamation-triangle' : 'bi-x-octagon'))); p.appendChild(document.createTextNode(' ' + is.message)); cell.appendChild(p); });
        why.appendChild(cell);
        tr.after(why);
      }
    });
    c.n = n; c.bad = bad;
    c.count.textContent = '';
    if (n && !c.saved) { c.count.appendChild(badge(n + ' change' + (n === 1 ? '' : 's') + ' not saved')); }
    else if (n) { c.count.appendChild(badge('Draft saved · ' + n + ' change' + (n === 1 ? '' : 's'), 'saved')); }
    c.review.textContent = n ? 'Review ' + n + ' change' + (n === 1 ? '' : 's') : 'Review changes';
    c.review.disabled = !n; c.undo.disabled = !n;
    sync(st);
  }

  function review(st) {
    var c = st.cells, dlg = el('div', 'fm-review'), box = el('div', 'fm-review-box');
    dlg.setAttribute('role', 'dialog'); dlg.setAttribute('aria-modal', 'true'); dlg.setAttribute('aria-label', 'Review your changes');
    box.appendChild(el('h4', '', 'Review ' + c.n + ' change' + (c.n === 1 ? '' : 's')));
    var list = el('ul', 'fm-review-list'), records = [], warns = 0;
    Object.keys(c.changes).forEach(function (i) {
      var r = st.form.rows[i] || { values: {} }, ch = c.changes[i], vals = {};
      Object.keys(ch).forEach(function (fid) {
        var f = fieldOf(st, fid);
        list.appendChild(el('li', '', (r.key || '') + ' · ' + f.label + ': ' + (fmt(f, r.values[fid], r.values) || '—') + ' → ' + fmt(f, ch[fid], Object.assign({}, r.values, ch))));
        vals[fid] = X.typed(f, ch[fid]);
      });
      cellIssues(st, i).forEach(function (is) { list.appendChild(el('li', 'fm-msg fm-' + (is.severity === 'warn' ? 'warn' : 'error'), (r.key || '') + ': ' + is.message)); if (is.severity === 'warn') { warns++; } });
      records.push({ record: r.key, values: vals, was: r.values });
    });
    box.appendChild(list);
    var foot = el('div', 'fm-foot'), msg = el('p', 'fm-general');
    var go = btn('fm-btn fm-primary', c.bad ? 'Fix ' + c.bad + ' problem' + (c.bad === 1 ? '' : 's') + ' first' : 'Save draft', function () {
      save(st, 'cells', payload(st, 'cells', records)).then(function () { c.saved = true; close(); paintCells(st); say('Draft saved'); })
        .catch(function (e) { msg.textContent = e.message; });
    });
    go.disabled = !!c.bad;
    foot.appendChild(go);
    foot.appendChild(btn('fm-btn', 'Keep editing', function () { close(); }));
    foot.appendChild(el('span', 'fm-note', 'Sending to the source arrives in 2.8.0'));
    box.appendChild(msg); box.appendChild(foot);
    dlg.appendChild(box);
    document.body.appendChild(dlg);
    box.tabIndex = -1;
    (go.disabled ? box : go).focus();
    function esc(e) { if (e.key === 'Escape') { e.preventDefault(); close(); } }
    function close() { document.removeEventListener('keydown', esc, true); dlg.remove(); c.review.focus(); }
    document.addEventListener('keydown', esc, true);
  }

  // ---- C: a guided drawer for New ------------------------------------------------------------------------------------------------
  function drawer(st, given, atStep, draftId) {
    if (st.drawer && st.drawer.dirty && !confirmLeave()) { return; }
    closeDrawer(st);
    var fields = st.form.fields.filter(function (f) { return !f.masked && !f.locked && f.onNew !== false; });
    var steps = st.form.steps && st.form.steps.length ? st.form.steps : [{ id: '_all', label: 'Details' }];
    var stepOf = function (f) { return f.step && steps.some(function (s) { return s.id === f.step; }) ? f.step : steps[0].id; };
    var values = {};
    Object.keys(given || {}).forEach(function (k) { values[k] = given[k]; });
    var at = Math.max(0, steps.map(function (s) { return s.id; }).indexOf(atStep));
    var auto = {};                                   // fields whose default is worked out (=notional * 0.001) until people type over it
    fields.forEach(function (f) {
      var dv = f['default'];
      if (dv === undefined || dv === null || !X.empty(values[f.id])) { return; }
      if (typeof dv === 'string' && dv.charAt(0) === '=') { auto[f.id] = dv.slice(1); } else { values[f.id] = dv; }
    });
    st.vals = values;
    var d = st.drawer = { values: values, at: at, done: {}, dirty: false, auto: auto };
    if (draftId) { st.draftIds['new'] = draftId; }
    for (var k = 0; k < at; k++) { d.done[k] = true; }
    var scrim = el('div', 'fm-scrim'); scrim.setAttribute('data-fm-scrim', '');
    var box = el('aside', 'fm-drawer'); box.setAttribute('role', 'dialog'); box.setAttribute('aria-modal', 'true'); box.setAttribute('aria-label', st.form.newLabel || 'New');
    box.setAttribute('data-fm-drawer', st.id);
    var head = el('header', 'fm-head');
    head.appendChild(el('h4', '', st.form.newLabel || 'New'));
    var bdg = el('span', 'fm-badges'); head.appendChild(bdg);
    head.appendChild(btn('raw-x fm-x', '×', function () { tryClose(); })).setAttribute('aria-label', 'Close');
    box.appendChild(head);
    var nav = el('ol', 'fm-steps'); box.appendChild(nav);
    var body = el('div', 'fm-body'); box.appendChild(body);
    var foot = el('footer', 'fm-foot'); box.appendChild(foot);
    view.classList.add('fm-dimmed');
    document.body.appendChild(scrim); document.body.appendChild(box);
    scrim.addEventListener('click', tryClose);
    box.addEventListener('keydown', function (e) { if (e.key === 'Escape') { tryClose(); } });
    function tryClose() { if (!d.dirty || confirmLeave()) { d.dirty = false; closeDrawer(st); } }
    function stepFields(i) { return fields.filter(function (f) { return stepOf(f) === steps[i].id; }); }
    function checkedUpTo(i) { var c = {}; for (var j = 0; j <= i; j++) { stepFields(j).forEach(function (f) { c[f.id] = true; }); } return c; }
    function issues(i) { return X.check(st.form, values, checkedUpTo(i), carries(st, values)); }
    function lastStep() { return d.at === steps.length - 1; }
    function render() {
      nav.textContent = '';
      steps.forEach(function (s, i) {
        var li = el('li', 'fm-step' + (i === d.at ? ' on' : '') + (d.done[i] ? ' done' : ''), (i + 1) + ' · ' + (s.label || s.id) + (d.done[i] && i !== d.at ? ' ✓' : ''));
        if (i === d.at) { li.setAttribute('aria-current', 'step'); }
        nav.appendChild(li);
      });
      body.textContent = '';
      var before = [];
      for (var j = 0; j < d.at; j++) { stepFields(j).forEach(function (f) { if (!X.empty(values[f.id])) { before.push(f.label + ' ' + fmt(f, values[f.id], values)); } }); }
      if (before.length) { body.appendChild(el('p', 'fm-sofar', 'So far: ' + before.join(' · ') + ' · in ' + page.id + '.')); }
      var mine = stepFields(d.at);
      mine.forEach(function (f) {
        var box = fieldBox(st, f, values[f.id], true, function (v) {
          if (auto[f.id]) { delete auto[f.id]; var note = box.querySelector('[data-fm-auto]'); if (note) { note.remove(); } }
          values[f.id] = v; d.dirty = true; live();
        });
        if (auto[f.id] && editable(f, true)) {
          var note = el('p', 'fm-auto', 'Worked out: ' + auto[f.id] + ' · type to change it'); note.setAttribute('data-fm-auto', f.id);
          box.appendChild(note);
        }
        body.appendChild(box);
      });
      if (lastStep()) {
        var sum = el('dl', 'fm-summary');
        fields.forEach(function (f) { if (stepOf(f) !== steps[d.at].id) { sum.appendChild(el('dt', '', f.label)); sum.appendChild(el('dd', 'mono', fmt(f, values[f.id], values) || '—')); } });
        body.appendChild(sum);
      }
      var hints = el('div', 'fm-hints'); hints.setAttribute('data-fm-hints', ''); body.appendChild(hints);
      d.general = el('div', 'fm-general'); body.appendChild(d.general);
      foot.textContent = '';
      if (d.at > 0) { foot.appendChild(btn('fm-btn fm-back', 'Back', function () { d.at--; render(); })); }
      if (!lastStep()) {
        foot.appendChild(btn('fm-btn fm-primary fm-next', 'Next: ' + (steps[d.at + 1].label || steps[d.at + 1].id).toLowerCase(), function () {
          var is = issues(d.at);
          if (is.some(function (x) { return x.severity !== 'warn'; })) { showIssues(body, is, d.general); focusBad(body); say('Fix the marked fields first'); return; }
          d.done[d.at] = true; d.at++; render();
        }));
      }
      foot.appendChild(btn('fm-btn' + (lastStep() ? ' fm-primary' : '') + ' fm-save', 'Save as draft', function () {
        var is = issues(d.at);
        if (is.some(function (x) { return x.severity !== 'warn'; })) { showIssues(body, is, d.general); focusBad(body); say('Fix the marked fields first'); return; }
        var vals = {};
        fields.forEach(function (f) { if (!X.empty(values[f.id])) { vals[f.id] = X.typed(f, values[f.id]); } });
        save(st, 'new', payload(st, 'new', [{ record: null, values: vals }], { step: steps[d.at].id === '_all' ? '' : steps[d.at].id })).then(function () {
          d.dirty = false; d.at2 = clock(); live(); say('Draft saved');
        }).catch(function (e) { showIssues(body, (e.issues || []).length ? e.issues : [{ field: null, message: e.message, severity: 'error' }], d.general); });
      }));
      foot.appendChild(el('span', 'fm-note', 'Sending to the source arrives in 2.8.0'));
      live();
      var first = body.querySelector('.fm-in, .fm-check');
      if (first) { first.focus(); }
    }
    function defaults() {
      var tv = {};
      fields.forEach(function (f) { tv[f.id] = X.typed(f, values[f.id]); });
      Object.keys(auto).forEach(function (fid) {
        var f = fieldOf(st, fid), r = X.evaluate(auto[fid], tv);
        values[fid] = r === null || r === undefined || (typeof r === 'number' && !isFinite(r)) ? '' : typeof r === 'number' ? Math.round(r * 1e6) / 1e6 : r;
        tv[fid] = X.typed(f, values[fid]);
        var inp = body.querySelector('[data-field="' + fid + '"]');
        if (inp && 'value' in inp && document.activeElement !== inp) { inp.value = X.empty(values[fid]) ? '' : numeric(f) ? fmt(f, values[fid]) : String(values[fid]); }
      });
    }
    function live() {
      defaults();
      paintCcy(st, body, values);
      var is = issues(d.at).filter(function (x) { return x.severity === 'warn' || touched(x.field); });
      showIssues(body, is, d.general);
      var h = body.querySelector('[data-fm-hints]');
      h.textContent = '';
      var vals = {};
      fields.forEach(function (f) { vals[f.id] = X.typed(f, values[f.id]); });
      vals.__carry = carries(st, values);
      (st.form.hints || []).forEach(function (hn) {
        var on = hn.step ? hn.step === steps[d.at].id : lastStep();
        if (!on) { return; }
        var v = X.evaluate(hn.value, vals), of = hn.of ? X.evaluate(hn.of, vals) : null;
        var p = el('p', 'fm-hint'); p.setAttribute('data-fm-hint', '');
        p.appendChild(el('i', 'bi bi-lightbulb'));
        var masked = function (x) { return x === '•••'; };
        var txt = v === null || v === undefined ? ' ' + hn.label + ': fill in the fields above to see it'
          : masked(v) || masked(of) ? ' ' + hn.label + ': Hidden for you'
          : ' ' + hn.label + ': ' + nf(v) + (of !== null && of !== undefined ? ' of ' + nf(of) + (X.num(of) ? ' (' + Math.round(100 * X.num(v) / X.num(of)) + '%)' : '') : '');
        p.appendChild(document.createTextNode(txt));
        if (of !== null && X.num(v) !== null && X.num(of) !== null && X.num(v) > X.num(of)) { p.classList.add('fm-hint-over'); }
        h.appendChild(p);
      });
      bdg.textContent = '';
      if (d.dirty) { bdg.appendChild(badge('Draft · not saved')); } else if (st.draftIds['new']) { bdg.appendChild(badge('Draft saved' + (d.at2 ? ' ' + d.at2 : ''), 'saved')); }
      sync(st);
    }
    function touched(fid) { return fid && !X.empty(values[fid]); }
    function nf(v) { var n = X.num(v); return n === null ? String(v) : n.toLocaleString('en-US', { maximumFractionDigits: 0 }); }
    render();
  }
  function closeDrawer(st) {
    document.querySelectorAll('[data-fm-drawer="' + st.id + '"], [data-fm-scrim]').forEach(function (n) { n.remove(); });
    view.classList.remove('fm-dimmed');
    st.drawer = null;
    sync(st);
    var b = st.sec.querySelector('[data-fm-new]');
    if (b) { b.focus(); }
  }

  // ---- set up, unsaved changes -------------------------------------------------------------------------------------------------
  function dirty(st) { return !!((st.side && st.side.dirty) || (st.drawer && st.drawer.dirty) || (st.cells && st.cells.n && !st.cells.saved)); }
  function sync(st) {
    var editing = !!(st.side || (st.cells && st.cells.n));
    if (editing) { st.sec.setAttribute('data-editing', ''); } else { st.sec.removeAttribute('data-editing'); }
    if (dirty(st)) { st.sec.setAttribute('data-unsaved', ''); } else { st.sec.removeAttribute('data-unsaved'); }
  }
  window.addEventListener('beforeunload', function (e) {
    if (Object.keys(forms).some(function (k) { return dirty(forms[k]); })) { e.preventDefault(); e.returnValue = ''; return ''; }
  });

  function setup(sec, override) {
    var form;
    try { form = override || JSON.parse(sec.getAttribute('data-form') || 'null'); } catch (e) { form = null; }
    if (!form || !form.fields) { return; }
    var id = sec.getAttribute('data-panel');
    var old = forms[id];
    if (old && old.sec === sec && !override) { return; }
    var st = forms[id] = { id: id, sec: sec, form: form, seq: 0, title: (sec.querySelector('.pnl-h h3') || {}).textContent || id,
      draftIds: old ? old.draftIds : {}, side: null, drawer: null, cells: null };
    form.rows = form.rows || [];
    st.rowsEl = Array.prototype.slice.call(sec.querySelectorAll('tbody > tr')).filter(function (tr) { return !tr.classList.contains('wire-kids') && !tr.classList.contains('fm-why'); });
    st.rowsEl.forEach(function (tr, i) { tr.setAttribute('data-frow', String(i)); });
    st.rowOf = function (key) { for (var i = 0; i < form.rows.length; i++) { if (String(form.rows[i].key) === String(key)) { return i; } } return -1; };
    sec.classList.add('fm-panel');
    if (old && old.sec === sec) { undo(sec); }
    if (!sec.fmBound) {                              // one set of listeners per panel: they act on its current form
      sec.fmBound = true;
      sec.addEventListener('click', function (e) {
        var s = forms[id], td = e.target.closest && e.target.closest('td.fm-editable');
        if (!s) { return; }
        if (s.form.edit === 'cells' && td) { if (!td.querySelector('input,select,textarea')) { editCell(s, td); } return; }
        var tr = e.target.closest && e.target.closest('tbody > tr[data-frow]');
        if (s.form.edit !== 'beside' || !tr || e.target.closest('a, button, [data-fm-side]')) { return; }
        beside(s, rowIndex(s, tr));
      });
      sec.addEventListener('keydown', function (e) {
        var s = forms[id], td = e.target.closest && e.target.closest('td.fm-editable');
        if (!s) { return; }
        if (s.form.edit === 'cells' && td && e.target === td && (e.key === 'Enter' || e.key === 'F2')) { e.preventDefault(); editCell(s, td); return; }
        var tr = e.target.closest && e.target.closest('tbody > tr[data-frow]');
        if (s.form.edit === 'beside' && tr && e.target === tr && e.key === 'e') { beside(s, rowIndex(s, tr)); }
      });
    }
    if (form.edit === 'beside') {
      sec.classList.add('fm-rows');
    } else if (form.edit === 'cells') {
      cellsSetup(st);
    }
    if (form['new'] === 'drawer') {
      var nb = btn('fm-new', '', function () { drawer(st, {}, null, null); });
      nb.setAttribute('data-fm-new', '');
      nb.appendChild(el('i', 'bi bi-plus-lg')); nb.appendChild(document.createTextNode(' ' + (form.newLabel || 'New')));
      var h = sec.querySelector('.pnl-h'), code = h && h.querySelector('.pnl-code');
      if (h) { h.insertBefore(nb, code); }
    }
    bar(st);
  }

  /* A panel's form drawn again (reset): what the last one added comes off first. */
  function undo(sec) {
    sec.querySelectorAll('[data-fm-toolbar], .fm-keys, .fm-pencil, [data-fm-new], [data-fm-side], tr.fm-why').forEach(function (n) { n.remove(); });
    sec.querySelectorAll('td[data-fcell]').forEach(function (td) {
      td.textContent = td.getAttribute('data-orig') || td.textContent;
      td.className = td.className.replace(/\bfm-[a-z]+\b/g, '').trim();
      td.removeAttribute('tabindex'); td.removeAttribute('data-fcell'); td.removeAttribute('aria-label');
    });
    sec.classList.remove('fm-beside-open', 'fm-rows');
  }

  function scan(root) { (root || view).querySelectorAll('section.pnl[data-form]').forEach(function (s) { setup(s); }); }
  scan();
  if (window.drishti && window.drishti.enhance) {
    var enhance = window.drishti.enhance;
    window.drishti.enhance = function (node) { var r = enhance.apply(this, arguments); if (node && node.matches && node.matches('section.pnl[data-form]')) { setup(node); } else if (node && node.querySelectorAll) { scan(node); } return r; };
  }
  if (Object.keys(forms).length) {
    var q = new URLSearchParams({ kind: page.kind, id: page.id });
    if (page.sutra) { q.set('sutra', page.sutra); }
    fetch('/api/drafts?' + q.toString(), { credentials: 'same-origin', headers: { Accept: 'application/json' } })
      .then(function (r) { return r.ok ? r.json() : []; })
      .then(function (list) { drafts = Array.isArray(list) ? list : []; Object.keys(forms).forEach(function (k) { bar(forms[k]); }); })
      .catch(function () {});
  }

  window.drishtiForms = {
    state: function () { var o = {}; Object.keys(forms).forEach(function (k) { o[k] = { dirty: dirty(forms[k]), changes: forms[k].cells ? forms[k].cells.n : 0, drafts: Object.assign({}, forms[k].draftIds) }; }); return o; },
    /* Draws a panel's form again as given (a test, or the designer after a change). */
    reset: function (panelId, form) { var s = view.querySelector('section.pnl[data-panel="' + panelId + '"]'); if (s) { s.setAttribute('data-form', JSON.stringify(form)); setup(s, form); } },
    open: function (panelId, row) { if (forms[panelId]) { beside(forms[panelId], row); } },
    create: function (panelId) { if (forms[panelId]) { drawer(forms[panelId], {}, null, null); } }
  };
}());
