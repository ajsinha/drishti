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
/* The host's settings for Drishti Elements (an external component, vendored under static/vendor/drishti-elements/<version>/;
   docs/architecture/ELEMENTS_IN_DRISHTI.md), loaded by base.html straight after drishti-core's bundle: the one place the console's
   routes are told to Elements. The live-set client, the grid's pivot link and the data client read these endpoints (with none they
   say the feature is off); the loader learns Drishti's own libraries (drishti-insight, drishti-renderer, drishti-risk), which live
   under static/elements/, so a page may load them by name. */
(function () {
  'use strict';
  var E = window.Drishti && window.Drishti.elements, core = E && E.core;
  if (!core || !core.configure) { return; }
  core.configure({ endpoints: { live: '/api/live', changes: '/api/changes', pivotPage: '/pivot', capabilities: '/api/v1/data/capabilities' } });
  var own = drishti_url('/static/elements/');          // drishti-core installed drishti_url (the console's URL sub-path)
  [['drishti-insight', ['drishti-core']], ['drishti-renderer', ['drishti-core', 'drishti-grid', 'drishti-chart', 'drishti-data', 'drishti-graph', 'drishti-tree']],
    ['drishti-risk', ['drishti-core', 'drishti-grid', 'drishti-chart']]].forEach(function (l) {
    var short = l[0].replace('drishti-', '');
    core.register(l[0], { base: own, bundle: l[0] + '/drishti_' + short + '.js', requires: l[1], module: l[0] === 'drishti-risk' ? 'banking' : 'core' });
  });
})();
