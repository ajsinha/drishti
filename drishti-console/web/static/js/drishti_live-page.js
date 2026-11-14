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
(function () {
  'use strict';
  window.Drishti = window.Drishti || {};
  var root = document.querySelector('[data-live-page]');
  if (!root) { return; }
  var grid = window.Drishti.elements && window.Drishti.elements.grid;
  var err = root.querySelector('[data-live-error]');
  function fail(text) { err.textContent = text; err.hidden = false; }
  if (!grid || !grid.LiveSetGrid || !window.Drishti.Channel) { fail("The live grid did not load: reload the page."); return; }
  var id = root.getAttribute('data-id'), spec;
  try { spec = JSON.parse(root.getAttribute('data-spec')); } catch (e) { fail("The page's declaration could not be read."); return; }
  var channel = { subscribe: function (handlers) { return window.Drishti.Channel.subscribe('live:' + id, handlers); } };
  var live = new grid.LiveSetGrid(root.querySelector('[data-live-host]'), { spec: spec, api: grid.httpLiveApi({ id: id }), channel: channel });
  window.Drishti.LivePage = { grid: live };
  live.start().then(function () { root.setAttribute('data-live-ready', 'true'); }, function (e) { fail(e.message || 'The rows could not be read.'); });
})();
