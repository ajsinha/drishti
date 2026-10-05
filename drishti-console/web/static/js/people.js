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
/* The people picker (COLLABORATION.md, Console design): a type-ahead over the server's directory (GET /api/directory), the one the share
   dialog uses (and, later, `@` in a comment). It is an ARIA combobox: the input keeps the focus, the list is a listbox whose active
   option is named by aria-activedescendant; arrow keys move, Enter picks, Escape closes the list (and only the list: the dialog stays).
   DrishtiPeople.attach(input, list, {kind, minQuery, onPick, taken}) returns {close()}. No library. */
(function () {
  'use strict';
  var DELAY_MS = 150;

  function el(tag, cls, text) {
    var n = document.createElement(tag);
    if (cls) { n.className = cls; }
    if (text != null) { n.textContent = text; }
    return n;
  }

  function label(e) {
    if (e.type === 'role') { return { main: e.name, sub: 'role', extra: (e.size || 0) + ' ' + ((e.size || 0) === 1 ? 'person' : 'people') }; }
    return { main: e.displayName || e.name, sub: e.name, extra: e.desk || '' };
  }

  function attach(input, list, opts) {
    opts = opts || {};
    var items = [], active = -1, timer = null, serial = 0;
    var min = opts.minQuery || 2;

    function close() {
      list.hidden = true; list.textContent = ''; items = []; active = -1;
      input.setAttribute('aria-expanded', 'false'); input.removeAttribute('aria-activedescendant');
    }
    function mark(i) {
      active = i;
      Array.prototype.forEach.call(list.children, function (li, n) {
        var on = n === i;
        li.setAttribute('aria-selected', on ? 'true' : 'false');
        li.classList.toggle('on', on);
        if (on) { input.setAttribute('aria-activedescendant', li.id); li.scrollIntoView({ block: 'nearest' }); }
      });
      if (i < 0) { input.removeAttribute('aria-activedescendant'); }
    }
    function pick(i) {
      var e = items[i];
      if (!e) { return; }
      input.value = '';
      close();
      if (opts.onPick) { opts.onPick(e); }
    }
    function show(found) {
      var taken = opts.taken ? opts.taken() : {};
      items = found.filter(function (e) { return !taken[e.type + ':' + e.name]; });
      list.textContent = '';
      if (!items.length) { list.hidden = true; input.setAttribute('aria-expanded', 'false'); return; }
      items.forEach(function (e, n) {
        var l = label(e), li = el('li', 'shr-opt');
        li.id = list.id + '-' + n; li.setAttribute('role', 'option'); li.setAttribute('aria-selected', 'false');
        li.appendChild(el('span', 'shr-opt-main', l.main));
        li.appendChild(el('span', 'shr-opt-sub mono', l.sub));
        if (l.extra) { li.appendChild(el('span', 'shr-opt-extra', l.extra)); }
        if (e.reach === false) { li.appendChild(el('span', 'shr-opt-no', 'cannot open this view')); }
        li.addEventListener('mousedown', function (ev) { ev.preventDefault(); pick(n); });
        list.appendChild(li);
      });
      list.hidden = false; input.setAttribute('aria-expanded', 'true'); mark(0);
    }
    function search() {
      var q = input.value.trim();
      if (q.length < min) { close(); return; }
      var mine = ++serial;
      var url = '/api/directory?q=' + encodeURIComponent(q) + (opts.kind ? '&kind=' + encodeURIComponent(opts.kind) : '');
      fetch(url, { headers: { Accept: 'application/json' }, credentials: 'same-origin' }).then(function (r) { return r.json().catch(function () { return []; }); })
        .then(function (found) { if (mine === serial && Array.isArray(found)) { show(found); } })
        .catch(function () { /* the picker is a convenience: typing a name still works when the directory cannot be reached */ });
    }

    input.addEventListener('input', function () { clearTimeout(timer); timer = setTimeout(search, DELAY_MS); });
    input.addEventListener('keydown', function (e) {
      var open = !list.hidden && items.length;
      if (e.key === 'ArrowDown') { if (open) { e.preventDefault(); mark((active + 1) % items.length); } }
      else if (e.key === 'ArrowUp') { if (open) { e.preventDefault(); mark((active - 1 + items.length) % items.length); } }
      else if (e.key === 'Enter' && open && active >= 0 && !e.ctrlKey && !e.metaKey) { e.preventDefault(); pick(active); }
      else if (e.key === 'Escape' && open) { e.preventDefault(); e.stopPropagation(); close(); }
      else if (e.key === 'Tab' && open && active >= 0 && input.value.trim()) { pick(active); }
    });
    input.addEventListener('blur', function () { setTimeout(close, 120); });
    return { close: close };
  }

  window.DrishtiPeople = { attach: attach };
})();
