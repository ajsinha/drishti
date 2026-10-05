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
/* Writing a comment (COLLABORATION.md, Console design): the two pickers inside a comment box. `@` opens a type-ahead over the server's
   directory (users and roles, GET /api/directory, the list the share dialog uses); `{` opens the fields of this page, and choosing one
   writes a value quote such as {$.mtm}, which every reader sees as THEIR view of the number (masked as ••• when it is masked for them).
   Both are one ARIA combobox: the textarea keeps the focus, the list is a listbox whose active option is named by aria-activedescendant;
   arrow keys move, Enter or Tab picks, Escape closes the list and only the list. DrishtiCompose.attach(textarea, list, opts) returns
   {close()}; opts = {kind, minQuery(), fields() -> [{path, label}]}. No library. */
(function () {
  'use strict';
  var DELAY_MS = 150, MENTION = /(?:^|[\s(])@([A-Za-z0-9_.-]{0,40})$/, QUOTE = /\{(\$?[^{}\s]{0,60})$/;

  function el(tag, cls, text) {
    var n = document.createElement(tag);
    if (cls) { n.className = cls; }
    if (text != null) { n.textContent = text; }
    return n;
  }

  function attach(ta, list, opts) {
    opts = opts || {};
    var items = [], active = -1, timer = null, serial = 0, trigger = null;

    function close() {
      list.hidden = true; list.textContent = ''; items = []; active = -1; trigger = null;
      ta.setAttribute('aria-expanded', 'false'); ta.removeAttribute('aria-activedescendant');
    }
    function mark(i) {
      active = i;
      Array.prototype.forEach.call(list.children, function (li, n) {
        li.setAttribute('aria-selected', n === i ? 'true' : 'false');
        li.classList.toggle('on', n === i);
        if (n === i) { ta.setAttribute('aria-activedescendant', li.id); li.scrollIntoView({ block: 'nearest' }); }
      });
    }
    // replaces the typed trigger (from `at` to the caret) with the chosen text
    function insert(text) {
      var end = ta.selectionStart, start = trigger.at;
      ta.value = ta.value.slice(0, start) + text + ta.value.slice(end);
      var caret = start + text.length;
      ta.setSelectionRange(caret, caret);
      close();
      ta.dispatchEvent(new Event('input', { bubbles: true }));
      ta.focus();
    }
    function pick(i) {
      var e = items[i];
      if (!e || !trigger) { return; }
      insert(trigger.kind === 'quote' ? '{' + e.path + '} ' : '@' + e.name + ' ');
    }
    function option(n, main, sub, extra, warn) {
      var li = el('li', 'shr-opt');
      li.id = list.id + '-' + n; li.setAttribute('role', 'option'); li.setAttribute('aria-selected', 'false');
      li.appendChild(el('span', 'shr-opt-main', main));
      li.appendChild(el('span', 'shr-opt-sub mono', sub));
      if (extra) { li.appendChild(el('span', 'shr-opt-extra', extra)); }
      if (warn) { li.appendChild(el('span', 'shr-opt-no', warn)); }
      li.addEventListener('mousedown', function (ev) { ev.preventDefault(); pick(n); });
      return li;
    }
    function show(found) {
      items = found; list.textContent = '';
      if (!items.length) { list.hidden = true; ta.setAttribute('aria-expanded', 'false'); return; }
      items.forEach(function (e, n) {
        if (trigger && trigger.kind === 'quote') { list.appendChild(option(n, e.label, e.path)); return; }
        var role = e.type === 'role';
        list.appendChild(option(n, role ? e.name : (e.displayName || e.name), role ? 'role' : e.name,
          role ? (e.size || 0) + ' ' + ((e.size || 0) === 1 ? 'person' : 'people') : (e.desk || ''), e.reach === false ? 'cannot open this view' : ''));
      });
      list.hidden = false; ta.setAttribute('aria-expanded', 'true'); mark(0);
    }
    function search(q) {
      var mine = ++serial;
      var url = '/api/directory?q=' + encodeURIComponent(q) + '&limit=8' + (opts.kind ? '&kind=' + encodeURIComponent(opts.kind) : '');
      fetch(url, { headers: { Accept: 'application/json' }, credentials: 'same-origin' }).then(function (r) { return r.json().catch(function () { return []; }); })
        .then(function (found) { if (mine === serial && trigger && trigger.kind === 'mention' && Array.isArray(found)) { show(found); } })
        .catch(function () { /* a convenience: typing a name still works when the directory cannot be reached */ });
    }
    // what the caret is in the middle of typing, if anything
    function detect() {
      var before = ta.value.slice(0, ta.selectionStart), m = MENTION.exec(before);
      clearTimeout(timer);
      if (m) {
        trigger = { kind: 'mention', at: before.length - m[1].length - 1 };
        if (m[1].length < (opts.minQuery ? opts.minQuery() : 2)) { list.hidden = true; list.textContent = ''; items = []; return; }
        timer = setTimeout(function () { search(m[1]); }, DELAY_MS);
        return;
      }
      m = QUOTE.exec(before);
      if (m) {
        trigger = { kind: 'quote', at: before.length - m[1].length - 1 };
        var q = m[1].toLowerCase().replace(/^\$\.?/, '');
        show((opts.fields ? opts.fields() : []).filter(function (f) { return !q || f.label.toLowerCase().indexOf(q) >= 0 || f.path.toLowerCase().indexOf(q) >= 0; }).slice(0, 12));
        return;
      }
      close();
    }

    ta.setAttribute('role', 'combobox'); ta.setAttribute('aria-autocomplete', 'list'); ta.setAttribute('aria-expanded', 'false'); ta.setAttribute('aria-controls', list.id);
    ta.addEventListener('input', detect);
    ta.addEventListener('keydown', function (e) {
      var open = !list.hidden && items.length;
      if (e.key === 'ArrowDown' && open) { e.preventDefault(); mark((active + 1) % items.length); }
      else if (e.key === 'ArrowUp' && open) { e.preventDefault(); mark((active - 1 + items.length) % items.length); }
      else if ((e.key === 'Enter' && !e.ctrlKey && !e.metaKey || e.key === 'Tab' && !e.shiftKey) && open && active >= 0) { e.preventDefault(); pick(active); }
      else if (e.key === 'Escape' && open) { e.preventDefault(); e.stopPropagation(); close(); }
    });
    ta.addEventListener('blur', function () { setTimeout(close, 120); });
    return { close: close };
  }

  window.DrishtiCompose = { attach: attach };
})();
