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
/* drishti-renderer's fixture harness (drishti_harness.html): a ViewModel of the parity suite drawn by the public entry of renderer 2.0.0,
   one shot or streamed (a skeleton, then every panel in reverse order, then done), with every drishti:* event counted on window.__rx for
   the browser test. No server: the fixtures are a static file beside this one. */
import { render, VERSION } from '../../render/2.0.0/drishti-render.js';
import { selectElement } from '../drishti-inputs/src/drishti_index.js';

const q = new URLSearchParams(location.search);
const host = document.getElementById('host'), log = document.getElementById('rx-log');
const fixtures = await (await fetch(new URL('drishti_fixtures.json', import.meta.url))).json();
/** The harness's three choices, drishti-inputs' Select each (#rx-case, #rx-behaviours, #rx-theme) after its words. */
const choices = { case: [Object.keys(fixtures), q.get('case') || 'showcase'], behaviours: [['library', 'console', 'host'], q.get('behaviours') || 'library'],
  theme: [['inherit', 'light', 'terminal', 'crimson-dark', 'wallstreet'], q.get('theme') || 'inherit'] };
const pick = {};
document.querySelectorAll('[data-pick]').forEach((at) => {
  const k = at.getAttribute('data-pick'), [values, value] = choices[k];
  pick[k] = selectElement(document, { label: at.textContent, options: values, value: value, search: false }, { id: 'rx-' + k });
  at.after(pick[k]);
});
const streamBtn = document.getElementById('rx-stream');
window.__rx = { events: {}, ready: false, version: VERSION };
['paint', 'ready', 'error', 'navigate', 'row-click', 'select', 'tick'].forEach((n) => host.addEventListener('drishti:' + n, (e) => {
  window.__rx.events[n] = (window.__rx.events[n] || 0) + 1;
  if (n === 'navigate') { e.preventDefault(); }
}));

streamBtn.setAttribute('aria-pressed', q.get('stream') === '1' ? 'true' : 'false');

/** The ViewModel as a stream: a skeleton of stubs, every panel in reverse order, done. */
async function* streamOf(vm) {
  const stubs = vm.panels.map((p) => ({ id: p.id, kind: p.kind, title: p.title, area: p.area, span: p.span, height: p.height }));
  yield { type: 'skeleton', view: Object.assign({}, vm, { panels: stubs }) };
  for (const p of vm.panels.slice().reverse()) { await new Promise((r) => setTimeout(r, 0)); yield { type: 'panel-replace', panel: p }; }
  yield { type: 'done', provenance: vm.provenance };
}

async function draw() {
  window.__rx.ready = false;
  const vm = fixtures[pick.case.value], streamed = streamBtn.getAttribute('aria-pressed') === 'true';
  const t0 = performance.now();
  window.__rx.handle = render(host, streamed ? streamOf(vm) : vm, { behaviours: pick.behaviours.value, theme: pick.theme.value, tables: true });
  await window.__rx.handle.ready;
  await window.__rx.handle.drawCharts();
  log.textContent = 'renderer ' + VERSION + ' · ' + pick.case.value + ' · ' + (streamed ? 'streamed' : 'one shot') + ' · ' + Math.round(performance.now() - t0) + ' ms';
  window.__rx.ready = true;
}

[pick.case, pick.behaviours, pick.theme].forEach((s) => s.addEventListener('change', draw));
streamBtn.addEventListener('click', () => { streamBtn.setAttribute('aria-pressed', streamBtn.getAttribute('aria-pressed') === 'true' ? 'false' : 'true'); draw(); });
await draw();
