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
/* UI Board P4b, Chip mode's Test bench (UI_BOARD.md 12.6). A chip is a pure function that paints UI, so the bench gives it input and looks:
   - sample data per in pin: rows or a document as JSON (made up from the pin's columns to start), a scalar typed; or "Use a supplier": the
     first manual example rendered through POST /ui/render as you (your masks), its rows taken as the samples;
   - the chip painted at once by the board viewer's own painter (ui-board-paint.js) from the samples, in the theme chosen (every theme of the
     console, or all of them in turn) and on a phone's width; what the person does shows on the out pins;
   - the manual's examples run as tests: the server's check (CP-05, the board check over each) and a real render of each, pass or fail;
   - the pins the body never reads and the out pins it never drives (CP-03).
   A composite chip (its body a board of chips, no connectors) is painted chip by chip, each inner chip fed through the wires of its pins. */
(function () {
  'use strict';
  const U = window.drishtiUiBoard, CE = U && U.chipEditor, root = document.querySelector('[data-ub-design]');
  if (!CE) { return; }
  const h = U.h, clear = U.clear, $ = (s) => root.querySelector(s), M = U.chipModel;
  const THEMES = ['terminal', 'light', 'wallstreet', 'blue', 'green', 'crimson', 'crimson-dark', 'terminal-green', 'terminal-crimson', 'terminal-blue'];
  const bench = $('[data-ce-bench]');
  const samples = {}, outs = {};
  let renders = [], themeBefore = null;

  const ins = () => ((CE.doc.pins || {}).in) || {};
  function fake(type, i, col) {
    switch (type) {
      case 'id': return String(1000 + i);
      case 'date': case 'datetime': return '2026-0' + (1 + (i % 9)) + '-1' + (i % 10);
      case 'measure': case 'number': return Math.round((i + 1) * 1234.5 * (col.length % 3 + 1)) / 10;
      case 'bool': return i % 2 === 0;
      default: return (col || 'item') + ' ' + String.fromCharCode(65 + (i % 26));
    }
  }
  function sampleOf(spec) {
    const shape = spec.shape || {};
    const row = (i) => { const r = {}; Object.keys(shape).forEach((c) => { const s = shape[c] || {}; r[c] = s.many ? [{ name: c.toUpperCase() + '_A', value: fake('measure', i, c) }, { name: c.toUpperCase() + '_B', value: fake('measure', i + 3, c) }] : fake(s.type, i, c); }); return r; };
    if (spec.type === 'rowset') { return [0, 1, 2, 3, 4].map(row); }
    if (spec.type === 'document') { return Object.keys(shape).length ? row(0) : { id: '1000', title: 'Sample', fields: {} }; }
    return spec.default !== undefined ? spec.default : (spec.type === 'enum' ? (spec.values || [])[0] : null);
  }

  // ---- the samples ------------------------------------------------------------------------------------------------------------------------
  const sampleBox = $('[data-ce-samples]');
  function paintSamples() {
    clear(sampleBox);
    Object.keys(ins()).forEach((p) => {
      const spec = ins()[p] || {}, big = spec.type === 'rowset' || spec.type === 'document';
      if (!(p in samples)) { samples[p] = sampleOf(spec); }
      const el = big ? h('textarea', { class: 'studio-in mono', rows: '5', 'aria-label': 'Sample data of ' + p, 'data-ce-sample': p }) : h('input', { class: 'studio-in', 'aria-label': 'Sample value of ' + p, 'data-ce-sample': p });
      el.value = big ? JSON.stringify(samples[p], null, 1) : (samples[p] == null ? '' : String(samples[p]));
      const note = h('small', { class: 'ub-ce-msg' });
      el.addEventListener('input', () => {
        if (big) {
          try { samples[p] = JSON.parse(el.value || 'null'); note.textContent = ''; el.classList.remove('ub-bad'); } catch (e) { note.textContent = 'Not JSON: ' + e.message; el.classList.add('ub-bad'); return; }
        } else { samples[p] = el.value === '' ? null : (/^-?\d+(\.\d+)?$/.test(el.value) && (spec.type === 'number' || spec.type === 'measure') ? Number(el.value) : el.value); }
        paint();
      });
      sampleBox.appendChild(h('label', { class: 'ub-field' }, h('span', { class: 'ub-field-l mono', text: p + ' · ' + (spec.type || '') + (spec.required ? ' · required' : '') }), el, note));
    });
    if (!Object.keys(ins()).length) { sampleBox.appendChild(h('p', { class: 'text-muted-d', text: 'The chip has no in pins yet.' })); }
  }
  $('[data-ce-reset-samples]').addEventListener('click', () => { Object.keys(samples).forEach((k) => delete samples[k]); paintSamples(); paint(); });

  /** "Use a supplier": the first example rendered as you, its chip's pins taken as the samples. */
  $('[data-ce-supplier]').addEventListener('click', () => {
    const ex = ((CE.doc.manual || {}).examples || [])[0];
    if (!ex || !ex.board) { CE.say('Write an example first (the Manual step): its supplier fills the bench.', true); return; }
    CE.save().then((ok) => {
      if (!ok) { return null; }
      return CE.api('POST', '/ui/api-yaml/load', { yaml: ex.board }).then((r) => {
        const board = Object.assign({ rachana: 1, board: 'bench-supplier' }, r.document || {});
        const mine = Object.keys(board.chips || {}).find((k) => String((board.chips[k] || {}).use) === CE.ref());
        let got = null;
        return U.stream('/ui/api/render', { method: 'POST', headers: { 'Content-Type': 'application/json', Accept: 'text/event-stream' }, body: JSON.stringify({ board, device: 'desktop', stream: 'once' }) }, (ev, d) => {
          if (ev === 'chip' && d && d.instance === mine && d.viewmodel) { got = d.viewmodel; }
        }).then(() => {
          if (!got) { CE.say('The supplier sent no rows for ' + (mine || 'the chip') + ' (check the example and your access).', true); return; }
          Object.keys(got.pins || {}).forEach((k) => { samples[k] = got.pins[k]; });
          paintSamples(); paint();
          CE.say('The bench holds real data from the example\'s supplier, as you see it (your masks).');
        });
      });
    }).catch(CE.fail);
  });

  // ---- the painting -----------------------------------------------------------------------------------------------------------------------
  const preview = $('[data-ce-preview]'), outBox = $('[data-ce-outs]');
  function vmOf(doc, pinValues, title) { return { chip: doc.chip + '@' + doc.version, title: title || doc.title || doc.chip, body: doc.body || {}, pins: pinValues, controls: {}, keys: null }; }
  function paintOuts() {
    clear(outBox);
    Object.keys(((CE.doc.pins || {}).out) || {}).forEach((o) => outBox.appendChild(h('li', { 'data-out': o }, h('b', { class: 'mono', text: o }), ' = ', h('span', { class: 'mono', text: o in outs ? JSON.stringify(outs[o]) : '(empty until the person acts)' }))));
  }
  function paint() {
    clear(preview);
    const onSelect = (pin, value) => { outs[pin] = value; paintOuts(); };
    const body = CE.doc.body || {};
    if (body.board) {                                       // a composite chip: each inner chip fed through the wires
      const inner = body.board.chips || {}, grid = h('div', { class: 'ub-ce-composite' });
      Object.keys(inner).forEach((inst) => {
        const pins = {};
        Object.keys(body.wires || {}).forEach((mp) => { const [i, p] = String(body.wires[mp]).split('.'); if (i === inst) { pins[p] = samples[mp]; } });
        const doc = U.designer.chipDoc(inner[inst].use) || {};
        const host = h('div', { class: 'ub-body', 'data-instance': inst });
        grid.appendChild(h('section', { class: 'bi-vis ub-chip', 'data-instance': inst }, h('header', { class: 'bi-vis-head' }, h('h3', { class: 'bi-vis-title', text: inst + ' · ' + inner[inst].use })), host));
        U.paint(host, vmOf(doc, pins, inst), { instance: inst, onSelect: (pin, v) => { const out = Object.keys(body.exposes || {}).find((o) => body.exposes[o] === inst + '.' + pin); if (out) { onSelect(out, v); } } });
      });
      preview.appendChild(grid);
    } else {
      const host = h('div', { class: 'ub-body', 'data-instance': 'bench' });
      preview.appendChild(h('section', { class: 'bi-vis ub-chip', 'data-instance': 'bench' }, h('header', { class: 'bi-vis-head' }, h('h3', { class: 'bi-vis-title', text: CE.doc.title || CE.doc.chip || 'Chip' })), host));
      try { U.paint(host, vmOf(CE.doc, Object.assign({}, samples)), { instance: 'bench', onSelect }); } catch (e) { host.appendChild(h('p', { class: 'ub-bad-text', text: 'The chip cannot paint these samples: ' + e.message })); }
    }
    paintOuts(); usage();
  }
  CE.paintBench = paint;
  function usage() {
    const u = M.usage(CE.doc), box = $('[data-ce-usage]');
    clear(box);
    box.appendChild(h('li', { class: u.unused.length ? 'bi-warn' : 'text-muted-d', 'data-ce-unused': u.unused.join(','), text: u.unused.length ? 'In pins the body never reads: ' + u.unused.join(', ') : 'Every in pin is read by the body.' }));
    box.appendChild(h('li', { class: u.undriven.length ? 'ub-bad-text' : 'text-muted-d', 'data-ce-undriven': u.undriven.join(','), text: u.undriven.length ? 'CP-03: out pins nothing drives: ' + u.undriven.join(', ') : 'Every out pin is driven.' }));
  }

  // ---- themes and the phone ---------------------------------------------------------------------------------------------------------------
  const themeSel = $('[data-ce-theme]');
  THEMES.forEach((t) => themeSel.appendChild(h('option', { value: t, text: t })));
  function setTheme(t) {
    if (themeBefore == null) { themeBefore = document.documentElement.getAttribute('data-theme') || 'terminal'; }
    document.documentElement.setAttribute('data-theme', t);
    document.dispatchEvent(new CustomEvent('drishti:theme', { detail: t }));
    themeSel.value = t;
    preview.setAttribute('data-bench-theme', t);
    paint();
  }
  function restoreTheme() { if (themeBefore != null) { document.documentElement.setAttribute('data-theme', themeBefore); document.dispatchEvent(new CustomEvent('drishti:theme', { detail: themeBefore })); themeBefore = null; } }
  themeSel.addEventListener('change', () => setTheme(themeSel.value));
  $('[data-ce-all-themes]').addEventListener('click', () => {
    const done = $('[data-ce-themes-done]');
    clear(done);
    let i = 0;
    (function next() {
      if (i >= THEMES.length) { CE.say('Painted in all ' + THEMES.length + ' themes.'); return; }
      const t = THEMES[i++];
      setTheme(t);
      const ok = !!preview.querySelector('.ub-body *');
      done.appendChild(h('li', { class: ok ? '' : 'ub-bad-text', 'data-theme-done': t, text: t + (ok ? ' ✓' : ' · nothing painted') }));
      setTimeout(next, 120);
    })();
  });
  $('[data-ce-phone]').addEventListener('click', (e) => {
    const b = e.currentTarget, on = b.getAttribute('aria-pressed') !== 'true';
    b.setAttribute('aria-pressed', String(on));
    preview.classList.toggle('ub-ce-phone', on);
    paint();
  });

  // ---- the examples as tests --------------------------------------------------------------------------------------------------------------
  $('[data-ce-run]').addEventListener('click', () => {
    renders.forEach((r) => r.close()); renders = [];
    clear(bench);
    const examples = ((CE.doc.manual || {}).examples) || [];
    if (!examples.length) { CE.say('The manual has no examples: add one in the Manual step (CP-01 needs one).', true); return; }
    CE.say('Saving your draft, then running ' + examples.length + ' example' + (examples.length === 1 ? '' : 's') + '…');
    CE.save().then((ok) => {
      if (!ok) { return; }
      CE.serverCheck();
      return fetch('/ui/api/chips/check', { method: 'POST', credentials: 'same-origin', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(CE.doc) }).then((r) => r.json()).then((chk) => {
        examples.forEach((ex, k) => {
          const fails = ((chk && chk.problems) || []).filter((p) => p.rule === 'CP-05' && String(p.path).indexOf('manual.examples[' + k + ']') === 0);
          const host = h('div', { class: 'ub-grid ub-bench-grid', 'data-example': String(k) }), line = h('p', { class: 'bi-msg', role: 'status' });
          const verdict = h('span', { class: fails.length ? 'ub-bad-text' : 'ub-ok', 'data-result': fails.length ? 'fail' : 'pass', text: fails.length ? 'fails CP-05: ' + fails.map((p) => p.message).join('; ') : 'passes the board check' });
          bench.appendChild(h('section', { class: 'ub-bench-ex', 'data-test': String(k) }, h('h3', {}, (ex.title || 'Example ' + (k + 1)) + ' · ', verdict), line, host));
          if (fails.length) { return; }
          CE.api('POST', '/ui/api-yaml/load', { yaml: ex.board || '' }).then((eb) => {
            const board = Object.assign({ rachana: 1, board: 'bench-' + (k + 1) }, eb.document || {});
            renders.push(U.render(host, { board }, { device: 'desktop', status: line }));
          }).catch((e) => { line.textContent = e.code + ': ' + e.detail; });
        });
      });
    }).catch(CE.fail);
  });

  CE.on((src) => {
    if (src === 'step:bench') { paintSamples(); paint(); return; }
    if (/^step:/.test(src)) { restoreTheme(); return; }
    if (CE.step !== 'bench') { return; }
    if (src === 'pins' || src === 'load' || src === 'yaml') { paintSamples(); }
    if (src !== 'server') { paint(); }
  });
  root.addEventListener('ub:mode', (e) => { if (e.detail !== 'chip') { restoreTheme(); } });
})();
