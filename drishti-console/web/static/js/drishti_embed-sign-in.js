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
/* The renderer's sign-in window, last step (CLIENT_RENDERER.md 10.1): hands the embed token to the page that opened the window, posted to
   that page's EXACT origin (the browser delivers it to no other), then closes; in the same-tab flow (no opener: a webview) it goes back to
   the page with a one-time code in the fragment. The token is never written anywhere here. */
(function () {
  'use strict';
  var raw = document.body.getAttribute('data-sign-in');
  var hint = document.querySelector('[data-esi-hint]');
  if (!raw) { if (hint) { hint.hidden = false; } return; }
  document.body.removeAttribute('data-sign-in');          // read once: the page keeps no copy of the token in its DOM
  var d;
  try { d = JSON.parse(raw); } catch (e) { return; }
  if (d.mode === 'redirect') { if (d.back) { location.replace(d.back); } return; }
  if (window.opener && d.origin && d.message) {
    try { window.opener.postMessage(d.message, d.origin); } catch (e) { /* the page went away */ }
    if (d.message.type === 'drishti:sign-in') { window.close(); }
  }
  if (hint) { setTimeout(function () { hint.hidden = false; }, 800); }
})();
