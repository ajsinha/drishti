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
/* Starts the gallery page (drishti_gallery.html): every library of the manifest loaded by drishti-core's loader (with its style sheets
   and vendored engine), the map shapes' folder next to this page's static root, a light theme when the address asks (theme=light), and
   the instance as window.__gallery for the browser test. A library that cannot be loaded is named on the page; the rest still run. A
   script file, so the page holds under a strict CSP. */
(function () {
  'use strict';
  const NS = window.Drishti, G = NS.gallery, core = NS.elements.core;
  const here = new URL('.', document.currentScript ? document.currentScript.src : location.href);
  const p = new URLSearchParams(location.search + '&' + location.hash.slice(1));
  if (p.get('theme') === 'light') { core.setTheme('light', { persist: false }); }
  const names = Object.keys(core.manifest().libraries).filter((n) => n !== 'drishti-core');
  const start = (problems) => {
    window.__gallery = G.start(document.getElementById('gallery'), { mapsBase: new URL('../vendor/echarts/maps/', here).href });
    window.__gallery.loadProblems = problems;
    problems.forEach((m) => core.toast({ text: m, timeout: 8000 }));
  };
  const problems = [];
  names.reduce((at, n) => at.then(() => core.load(n).catch((e) => { problems.push(e.message); })), Promise.resolve()).then(() => start(problems));
}());
