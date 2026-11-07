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
/* One search box (2.9.11.e): the command line's type-ahead, Help's search and the global search (Alt+K) are this component over one
   endpoint, GET /api/search?in=command|help|all (core/search.py). It asks as you type (debounced, a stale answer aborted), shows the
   answer in an ARIA listbox (grouped by kind when asked), and is driven from the keyboard inside its own field: ↑ ↓ move, Enter opens,
   Esc closes. A field's keys are its own: the keymap never acts while you type (drishti_keys.js).

     var box = Drishti.searchBox(input, { where: 'all', list: ul, grouped: true, row: fn(item, q) -> html, keys: fn(e, box) -> handled,
                                          go: fn(item, i, box), enter: fn(box) })                                            */
(function () {
  'use strict';
  var D = window.Drishti;
  if (D.searchBox) { return; }
  var seq = 0;

  function esc(s) { return String(s == null ? '' : s).replace(/[&<>"]/g, function (c) { return { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[c]; }); }
  function mark(text, q) {
    var t = String(text || ''), word = (q.trim().split(/\s+/).pop() || '').toLowerCase();
    var i = word ? t.toLowerCase().indexOf(word) : -1;
    return i < 0 ? esc(t) : esc(t.slice(0, i)) + '<mark>' + esc(t.slice(i, i + word.length)) + '</mark>' + esc(t.slice(i + word.length));
  }
  function defaultRow(s, q) {
    return '<span class="sg-m">' + esc(s.mnemonic || '') + '</span><span class="sg-t">' + mark(s.id || s.title, q) + '</span>' +
      '<span class="sg-s">' + esc(s.subtitle || '') + '</span>';
  }
  function hrefOf(s) {
    if (s.href) { return s.href; }
    if (s.id && s.kind && s.type !== 'range') { return '/v/' + encodeURIComponent(s.kind) + '/' + encodeURIComponent(s.id); }
    return '';
  }

  function searchBox(input, opts) {
    opts = opts || {};
    var where = opts.where || 'all';
    var list = opts.list;
    if (!list) {
      list = document.createElement('ul');
      list.className = 'cmd-list drishti_search-list';
      list.setAttribute('role', 'listbox');
      list.setAttribute('aria-label', opts.label || 'Results');
      list.hidden = true;
      input.insertAdjacentElement('afterend', list);
    }
    if (!list.id) { list.id = 'drishtiSearch' + (++seq); }
    var pre = list.id + '-o';
    input.setAttribute('role', 'combobox');
    input.setAttribute('aria-autocomplete', 'list');
    input.setAttribute('aria-expanded', 'false');
    input.setAttribute('aria-controls', list.id);
    input.setAttribute('autocomplete', 'off');
    var box = { input: input, list: list, items: [], active: -1, lastQ: null, answer: null };
    var timer = 0, ctrl = null;
    var clean = opts.clean || function (q) { return q; };

    function open(show) {
      list.hidden = !show;
      input.setAttribute('aria-expanded', show ? 'true' : 'false');
      if (!show) { box.active = -1; input.removeAttribute('aria-activedescendant'); }
    }
    function row(s, i, q) {
      return '<li role="option" id="' + pre + i + '" data-i="' + i + '" data-group="' + esc(s.group || '') + '" aria-selected="false"' +
        (s.id && s.kind && s.type !== 'range' ? ' data-ref data-kind="' + esc(s.kind) + '" data-id="' + esc(s.id) + '" data-label="' + esc(s.id) + '"' : '') + '>' +
        (opts.row || defaultRow)(s, q) + '</li>';
    }
    function render(q) {
      if (!box.items.length) {
        list.innerHTML = q.trim() ? '<li class="sg-empty" role="option" aria-disabled="true">No matches for “' + esc(q.trim()) + '”</li>' : '';
        open(!!q.trim());
        return;
      }
      if (opts.grouped && box.answer && box.answer.groups) {
        var i = 0;
        list.innerHTML = box.answer.groups.map(function (gr) {
          return '<li class="sg-group" role="presentation" data-search-group="' + esc(gr.group) + '">' + esc(gr.label) + '</li>' +
            gr.items.map(function (s) { return row(s, i++, q); }).join('');
        }).join('');
      } else {
        list.innerHTML = box.items.map(function (s, i) { return row(s, i, q); }).join('');
      }
      open(true);
    }
    box.select = function (i) {
      var nodes = list.querySelectorAll('[role="option"][data-i]');
      if (!nodes.length) { return; }
      box.active = (i + nodes.length) % nodes.length;
      nodes.forEach(function (n, k) { n.setAttribute('aria-selected', k === box.active ? 'true' : 'false'); });
      input.setAttribute('aria-activedescendant', pre + box.active);
      nodes[box.active].scrollIntoView({ block: 'nearest' });
    };
    box.fetch = function () {
      var q = clean(input.value);
      if (q === box.lastQ) { return; }
      box.lastQ = q;
      if (ctrl) { ctrl.abort(); }
      if (opts.minChars && q.trim().length < opts.minChars) { box.items = []; box.answer = null; open(false); return; }
      ctrl = window.AbortController ? new AbortController() : null;
      fetch('/api/search?in=' + encodeURIComponent(where) + '&q=' + encodeURIComponent(q), { signal: ctrl ? ctrl.signal : undefined, headers: { Accept: 'application/json' } })
        .then(function (r) { return r.ok ? r.json() : null; })
        .then(function (data) {
          box.answer = data;
          box.items = data && Array.isArray(data.items) ? data.items : [];
          box.active = -1;
          render(q);
          list.setAttribute('data-searched', q);
        })
        .catch(function () { /* aborted or offline: keep the current list */ });
    };
    box.refresh = function () { box.lastQ = null; box.fetch(); };
    box.close = function () { open(false); };
    box.go = function (i) {
      var s = box.items[i];
      if (opts.go && opts.go(s, i, box) !== false) { return; }
      var href = s ? hrefOf(s) : '';
      if (href) { window.location.href = href; }
    };

    input.addEventListener('input', function () { clearTimeout(timer); timer = setTimeout(box.fetch, opts.debounce == null ? 60 : opts.debounce); });
    if (opts.fetchOnFocus !== false) { input.addEventListener('focus', function () { box.lastQ = null; box.fetch(); }); }
    input.addEventListener('keydown', function (e) {
      if (opts.keys && opts.keys(e, box)) { return; }
      if (e.key === 'ArrowDown') { e.preventDefault(); if (list.hidden) { box.refresh(); } else { box.select(box.active + 1); } }
      else if (e.key === 'ArrowUp') { e.preventDefault(); box.select(box.active - 1); }
      else if (e.key === 'Enter') {
        if (box.active >= 0) { e.preventDefault(); box.go(box.active); }
        else if (opts.enter) { e.preventDefault(); opts.enter(box); }
      } else if (e.key === 'Escape' && !list.hidden) { e.preventDefault(); open(false); }
    });
    list.addEventListener('mousedown', function (e) {
      var li = e.target.closest('[data-i]');
      if (li) { e.preventDefault(); box.go(+li.getAttribute('data-i')); }
    });
    input.addEventListener('blur', function () { setTimeout(function () { open(false); }, 120); });
    return box;
  }
  D.searchBox = searchBox;

  // ---- the global search: one box over everything (Alt+K, or the top bar's search button) ---------------------------------------------
  var dlg = null, field = null, back = null;
  function closeFind() {
    if (!dlg || dlg.hidden) { return; }
    dlg.hidden = true;
    if (back && back.focus) { back.focus(); }
  }
  function openFind() {
    if (!dlg) {
      dlg = document.createElement('div');
      dlg.className = 'drishti_find';
      dlg.setAttribute('role', 'dialog');
      dlg.setAttribute('aria-modal', 'true');
      dlg.setAttribute('aria-label', 'Search everything');
      dlg.setAttribute('data-find', '');
      dlg.hidden = true;
      dlg.innerHTML = '<div class="drishti_find-card"><label class="visually-hidden" for="drishtiFind">Search everything</label>' +
        '<div class="cmd-box"><i class="bi bi-search" aria-hidden="true"></i><input id="drishtiFind" class="cmd-input" type="search" spellcheck="false" ' +
        'placeholder="Search entities, commands, screens, Help and what you can build"></div>' +
        '<p class="drishti_find-foot">↑ ↓ to move · Enter to open · Esc to close</p></div>';
      document.body.appendChild(dlg);
      field = dlg.querySelector('input');
      searchBox(field, { where: 'all', grouped: true, label: 'Everything', minChars: 1, fetchOnFocus: false,
        row: function (s, q) {
          return '<span class="sg-m">' + esc(s.mnemonic || '') + '</span><span class="sg-t">' + mark(s.id || s.title, q) + '</span><span class="sg-s">' +
            esc(s.id && s.title && s.title !== s.id ? s.title + (s.subtitle ? ' · ' + s.subtitle : '') : s.subtitle || '') + '</span>';
        } });
      field.addEventListener('keydown', function (e) { if (e.key === 'Escape' && !e.defaultPrevented) { e.preventDefault(); closeFind(); } });
      dlg.addEventListener('mousedown', function (e) { if (e.target === dlg) { closeFind(); } });
    }
    back = document.activeElement;
    dlg.hidden = false;
    field.focus();
    field.select();
  }
  D.find = { open: openFind, close: closeFind, isOpen: function () { return !!dlg && !dlg.hidden; } };
  document.addEventListener('click', function (e) {
    var b = e.target.closest && e.target.closest('[data-find-open]');
    if (b) { e.preventDefault(); openFind(); }
  });
  if (D.keys) {
    D.keys.register({ id: 'find.open', keys: ['Alt+K'], scope: 'global', group: 'Search', description: 'Search everything: entities, commands, screens, Help, what you can build',
      typing: true, run: function () { if (D.find.isOpen()) { closeFind(); } else { openFind(); } } });
  }
}());
