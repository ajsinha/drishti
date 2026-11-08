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
/* Forms on a view page (RACHANA_REFERENCE.md 10). A panel whose Sutra has form: carries it as data-form, already as this reader may use it (the
   server marks masked fields and locks other roles' fields). Three ways in:
   * edit: beside   click a row and a form opens beside the table: typed fields, locked ones say why, warnings and errors in words under
                    the field, "Draft · not saved", Save draft and Discard;
   * edit: cells    a pencil marks the editable columns; a cell opens on a click, Enter or F2; Enter keeps the value in the draft, Esc
                    undoes it, Tab moves on; a changed cell is tinted and says "was …"; a bad value has a red border and the reason under
                    the row; "N changes not saved", Undo all, Review N changes (then Save draft); a masked cell says Hidden for you;
   * new: drawer    New opens a drawer over the dimmed page, in steps (Who ✓ → What → Check), with Back, Next and Save as draft, and the
                    hints computed as people type (a limit's headroom from the chosen counterparty).
   Saving keeps a draft per person on the server (/api/drafts, checked again there with the same rules). A form whose Sutra says where it
   goes (save:) also has Submit (2.8.0 write-back, drishti_write-back.js): the draft is saved, then the review and confirm sheet sends it as change
   requests; a draft the server refused or found conflicted says so in the drafts bar. "You have N drafts on this page" lets a person resume or discard one; leaving with changes not saved asks first.
   Field types beyond text, numbers, dates and choices: money (an amount typed as 1.5m, 250k or 1,000,000, shown grouped, with its
   currency beside it: fixed, or the one another field holds), datetime (a date and a time, the time zone shown beside it) and entity (a
   linked record picked by search: its id kept, its name shown, Open beside it). A field's default starts a new record: a value, or
   =<expression> worked out as people type until they type over it.
   window.Drishti.forms. */
