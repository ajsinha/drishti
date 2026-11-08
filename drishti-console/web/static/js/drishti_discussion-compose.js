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
/* Writing a comment (ARCHITECTURE.md §31, Console design): the two pickers inside a comment box. `@` opens a type-ahead over the server's
   directory (users and roles, GET /api/directory, the list the share dialog uses); `{` opens the fields of this page, and choosing one
   writes a value quote such as {$.mtm}, which every reader sees as THEIR view of the number (masked as ••• when it is masked for them).
   Drawn with drishti-inputs' combobox parts: its type-ahead (the search waits for typing to pause, a late answer is dropped, the request
   it replaces is aborted), its listbox and its floating panel. The textarea keeps the focus and names the active option by
   aria-activedescendant; arrow keys move, Enter or Tab picks, Escape closes the list and only the list.
   Drishti.Compose.attach(textarea, opts) returns {close()}; opts = {kind, minQuery(), fields() -> [{path, label}]}. */
(function () {
  'use strict';
  var DELAY_MS = 150, MENTION = /(?:^|[\s(])@([A-Za-z0-9_.-]{0,40})$/, QUOTE = /\{(\$?[^{}\s]{0,60})$/;

  function span(cls, text) { var s = document.createElement('span'); s.className = cls; s.textContent = text; return s; }

  function attach(ta, opts) {
    opts = opts || {};
    var I = window.Drishti.elements && window.Drishti.elements.inputs;
    if (!ta || !I) { return { close: function () {} }; }
    var trigger = null;
    var list = new I.Listbox(document, { label: 'Suggestions', onPick: function (it) { pick(it); }, render: function (it, el) {
      el.classList.add('shr-opt');
      el.appendChild(span('shr-opt-main', it.main));
      el.appendChild(span('shr-opt-sub mono', it.sub));
      if (it.extra) { el.appendChild(span('shr-opt-extra', it.extra)); }
      if (it.warn) { el.appendChild(span('shr-opt-no', it.warn)); }
    } });
    var pop = new I.Popover(document, { cls: 'di-combo-pop disc-opts', role: 'presentation', onClose: function () {
      ta.setAttribute('aria-expanded', 'false'); ta.removeAttribute('aria-activedescendant'); ta.removeAttribute('aria-controls');
    } });
    var people = new I.TypeAhead({ debounceMs: DELAY_MS, minChars: 0, cacheSize: 20,
      source: function (q, signal) {
        var url = '/api/directory?q=' + encodeURIComponent(q) + '&limit=8' + (opts.kind ? '&kind=' + encodeURIComponent(opts.kind) : '');
        return fetch(url, { headers: { Accept: 'application/json' }, credentials: 'same-origin', signal: signal })
          .then(function (r) { return r.json().catch(function () { return []; }); })
          .then(function (found) {
            return (Array.isArray(found) ? found : []).map(function (e) {
              var role = e.type === 'role', key = e.type + ':' + e.name;
              entries[key] = { value: key, label: e.name, name: e.name, main: role ? e.name : (e.displayName || e.name), sub: role ? 'role' : e.name,
                extra: role ? (e.size || 0) + ' ' + ((e.size || 0) === 1 ? 'person' : 'people') : (e.desk || ''), warn: e.reach === false ? 'cannot open this view' : '' };
              return { value: key, label: e.name };
            });
          });
      },
      onResult: function (r) {                 // a convenience: an unreachable directory shows nothing, and typing a name still works
        if (r.loading || r.error || !trigger || trigger.kind !== 'mention') { return; }
        show((r.options || []).map(function (o) { return entries[o.value]; }).filter(Boolean));
      } });
    var entries = {};                          // the directory's entries as drawn: the type-ahead keeps only value and label (and caches those)

    function close() { trigger = null; people.cancel(); pop.close(); }
    function show(items) {
      if (!items.length) { pop.close(); return; }
      list.render(items, function () { return false; });
      if (!pop.isOpen()) { pop.open(ta, list.el, { focus: false }); ta.setAttribute('aria-controls', list.id); ta.setAttribute('aria-expanded', 'true'); } else { pop.place(); }
      list.setActive(0, ta);
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
    function pick(it) {
      if (!it || !trigger) { return; }
      insert(trigger.kind === 'quote' ? '{' + it.path + '} ' : '@' + it.name + ' ');
    }
    // what the caret is in the middle of typing, if anything
    function detect() {
      var before = ta.value.slice(0, ta.selectionStart), m = MENTION.exec(before);
      if (m) {
        trigger = { kind: 'mention', at: before.length - m[1].length - 1 };
        if (m[1].length < (opts.minQuery ? opts.minQuery() : 2)) { people.cancel(); pop.close(); return; }
        people.query(m[1]);
        return;
      }
      people.cancel();
      m = QUOTE.exec(before);
      if (m) {
        trigger = { kind: 'quote', at: before.length - m[1].length - 1 };
        var q = m[1].toLowerCase().replace(/^\$\.?/, '');
        show((opts.fields ? opts.fields() : []).filter(function (f) { return !q || f.label.toLowerCase().indexOf(q) >= 0 || f.path.toLowerCase().indexOf(q) >= 0; }).slice(0, 12)
          .map(function (f) { return { value: 'field:' + f.path, label: f.label, path: f.path, main: f.label, sub: f.path }; }));
        return;
      }
      close();
    }

    ta.setAttribute('role', 'combobox'); ta.setAttribute('aria-autocomplete', 'list'); ta.setAttribute('aria-expanded', 'false');
    ta.addEventListener('input', detect);
    ta.addEventListener('keydown', function (e) {
      if (!pop.isOpen()) { return; }
      if (e.key === 'Escape') { e.preventDefault(); e.stopPropagation(); close(); return; }
      if (e.key === 'Tab' && !e.shiftKey && list.active >= 0) { e.preventDefault(); pick(list.items[list.active]); return; }
      if (e.key === 'Enter' && (e.ctrlKey || e.metaKey)) { return; }
      list.key(e, ta);
    });
    ta.addEventListener('blur', function () { setTimeout(close, 120); });
    return { close: close };
  }

  window.Drishti.Compose = { attach: attach };
})();
