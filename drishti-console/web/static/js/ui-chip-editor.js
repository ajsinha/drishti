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
/* UI Board P4, the designer's Chip mode as a placeholder (UI_BOARD.md section 10, "Chip authoring"): P4b's chip designer replaces the body of
   this file (the manual form, the pin table, the body editor) and keeps its entry points: the mode (drishtiUiBoard.designer.setMode('chip'),
   /build/chip/<name>), the shared YAML tab (the chip's document while in Chip mode), the palette (designer.onChipPick) and the test bench.
   For now: one YAML document (manual, pins, body): Open my draft (GET /ui/api/chips/<name>/draft), a store chip picked in the palette opens as
   a copy (its YAML, renamed, version 1), Save draft (PUT, compare-and-set),
   Run examples (each manual example made a board and rendered through POST /ui/api/render, as you: your own draft is usable on your own
   boards), Submit for review (POST /ui/api/chips/<name>/<version>/submit: the publish check runs there, its problems listed here). */
(function () {
  'use strict';
  const U = window.drishtiUiBoard, root = document.querySelector('[data-ub-design]');
  if (!root || !U || !U.designer) { return; }
  const D = U.designer, h = U.h, clear = U.clear, $ = (s) => root.querySelector(s);
  const nameIn = $('[data-ce-name]'), text = $('[data-ub-yaml]'), status = $('[data-ub-status]'), bench = $('[data-ce-bench]');
  let etag = null, renders = [];
  const say = (t, bad) => { status.textContent = t; status.classList.toggle('bi-bad', !!bad); };
  const name = () => nameIn.value.trim();
  function call(method, url, body, headers, raw) {
    return fetch(url, { method, credentials: 'same-origin', headers: Object.assign({ 'Content-Type': raw ? 'application/yaml' : 'application/json' }, headers || {}), body: body == null ? undefined : (raw ? body : JSON.stringify(body)) })
      .then((r) => r.text().then((t) => { let b = null; try { b = t ? JSON.parse(t) : null; } catch (e) { b = { detail: t }; } if (!r.ok) { b = b || {}; b.code = b.code || 'HTTP-' + r.status; throw b; } return { b, etag: r.headers.get('ETag') }; }));
  }
  const fail = (e) => say(e.code + ': ' + (e.detail || '') + (e.problems && e.problems.length ? ' · ' + e.problems.map((p) => p.rule + ' ' + p.message).join(' · ') : ''), true);

  $('[data-ce-open]').addEventListener('click', () => {
    if (!name()) { say('Name the chip first.', true); return; }
    call('GET', '/ui/api/chips/' + encodeURIComponent(name()) + '/draft').then(({ b, etag: e }) => { etag = e; text.value = b.yaml || ''; say('Opened your draft of ' + name() + '.'); }).catch(fail);
  });
  D.onChipPick = (from) => {
    const m = /^([a-z0-9-]+)@(\d+)$/.exec(from || '');
    if (!m) { return; }
    call('GET', '/ui/api/chips/' + m[1] + '/' + m[2]).then(({ b }) => {
      const mine = name() || m[1] + '-mine';
      nameIn.value = mine;
      text.value = String(b.yaml || '').replace(/^chip:\s*.*$/m, 'chip: ' + mine).replace(/^version:\s*.*$/m, 'version: 1');
      etag = null;
      say('A copy of ' + from + ' as ' + mine + '@1 is in the YAML / JSON tab: Save draft keeps it.');
      D.tab('yaml');
    }).catch(fail);
  };
  $('[data-ce-save]').addEventListener('click', () => {
    if (!name()) { say('Name the chip first.', true); return; }
    call('PUT', '/ui/api/chips/' + encodeURIComponent(name()) + '/draft', text.value, etag ? { 'If-Match': etag } : {}, true)
      .then(({ etag: e }) => { etag = e || etag; say('Saved your draft of ' + name() + '.'); }).catch((e) => { if (e.code === 'DRS-5057') { say('DRS-5057: changed elsewhere. Open my draft to reload it.', true); } else { fail(e); } });
  });
  $('[data-ce-submit]').addEventListener('click', () => {
    const v = /^version:\s*(\d+)/m.exec(text.value);
    if (!name() || !v) { say('The document needs chip: and version:.', true); return; }
    call('POST', '/ui/api/chips/' + encodeURIComponent(name()) + '/' + v[1] + '/submit', {}).then(() => say('Submitted ' + name() + '@' + v[1] + ' for review.')).catch(fail);
  });
  $('[data-ce-run]').addEventListener('click', () => {
    renders.forEach((r) => r.close()); renders = [];
    clear(bench);
    call('POST', '/ui/api-yaml/load', { yaml: text.value }).then(({ b }) => {
      const doc = b.document || {}, examples = ((doc.manual || {}).examples) || [];
      if (!examples.length) { say('The manual has no examples: add one under manual.examples (CP-01 needs one).', true); return; }
      say('Running ' + examples.length + ' example' + (examples.length === 1 ? '' : 's') + '…');
      examples.forEach((ex, k) => {
        const host = h('div', { class: 'ub-grid ub-bench-grid', 'data-example': String(k) }), line = h('p', { class: 'bi-msg', role: 'status' });
        bench.appendChild(h('section', { class: 'ub-bench-ex' }, h('h3', { text: ex.title || 'Example ' + (k + 1) }), line, host));
        call('POST', '/ui/api-yaml/load', { yaml: ex.board || '' }).then(({ b: eb }) => {
          const board = Object.assign({ rachana: 1, board: 'bench-' + (k + 1) }, eb.document || {});
          renders.push(U.render(host, { board }, { device: 'desktop', status: line }));
        }).catch((e) => { line.textContent = e.code + ': ' + e.detail; });
      });
    }).catch(fail);
  });
})();