(function () {
  'use strict';
  var view = document.querySelector('[data-view]');
  var X = window.Drishti.formExpr;
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
      var s = window.Drishti.elements.core.formatNumber(n, { maxDecimals: /0$/.test(f.fmt || '') ? 0 : f.type === 'money' ? 2 : 6, minus: '-' }), c = f.type === 'money' && vals ? ccy(f, vals) : '';
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
  function valueOf(f, n) { return f.type === 'bool' ? n.checked : f.type === 'entity' ? (n.getAttribute('data-value') || '') : n.value == null ? '' : n.value; }
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
  /* An entity field: drishti-inputs' Combobox over the records it may link to (as the viewer may see them), the id kept and the name
     shown. The search is the server's records of the kind (/api/forms/lookup, read as the viewer) for what is typed, 250 ms after the last
     key, a late answer dropped; the preloaded choices answer at once and stay the answer when the server cannot be reached. */
  function entityInput(st, f, value, onChange, id) {
    var opts = (f.options || []).map(function (o) { return { value: String(o.value), label: String(o.label) }; });
    var wrap = el('span', 'fm-entity');
    var n = el('input', 'fm-in fm-entity-in'); n.type = 'search'; n.id = id; n.name = f.id;
    n.setAttribute('data-field', f.id);
    n.placeholder = f.placeholder || 'Search by name or id…';
    if (f.required) { n.setAttribute('aria-required', 'true'); }
    var open = el('a', 'fm-entity-open', 'Open'); open.target = '_blank'; open.rel = 'noopener'; open.setAttribute('data-fm-open', f.id);
    function label(v) { var o = opts.filter(function (x) { return x.value === String(v); })[0]; return o ? (o.label !== o.value ? o.label + ' · ' + o.value : o.value) : (X.empty(v) ? '' : String(v)); }
    function set(v) {
      n.setAttribute('data-value', X.empty(v) ? '' : String(v));
      open.hidden = X.empty(v) || !f.entity;
      if (!open.hidden) { open.href = '/v/' + encodeURIComponent(f.entity.kind) + '/' + encodeURIComponent(v); open.setAttribute('aria-label', 'Open ' + label(v)); }
    }
    function local(q) { q = q.toLowerCase(); return opts.filter(function (o) { return !q || o.value.toLowerCase().indexOf(q) >= 0 || o.label.toLowerCase().indexOf(q) >= 0; }); }
    function source(text, signal) {
      var q = text.trim();
      if (q.toLowerCase() === label(n.getAttribute('data-value')).toLowerCase()) { q = ''; }
      if (!f.entity || !f.entity.kind || !q) { return Promise.resolve(local(q).slice(0, 8)); }
      var p = new URLSearchParams({ kind: f.entity.kind, q: q, limit: '20' });
      if (f.entity.pack) { p.set('pack', f.entity.pack); }
      if (f.entity.label) { p.set('label', f.entity.label); }
      if (f.entity.value) { p.set('value', f.entity.value); }
      return fetch('/api/forms/lookup?' + p.toString(), { credentials: 'same-origin', headers: { Accept: 'application/json' }, signal: signal })
        .then(function (r) { return r.ok ? r.json() : null; })
        .catch(function (e) { if (e && e.name === 'AbortError') { throw e; } return null; })
        .then(function (j) {
          var found = j && Array.isArray(j.results) ? j.results.map(function (x) { return { value: String(x.id), label: String(x.label || x.id) }; }) : [];
          found.forEach(function (o) { if (!opts.some(function (x) { return x.value === o.value; })) { opts.push(o); } });
          var ids = found.map(function (o) { return o.value; });           // the server's answer first, then any preloaded choice it did not name
          return found.concat(local(q).filter(function (o) { return ids.indexOf(o.value) < 0; })).slice(0, 8);
        });
    }
    wrap.appendChild(n);
    var I = window.Drishti.elements.inputs;
    var cb = new I.Combobox(wrap, { input: n, source: source, minChars: 0, cacheSize: 0, debounceMs: f.entity && f.entity.kind ? 250 : 0, label: f.label || f.id,
      value: X.empty(value) ? null : { value: String(value), label: label(value) },
      renderOption: function (o, li) { li.setAttribute('data-value', o.value); li.appendChild(el('b', '', o.label)); if (o.label !== o.value) { li.appendChild(el('span', 'fm-entity-id', ' ' + o.value)); } } });
    cb.on('change', function (e) {
      var v = e.value ? e.value.value : '';
      if (e.value) { cb.setValue({ value: v, label: label(v) }); }
      set(v); onChange(v);
    });
    n.addEventListener('click', function () { if (!cb.isOpen()) { cb.ta.query(''); } });
    n.addEventListener('keydown', function (e) { if ((e.key === 'Enter' || e.key === 'Escape') && cb.isOpen()) { e.stopImmediatePropagation(); } });
    set(value);
    wrap.appendChild(open);
    n.fmWrap = wrap;
    return n;
  }

  function input(st, f, value, onChange) {
    var n, id = 'fm-' + st.id + '-' + f.id + '-' + (++st.seq);
    if (f.type === 'entity') { return entityInput(st, f, value, onChange, id); }
    if (f.type === 'enum' || f.type === 'lookup') {
      n = el('drishti-select', 'fm-pick');                              // drishti-inputs' Select (a cell's editor: it opens on the value)
      var choices = [{ value: '', label: f.type === 'lookup' ? 'Choose…' : '—' }].concat((f.options || []).map(function (o) {
        return { value: String(o.value), label: f.type === 'lookup' && o.label !== o.value ? o.value + ' · ' + o.label : String(o.label) }; }));
      n.options = { label: f.label || f.id, options: choices, value: !X.empty(value) && choices.some(function (o) { return o.value === String(value); }) ? String(value) : '' };
    } else if (f.type === 'textarea') {
      n = el('textarea', 'fm-in'); n.rows = 3; n.value = X.empty(value) ? '' : String(value);
    } else if (f.type === 'bool') {
      n = el('input', 'fm-check'); n.type = 'checkbox'; n.checked = value === true || value === 'true';
    } else {
      n = el('input', 'fm-in');
      n.type = 'text';                                       // a date or a date with a time: drishti-inputs' pickers (picker() upgrades it once it has a place)
      if (numeric(f)) { n.inputMode = 'decimal'; n.value = X.empty(value) ? '' : fmt(f, value); } else { n.value = X.empty(value) ? '' : String(value).slice(0, f.type === 'datetime' ? 16 : undefined); }
    }
    if (f.placeholder && 'placeholder' in n) { n.placeholder = f.placeholder; }
    n.id = id; n.name = f.id; n.setAttribute('data-field', f.id);
    if (f.required) { n.setAttribute('aria-required', 'true'); }
    n.addEventListener(f.type === 'bool' ? 'change' : n.component ? 'drishti:change' : 'input', function () {
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

  /* A date or a date-and-time field (once it is in the page): drishti-inputs' date picker, or its date-and-time picker (the zone stays the
     field's own unit beside it); the field keeps its value, its input events and its focus(). Null for other fields. */
  function picker(f, n, placeholder) {
    var I = window.Drishti.elements.inputs;
    if (f.type === 'date') { n.type = 'date'; return I.upgradeDateInput(n, { label: f.label || f.id, placeholder: placeholder }); }
    if (f.type === 'datetime') { n.type = 'datetime-local'; return I.upgradeDateTimeInput(n, { label: f.label || f.id }); }
    return null;
  }

  /* The fields of the form beside the table or of a step of the drawer: drishti-inputs' Form (live), each record field as a field of its
     schema: text, a number and money (shown grouped, the currency beside it: fixed or what another field holds), a date, a date and a
     time (its zone beside it), a choice or a look-up, yes or no, long text, and an entity (the combobox above); a masked field says
     Hidden for you, a locked or read-only one shows its value and why. The rules and hints are the form expressions' (drishti_form-expr.js),
     so a message here is the server's own: errors stop a save, warnings are notes. Each control carries data-field. */
  function schemaOf(st, f, isNew, masked) {
    var label = f.label + (f.required && !masked && editable(f, isNew) ? ' *' : '');
    var hook = { 'data-field': f.id }, base = { title: label, hook: hook, placeholder: f.placeholder || undefined };
    if (masked) { return Object.assign(base, { type: 'string', title: f.label + ' (Hidden for you)', widget: 'fm-show', 'x-text': 'Hidden for you', 'x-hidden': true }); }
    if (!editable(f, isNew)) { return Object.assign(base, { type: 'string', title: f.label + ' (' + why(f) + ')', widget: 'fm-show', 'x-field': f }); }
    switch (f.type) {
      case 'enum': case 'lookup':
        return Object.assign(base, { type: 'string', widget: 'select', emptyLabel: f.type === 'lookup' ? 'Choose…' : '', enum: (f.options || []).map(function (o) { return String(o.value); }),
          enumLabels: (f.options || []).map(function (o) { return f.type === 'lookup' && o.label !== o.value ? o.value + ' · ' + o.label : String(o.label); }) });
      case 'textarea': return Object.assign(base, { type: 'string', format: 'textarea' });
      case 'bool': return Object.assign(base, { type: 'boolean' });
      case 'money': return Object.assign(base, { type: 'money', currency: f.currency || undefined, parseMessage: f.label + ' is an amount (1,000,000, 1.5m or 250k)' });
      case 'number': return Object.assign(base, { type: 'number', parseMessage: f.label + ' is a number', hook: Object.assign(hook, { inputmode: 'decimal' }) });
      case 'date': return Object.assign(base, { type: 'date', placeholder: f.placeholder || '2026-11-06, T-1' });
      case 'datetime': return Object.assign(base, { type: 'datetime', timeZone: f.tz || 'UTC' });
      case 'entity': return Object.assign(base, { type: 'string', widget: 'fm-entity', 'x-field': f });
      default: return Object.assign(base, { type: 'string' });
    }
  }
  function fieldsForm(st, fields, values, isNew, masked, onChange) {
    var I = window.Drishti.elements.inputs, props = {}, v = {}, host = el('div', 'fm-fields');
    fields.forEach(function (f) {
      var m = !!f.masked || (masked || []).indexOf(f.id) >= 0;
      props[f.id] = schemaOf(st, f, isNew, m);
      if (!X.empty(values[f.id])) { v[f.id] = f.type === 'bool' ? values[f.id] === true || values[f.id] === 'true' : props[f.id].type === 'money' || props[f.id].type === 'number' ? X.typed(f, values[f.id]) : f.type === 'datetime' ? String(values[f.id]).slice(0, 16) : values[f.id]; }
    });
    var form = new I.Form(host, { live: true, label: st.form.title || 'Edit', schema: { type: 'object', properties: props }, value: v, widgets: {
      'fm-show': function (w) {                                          // a value that may not be changed here: shown, with why in the label
        var ro = el('div', 'fm-ro mono' + (w.field['x-hidden'] ? ' fm-hidden' : ''), w.field['x-hidden'] ? w.field['x-text'] : fmt(w.field['x-field'], values[w.field['x-field'].id], st.vals) || '—');
        ro.setAttribute('aria-readonly', 'true'); ro.id = w.id;
        return { el: ro };
      },
      'fm-entity': function (w) { var n = entityInput(st, w.field['x-field'], w.value, function (x) { w.set(x === '' ? undefined : x); w.touch(); }, w.id); return { el: n.fmWrap, input: n }; }
    } });
    form.on('change', function (e) { onChange(e.path, e.value === undefined ? '' : e.value); });
    return { el: host, form: form,
      /** the expressions' problems: errors on their fields (and the rest in the general box), warnings as notes */
      show: function (issues, general) {
        var errs = {}, notes = {};
        if (general) { general.textContent = ''; }
        issues.forEach(function (is) {
          if (is.field && props[is.field]) { if (is.severity === 'warn') { notes[is.field] = notes[is.field] || is.message; } else { errs[is.field] = errs[is.field] || is.message; } return; }
          if (general) { var p = el('p', 'fm-msg fm-' + (is.severity === 'warn' ? 'warn' : 'error')); p.appendChild(document.createTextNode(is.message)); general.appendChild(p); }
        });
        form.setNotes(notes);
        form.showErrors(errs);
      },
      focusBad: function () { var bad = Object.keys(form.errors || {})[0]; if (bad) { form.focusField(bad); } },
      set: function (fid, x) { form.setField(fid, x); }
    };
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

  // ---- Submit (2.8.0 write-back, drishti_write-back.js) -----------------------------------------------------------------------------------
  var WB = window.Drishti.writeBack, wb = { mayEdit: false }, wbKnown = !WB;
  /* Whether this person may submit arrives after the page draws: a form opened before it is known gets its Submit and its note when it
     arrives (a quick open, a phone, used to draw the form without its Submit for good). */
  var wbReady = WB ? WB.settings().then(function (s) { wb = s || wb; wbKnown = true; }, function () { wbKnown = true; }) : Promise.resolve();
  /* A new record cannot be a correction laid over a source record: a form whose save: is an overlay submits edits only. */
  function canSubmit(st, isNew) { return !!(WB && wb.mayEdit && st.form.save && !(isNew && st.form.save.target === 'overlay')); }
  function sendNote(st, isNew) {
    var n = el('span', 'fm-note', noteText(st, isNew)); n.setAttribute('data-fm-send-note', '');
    if (!wbKnown) { wbReady.then(function () { n.textContent = noteText(st, isNew); }); }
    return n;
  }
  function noteText(st, isNew) {
    var sv = st.form.save;
    var t = !sv ? 'Drafts only: this form sends nothing'
      : canSubmit(st, isNew) ? (sv.target === 'source' ? 'Submit amends the source' : sv.target === 'owned' ? 'Submit saves the record' : 'Submit keeps a correction over the source')
        + (sv.approval > 0 ? ' after ' + (sv.approval === 1 ? 'one approver' : sv.approval + ' approvers') : '')
      : isNew && sv.target === 'overlay' ? 'A new record is kept as a draft: this form submits corrections only'
      : wb.enabled === false ? 'Editing is off on this server: drafts only' : 'Drafts only for you: submitting needs the edit power';
    return t;
  }
  /* Submit: the draft saved first (saveFirst returns a promise of it, or null when the form shows what to fix), then the sheet. */
  function submitButton(st, isNew, saveFirst, foot, after) {
    if (!wbKnown) {
      var spot = document.createComment('submit'); foot.appendChild(spot);
      wbReady.then(function () {
        if (!spot.parentNode) { return; }
        var tmp = document.createElement('span'); submitButton(st, isNew, saveFirst, tmp, after);
        if (tmp.firstChild) { spot.parentNode.replaceChild(tmp.firstChild, spot); } else { spot.remove(); }
      });
      return;
    }
    if (!canSubmit(st, isNew)) { return; }
    var b = btn('fm-btn fm-primary fm-submit', 'Submit…', function () {
      var p = saveFirst();
      if (!p) { return; }
      p.then(function (d) {
        if (!d || !d.draft) { return; }
        WB.submit({ draft: d.draft, title: st.form.title || st.title, onDone: function () { if (after) { after(); } } });
      });
    });
    b.setAttribute('data-fm-submit', '');
    foot.appendChild(b);
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
      if (d.change && d.change.state) {                // submitted, then refused, conflicted or rejected: kept to edit again
        var ch = el('span', 'fm-draft-change', (WB ? WB.words(d.change.state) : d.change.state) + (d.change.reason ? ': ' + d.change.reason : ''));
        ch.setAttribute('data-fm-draft-change', d.change.state);
        it.appendChild(ch);
      }
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
    var ff = fieldsForm(st, st.form.fields, values, false, base.masked, function (fid, v) { values[fid] = v; s.dirty = true; s.saved = false; refresh(); });
    body.appendChild(ff.el);
    aside.appendChild(body); aside.appendChild(general);
    var foot = el('footer', 'fm-foot');
    function saveDraft() {
      var issues = issuesOf();
      if (issues.some(function (i) { return i.severity !== 'warn'; })) { ff.show(issues, general); ff.focusBad(); say('Fix the marked fields first'); return null; }
      var changed = {};
      st.form.fields.forEach(function (f) { if (editable(f, false) && (base.masked || []).indexOf(f.id) < 0 && !same(f, values[f.id], was[f.id])) { changed[f.id] = typed(f, values[f.id]); } });
      return save(st, 'row:' + key, payload(st, 'edit', [{ record: key, values: changed, was: was }])).then(function (d) {
        s.dirty = false; s.saved = true; s.at = clock(); paint(d.warnings || []); say('Draft saved');
        return d;
      }).catch(function (e) { ff.show((e.issues || []).length ? e.issues : [{ field: null, message: e.message, severity: 'error' }], general); return null; });
    }
    var saveB = btn('fm-btn fm-primary fm-save', 'Save draft', function () { saveDraft(); });
    foot.appendChild(saveB);
    submitButton(st, false, saveDraft, foot, function () { closeBeside(st); });
    foot.appendChild(btn('fm-btn fm-discard', 'Discard', function () {
      discard(st, 'row:' + key).then(function () { s.dirty = false; closeBeside(st); say('Changes discarded'); });
    }));
    foot.appendChild(sendNote(st, false));
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
      if (extra && extra.length) { ff.show(issuesOf(), general); }
    }
    function refresh() { ff.show(issuesOf(), general); paint(); }
    s.paint = paint;
    refresh();
    var first = aside.querySelector('.fm-fields input:not([type=hidden]), .fm-fields select, .fm-fields textarea');
    if (first) { first.focus(); }
  }
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
    var sel = n.component || null;                                       // an enum or a lookup: drishti-inputs' Select
    n.classList.add('fm-cell-in');
    if (!sel) { n.setAttribute('aria-label', f.label); }
    td.textContent = ''; td.appendChild(n.fmWrap || n);
    var dp = picker(f, n, '2026-11-06, T-1'), keys = dp ? dp.host : n;      // a date (and time): drishti-inputs' picker in the cell, its fields take the keys
    var open = function () { return !!(dp || sel) && !!n.ownerDocument.querySelector('.di-pop'); };
    if (sel) { sel.focus(); sel.open(); } else { (dp ? dp.input : n).focus(); if ((dp ? dp.input : n).select) { (dp ? dp.input : n).select(); } }
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
    if (sel) {
      sel.on('pick', function () { finish(true, 0); });                    // a pick saves the cell
      sel.on('close', function () { setTimeout(function () { if (!done && !keys.contains(keys.ownerDocument.activeElement)) { finish(true, null); } }, 0); });   // closed by a click away
    }
    keys.addEventListener('keydown', function (e) {
      if (sel && e.defaultPrevented && e.key !== 'Tab') { return; }        // the Select's own keys (open, move, pick)
      if (e.key === 'Enter') { e.preventDefault(); finish(true, 0); }
      else if (e.key === 'Escape') { if (open()) { return; } e.preventDefault(); e.stopPropagation(); finish(false, 0); }
      else if (e.key === 'Tab') {
        if (f.type === 'datetime' && !e.shiftKey && dp && dp.date && dp.date.input === e.target) { return; }   // from the date on to its time
        e.preventDefault(); finish(true, e.shiftKey ? -1 : 1);
      }
    });
    keys.addEventListener('focusout', function (e) { if (dp && e.relatedTarget && keys.contains(e.relatedTarget)) { return; } setTimeout(function () { if (!open() && !(dp && keys.contains(keys.ownerDocument.activeElement))) { finish(true, null); } }, 0); });
    if (dp && f.type === 'date') { dp.on('change', function () { setTimeout(function () { if (!open()) { finish(true, 0); } }, 0); }); }
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
    if (!c.bad) {
      submitButton(st, false, function () {
        return save(st, 'cells', payload(st, 'cells', records)).then(function (d) { c.saved = true; close(); paintCells(st); return d; })
          .catch(function (e) { msg.textContent = e.message; return null; });
      }, foot, function () { c.changes = {}; paintCells(st); });
    }
    foot.appendChild(btn('fm-btn', 'Keep editing', function () { close(); }));
    foot.appendChild(sendNote(st, false));
    box.appendChild(msg); box.appendChild(foot);
    dlg.appendChild(box);
    document.body.appendChild(dlg);
    box.tabIndex = -1;
    (go.disabled ? box : go).focus();
    var off = window.Drishti.keys.register({ id: 'records.review.close', keys: ['Escape'], scope: 'dialog', area: 'records-review', capture: true, typing: true,
      description: 'Keep editing: close the review', run: function () { close(); } });
    function close() { off(); dlg.remove(); c.review.focus(); }
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
      d.ff = fieldsForm(st, mine, values, true, [], function (fid, v) {
        if (auto[fid]) { delete auto[fid]; }
        values[fid] = v; d.dirty = true; live();
      });
      body.appendChild(d.ff.el);
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
          if (is.some(function (x) { return x.severity !== 'warn'; })) { d.ff.show(is, d.general); d.ff.focusBad(); say('Fix the marked fields first'); return; }
          d.done[d.at] = true; d.at++; render();
        }));
      }
      function saveNew() {
        var is = issues(d.at);
        if (is.some(function (x) { return x.severity !== 'warn'; })) { d.ff.show(is, d.general); d.ff.focusBad(); say('Fix the marked fields first'); return null; }
        var vals = {};
        fields.forEach(function (f) { if (!X.empty(values[f.id])) { vals[f.id] = X.typed(f, values[f.id]); } });
        return save(st, 'new', payload(st, 'new', [{ record: null, values: vals }], { step: steps[d.at].id === '_all' ? '' : steps[d.at].id })).then(function (r) {
          d.dirty = false; d.at2 = clock(); live(); say('Draft saved');
          return r;
        }).catch(function (e) { d.ff.show((e.issues || []).length ? e.issues : [{ field: null, message: e.message, severity: 'error' }], d.general); return null; });
      }
      foot.appendChild(btn('fm-btn' + (lastStep() ? ' fm-primary' : '') + ' fm-save', 'Save as draft', function () { saveNew(); }));
      if (lastStep()) { submitButton(st, true, saveNew, foot, function () { closeDrawer(st); }); }
      foot.appendChild(sendNote(st, true));
      live();
      var first = body.querySelector('.fm-fields input:not([type=hidden]), .fm-fields select, .fm-fields textarea');
      if (first) { first.focus(); }
    }
    function defaults() {
      var tv = {};
      fields.forEach(function (f) { tv[f.id] = X.typed(f, values[f.id]); });
      Object.keys(auto).forEach(function (fid) {
        var f = fieldOf(st, fid), r = X.evaluate(auto[fid], tv);
        values[fid] = r === null || r === undefined || (typeof r === 'number' && !isFinite(r)) ? '' : typeof r === 'number' ? Math.round(r * 1e6) / 1e6 : r;
        tv[fid] = X.typed(f, values[fid]);
        if (d.ff && d.ff.form.parts.has(fid)) { d.ff.set(fid, X.empty(values[fid]) ? undefined : numeric(f) ? X.typed(f, values[fid]) : values[fid]); }
      });
      if (!d.ff) { return; }
      d.ff.form.parts.forEach(function (p, fid) {                  // a default worked out says so under its field until people type over it
        var note = p.wrap.querySelector('[data-fm-auto]');
        if (auto[fid] && !note) { note = el('p', 'fm-auto', 'Worked out: ' + auto[fid] + ' · type to change it'); note.setAttribute('data-fm-auto', fid); p.wrap.appendChild(note); }
        else if (!auto[fid] && note) { note.remove(); }
      });
    }
    function live() {
      defaults();
      var is = issues(d.at).filter(function (x) { return x.severity === 'warn' || touched(x.field); });
      d.ff.show(is, d.general);
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
    function nf(v) { var n = X.num(v); return n === null ? String(v) : window.Drishti.elements.core.formatNumber(n, { minus: '-' }); }
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
  if (window.Drishti.view && window.Drishti.view.enhance) {
    var enhance = window.Drishti.view.enhance;
    window.Drishti.view.enhance = function (node) { var r = enhance.apply(this, arguments); if (node && node.matches && node.matches('section.pnl[data-form]')) { setup(node); } else if (node && node.querySelectorAll) { scan(node); } return r; };
  }
  if (Object.keys(forms).length) {
    var q = new URLSearchParams({ kind: page.kind, id: page.id });
    if (page.sutra) { q.set('sutra', page.sutra); }
    fetch('/api/drafts?' + q.toString(), { credentials: 'same-origin', headers: { Accept: 'application/json' } })
      .then(function (r) { return r.ok ? r.json() : []; })
      .then(function (list) { drafts = Array.isArray(list) ? list : []; Object.keys(forms).forEach(function (k) { bar(forms[k]); }); })
      .catch(function () {});
  }

  window.Drishti.forms = {
    state: function () { var o = {}; Object.keys(forms).forEach(function (k) { o[k] = { dirty: dirty(forms[k]), changes: forms[k].cells ? forms[k].cells.n : 0, drafts: Object.assign({}, forms[k].draftIds) }; }); return o; },
    /* Draws a panel's form again as given (a test, or the designer after a change). */
    reset: function (panelId, form) { var s = view.querySelector('section.pnl[data-panel="' + panelId + '"]'); if (s) { s.setAttribute('data-form', JSON.stringify(form)); setup(s, form); } },
    open: function (panelId, row) { if (forms[panelId]) { beside(forms[panelId], row); } },
    create: function (panelId) { if (forms[panelId]) { drawer(forms[panelId], {}, null, null); } }
  };
}());
