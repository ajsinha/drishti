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
/* About this page, Ask (docs/architecture/CONTEXT_HELP.md, Optional: Ask). The box exists only when the server says Ask is on for the page's
   pack; this file does nothing otherwise. It posts the question to the console, which proxies the server (which holds the credentials and
   builds the prompt). The answer is written with textContent, never as HTML. A failed Ask never touches the layers of the drawer:
   the page explanation stays complete without it. Alt+? focuses the box. */
(function () {
  'use strict';
  var MESSAGES = {
    'DRS-4007': 'Ask is switched off for this page.',
    'DRS-4008': 'Ask is unavailable; the page explanation above is complete.',
    'DRS-4009': 'Too many questions just now. Try again in a minute.'
  };
  var busy = false;

  function show(box, text, problem) {
    var out = box.querySelector('[data-ask-answer]');
    out.hidden = false;
    out.textContent = text;
    out.classList.toggle('about-ask-problem', !!problem);
  }

  document.addEventListener('submit', function (e) {
    var form = e.target.closest ? e.target.closest('[data-ask-form]') : null;
    if (!form) { return; }
    e.preventDefault();
    var box = form.closest('[data-about-ask]');
    var input = form.querySelector('[data-ask-input]');
    var q = input.value.trim();
    if (!q || busy) { return; }
    busy = true;
    var send = form.querySelector('[data-ask-send]');
    send.disabled = true;
    show(box, 'Asking…', true);
    fetch(box.getAttribute('data-ask-url'), {
      method: 'POST', credentials: 'same-origin',
      headers: { 'Content-Type': 'application/json', Accept: 'application/json' },
      body: JSON.stringify({ question: q })
    }).then(function (r) {
      return r.json().catch(function () { return {}; }).then(function (b) { return { ok: r.ok, body: b }; });
    }).then(function (res) {
      if (res.ok && typeof res.body.answer === 'string') {
        var text = res.body.answer || 'The page does not say.';
        show(box, text, false);
      } else {
        show(box, MESSAGES[res.body.code] || 'Ask is unavailable; the page explanation above is complete.', true);
      }
    }).catch(function () {
      show(box, MESSAGES['DRS-4008'], true);
    }).then(function () { busy = false; send.disabled = false; });
  });

  // Alt+? (Alt+Shift+/) focuses the box when it is there; opening the drawer first if need be.
  document.addEventListener('keydown', function (e) {
    if (!e.altKey || e.ctrlKey || e.metaKey || e.key !== '?') { return; }
    var go = function () {
      var input = document.querySelector('#aboutDrawer [data-ask-input]');
      if (input) { e.preventDefault(); input.focus(); }
    };
    var drawer = document.getElementById('aboutDrawer');
    if (drawer && drawer.hidden && window.drishtiAbout) {
      e.preventDefault();
      window.drishtiAbout.ensure().then(function () {
        var opener = document.querySelector('[data-about-open]');
        if (opener) { opener.click(); }
        go();
      });
    } else { go(); }
  });
})();
