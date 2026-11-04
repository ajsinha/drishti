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
/* Applies the saved theme before first paint (no inline script, strict CSP), and gives every page the one way to name what it keeps in
   this browser: window.Drishti.key(name) is the name with the person's mark (server and user, the page's <meta name="drishti-me">), so
   one person's basket, drafts, filters and settings are never read by the next person signing in on the same browser. */
window.Drishti.key = function (name) {
  var m = document.querySelector('meta[name="drishti-me"]');
  return name + '@' + ((m && m.content) || 'anonymous');
};
(function () {
  'use strict';
  try {
    // a theme saved in the user's settings (server side) wins over this browser's last choice
    var t = document.documentElement.getAttribute('data-user-theme') || localStorage.getItem('drishti.theme');
    if (t) { document.documentElement.setAttribute('data-theme', t); }
  } catch (e) { /* storage blocked: keep the server default */ }
})();
/* The links that change something (a lens opened or left, Home's landing chosen) are posted, never fetched by GET: a crawler, a prefetch or
   another site's link changes nothing (a GET of the address shows a confirmation form). */
document.addEventListener('click', function (ev) {
  var a = ev.target && ev.target.closest ? ev.target.closest('a[href]') : null;
  if (!a || ev.defaultPrevented || ev.button !== 0 || ev.metaKey || ev.ctrlKey || ev.shiftKey || ev.altKey) { return; }
  var url = new URL(a.getAttribute('href'), location.href);
  if (url.origin !== location.origin || !/^\/(lens\/[^/]+|home\/landing\/[^/]+)$/.test(url.pathname)) { return; }
  ev.preventDefault();
  var f = document.createElement('form');
  f.method = 'post';
  f.action = url.pathname;
  document.body.appendChild(f);
  f.submit();
}, true);
