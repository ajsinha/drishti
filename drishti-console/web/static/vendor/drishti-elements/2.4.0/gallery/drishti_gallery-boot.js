/*
 * Drishti Elements
 *
 * Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>.
 * All rights reserved.
 *
 * PROPRIETARY.
 *
 * This file is the proprietary property of Ashutosh Sinha.
 * Copying, use, modification or distribution of this file, via any medium,
 * is not permitted except with the express prior written permission of the
 * copyright holder.
 *
 * See the LICENSE file in the root of this repository.
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
  /* a host's own visuals (the custom-visual SDK): their libraries loaded, each kind a page. The address that lists them is the page's
     option ?visuals=<same-origin URL> (the host's answer: {modules, visuals}); none asked when the option is absent or the page is
     opened from the file system */
  const script = (src) => new Promise((resolve, reject) => {
    const s = document.createElement('script');
    s.src = drishti_url(src);
    s.onload = resolve;
    s.onerror = () => reject(new Error('Could not load ' + src));
    document.head.appendChild(s);
  });
  /* the host's answer: the visuals of the modules hosted now and the modules this person's pages may load libraries of (a library of a
     module that is off here is not asked for: the loader refuses it); nothing from the file system */
  const visualsUrl = p.get('visuals') && new URL(p.get('visuals'), location.href).origin === location.origin ? p.get('visuals') : '';
  const answer = () => (location.protocol === 'file:' || !visualsUrl ? Promise.resolve({}) : fetch(visualsUrl, { credentials: 'same-origin' })
    .then((r) => (r.ok ? r.json() : {})).catch(() => ({})));
  const moduleVisuals = (list) => list.reduce((at, v) => at.then(() => script(v.script)
    .then(() => (NS.elements.visuals && NS.elements.visuals[v.module] ? NS.elements.visuals[v.module].ready : null))
    .then(() => G.moduleVisual(v)).catch((e) => { problems.push(e.message); })), Promise.resolve());
  answer().then((b) => {
    if (Array.isArray(b.modules)) { core.configure({ modules: b.modules }); }
    const mods = Array.isArray(b.modules) ? b.modules : null;
    const libs = core.manifest().libraries;
    const wanted = names.filter((n) => !mods || !libs[n].module || libs[n].module === 'core' || mods.indexOf(libs[n].module) >= 0);
    return wanted.reduce((at, n) => at.then(() => core.load(n).catch((e) => { problems.push(e.message); })), Promise.resolve())
      .then(() => moduleVisuals(b.visuals || []));
  }).then(() => start(problems));
}());
