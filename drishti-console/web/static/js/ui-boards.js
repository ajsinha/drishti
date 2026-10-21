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
/* UI Board P4: what every Boards page shares (the rail's Keys list and ? for it, g b for My boards) and the My boards list (GET /ui/api/boards:
   yours, the ones shared with you, and a workspace's when one is named). */
(function () {
  'use strict';
  const U = window.drishtiUiBoard || {}, h = U.h, clear = U.clear;
  const keys = document.getElementById('biKeys'), keysBtn = document.querySelector('[data-bi-keys]');
  if (keysBtn && keys) { keysBtn.addEventListener('click', () => keys.showModal()); }
  let pendingG = 0;
  window.addEventListener('keydown', (e) => {
    if (e.ctrlKey || e.metaKey || e.altKey) { return; }
    const t = e.target, tag = (t.tagName || '').toLowerCase();
    if (tag === 'input' || tag === 'textarea' || tag === 'select' || t.isContentEditable) { return; }
    if (e.key === '?' && keys) { e.preventDefault(); if (!keys.open) { keys.showModal(); } } else if (pendingG && Date.now() - pendingG < 1500) {
      pendingG = 0;
      const to = { b: '/build', n: '/build/board/new', c: '/build/chip/new' }[e.key];
      if (to) { e.preventDefault(); location.href = to; }
    } else if (e.key === 'g') { pendingG = Date.now(); }
  });

  const page = document.querySelector('[data-ub-boards]');
  if (!page || !h) { return; }
  const tb = page.querySelector('[data-ub-list] tbody'), status = page.querySelector('[data-ub-status]'), ws = page.querySelector('[data-ub-ws]');
  function load() {
    const q = ws.value.trim() ? '?workspace=' + encodeURIComponent(ws.value.trim()) : '';
    status.textContent = 'Loading…';
    fetch('/ui/api/boards' + q, { credentials: 'same-origin' }).then((r) => r.json().then((b) => ({ ok: r.ok, b }))).then(({ ok, b }) => {
      if (!ok) { status.textContent = b.code + ': ' + b.detail; return; }
      const list = Array.isArray(b) ? b : (b.boards || []);
      clear(tb);
      list.forEach((x) => {
        const q2 = x.workspace ? '?workspace=' + encodeURIComponent(x.workspace) : (x.access === 'shared' && x.owner ? '?owner=' + encodeURIComponent(x.owner) : '');
        const where = x.workspace ? 'Workspace ' + x.workspace : (x.access === 'shared' ? 'Shared by ' + x.owner : 'Mine');
        const design = x.access === 'shared' ? null : h('a', { class: 'btn-pill btn-ghost', href: '/build/board/' + encodeURIComponent(x.board) + q2, text: 'Design' });
        tb.appendChild(h('tr', { 'data-board': x.board }, h('td', {}, h('a', { href: '/ui/b/' + encodeURIComponent(x.board) + q2, class: 'mono', text: x.board })), h('td', { text: x.title || '' }),
          h('td', { text: where }), h('td', { class: 'num', text: String(x.version || '') }), h('td', { class: 'num', text: String(x.chips == null ? '' : x.chips) }), h('td', {}, design)));
      });
      status.textContent = list.length ? list.length + ' board' + (list.length === 1 ? '' : 's') : 'No boards yet: New board opens the designer in Board mode.';
    }).catch(() => { status.textContent = 'The console could not be reached.'; });
  }
  ws.addEventListener('change', load);
  load();
})();
