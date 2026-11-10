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
/* An open page of a module that goes (docs/architecture/KERNEL_AND_MODULES.md section 12.4): the shell, not the module's own script, asks
 * every 15 seconds whether the page's module is still hosted; when it is off or removed the page is frozen (inert: no more input, the
 * module's script is not called again) under the kernel's notice "<module> is no longer installed on this system" with Go to Home. On any
 * page, a module's slot (an element with data-module-slot) goes with its module. Inert on pages that hold nothing of a module. */
(function () {
  'use strict';
  var meta = document.querySelector('meta[name="drishti-module-page"]');
  var page = meta ? meta.getAttribute('content') : '';
  if (!page && !document.querySelector('[data-module-slot]')) { return; }
  var shown = false;
  function notice(m) {
    if (shown) { return; }
    shown = true;
    var main = document.querySelector('#main') || document.body;
    Array.prototype.forEach.call(main.children, function (c) { c.setAttribute('inert', ''); c.setAttribute('aria-hidden', 'true'); });
    var box = document.createElement('section');
    box.className = 'mod-notice';
    box.setAttribute('role', 'alert');
    box.setAttribute('data-module-gone', m.id);
    var h = document.createElement('h1');
    h.className = 'ib-h2';
    h.textContent = m.state === 'off' ? (m.title + ' is switched off') : (m.title + ' is no longer installed on this system');
    var p = document.createElement('p');
    p.textContent = 'Nothing you saved is lost: work that uses it is kept, marked "needs module ' + m.id + '", and comes back when it is ' +
      (m.state === 'off' ? 'switched on again.' : 'reinstalled.');
    var a = document.createElement('a');
    a.className = 'btn-pill ib-btn';
    a.href = '/';
    a.textContent = 'Go to Home';
    box.appendChild(h); box.appendChild(p); box.appendChild(a);
    main.insertBefore(box, main.firstChild);
  }
  function check() {
    fetch('/api/modules/hosted', { credentials: 'same-origin', headers: { Accept: 'application/json' } }).then(function (r) {
      return r.ok ? r.json() : null;
    }).then(function (s) {
      if (!s) { return; }
      var gone = s.gone || {};
      Array.prototype.forEach.call(document.querySelectorAll('[data-module-slot]'), function (el) {
        if (gone[el.getAttribute('data-module-slot')]) { el.remove(); }
      });
      if (page && gone[page]) { notice(gone[page]); }
    }).catch(function () { /* the console is restarting: asked again */ });
  }
  setInterval(check, 15000);
  window.Drishti.moduleGoneCheck = check;
})();
