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
/* The people picker (HOW_IT_FITS.md §A31, Console design): drishti-inputs' Combobox over the server's directory (GET /api/directory), the one
   the share dialog uses, on a field of the page (suggestions only: the field's text stays the page's, so typed names still work). Each
   suggestion shows the name, the user id or "role", and the desk or the role's size; someone who cannot open the view says so. The arrows
   move, Enter or Tab picks, Escape closes the list (and only the list: a dialog stays). The search waits for typing to pause, needs
   minQuery characters, and drops a late answer.
   Drishti.People.attach(input, {kind, minQuery, onPick, taken}) returns {close(), isOpen()}. */
(function () {
  'use strict';
  var DELAY_MS = 150;

  function label(e) {
    if (e.type === 'role') { return { main: e.name, sub: 'role', extra: (e.size || 0) + ' ' + ((e.size || 0) === 1 ? 'person' : 'people') }; }
    return { main: e.displayName || e.name, sub: e.name, extra: e.desk || '' };
  }
  function span(cls, text) { var s = document.createElement('span'); s.className = cls; s.textContent = text; return s; }

  function attach(input, opts) {
    opts = opts || {};
    var I = window.Drishti.elements && window.Drishti.elements.inputs;
    if (!input || !I) { return { close: function () {}, isOpen: function () { return false; } }; }
    var found = {};                                      // this field's last answer: an option's value to its directory entry
    var host = document.createElement('span');
    host.className = 'ppl-pick';
    var cb = new I.Combobox(host, {
      input: input, suggest: true, minChars: opts.minQuery || 2, debounceMs: DELAY_MS, label: input.getAttribute('aria-label') || 'People and roles',
      source: function (q, signal) {
        var url = '/api/directory?q=' + encodeURIComponent(q) + (opts.kind ? '&kind=' + encodeURIComponent(opts.kind) : '');
        return fetch(url, { headers: { Accept: 'application/json' }, credentials: 'same-origin', signal: signal })
          .then(function (r) { return r.json().catch(function () { return []; }); })
          .then(function (list) {
            var taken = opts.taken ? opts.taken() : {};
            found = {};
            return (Array.isArray(list) ? list : []).filter(function (e) { return !taken[e.type + ':' + e.name]; }).map(function (e) {
              var key = e.type + ':' + e.name, l = label(e);
              found[key] = e;
              return { value: key, label: l.main, detail: l.sub };
            });
          });
      },
      renderOption: function (it, el) {
        var e = found[it.value] || {}, l = label(e);
        el.classList.add('shr-opt');
        el.appendChild(span('shr-opt-main', l.main));
        el.appendChild(span('shr-opt-sub mono', l.sub));
        if (l.extra) { el.appendChild(span('shr-opt-extra', l.extra)); }
        if (e.reach === false) { el.appendChild(span('shr-opt-no', 'cannot open this view')); }
      }
    });
    cb.on('change', function (ev) {
      var e = ev.option ? found[ev.option.value] : null;
      cb.value = null;
      if (!e) { return; }
      input.value = '';
      if (opts.onPick) { opts.onPick(e); }
    });
    return { close: function () { cb.close(); }, isOpen: function () { return cb.isOpen(); } };
  }

  window.Drishti.People = { attach: attach };
})();
