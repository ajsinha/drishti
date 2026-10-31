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
/* Wave C (2.4.0), Analyse's Profile tab (docs/guides/USER_GUIDE.md › Analyse: slice, SQL and Python, section 15; docs/architecture/BI_DESIGN.md section 11.1, item P3): the extract's profile, worked out by the console
   (POST /bi/explore/profile, in its sandbox, over the rows the reader may see) and drawn by the Transform editor's panel (bi-transform-view.js). The page
   adds nothing up. Asked when the tab opens, again after the extract changes. */
(function () {
  'use strict';
  const B = window.drishtiBi, root = document.getElementById('bx');
  if (!B || !root || !B.explore || !B.txView) { return; }
  const X = B.explore, out = root.querySelector('[data-profile-out]'), meta = root.querySelector('[data-profile-meta]');
  let shownFor = null;
  function draw() {
    if (!X.spec) { meta.textContent = 'Open an extract first.'; return; }
    const key = JSON.stringify(X.spec);
    if (key === shownFor) { return; }
    shownFor = key;
    meta.textContent = 'Profiling the extract…';
    X.post('/bi/explore/profile', { extract: X.spec }).then((p) => {
      meta.textContent = 'Worked out by the console over ' + p.inputRows.toLocaleString('en-US') + ' rows' + (p.truncatedInput ? ' (the extract was cut)' : '') + ' in ' + (p.ms || 0) + ' ms.'
        + (p.masked.length ? ' Masked for you: ' + p.masked.join(', ') + '.' : '');
      B.txView.profile(out, p, { masked: p.masked });
    }).catch((e) => { shownFor = null; meta.textContent = e.message; });
  }
  const tab = root.querySelector('[data-tab=profile]');
  tab.addEventListener('click', draw);
  document.addEventListener('bx:extract', () => { shownFor = null; if (tab.getAttribute('aria-selected') === 'true') { draw(); } });
})();
