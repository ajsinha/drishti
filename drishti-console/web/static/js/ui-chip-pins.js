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
/* UI Board P4b, two steps of Chip mode (UI_BOARD.md 2, 4.1, 12.6):
   - Pins: the pin table. One row per pin: in (data or control) or out, its name, its type (4.1), the columns of a rowset or document
     ("id:id!, label:dimension, measures:measure*": ! required, * many), the values of an enum, the kind of an id (of:), required, its default.
     Each keystroke changes the chip document and the row's mistakes go red at once (rule PIN of ui-chip-model.js).
   - Manual: purpose, behaviour, defaults, limits, not-for, accessibility, a line per pin and the wiring examples, each with the prompt of what
     is missing (CP-01). "Add example" writes a board fragment that uses this chip with its required pins mapped, to edit. */
(function () {
  'use strict';
  const U = window.drishtiUiBoard, CE = U && U.chipEditor, root = document.querySelector('[data-ub-design]');
  if (!CE) { return; }
  const M = U.chipModel, h = U.h, clear = U.clear;
  const pinsBox = root.querySelector('[data-ce-pins]'), manualBox = root.querySelector('[data-ce-manual]');
  const pins = (dir) => { CE.doc.pins = CE.doc.pins || { in: {}, out: {} }; CE.doc.pins[dir] = CE.doc.pins[dir] || {}; return CE.doc.pins[dir]; };

  // ---- the pin table ----------------------------------------------------------------------------------------------------------------------
  function typed(spec, text) {
    const t = String(text).trim();
    if (t === '') { return undefined; }
    if ((spec.type === 'number' || spec.type === 'measure') && /^-?\d+(\.\d+)?$/.test(t)) { return Number(t); }
    if (spec.type === 'bool' && (t === 'true' || t === 'false')) { return t === 'true'; }
    return t;
  }
  function rename(dir, old, name) {
    const src = pins(dir), out = {};
    Object.keys(src).forEach((k) => { out[k === old ? name : k] = src[k]; });
    CE.doc.pins[dir] = out;
    const mp = CE.doc.manual && CE.doc.manual.pins;
    if (mp && old in mp) { const m2 = {}; Object.keys(mp).forEach((k) => { m2[k === old ? name : k] = mp[k]; }); CE.doc.manual.pins = m2; }
  }
  function field(attrs, value, on) {
    const i = h('input', Object.assign({ class: 'studio-in' }, attrs));
    i.value = value == null ? '' : String(value);
    i.addEventListener('input', () => on(i.value, i));
    return i;
  }
  function select(options, value, on, attrs) {
    const s = h('select', Object.assign({ class: 'studio-select' }, attrs || {}), options.map((o) => h('option', { value: o, text: o, selected: o === value })));
    s.addEventListener('change', () => on(s.value));
    return s;
  }
  function row(dir, name) {
    const spec = pins(dir)[name] || {}, at = 'pins.' + dir + '.' + name;
    let cur = name;
    const tr = h('tr', { 'data-pin': name, 'data-dir': dir });
    const path = (f) => 'pins.' + dir + '.' + cur + (f ? '.' + f : '');
    const nameIn = field({ class: 'studio-in mono', 'aria-label': 'Pin name', 'data-path': at, 'data-f': '' }, name, (v) => {
      v = v.trim();
      if (!v || v === cur || v in pins(dir)) { return; }
      rename(dir, cur, v); cur = v; tr.setAttribute('data-pin', v);
      tr.querySelectorAll('[data-f]').forEach((el) => el.setAttribute('data-path', path(el.getAttribute('data-f'))));
      CE.changed('pins');
    });
    const typeSel = select(M.TYPES, spec.type || 'text', (v) => { pins(dir)[cur].type = v; if ((v === 'rowset' || v === 'document') && dir === 'in') { pins(dir)[cur].role = 'data'; } CE.changed('pins'); paintTable(); }, { 'aria-label': 'Type', 'data-path': at + '.type', 'data-f': 'type' });
    const roleSel = dir === 'in' ? select(['data', 'control'], spec.role || 'control', (v) => { pins(dir)[cur].role = v; CE.changed('pins'); }, { 'aria-label': 'Role', 'data-path': at + '.role', 'data-f': 'role' }) : h('span', { class: 'text-muted-d', text: 'out' });
    const shaped = spec.type === 'rowset' || spec.type === 'document';
    const detail = shaped
      ? field({ class: 'studio-in mono', placeholder: 'id:id!, label:dimension, measures:measure*', 'aria-label': 'Columns', 'data-path': at + '.shape', 'data-f': 'shape' }, M.shapeText(spec.shape), (v, el) => {
        const r = M.parseShape(v);
        el.classList.toggle('ub-bad', r.bad.length > 0);
        el.title = r.bad.length ? 'Not a column: ' + r.bad.join(', ') + ' (write name:type, with ! for required, * for many; types ' + M.COLUMN_TYPES.join(', ') + ')' : '';
        if (Object.keys(r.shape).length) { pins(dir)[cur].shape = r.shape; } else { delete pins(dir)[cur].shape; }
        CE.changed('pins');
      })
      : spec.type === 'enum'
        ? field({ class: 'studio-in', placeholder: 'day, week, month', 'aria-label': 'Values', 'data-path': at + '.values', 'data-f': 'values' }, (spec.values || []).join(', '), (v) => {
          const vals = v.split(',').map((x) => x.trim()).filter(Boolean);
          if (vals.length) { pins(dir)[cur].values = vals; } else { delete pins(dir)[cur].values; }
          CE.changed('pins');
        })
        : (spec.type === 'id' || spec.type === 'selection')
          ? field({ class: 'studio-in mono', placeholder: 'of: pack/kind (optional)', 'aria-label': 'Of kind', 'data-path': at + '.of', 'data-f': 'of' }, spec.of, (v) => { if (v.trim()) { pins(dir)[cur].of = v.trim(); } else { delete pins(dir)[cur].of; } CE.changed('pins'); })
          : h('span', { class: 'text-muted-d', text: '' });
    const req = h('input', { type: 'checkbox', 'aria-label': 'Required', 'data-path': at + '.required', 'data-f': 'required' });
    req.checked = !!spec.required; req.disabled = dir === 'out';
    req.addEventListener('change', () => { if (req.checked) { pins(dir)[cur].required = true; } else { delete pins(dir)[cur].required; } CE.changed('pins'); });
    const def = dir === 'in' ? field({ class: 'studio-in', placeholder: 'default', 'aria-label': 'Default', 'data-path': at + '.default', 'data-f': 'default' }, spec.default, (v) => {
      const t = typed(pins(dir)[cur], v);
      if (t === undefined) { delete pins(dir)[cur].default; } else { pins(dir)[cur].default = t; }
      CE.changed('pins');
    }) : h('span');
    const rm = h('button', { type: 'button', class: 'btn-pill btn-ghost', 'aria-label': 'Remove pin ' + name, text: '×' });
    rm.addEventListener('click', () => { delete pins(dir)[cur]; if (CE.doc.manual && CE.doc.manual.pins) { delete CE.doc.manual.pins[cur]; } CE.changed('pins'); paintTable(); });
    [h('span', { class: 'ub-ce-dir ub-ce-' + dir, text: dir }), nameIn, roleSel, typeSel, detail, req, def, h('span', { class: 'ub-ce-msg', 'data-msg': true }), rm]
      .forEach((x) => tr.appendChild(h('td', {}, x)));
    return tr;
  }
  function paintTable() {
    clear(pinsBox);
    const head = h('tr', {}, ['', 'Name', 'Role', 'Type', 'Columns · values · kind', 'Required', 'Default', '', ''].map((t) => h('th', { scope: 'col', text: t })));
    pinsBox.appendChild(h('thead', {}, head));
    const tb = h('tbody');
    Object.keys(pins('in')).forEach((n) => tb.appendChild(row('in', n)));
    Object.keys(pins('out')).forEach((n) => tb.appendChild(row('out', n)));
    if (!tb.children.length) { tb.appendChild(h('tr', {}, h('td', { colspan: '9', class: 'text-muted-d', text: 'No pins yet. A chip reads only its pins: add a data pin for its rows, control pins for filters and dates, out pins for what the person does.' }))); }
    pinsBox.appendChild(tb);
    mark();
  }
  /** The row's mistakes in red, as the person types (no redraw under their hands). */
  function mark() {
    pinsBox.querySelectorAll('tr[data-pin]').forEach((tr) => {
      const at = 'pins.' + tr.getAttribute('data-dir') + '.' + tr.getAttribute('data-pin');
      const probs = CE.problemsAt(at).filter((p) => p.rule === 'PIN' || p.rule === 'CP-03');
      tr.querySelectorAll('[data-path]').forEach((el) => {
        const mine = probs.filter((p) => p.path === el.getAttribute('data-path'));
        if (el.getAttribute('data-f') === 'shape' && el.title) { return; }
        el.classList.toggle('ub-bad', mine.length > 0);
        el.setAttribute('aria-invalid', String(mine.length > 0));
      });
      const msg = tr.querySelector('[data-msg]');
      msg.textContent = probs.map((p) => p.message).join('; ');
      msg.className = 'ub-ce-msg ' + (probs.some((p) => p.level !== 'warning') ? 'ub-bad-text' : 'bi-warn');
      tr.classList.toggle('ub-bad-row', probs.some((p) => p.level !== 'warning'));
    });
  }
  root.querySelectorAll('[data-ce-add-pin]').forEach((b) => b.addEventListener('click', () => {
    const dir = b.getAttribute('data-ce-add-pin'), p = pins(dir);
    let n = dir === 'out' ? 'selected' : (Object.keys(p).length ? 'pin' : 'rows'), k = 2;
    const base = n;
    while (n in pins('in') || n in pins('out')) { n = base + '-' + k++; }
    p[n] = dir === 'out' ? { type: 'id' } : (n === 'rows' ? { role: 'data', type: 'rowset', required: true, shape: { id: { type: 'id', required: true }, label: { type: 'dimension' } } } : { role: 'control', type: 'text' });
    CE.changed('pins');
    paintTable();
    const last = pinsBox.querySelector('tr[data-pin="' + n + '"] input');
    if (last) { last.focus(); last.select(); }
  }));

  // ---- the manual -------------------------------------------------------------------------------------------------------------------------
  const PROMPTS = {
    purpose: 'What does the chip show, and when should a designer use it? (one paragraph)',
    behaviour: 'What can the person do (sort, filter, select), and which out pin does each action drive?',
    defaults: 'What does the chip do with no control input?',
    limits: 'Rows, columns, refresh, sizes; what happens past them?',
    'not-for': 'What to use instead in the common wrong cases (read by people and agents)',
    accessibility: 'Keyboard and screen-reader behaviour'
  };
  const man = () => { CE.doc.manual = CE.doc.manual || {}; return CE.doc.manual; };
  function example() {
    const ins = (CE.doc.pins || {}).in || {}, map = [], controls = [];
    Object.keys(ins).forEach((p) => {
      const s = ins[p] || {};
      if (s.role === 'data') { Object.keys(s.shape || {}).forEach((c) => { if ((s.shape[c] || {}).required) { map.push(p + '.' + c + ': ' + c.toUpperCase()); } }); } else if (s.required && s.default == null) { controls.push(p + ': ' + (s.type === 'date' ? 'today' : s.type === 'number' ? '1' : 'x')); }
    });
    return 'chips:\n  c: { use: ' + CE.ref() + ', supply: { connector: trading/trade-search }' + (map.length ? ', map: { ' + map.join(', ') + ' }' : '') + (controls.length ? ', controls: { ' + controls.join(', ') + ' }' : '') + ' }\n';
  }
  function text(key, rows) {
    const t = h('textarea', { class: 'studio-in', rows: String(rows || 2), placeholder: PROMPTS[key], 'aria-label': 'Manual: ' + key, 'data-path': 'manual.' + key });
    t.value = man()[key] == null ? '' : String(man()[key]);
    t.addEventListener('input', () => { man()[key] = t.value; CE.changed('manual'); markManual(); });
    return h('label', { class: 'ub-field' }, h('span', { class: 'ub-field-l', text: key }), t, h('small', { class: 'ub-ce-msg', 'data-msg-for': 'manual.' + key }));
  }
  function paintManual() {
    clear(manualBox);
    manualBox.appendChild(text('purpose', 3));
    const pf = h('fieldset', { class: 'ub-fs' }, h('legend', { text: 'pins: a line per pin (what it means, what happens when it is empty)' }));
    const all = Object.keys((CE.doc.pins || {}).in || {}).map((p) => ['in', p]).concat(Object.keys((CE.doc.pins || {}).out || {}).map((p) => ['out', p]));
    if (!all.length) { pf.appendChild(h('p', { class: 'text-muted-d', text: 'Declare the pins first (the Pins step).' })); }
    all.forEach(([dir, p]) => {
      const i = h('input', { class: 'studio-in', 'aria-label': 'Manual: pin ' + p, 'data-path': 'manual.pins.' + p, placeholder: dir === 'out' ? 'Out. What the person did, and when it is empty' : 'What it means; what happens when it is empty' });
      i.value = ((man().pins || {})[p]) || '';
      i.addEventListener('input', () => { man().pins = man().pins || {}; man().pins[p] = i.value; if (!i.value) { delete man().pins[p]; } CE.changed('manual'); markManual(); });
      pf.appendChild(h('label', { class: 'ub-field' }, h('span', { class: 'ub-field-l mono', text: p + ' (' + dir + ')' }), i, h('small', { class: 'ub-ce-msg', 'data-msg-for': 'manual.pins.' + p })));
    });
    manualBox.appendChild(pf);
    ['behaviour', 'defaults', 'limits', 'not-for', 'accessibility'].forEach((k) => manualBox.appendChild(text(k, 2)));
    const ef = h('fieldset', { class: 'ub-fs', 'data-path': 'manual.examples' }, h('legend', { text: 'examples: board fragments that use the chip (checked by the board check at publish, CP-05)' }));
    (Array.isArray(man().examples) ? man().examples : []).forEach((ex, k) => {
      const t = h('input', { class: 'studio-in', 'aria-label': 'Example ' + (k + 1) + ' title', placeholder: 'What the example shows', 'data-path': 'manual.examples[' + k + '].title' });
      t.value = ex.title || '';
      t.addEventListener('input', () => { man().examples[k].title = t.value; CE.changed('manual'); });
      const b = h('textarea', { class: 'studio-in mono', rows: '4', 'aria-label': 'Example ' + (k + 1) + ' board', 'data-path': 'manual.examples[' + k + '].board' });
      b.value = ex.board || '';
      b.addEventListener('input', () => { man().examples[k].board = b.value; CE.changed('manual'); markManual(); });
      const rm = h('button', { type: 'button', class: 'btn-pill btn-ghost', text: 'Remove example ' + (k + 1) });
      rm.addEventListener('click', () => { man().examples.splice(k, 1); CE.changed('manual'); paintManual(); });
      ef.appendChild(h('div', { class: 'ub-ce-example', 'data-example': String(k) }, t, b, h('small', { class: 'ub-ce-msg', 'data-msg-for': 'manual.examples[' + k + ']' }), rm));
    });
    const add = h('button', { type: 'button', class: 'btn-pill btn-ghost', 'data-ce-add-example': true, text: 'Add example' });
    add.addEventListener('click', () => { man().examples = Array.isArray(man().examples) ? man().examples : []; man().examples.push({ title: 'Example ' + (man().examples.length + 1), board: example() }); CE.changed('manual'); paintManual(); });
    ef.appendChild(add);
    manualBox.appendChild(ef);
    markManual();
  }
  function markManual() {
    manualBox.querySelectorAll('[data-msg-for]').forEach((m) => {
      const at = m.getAttribute('data-msg-for'), probs = CE.problems.filter((p) => p.path === at || p.path.indexOf(at + '.') === 0 || p.path.indexOf(at + '[') === 0);
      m.textContent = probs.map((p) => p.rule + ': ' + p.message + (p.fix && p.rule === 'CP-01' ? ' · ' + p.fix : '')).join('; ');
      m.className = 'ub-ce-msg' + (probs.length ? ' ub-bad-text' : '');
      const input = m.parentNode.querySelector('textarea, input');
      if (input) { input.classList.toggle('ub-bad', probs.length > 0); }
    });
  }

  CE.on((src) => {
    if (src === 'pins') { mark(); paintManual(); return; }                       // the manual lists the pins
    if (src === 'manual' || src === 'server' || src === 'toolbar' || src === 'body') { mark(); markManual(); return; }
    if (src === 'step:manual') { paintManual(); return; }
    if (/^step:/.test(src)) { return; }
    paintTable(); paintManual();                                                  // load, the YAML tab
  });
  paintTable();
  paintManual();
})();
