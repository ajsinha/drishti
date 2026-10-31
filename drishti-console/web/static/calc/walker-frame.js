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
/* The page inside Calc's sandboxed Graphic Walker frame (drishti_bi.walk, docs/guides/USER_GUIDE.md "Exploring a frame").

   The frame is sandboxed (allow-scripts only: no same-origin, so no cookies and no console API) and its policy has connect-src 'none'. Calc posts the rows to it:

     frame -> page  {type: 'walker-ready'}
     page -> frame  {type: 'walker-data', fields: [{fid, name, semanticType, analyticType}], data: [{...}], dark}

   and Graphic Walker (vendor/graphic-walker) is mounted on them. Nothing goes back out. */
(function () {
  'use strict';
  var started = false;
  window.addEventListener('message', function (e) {
    var m = e.data;
    if (started || e.source !== window.parent || !m || m.type !== 'walker-data' || !Array.isArray(m.data) || !Array.isArray(m.fields)) { return; }
    started = true;
    if (m.dark) { document.body.classList.add('dark'); }
    try {
      window.DrishtiWalker.mount(document.getElementById('walker'), { fields: m.fields, data: m.data, dark: !!m.dark });
      document.getElementById('walker').setAttribute('data-ready', '1');
    } catch (err) {
      var p = document.createElement('p');
      p.setAttribute('role', 'alert');
      p.textContent = 'Graphic Walker could not start: ' + (err && err.message ? err.message : err);
      document.body.appendChild(p);
    }
  });
  window.parent.postMessage({ type: 'walker-ready' }, '*');
})();
