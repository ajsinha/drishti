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
/* UI Board P4b, composite chips: "a board with no connectors" (the product owner; UI_BOARD.md 9.5, 12.6). A chip may be built from other chips,
   like an electronic chip with chips inside: its body is a board (body.board: chips, traces, layout) with NO supply anywhere; its data enters only
   through its own pins. Each chip pin is exported from an inner pin: body.wires.<chip in pin>: <instance>.<pin>, body.exposes.<chip out pin>:
   <instance>.<pin>, with the same type. A required inner pin neither traced, exposed nor constant is red (CP-07), as is any supply inside.
   - Body: chips (in Chip mode): make the body a board, the exposure table ("expose this pin as the chip's <name>"), and Edit the inner board,
     which draws it on Board mode's own canvas (palette, traces, mapping sheet) with the supplier pickers disabled; Done brings it back.
   - Make a chip (in Board mode): wired chips chosen on a board become a composite chip draft; on the board they are replaced by one instance
     of it, the supplies staying outside (on the new instance) and the traces to the rest of the board rewired to its pins. */
(function () {
  'use strict';
  const U = window.drishtiUiBoard, CE = U && U.chipEditor, D = U && U.designer, root = document.querySelector('[data-ub-design]');
  if (!CE || !D) { return; }
  const M = U.chipModel, h = U.h, clear = U.clear, $ = (s) => root.querySelector(s);
  const pane = $('[data-ce-body-pane="chips"]'), banner = $('[data-ub-composite]');
  const body = () => CE.doc.body || {};
  const inner = () => (body().board || {}).chips || {};

  function fetchInner() {
    return Promise.all(Object.keys(inner()).map((k) => D.fetchChip(inner()[k].use)));
  }

  // ---- the exposure table ------------------------------------------------------------------------------------------------------------------
  function expose(dir, ref, name) {
    const b = CE.doc.body, map = dir === 'in' ? (b.wires = b.wires || {}) : (b.exposes = b.exposes || {}), pins = CE.doc.pins[dir];
    Object.keys(map).forEach((k) => { if (map[k] === ref) { delete map[k]; delete pins[k]; if (CE.doc.manual.pins) { delete CE.doc.manual.pins[k]; } } });
    if (name) {
      const [inst, pin] = ref.split('.'), c = D.chipDoc(inner()[inst].use) || {}, spec = JSON.parse(JSON.stringify((((c.pins || {})[dir]) || {})[pin] || {}));
      delete spec.of; delete spec['shape-of'];
      if (pins[name] && pins[name].type !== spec.type) { CE.say('The chip pin ' + name + ' is ' + pins[name].type + ' already; ' + ref + ' is ' + spec.type + ': choose another name.', true); return; }
      map[name] = ref;
      pins[name] = spec;
      CE.doc.manual.pins = CE.doc.manual.pins || {};
      if (!CE.doc.manual.pins[name]) { CE.doc.manual.pins[name] = (((c.manual || {}).pins) || {})[pin] || ''; if (!CE.doc.manual.pins[name]) { delete CE.doc.manual.pins[name]; } }
    }
    CE.changed('composite');
  }
  function paint() {
    clear(pane);
    if (!body().board) {
      const make = h('button', { type: 'button', class: 'btn-pill btn-accent', 'data-ce-make-composite': true, text: 'Build the body from chips' });
      make.addEventListener('click', () => {
        if ((body().panels || []).length && !window.confirm('The body\'s panels are replaced by a board of chips. Go on?')) { return; }
        CE.doc.body = { board: { chips: {}, traces: [], layout: { desktop: { columns: 12, areas: [] }, phone: { order: [], collapsed: [] } } }, wires: {}, exposes: {} };
        CE.changed('composite'); paint();
      });
      pane.appendChild(h('p', { class: 'text-muted-d', text: 'A composite chip is a board with no connectors: chips inside, wired by traces, its data entering only through its own pins.' }));
      pane.appendChild(make);
      return;
    }
    const edit = h('button', { type: 'button', class: 'btn-pill btn-accent', 'data-ce-edit-inside': true, text: 'Edit the inner board' });
    edit.addEventListener('click', enter);
    pane.appendChild(h('div', { class: 'ub-row' }, edit, h('span', { class: 'text-muted-d', text: Object.keys(inner()).length + ' chip' + (Object.keys(inner()).length === 1 ? '' : 's') + ' inside, ' + ((body().board.traces || []).length) + ' trace' + ((body().board.traces || []).length === 1 ? '' : 's') + '. No connectors: data enters through the chip\'s pins.' })));
    const t = h('table', { class: 'tbl ub-ce-expose', 'data-ce-expose': true });
    t.appendChild(h('thead', {}, h('tr', {}, ['Inner pin', 'Type', 'Fed by', 'Expose as the chip\'s pin'].map((x) => h('th', { scope: 'col', text: x })))));
    const tb = h('tbody'), traces = body().board.traces || [];
    const probs = M.compositeProblems(CE.doc, (r) => D.chipDoc(r));
    Object.keys(inner()).forEach((inst) => {
      const i = inner()[inst], c = D.chipDoc(i.use);
      if (!c) { tb.appendChild(h('tr', {}, h('td', { colspan: '4', class: 'text-muted-d', text: inst + ' (' + i.use + '): reading its pins…' }))); return; }
      ['in', 'out'].forEach((dir) => Object.keys(((c.pins || {})[dir]) || {}).forEach((pin) => {
        const spec = c.pins[dir][pin] || {}, ref = inst + '.' + pin, map = dir === 'in' ? (body().wires || {}) : (body().exposes || {});
        const as = Object.keys(map).find((k) => map[k] === ref) || '';
        const tr = dir === 'in' ? traces.find((x) => x.to === ref) : traces.find((x) => x.from === ref);
        const fed = dir === 'in' ? (tr ? 'trace from ' + tr.from : (i.controls && pin in i.controls) ? 'constant ' + JSON.stringify(i.controls[pin]) : as ? 'the chip pin ' + as : (spec.default != null ? 'its default' : '')) : (tr ? 'drives ' + tr.to : '');
        const bad = probs.filter((p) => p.path === 'body.board.chips.' + inst + '.' + pin || (as && p.path === 'pins.' + dir + '.' + as));
        const input = h('input', { class: 'studio-in mono' + (bad.length ? ' ub-bad' : ''), value: as, placeholder: tr ? '' : inst + '-' + pin, 'aria-label': 'Expose ' + ref + ' as the chip pin', 'data-ce-expose-pin': ref, 'data-dir': dir });
        input.addEventListener('change', () => expose(dir, ref, input.value.trim()));
        tb.appendChild(h('tr', { class: bad.length ? 'ub-bad-row' : '' }, h('td', { class: 'mono', text: dir + ' ' + ref }), h('td', { text: (spec.type || '') + (spec.required ? ' · required' : '') }),
          h('td', { class: bad.length ? 'ub-bad-text' : '', text: fed || (bad.length ? bad.map((p) => p.message).join('; ') : '—') }), h('td', {}, input)));
      }));
    });
    t.appendChild(tb);
    pane.appendChild(t);
  }

  // ---- editing the inner board on Board mode's canvas ----------------------------------------------------------------------------------------
  let outer = null;
  function enter() {
    outer = { doc: D.doc, name: D.name, yaml: D.yaml };
    const b = JSON.parse(JSON.stringify(body().board));
    D.composite = { chip: CE.name() || 'chip', problems: () => M.compositeProblems(Object.assign({}, CE.doc, { body: Object.assign({}, body(), { board: D.doc }) }), (r) => D.chipDoc(r)).filter((p) => /^body\.board/.test(p.path)).map((p) => Object.assign({}, p, { path: p.path.replace(/^body\.board\./, '') })) };
    root.setAttribute('data-composite', 'true');
    banner.hidden = false;
    $('[data-ub-composite-name]').textContent = CE.ref();
    D.setMode('board');
    D.edit(Object.assign({ rachana: 1, board: (CE.name() || 'chip') + '-inside' }, b));
    D.say('Inside ' + CE.ref() + ': place and wire its chips. There are no connectors here: the chip\'s pins feed them (expose them in Body: chips).');
  }
  function leave(keep) {
    if (keep) {
      const supplied = Object.keys(D.doc.chips || {}).filter((k) => (D.doc.chips[k] || {}).supply);
      if (supplied.length) { D.say('CP-07: a composite chip has no connector: remove the supply of ' + supplied.join(', ') + ' (its data enters through the chip\'s pins).', true); return; }
      CE.doc.body.board = { chips: D.doc.chips || {}, traces: D.doc.traces || [], layout: D.doc.layout || {} };
      const names = Object.keys(CE.doc.body.board.chips);
      ['wires', 'exposes'].forEach((k) => { const m = CE.doc.body[k] || {}; Object.keys(m).forEach((p) => { if (names.indexOf(String(m[p]).split('.')[0]) < 0) { delete m[p]; } }); });
    }
    D.composite = null;
    root.removeAttribute('data-composite');
    banner.hidden = true;
    D.doc = outer.doc; D.name = outer.name; D.yaml = outer.yaml;
    outer = null;
    D.setMode('chip');
    CE.goto('body');
    showChips();
    fetchInner().then(() => { CE.changed('composite'); paint(); });
    D.changed('chips');
    D.setMode('chip');
  }
  $('[data-ub-composite-done]').addEventListener('click', () => leave(true));
  $('[data-ub-composite-cancel]').addEventListener('click', () => leave(false));

  function showChips() {
    root.querySelectorAll('[data-ce-body-tab]').forEach((x) => x.setAttribute('aria-selected', String(x.getAttribute('data-ce-body-tab') === 'chips')));
    root.querySelectorAll('[data-ce-body-pane]').forEach((p) => { p.hidden = p.getAttribute('data-ce-body-pane') !== 'chips'; });
  }
  root.querySelectorAll('[data-ce-body-tab]').forEach((b) => b.addEventListener('click', () => {
    const which = b.getAttribute('data-ce-body-tab');
    root.querySelectorAll('[data-ce-body-tab]').forEach((x) => x.setAttribute('aria-selected', String(x === b)));
    root.querySelectorAll('[data-ce-body-pane]').forEach((p) => { p.hidden = p.getAttribute('data-ce-body-pane') !== which; });
    if (which === 'chips') { fetchInner().then(paint); }
  }));

  // ---- Make a chip, in Board mode ----------------------------------------------------------------------------------------------------------
  const dlg = $('[data-ub-make-dlg]'), list = $('[data-ub-make-list]'), nameIn = $('[data-ub-make-name]');
  $('[data-ub-make-chip]').addEventListener('click', () => {
    const chips = Object.keys(D.doc.chips || {});
    if (chips.length < 1) { D.say('Place and wire the chips first, then make a chip of them.', true); return; }
    clear(list);
    const sel = D.selected, near = new Set(sel ? [sel] : []);
    (D.doc.traces || []).forEach((t) => { const a = t.from.split('.')[0], b = t.to.split('.')[0]; if (near.has(a)) { near.add(b); } if (near.has(b)) { near.add(a); } });
    chips.forEach((k) => {
      const cb = h('input', { type: 'checkbox', value: k, 'data-make-inst': k });
      cb.checked = near.has(k);
      list.appendChild(h('li', {}, h('label', { class: 'ub-field-inline' }, cb, h('b', { class: 'mono', text: k }), h('small', { class: 'text-muted-d', text: ' ' + (D.doc.chips[k] || {}).use }))));
    });
    nameIn.value = nameIn.value || ((D.name || 'my') + '-module');
    dlg.showModal();
  });
  dlg.querySelector('[data-cancel]').addEventListener('click', () => dlg.close());
  dlg.querySelector('form').addEventListener('submit', (e) => {
    e.preventDefault();
    const chosen = Array.prototype.map.call(list.querySelectorAll('input:checked'), (x) => x.value), name = nameIn.value.trim();
    if (!chosen.length) { D.say('Choose the chips to put inside.', true); return; }
    if (!M.NAME.test(name)) { D.say('A chip name is lower-case letters, digits and dashes.', true); return; }
    dlg.close();
    make(chosen, name);
  });
  function make(chosen, name) {
    const mod = M.makeModule(D.doc, chosen, (r) => D.chipDoc(r), name);
    mod.manual.examples = [{ title: 'The module on a board', board: 'chips:\n  m: { use: ' + name + '@1 }\n' }];
    // the outer board: one instance of the new chip, the supplies kept outside, the traces to the rest rewired to its pins
    const doc = D.doc, inst = name, out = {}, supplies = [];
    chosen.forEach((k) => { const i = doc.chips[k] || {}; if (i.supply) { supplies.push([k, i]); } });
    if (supplies.length) {
      const [k, i] = supplies[0];
      out.supply = JSON.parse(JSON.stringify(i.supply));
      out.map = {};
      Object.keys(i.map || {}).forEach((m) => { const [pin, ...rest] = m.split('.'); out.map[[k + '-' + pin].concat(rest).join('.')] = i.map[m]; });
      if (supplies.length > 1) { D.say('Only one supply can feed the new chip: ' + supplies.slice(1).map((s) => s[0]).join(', ') + ' lost theirs (trace them from another chip).', true); }
    }
    const areas = ((doc.layout || {}).desktop || {}).areas || [];
    const mine = areas.filter((a) => chosen.indexOf(a.chip) >= 0);
    chosen.forEach((k) => { delete doc.chips[k]; });
    doc.chips[inst] = Object.assign({ use: name + '@1' }, out);
    doc.traces = (doc.traces || []).filter((t) => !(chosen.indexOf(t.from.split('.')[0]) >= 0 && chosen.indexOf(t.to.split('.')[0]) >= 0)).map((t) => {
      const [fa, fp] = t.from.split('.'), [ta, tp] = t.to.split('.');
      return Object.assign({}, t, { from: chosen.indexOf(fa) >= 0 ? inst + '.' + fa + '-' + fp : t.from, to: chosen.indexOf(ta) >= 0 ? inst + '.' + ta + '-' + tp : t.to });
    });
    if (doc.layout && doc.layout.desktop) {
      const x = Math.min(...mine.map((a) => a.at[0]).concat([1])), y = Math.min(...mine.map((a) => a.at[1]).concat([1]));
      const x2 = Math.max(...mine.map((a) => a.at[0] + (a.span || [4, 4])[0]).concat([x + 6])), y2 = Math.max(...mine.map((a) => a.at[1] + (a.span || [4, 4])[1]).concat([y + 4]));
      doc.layout.desktop.areas = areas.filter((a) => chosen.indexOf(a.chip) < 0).concat([{ chip: inst, at: [x, y], span: [Math.min(12, x2 - x), y2 - y] }]);
    }
    if (doc.layout && doc.layout.phone) { doc.layout.phone.order = (doc.layout.phone.order || []).filter((k) => chosen.indexOf(k) < 0).concat([inst]); }
    D.chips[name + '@1'] = { chip: name, version: 1, state: 'draft', document: mod };
    CE.etag = null;
    CE.load(mod);
    CE.save().then((ok) => {
      D.changed('chips');
      D.say('Made the chip ' + name + '@1 of ' + chosen.join(', ') + (ok ? ' (your draft)' : '') + ': it replaces them on this board, the supply outside. Open it in Chip mode to finish its manual.');
    });
  }

  CE.on((src) => {
    if (src === 'load' || src === 'yaml') { fetchInner().then(() => { CE.recheck(); paint(); }); return; }
    if (src === 'composite' || src === 'server') { paint(); }
  });
})();
