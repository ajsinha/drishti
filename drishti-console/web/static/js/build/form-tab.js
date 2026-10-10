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
/* The Form tab: the search form of the design's kind (FORM <pack>; the pack's form: section for the kind) edited visually, two ways.
 *
 *   palette   the scalar fields of the design's samples, typed from their values: one click (or Enter) adds a field.
 *   fields    a card per field: path, label, type, the operators offered, default, group, help, required, hidden. Drag a card by its handle,
 *             or focus it and press Alt+Up or Alt+Down (the buttons do the same); Delete removes the focused card's field.
 *   YAML      the same form as text, below. Edit either: the cards write the YAML, and typed YAML (once it reads without problems)
 *             rebuilds the cards. The server reads the text (POST /build/designs/{id}/form/preview) and lists what is wrong.
 *   preview   the form as people will see it, over the fields now in the cards.
 *   keeping   after a pause the text is kept as a step of the design's log (PUT .../form): undo, redo and revisions cover it. A stale
 *             revision is a 409 and the design is reloaded, as for operations. Proposing the design sends the change to review.
 * All text from the server goes in as text.
 *
 *   new WB.FormTab(pane, store) -> {show(), flush(), pending(), value(), model()}                                                     */
(function () {
  'use strict';
  var WB = window.DrishtiWB = window.DrishtiWB || {};
  var PREVIEW = 500, SAVE = 1500;
  var TYPES = { text: ['equals', 'contains', 'starts', 'regex'], number: ['=', 'between', '>', '<', '>=', '<='], amount: ['=', 'between', '>', '<', '>=', '<='],
    date: ['on', 'between', 'relative', 'before', 'after'], enum: ['in'], id: ['in'], bool: ['is'] };
  var OPS = { equals: 'equals', contains: 'contains', starts: 'starts with', regex: 'matches regex', '>=': '≥', '<=': '≤', between: 'between', on: 'on',
    relative: 'relative', before: 'before', after: 'after', 'in': 'is one of', is: 'is' };
  var KEYS = ['path', 'label', 'type', 'operators', 'default', 'order', 'group', 'help', 'required', 'hidden', 'values'];

  WB.FormTab = function (pane, store) {
    var $ = function (s) { return pane.querySelector(s); };
    var list = $('[data-form-list]'), src = $('[data-form-src]'), palBox = $('[data-form-palette]'), probBox = $('[data-form-problems]'),
      prev = $('[data-form-preview]'), statusEl = $('[data-form-status]');
    var base = '/build/designs/' + encodeURIComponent(store.state.id);
    var fields = [], palette = [], starter = [], inforce = '', previewT = 0, saveT = 0, token = 0, loaded = false, applying = false, dragFrom = -1;

    prev.addEventListener('submit', function (e) { e.preventDefault(); });

    // ---- the model and its YAML ----------------------------------------------------------------------------------------------------
    function q(v) { return JSON.stringify(v); }
    function scalar(k, v) { return typeof v === 'boolean' || typeof v === 'number' ? String(v) : q(String(v)); }
    function line(f) {
      var parts = [];
      KEYS.forEach(function (k) {
        var v = f[k];
        if (v === undefined || v === '' || v === false || (Array.isArray(v) && !v.length)) { return; }
        if (k === 'operators' && sameOps(v, TYPES[f.type] || [])) { return; }
        parts.push(k + ': ' + (Array.isArray(v) ? '[' + v.map(q).join(', ') + ']' : scalar(k, v)));
      });
      return '- {' + parts.join(', ') + '}';
    }
    function toYaml() { return fields.map(line).join('\n') + (fields.length ? '\n' : ''); }
    function sameOps(a, b) { return a.length === b.length && a.every(function (x, i) { return x === b[i]; }); }
    function value() { return src.value; }
    function setText(t) { applying = true; src.value = t; applying = false; }
    function typeOps(f) { return TYPES[f.type] || TYPES.text; }
    function cleanOps(f) {
      var all = typeOps(f);
      f.operators = (f.operators || []).filter(function (o) { return all.indexOf(o) >= 0; });
      if (!f.operators.length) { delete f.operators; }
    }

    // ---- painting --------------------------------------------------------------------------------------------------------------------
    function labelled(text, control, cls) { var l = WB.el('label', 'wbf-lbl ' + (cls || '')); l.appendChild(WB.el('span', null, text)); l.appendChild(control); return l; }
    function input(f, key, type) {
      var i = WB.el('input', 'wbf-in', null, { type: type || 'text', 'aria-label': (f.path || 'field') + ' ' + key });
      i.value = f[key] === undefined ? '' : f[key];
      i.addEventListener('input', function () { if (i.value === '') { delete f[key]; } else { f[key] = i.value; } changed(false); });
      return i;
    }
    function check(f, key, text) {
      var c = WB.el('input', null, null, { type: 'checkbox' }), l = WB.el('label', 'wbf-chk');
      c.checked = !!f[key]; c.addEventListener('change', function () { if (c.checked) { f[key] = true; } else { delete f[key]; } changed(false); });
      l.appendChild(c); l.appendChild(WB.el('span', null, text)); return l;
    }
    function card(f, i) {
      var li = WB.el('li', 'wbf-card', null, { tabindex: '0', draggable: 'true', 'data-i': String(i), 'aria-label': (f.label || f.path) + ', ' + f.type + ', field ' + (i + 1) + ' of ' + fields.length });
      var head = WB.el('div', 'wbf-head');
      head.appendChild(WB.el('span', 'wbf-grip', '⠿', { 'aria-hidden': 'true', title: 'Drag to reorder' }));
      head.appendChild(WB.el('code', 'mono wbf-path', f.path));
      var up = WB.el('button', 'btn-pill btn-ghost', '↑', { type: 'button', 'aria-label': 'Move ' + f.path + ' up' }),
        down = WB.el('button', 'btn-pill btn-ghost', '↓', { type: 'button', 'aria-label': 'Move ' + f.path + ' down' }),
        del = WB.el('button', 'btn-pill btn-ghost', '✕', { type: 'button', 'aria-label': 'Remove ' + f.path });
      up.addEventListener('click', function () { move(i, -1); }); down.addEventListener('click', function () { move(i, 1); });
      del.addEventListener('click', function () { remove(i); });
      [up, down, del].forEach(function (b) { head.appendChild(b); });
      li.appendChild(head);
      var body = WB.el('div', 'wbf-body');
      body.appendChild(labelled('Label', input(f, 'label')));
      var type = WB.el('select', 'wbf-in', null, { 'aria-label': f.path + ' type' });
      Object.keys(TYPES).forEach(function (t) { var o = WB.el('option', null, t, { value: t }); if (t === f.type) { o.selected = true; } type.appendChild(o); });
      type.addEventListener('change', function () { f.type = type.value; cleanOps(f); if (f.type !== 'enum') { delete f.values; } changed(true); });
      body.appendChild(labelled('Type', type));
      body.appendChild(labelled('Group', input(f, 'group')));
      body.appendChild(labelled('Default', input(f, 'default')));
      var ops = WB.el('fieldset', 'wbf-ops', null, { 'aria-label': f.path + ' operators' });
      ops.appendChild(WB.el('legend', null, 'Operators offered'));
      typeOps(f).forEach(function (o) {
        var on = !f.operators || f.operators.indexOf(o) >= 0, c = WB.el('input', null, null, { type: 'checkbox' }), l = WB.el('label', 'wbf-chk');
        c.checked = on;
        c.addEventListener('change', function () {
          var cur = (f.operators || typeOps(f)).slice();
          cur = c.checked ? typeOps(f).filter(function (x) { return cur.indexOf(x) >= 0 || x === o; }) : cur.filter(function (x) { return x !== o; });
          if (!cur.length) { c.checked = true; return; }                            // at least one: the first is the default
          f.operators = cur; if (sameOps(cur, typeOps(f))) { delete f.operators; } changed(false);
        });
        l.appendChild(c); l.appendChild(WB.el('span', null, OPS[o] || o)); ops.appendChild(l);
      });
      body.appendChild(ops);
      body.appendChild(labelled('Help', input(f, 'help'), 'wbf-wide'));
      var flags = WB.el('div', 'wbf-flags');
      flags.appendChild(check(f, 'required', 'Required')); flags.appendChild(check(f, 'hidden', 'Hidden'));
      body.appendChild(flags);
      li.appendChild(body);
      // drag (pointer) and Alt+Up / Alt+Down (keyboard)
      li.addEventListener('dragstart', function (e) { dragFrom = i; li.classList.add('wbf-drag'); if (e.dataTransfer) { e.dataTransfer.effectAllowed = 'move'; e.dataTransfer.setData('text/plain', f.path); } });
      li.addEventListener('dragend', function () { dragFrom = -1; li.classList.remove('wbf-drag'); });
      li.addEventListener('dragover', function (e) { if (dragFrom >= 0) { e.preventDefault(); } });
      li.addEventListener('drop', function (e) { e.preventDefault(); if (dragFrom >= 0 && dragFrom !== i) { reorder(dragFrom, i); } dragFrom = -1; });
      li.addEventListener('keydown', function (e) {
        if (e.target !== li) { return; }
        if (e.altKey && e.key === 'ArrowUp') { e.preventDefault(); move(i, -1, true); }
        else if (e.altKey && e.key === 'ArrowDown') { e.preventDefault(); move(i, 1, true); }
        else if (e.key === 'Delete') { e.preventDefault(); remove(i); }
      });
      return li;
    }
    function paintList(focus) {
      list.textContent = '';
      if (!fields.length) { list.appendChild(WB.el('li', 'text-muted-d', 'No fields yet: add some from the samples above, or press Starter. Without a form the server infers one.')); }
      fields.forEach(function (f, i) { list.appendChild(card(f, i)); });
      if (focus !== undefined && list.children[focus] && list.children[focus].tabIndex === 0) { list.children[focus].focus(); }
    }
    function paintPalette() {
      palBox.textContent = '';
      if (!palette.length) { palBox.appendChild(WB.el('p', 'text-muted-d', 'The design has no samples to take fields from. Add one under Data, or write the YAML.')); return; }
      palette.forEach(function (p) {
        var used = fields.some(function (f) { return f.path === p.path; });
        var b = WB.el('button', 'chip wbf-chip', null, { type: 'button', 'aria-label': 'Add ' + p.path + ' (' + p.type + ')', title: (p.samples || []).join(' · ') });
        b.appendChild(WB.el('span', 'mono', p.path)); b.appendChild(WB.el('span', 'text-muted-d', ' ' + p.type));
        b.disabled = used;
        b.addEventListener('click', function () { add(p); });
        palBox.appendChild(b);
      });
    }
    function paintProblems(ps) {
      probBox.textContent = '';
      if (!ps.length) { probBox.appendChild(WB.el('p', 'text-muted-d', fields.length ? 'The form reads without problems.' : '')); return; }
      var ul = WB.el('ul', 'bs-list', null, { 'aria-label': 'Form problems' });
      ps.forEach(function (p) { var li = WB.el('li', 'wb-problem pr-yaml'); li.appendChild(WB.el('span', 'bs-role', p.code)); li.appendChild(document.createTextNode(' ' + p.message)); ul.appendChild(li); });
      probBox.appendChild(ul);
    }
    function paintPreview(spec) {
      prev.textContent = '';
      var fs = ((spec && spec.fields) || []).filter(function (f) { return !f.hidden; });
      if (!fs.length) { prev.appendChild(WB.el('p', 'text-muted-d', 'The form appears here.')); return; }
      var grid = WB.el('div', 'qf-fields');
      fs.forEach(function (f) {
        var box = WB.el('div', 'qf-field'), lab = WB.el('label', 'qf-label'), row = WB.el('div', 'qf-in');
        lab.appendChild(document.createTextNode(f.label)); if (f.required) { lab.appendChild(WB.el('span', 'qf-req', ' *')); }
        box.appendChild(lab);
        var id = 'wbfp-' + f.path.replace(/[^A-Za-z0-9]/g, '-'); lab.setAttribute('for', id);
        if ((f.operators || []).length > 1) {
          var sel = WB.el('select', 'qf-op', null, { 'aria-label': f.label + ': how to compare' });
          f.operators.forEach(function (o) { sel.appendChild(WB.el('option', null, OPS[o] || o, { value: o })); }); row.appendChild(sel);
        }
        var v;
        if (f.type === 'enum' || f.type === 'bool') {
          v = WB.el('div', 'qf-enum', null, { id: id, role: 'group' });
          (f.type === 'bool' ? ['Any', 'Yes', 'No'] : (f.values || [])).forEach(function (t) {
            var l = WB.el('label', 'qf-chk'), c = WB.el('input', null, null, { type: f.type === 'bool' ? 'radio' : 'checkbox', name: 'wbfp-' + f.path });
            l.appendChild(c); l.appendChild(WB.el('span', null, t)); v.appendChild(l);
          });
          if (f.type === 'enum' && !(f.values || []).length) { v.appendChild(WB.el('span', 'text-muted-d', 'values come from the data')); }
        } else if (f.type === 'id') {
          v = WB.el('textarea', 'qf-v mono', null, { id: id, rows: '2', placeholder: 'one or many: paste a list' });
        } else {
          v = WB.el('input', 'qf-v mono', null, { id: id, type: f.type === 'date' ? 'date' : 'text', placeholder: f.type === 'amount' ? '250k, 1.5m, 2bn' : '' });
          if (f['default']) { v.value = f['default']; }
        }
        row.appendChild(v); box.appendChild(row);
        if (f.help) { box.appendChild(WB.el('p', 'qf-fhelp text-muted-d', f.help)); }
        grid.appendChild(box);
      });
      prev.appendChild(grid);
    }

    // ---- edits -----------------------------------------------------------------------------------------------------------------------
    function renumber() { if (fields.some(function (f) { return f.order; })) { fields.forEach(function (f, i) { f.order = i + 1; }); } }
    function changed(repaint, focus) {
      renumber();
      if (repaint) { paintList(focus); }
      paintPalette();
      setText(toYaml());
      schedulePreview(); scheduleSave();
    }
    function add(p) {
      if (fields.some(function (f) { return f.path === p.path; })) { return; }
      var f = { path: p.path, label: p.label, type: p.type };
      if (p.type === 'enum' && p.samples) { /* values come from the data at run time */ }
      fields.push(f); changed(true, fields.length - 1);
      store.emit('say', 'Added ' + p.path + ' to the form.');
    }
    function remove(i) { var f = fields.splice(i, 1)[0]; changed(true, Math.min(i, fields.length - 1)); store.emit('say', 'Removed ' + f.path + ' from the form.'); }
    function move(i, d, keepFocus) {
      var j = i + d; if (j < 0 || j >= fields.length) { return; }
      reorder(i, j, keepFocus);
    }
    function reorder(from, to) {
      var f = fields.splice(from, 1)[0]; fields.splice(to, 0, f);
      changed(true, to); store.emit('say', f.path + ' is now field ' + (to + 1) + ' of ' + fields.length + '.');
    }

    // ---- asking the server, keeping the text --------------------------------------------------------------------------------------------
    function adoptAnswer(a, fromYaml) {
      if (Array.isArray(a.palette)) { palette = a.palette; }
      if (Array.isArray(a.starter)) { starter = a.starter; }
      if (typeof a.effective === 'string') { inforce = a.effective; }
      paintPalette(); paintProblems(a.problems || []); paintPreview(a.spec);
      if (fromYaml && a.spec && !(a.problems || []).length) {                        // typed YAML that reads: the cards follow it
        var active = document.activeElement && list.contains(document.activeElement);
        if (!active) {
          fields = a.spec.fields.map(function (f) { var c = {}; KEYS.forEach(function (k) { if (f[k] !== undefined && f[k] !== '' && f[k] !== false) { c[k] = f[k]; } });
            if (c.operators && sameOps(c.operators, TYPES[c.type] || [])) { delete c.operators; } return c; });
          paintList(); paintPalette();
        }
      }
      if (fromYaml && !value().trim()) { fields = []; paintList(); }
    }
    function refresh(fromYaml) {
      loaded = true; var mine = ++token;
      return WB.call('POST', base + '/form/preview', { text: value() }).then(function (r) {
        if (mine !== token) { return; }
        if (!r.ok) { statusEl.textContent = 'The form could not be read: ' + WB.why(r); return; }
        statusEl.textContent = '';
        adoptAnswer(r.body, fromYaml);
      });
    }
    var yamlEdit = false;
    function schedulePreview() { clearTimeout(previewT); previewT = setTimeout(function () { previewT = 0; var y = yamlEdit; yamlEdit = false; refresh(y); }, PREVIEW); }
    function scheduleSave() { clearTimeout(saveT); saveT = setTimeout(save, SAVE); }
    function save() {
      clearTimeout(saveT); saveT = 0;
      if (value() === store.state.form) { return Promise.resolve(); }
      return store.exclusive(function () {
        var text = value();
        if (text === store.state.form) { return null; }
        return WB.call('PUT', base + '/form', { baseRev: store.state.rev, text: text }).then(function (r) {
          if (r.status === 409) { return store.reload(); }
          if (!r.ok) { store.emit('say', 'The form was not saved: ' + WB.why(r), true); return null; }
          store.adoptForm(r.body);
          store.emit('say', 'Search form saved (revision ' + r.body.rev + '). Ctrl+Z undoes it.');
          return null;
        });
      });
    }
    src.addEventListener('input', function () { if (applying) { return; } yamlEdit = true; schedulePreview(); scheduleSave(); });
    src.addEventListener('blur', function () { if (saveT) { save(); } });
    function pending() { return !!saveT; }
    function flush() { return saveT ? save() : Promise.resolve(); }

    store.on('doc', function (d) {
      if (typeof d.form === 'string' && d.form !== value() && !saveT) { setText(d.form); yamlEdit = true; if (loaded) { refresh(true); } }      // undo, redo or a reload moved the text
    });

    // ---- starters ----------------------------------------------------------------------------------------------------------------------
    function replaceWith(text, what) {
      var go = function () { setText(text); yamlEdit = true; scheduleSave(); refresh(true); store.emit('say', what); };
      if (!value().trim()) { go(); return; }
      WB.ask({ title: 'Replace the form?', message: 'This replaces the form in the box. Undo (Ctrl+Z) brings it back.', ok: 'Replace' }).then(function (yes) { if (yes) { go(); } });
    }
    $('[data-form-starter]').addEventListener('click', function () {
      if (!starter.length) { store.emit('say', 'There are no samples to write a form from.', true); return; }
      replaceWith(starter.map(function (f) { return line(f); }).join('\n') + '\n', 'A starter form was written from the samples.');
    });
    $('[data-form-inforce]').addEventListener('click', function () {
      if (!inforce.trim()) { store.emit('say', 'No form is declared for this kind yet: the server infers one.', true); return; }
      replaceWith(inforce, 'The form now in force was copied in.');
    });

    setText(store.state.form || '');
    return {
      show: function () { if (!loaded) { refresh(true); } },
      flush: flush, pending: pending, value: value, model: function () { return fields; }
    };
  };
})();
