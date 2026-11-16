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
/* The page of one live result set (/live/<id>): hands the declaration, the /api/live calls and the console's one live channel (the
   subscription live:<id>) to drishti-grid's live mode (LiveSetGrid). Nothing else is drawn here. */
(function (g) { g.Drishti = g.Drishti || {}; })(typeof window !== 'undefined' ? window : globalThis); /* one namespace for everything the console puts on window (tools/dev/asset_prefix.py) */
(function () {
  'use strict';
  var root = document.querySelector('[data-live-page]');
  if (!root) { return; }
  var grid = window.Drishti.elements && window.Drishti.elements.grid;
  var err = root.querySelector('[data-live-error]');
  function fail(text) { err.textContent = text; err.hidden = false; }
  if (!grid || !grid.LiveSetGrid || !window.Drishti.Channel) { fail("The live grid did not load: reload the page."); return; }
  var id = root.getAttribute('data-id'), spec;
  try { spec = JSON.parse(root.getAttribute('data-spec')); } catch (e) { fail("The page's declaration could not be read."); return; }
  function start() {
    var records = window.Drishti.records;
    if (!records) { fail("The records grid did not load: reload the page."); return; }
    /* the same mount as every list of records: full width, the viewport's height, one toolbar */
    var live = records.mount(root.querySelector('[data-live-host]'), { set: id, live: true, spec: spec, fill: true });
    window.Drishti.LivePage = { grid: live };
    new MutationObserver(function (m, o) { if (root.querySelector('[data-live-host]').getAttribute('data-records-ready')) { root.setAttribute('data-live-ready', 'true'); o.disconnect(); } })
      .observe(root.querySelector('[data-live-host]'), { attributes: true });
  }
  if (document.readyState === 'loading') { document.addEventListener('DOMContentLoaded', start); } else { start(); }
})();
