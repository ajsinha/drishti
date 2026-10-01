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
/* The command line: Bloomberg-style type-ahead. Suggestions are fetched as you type (debounced 60 ms,
   stale requests aborted), shown in an ARIA listbox, and driven from the keyboard:
   ↑/↓ move, Tab completes, Enter opens (<GO>), Esc closes. "/" focuses the command line anywhere. */
(function () {
  'use strict';
  var form = document.querySelector('[data-command]');
  if (!form) { return; }
  var input = form.querySelector('.cmd-input');
  var list = form.querySelector('.cmd-list');
  var items = [], active = -1, timer = 0, ctrl = null, lastQ = null;

  function esc(s) { return String(s == null ? '' : s).replace(/[&<>"]/g, function (c) { return { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[c]; }); }
  function mark(text, q) {
    var t = String(text || ''), word = (q.trim().split(/\s+/).pop() || '').toLowerCase();
    var i = word ? t.toLowerCase().indexOf(word) : -1;
    return i < 0 ? esc(t) : esc(t.slice(0, i)) + '<mark>' + esc(t.slice(i, i + word.length)) + '</mark>' + esc(t.slice(i + word.length));
  }
  function clean(q) { return q.replace(/<\s*GO\s*>/ig, ''); }

  function open(show) {
    list.hidden = !show;
    input.setAttribute('aria-expanded', show ? 'true' : 'false');
    if (!show) { active = -1; input.removeAttribute('aria-activedescendant'); }
  }

  function render(q) {
    if (!items.length) {
      list.innerHTML = q.trim() ? '<li class="sg-empty" role="option" aria-disabled="true">No matches for “' + esc(q.trim()) + '”</li>' : '';
      open(!!q.trim());
      return;
    }
    list.innerHTML = items.map(function (s, i) {
      return '<li role="option" id="sg-' + i + '" data-i="' + i + '" aria-selected="false">' +
        '<span class="sg-m">' + esc(s.mnemonic || '') + '</span>' +
        '<span class="sg-t">' + mark(s.id || s.title, q) + '</span>' +
        '<span class="sg-s">' + (s.type === 'recent' ? '<span class="sg-type">recent · </span>' : '') + esc(s.subtitle || '') + '</span></li>';
    }).join('');
    open(true);
  }

  function select(i) {
    var nodes = list.querySelectorAll('[role="option"][data-i]');
    if (!nodes.length) { return; }
    active = (i + nodes.length) % nodes.length;
    nodes.forEach(function (n, k) { n.setAttribute('aria-selected', k === active ? 'true' : 'false'); });
    input.setAttribute('aria-activedescendant', 'sg-' + active);
    nodes[active].scrollIntoView({ block: 'nearest' });
  }

  function fetchSuggestions() {
    var q = clean(input.value);
    if (q === lastQ) { return; }
    lastQ = q;
    if (ctrl) { ctrl.abort(); }
    ctrl = window.AbortController ? new AbortController() : null;
    fetch('/api/suggest?q=' + encodeURIComponent(q), { signal: ctrl ? ctrl.signal : undefined, headers: { Accept: 'application/json' } })
      .then(function (r) { return r.ok ? r.json() : []; })
      .then(function (data) { items = Array.isArray(data) ? data : []; active = -1; render(q); })
      .catch(function () { /* aborted or offline: keep the current list */ });
  }

  function complete(i) {
    var s = items[i];
    if (!s) { return false; }
    input.value = s.complete + (s.id ? ' <GO>' : '');
    lastQ = null;
    if (s.id) { open(false); } else { fetchSuggestions(); }
    return true;
  }

  function go(i) {
    var s = items[i];
    if (s && s.id) {
      window.location.href = '/v/' + encodeURIComponent(s.kind) + '/' + encodeURIComponent(s.id);
      return;
    }
    if (s) { complete(i); return; }
    form.submit();
  }

  input.addEventListener('input', function () { clearTimeout(timer); timer = setTimeout(fetchSuggestions, 60); });
  input.addEventListener('focus', function () { input.select(); lastQ = null; fetchSuggestions(); });
  input.addEventListener('keydown', function (e) {
    if (e.key === 'ArrowDown') { e.preventDefault(); if (list.hidden) { fetchSuggestions(); } else { select(active + 1); } }
    else if (e.key === 'ArrowUp') { e.preventDefault(); select(active - 1); }
    else if (e.key === 'Tab' && !list.hidden && items.length) { e.preventDefault(); complete(active < 0 ? 0 : active); }
    else if (e.key === 'Enter') { e.preventDefault(); if (active >= 0) { go(active); } else { form.submit(); } }
    else if (e.key === 'Escape') { open(false); input.blur(); }
  });
  list.addEventListener('mousedown', function (e) {
    var li = e.target.closest('[data-i]');
    if (li) { e.preventDefault(); go(+li.getAttribute('data-i')); }
  });
  input.addEventListener('blur', function () { setTimeout(function () { open(false); }, 120); });
  document.addEventListener('keydown', function (e) {
    var tag = (e.target.tagName || '').toLowerCase();
    if (e.key === '/' && tag !== 'input' && tag !== 'textarea') { e.preventDefault(); input.focus(); }
  });
})();
