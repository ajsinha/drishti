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
/* UI Board P4b, Chip mode's Body step (UI_BOARD.md 2, 12.6): the chip's inside built with the screen designer's own panel tools, not new ones:
   its panel palette (build/palette.js: the 22 Rachana panel kinds) and its inspector (build/inspector.js, the form made from the Rachana schema),
   over the chip's body instead of a Sutra. The inspector talks to a small store that applies its SetOption operations to body.panels.
   The field pickers offer the pins (@rows, @.label of a rowset pin's columns, %{page-size}) instead of document paths, and any reference
   that names no pin is red at once (CP-02). The Body YAML tab is the body alone, in the Rachana grammar. */
(function () {
  'use strict';
  const U = window.drishtiUiBoard, CE = U && U.chipEditor, WB = window.DrishtiWB, root = document.querySelector('[data-ub-design]');
  if (!CE || !WB || !WB.Inspector) { return; }
  const M = U.chipModel, h = U.h, clear = U.clear, $ = (s) => root.querySelector(s);
  const kindsBox = $('[data-ce-kinds]'), listBox = $('[data-ce-panels]'), insBox = $('[data-ce-inspector]'), pinsBox = $('[data-ce-pin-list]');
  const bodyText = $('[data-ce-body-yaml]'), bodyNote = $('[data-ce-body-note]');
  let selected = null;
  const panels = () => { CE.doc.body = CE.doc.body || {}; CE.doc.body.panels = Array.isArray(CE.doc.body.panels) ? CE.doc.body.panels : []; return CE.doc.body.panels; };

  // ---- the store the inspector edits through ---------------------------------------------------------------------------------------------
  const handlers = {};
  const store = {
    state: { yaml: '' },
    on(ev, fn) { (handlers[ev] = handlers[ev] || []).push(fn); },
    send(ops) {
      (ops || []).forEach((op) => {
        if (op.op !== 'setOption') { return; }
        const p = panels().find((x) => x.id === op.panel);
        if (!p) { return; }
        if (op.value === null || op.value === undefined || op.value === '') { delete p[op.option]; } else { p[op.option] = op.value; }
      });
      sync();
      CE.changed('body');
      (handlers.doc || []).forEach((fn) => fn({ changed: true, source: 'ops' }));
      return Promise.resolve({ ok: true });
    }
  };
  function sync() { store.state.yaml = 'rachana: 1\nsutra: chip-body\nversion: 1\npanels:' + M.yaml(panels(), '  ') + '\n'; }
  const pinList = () => {
    const ins = ((CE.doc.pins || {}).in) || {};
    return Object.keys(ins).map((n) => ({ name: n, type: (ins[n] || {}).type, role: (ins[n] || {}).role, columns: Object.keys((ins[n] || {}).shape || {}) }));
  };
  const schemaP = fetch('/studio/schema', { credentials: 'same-origin' }).then((r) => r.json());
  const insp = new WB.Inspector(insBox, store, () => schemaP, {
    paths: () => [],
    pins: pinList,
    remove: (id) => removePanel(id),
    actions: { menuBind: (btn) => bindMenu(btn) }
  });

  // ---- the palette, the panel list, the pins ---------------------------------------------------------------------------------------------
  function addPanel(kind) {
    let n = 1;
    while (panels().some((p) => p.id === kind + (n === 1 ? '' : '-' + n))) { n++; }
    const p = { id: kind + (n === 1 ? '' : '-' + n), kind };
    const data = pinList().find((x) => x.role === 'data');
    if (data && kind !== 'markdown') { p.rows = '@' + data.name; }
    if (kind === 'markdown') { p.text = '%{title}'; }
    panels().push(p);
    selected = p.id;
    sync(); CE.changed('body'); paint();
    CE.say('Added a ' + kind + ' panel: set its options in the inspector, reading only the pins.');
  }
  function removePanel(id) {
    CE.doc.body.panels = panels().filter((p) => p.id !== id);
    if (selected === id) { selected = null; }
    sync(); CE.changed('body'); paint();
  }
  function move(i, d) {
    const ps = panels(), j = i + d;
    if (j < 0 || j >= ps.length) { return; }
    ps.splice(j, 0, ps.splice(i, 1)[0]);
    sync(); CE.changed('body'); paint();
  }
  function paintKinds() {
    clear(kindsBox);
    (WB.palette ? WB.palette.kinds : []).forEach((k) => {
      const b = h('button', { type: 'button', class: 'btn-pill btn-ghost ub-ce-kind', 'data-ce-kind': k.kind, title: k.text, 'aria-label': 'Add a ' + k.kind + ' panel' },
        h('i', { class: 'bi bi-' + k.icon, 'aria-hidden': 'true' }), ' ' + k.kind);
      b.addEventListener('click', () => addPanel(k.kind));
      kindsBox.appendChild(h('li', {}, b));
    });
  }
  function paintPins() {
    clear(pinsBox);
    const list = pinList();
    if (!list.length) { pinsBox.appendChild(h('li', { class: 'text-muted-d', text: 'No in pins: declare them in the Pins step.' })); }
    list.forEach((p) => {
      const ref = p.role === 'data' ? '@' + p.name : '%{' + p.name + '}';
      const b = h('button', { type: 'button', class: 'btn-pill btn-ghost mono', 'data-ce-pin-ref': ref, title: 'Put ' + ref + ' in the field you were in' + (p.columns.length ? '; its columns: ' + p.columns.map((c) => '@.' + c).join(', ') : '') }, ref, h('small', { class: 'text-muted-d', text: ' ' + p.type }));
      b.addEventListener('mousedown', (e) => e.preventDefault());               // keep the focus in the field
      b.addEventListener('click', () => insert(ref));
      pinsBox.appendChild(h('li', {}, b));
    });
  }
  let lastField = null;
  insBox.addEventListener('focusin', (e) => { if (/input|textarea/i.test(e.target.tagName)) { lastField = e.target; } });
  function insert(ref) {
    const f = lastField && document.body.contains(lastField) ? lastField : null;
    if (!f) { CE.say('Click in a field of the inspector first, then pick the pin.'); return; }
    const a = f.selectionStart == null ? f.value.length : f.selectionStart, b = f.selectionEnd == null ? a : f.selectionEnd;
    f.value = f.value.slice(0, a) + ref + f.value.slice(b);
    f.focus();
    f.dispatchEvent(new Event('input', { bubbles: true }));
    f.dispatchEvent(new Event('change', { bubbles: true }));
  }
  function bindMenu(btn) {
    const data = pinList().filter((p) => p.role === 'data');
    if (!selected || !data.length) { CE.say(data.length ? 'Select a panel first.' : 'Declare a data pin first (the Pins step).', true); return; }
    const menu = h('div', { class: 'ub-ce-menu', role: 'menu' });
    data.forEach((p) => {
      const it = h('button', { type: 'button', role: 'menuitem', class: 'btn-pill btn-ghost mono', text: 'rows: @' + p.name });
      it.addEventListener('click', () => { store.send([{ op: 'setOption', panel: selected, option: 'rows', value: '@' + p.name }]); menu.remove(); insp.show({ type: 'panel', id: selected }); markRefs(); });
      menu.appendChild(it);
    });
    btn.parentNode.appendChild(menu);
    const first = menu.querySelector('button'); if (first) { first.focus(); }
  }
  function paintList() {
    clear(listBox);
    const errs = CE.problems.filter((p) => /^body/.test(p.path || ''));
    panels().forEach((p, i) => {
      const bad = errs.filter((x) => x.path.indexOf('body.panels[' + i + ']') === 0);
      const pick = h('button', { type: 'button', class: 'btn-pill btn-ghost' + (bad.length ? ' ub-bad-text' : ''), 'aria-pressed': String(p.id === selected), 'data-ce-panel': p.id },
        h('span', { class: 'bs-role', text: p.kind }), ' ', h('b', { class: 'mono', text: p.id }), bad.length ? h('small', { text: ' · ' + bad.map((x) => x.rule + ' ' + x.message).join('; ') }) : null);
      pick.addEventListener('click', () => { selected = p.id; paintList(); insp.show({ type: 'panel', id: p.id }); setTimeout(markRefs, 50); });
      const up = h('button', { type: 'button', class: 'btn-pill btn-ghost', text: '↑', 'aria-label': 'Move ' + p.id + ' up' }), dn = h('button', { type: 'button', class: 'btn-pill btn-ghost', text: '↓', 'aria-label': 'Move ' + p.id + ' down' });
      up.disabled = i === 0; dn.disabled = i === panels().length - 1;
      up.addEventListener('click', () => move(i, -1)); dn.addEventListener('click', () => move(i, 1));
      listBox.appendChild(h('li', { class: 'ub-row' }, pick, up, dn));
    });
    if (!panels().length) { listBox.appendChild(h('li', { class: 'text-muted-d', text: (CE.doc.body || {}).board ? 'A module: its body is the inner board (see the YAML tab); full module editing comes with P6.' : 'No panels yet: add one from the panel kinds.' })); }
  }
  /** Any reference in the inspector's fields that names no in pin: red, with why (CP-02). */
  function markRefs() {
    insBox.querySelectorAll('input[type="text"], input:not([type]), textarea').forEach((f) => {
      const bad = M.badRefs(CE.doc, f.value);
      f.classList.toggle('ub-bad', bad.length > 0);
      f.setAttribute('aria-invalid', String(bad.length > 0));
      f.title = bad.length ? 'CP-02: ' + bad.join(', ') + ' names no in pin (pins: ' + pinList().map((p) => p.name).join(', ') + ')' : '';
    });
  }
  insBox.addEventListener('input', () => setTimeout(markRefs, 0));
  function paint() {
    paintPins(); paintList();
    if (selected && panels().some((p) => p.id === selected)) { insp.show({ type: 'panel', id: selected }); } else { clear(insBox); insBox.appendChild(h('p', { class: 'text-muted-d', text: 'Select a panel to set its options. The fields offer the pins: type @ or %{.' })); }
    setTimeout(markRefs, 60);
  }

  // ---- the body YAML (Rachana) -----------------------------------------------------------------------------------------------------------
  function writeBody() {
    if (document.activeElement === bodyText) { return; }
    CE.api('POST', '/ui/api-yaml/dump', { document: CE.doc.body || {}, yaml: '' }).then((r) => { if (document.activeElement !== bodyText) { bodyText.value = r.yaml; } }).catch(() => {});
  }
  let t = 0;
  bodyText.addEventListener('input', () => {
    clearTimeout(t);
    t = setTimeout(() => {
      CE.api('POST', '/ui/api-yaml/load', { yaml: bodyText.value }).then((r) => {
        CE.doc.body = r.document || {};
        bodyNote.textContent = 'In step with the panels.'; bodyNote.classList.remove('bi-bad');
        sync(); CE.changed('body'); paintList();
      }).catch((e) => { bodyNote.textContent = (e.line ? 'Line ' + e.line + ': ' : '') + (e.detail || 'not YAML'); bodyNote.classList.add('bi-bad'); });
    }, 350);
  });
  root.querySelectorAll('[data-ce-body-tab]').forEach((b) => b.addEventListener('click', () => {
    const which = b.getAttribute('data-ce-body-tab');
    root.querySelectorAll('[data-ce-body-tab]').forEach((x) => x.setAttribute('aria-selected', String(x === b)));
    $('[data-ce-body-pane="panels"]').hidden = which !== 'panels';
    $('[data-ce-body-pane="yaml"]').hidden = which !== 'yaml';
    if (which === 'yaml') { writeBody(); }
  }));

  CE.on((src) => {
    if (src === 'body') { paintList(); return; }
    if (src === 'pins' || src === 'server') { paintPins(); paintList(); markRefs(); return; }
    if (/^step:/.test(src) || src === 'manual' || src === 'toolbar') { if (src === 'step:body') { paint(); } return; }
    sync();                                                                       // load, the YAML tab
    if (selected && !panels().some((p) => p.id === selected)) { selected = null; }
    if (!selected && panels().length) { selected = panels()[0].id; }
    (handlers.doc || []).forEach((fn) => fn({ changed: true, source: 'yaml' }));
    paint(); writeBody();
  });
  sync(); paintKinds(); paint();
})();
