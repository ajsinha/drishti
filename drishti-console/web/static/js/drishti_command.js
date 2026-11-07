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
/* The command line: Bloomberg-style type-ahead on the one search box (drishti_search-box.js, GET /api/search?in=command): suggestions
   as you type (debounced 60 ms, stale requests aborted) in an ARIA listbox, driven from the keyboard: ↑/↓ move (↑ in an empty box:
   earlier commands), Tab completes, Enter opens (<GO>), Esc closes. After one entity's id a space offers history ranges (30, 3M, YTD,
   30 DIFF): choosing one opens the entity over time. "/" focuses the command line anywhere (the keymap, drishti_keys.js).
   An entity suggestion carries its kind and id, so a workspace can take it by drag and drop (drishti_workspace.js). */
(function () {
  'use strict';
  var form = document.querySelector('[data-command]');
  if (!form || !window.Drishti.searchBox) { return; }
  var input = form.querySelector('.cmd-input');
  var D = window.Drishti;

  function esc(s) { return String(s == null ? '' : s).replace(/[&<>"]/g, function (c) { return { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[c]; }); }
  function mark(text, q) {
    var t = String(text || ''), word = (q.trim().split(/\s+/).pop() || '').toLowerCase();
    var i = word ? t.toLowerCase().indexOf(word) : -1;
    return i < 0 ? esc(t) : esc(t.slice(0, i)) + '<mark>' + esc(t.slice(i, i + word.length)) + '</mark>' + esc(t.slice(i + word.length));
  }

  var box = D.searchBox(input, {
    where: 'command', list: form.querySelector('.cmd-list'),
    clean: function (q) { return q.replace(/<\s*GO\s*>/ig, ''); },
    row: function (s, q) {
      return '<span class="sg-m">' + esc(s.mnemonic || '') + '</span>' +
        (s.type === 'range' ? '<span class="sg-t">' + esc(s.id) + ' <b class="sg-range">' + esc(s.title) + '</b></span>'
          : '<span class="sg-t">' + mark(s.id || s.title, q) + '</span>') +
        '<span class="sg-s">' + (s.type === 'recent' ? '<span class="sg-type">recent · </span>' : '') + esc(s.subtitle || '') + '</span>';
    },
    go: function (s, i) { go(i); },
    keys: function (e) {
      if (e.key === 'ArrowUp' && box.active < 0 && recalling()) { e.preventDefault(); recall(1); return true; }
      if (e.key === 'ArrowDown' && box.active < 0 && hIdx >= 0) { e.preventDefault(); recall(-1); return true; }
      if (e.key === 'Tab' && !box.list.hidden && box.items.length) { e.preventDefault(); complete(box.active < 0 ? 0 : box.active); return true; }
      if (e.key === 'Enter') { e.preventDefault(); if (box.active >= 0) { go(box.active); } else { form.submit(); } return true; }
      if (e.key === 'Escape') { box.close(); input.blur(); return true; }
      return false;
    }
  });

  function complete(i) {
    var s = box.items[i];
    if (!s) { return false; }
    input.value = s.complete + (s.id ? ' <GO>' : '');
    box.lastQ = null;
    if (s.id) { box.close(); } else { box.fetch(); }      // a range (TRD 1000015 30) completes like an entity
    return true;
  }

  function go(i) {
    var s = box.items[i];
    if (s && s.href) { window.location.href = s.href; return; }          // FORM <pack> [kind]: the pack's search form
    if (s && s.type === 'range') { input.value = s.complete; form.submit(); return; }   // TRD 1000015 30: the history page, through <GO>
    if (s && s.id) {
      window.location.href = '/v/' + encodeURIComponent(s.kind) + '/' + encodeURIComponent(s.id);
      return;
    }
    if (s) { complete(i); return; }
    form.submit();
  }

  // History: ↑ in an empty box (or while stepping through history, with no suggestion picked) recalls earlier commands,
  // newest first; ↓ goes forward again. The server keeps them, so they follow you between browsers.
  var history = null, hIdx = -1;
  function recall(step) {
    function show() {
      if (!history.length) { return; }
      hIdx = Math.max(-1, Math.min(history.length - 1, hIdx + step));
      input.value = hIdx < 0 ? '' : history[hIdx] + ' <GO>';
      box.close();
    }
    if (history) { show(); return; }
    fetch('/api/history', { headers: { Accept: 'application/json' } })
      .then(function (r) { return r.ok ? r.json() : []; })
      .then(function (h) { history = Array.isArray(h) ? h : []; show(); })
      .catch(function () { history = []; });
  }
  function recalling() { return input.value.trim() === '' || hIdx >= 0; }

  input.addEventListener('input', function () { hIdx = -1; });
  input.addEventListener('focus', function () { input.select(); });
  /** For a workspace: a suggestion dragged into a pane opens there; one clicked without dragging opens as usual. */
  D.command = { go: go, close: function () { box.close(); } };
  /** A menu item to finish (EXP CPTY …, ECON …): its words go in the command line, ready for the rest, in place of opening a page. */
  function fill(text) {
    input.value = String(text || '').replace(/\s*$/, ' ');
    input.focus();
    input.setSelectionRange(input.value.length, input.value.length);
    box.fetch();
  }
  document.addEventListener('click', function (e) {
    var a = e.target.closest('a[data-fill]');
    if (!a || e.ctrlKey || e.metaKey || e.shiftKey) { return; }
    e.preventDefault();
    var dd = a.closest('.dropdown');
    var btn = dd && dd.querySelector('[data-bs-toggle="dropdown"]');
    if (btn && window.bootstrap && window.bootstrap.Dropdown) { window.bootstrap.Dropdown.getOrCreateInstance(btn).hide(); }
    fill(a.getAttribute('data-fill'));
  });
  var asked = new URLSearchParams(location.search).get('cmd');
  if (asked && location.pathname === '/t') { fill(asked); }
  D.keys.register({ id: 'command.focus', keys: ['/'], scope: 'global', group: 'Command line', description: 'Focus the command line, from anywhere',
    run: function () { input.focus(); } });
  D.keys.describe({ id: 'command.keys', keys: ['ArrowUp', 'ArrowDown', 'Tab', 'Enter', 'Escape'], scope: 'panel', area: 'command-line', group: 'Command line',
    label: '↑ ↓ · Tab · Enter · Esc', description: 'In the command line: move through the suggestions (↑ in an empty box: earlier commands), complete, open (<GO>), close' });
})();
