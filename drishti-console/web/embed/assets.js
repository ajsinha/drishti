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
/* What the element loads, each once per page and server: the generated sheet (adopted by every element, so it works under a host CSP
   with style-src 'self'), the icon font (through the FontFace API: an @font-face in a shadow sheet does not load), and the chart
   library with the console's enhancer scripts. */
import { API } from './config.js';
import { scriptUrl } from './sanitize.js';

const once = {};
const memo = (key, fn) => (once[key] || (once[key] = fn().catch((e) => { delete once[key]; throw e; })));

export const sheet = (server) => memo('sheet:' + server, async () => {
  const text = await (await fetch(server + API + '/drishti-view.css')).text();
  const s = new CSSStyleSheet();
  s.replaceSync(text);
  return s;
});

/** The element's own frame rules (adopted after the console's sheet). */
export const frameSheet = new CSSStyleSheet();
frameSheet.replaceSync(':host{display:block;position:relative;contain:content;container:drishti/inline-size}.view{min-height:0}'
  + ':host([data-state="loading"]) .view{opacity:.55;transition:opacity .15s}'
  + '.view:focus{outline:none}.dv-note{padding:.4rem .8rem;font:12px/1.4 var(--d-font-mono,monospace);color:var(--d-muted,#666)}'
  + '.dv-error{padding:.8rem;border:1px solid var(--d-bad,#b00);color:var(--d-bad,#b00)}'
  + '.dv-slot[hidden]{display:none}'
  + ':host([density="compact"]) :is(td,th){padding-block:.1rem}:host([density="comfortable"]) :is(td,th){padding-block:.55rem}');

export const font = (server) => memo('font:' + server, async () => {
  const face = new FontFace('bootstrap-icons', 'url(' + server + API + '/icons.woff2) format("woff2")');
  document.fonts.add(await face.load());
});

const script = (url) => memo('script:' + url, () => new Promise((resolve, reject) => {
  const s = document.createElement('script');
  s.src = scriptUrl(url); s.async = false; s.setAttribute('data-manual', '');        // data-manual: the script registers its init(root, options) and does not start on the host's document
  s.onload = resolve; s.onerror = () => reject(new Error('cannot load ' + url));
  document.head.appendChild(s);
}));

// the console's own scripts, in order: the engine before the grid before the pivot (served from the allow-list in routes/embed_routes.py)
export const ENHANCERS = ['pivot-engine', 'pivot-grid', 'view', 'charts', 'tables', 'tree-rows', 'pivot', 'about', 'about-hints'];
export const chartLibs = (server) => memo('charts:' + server, async () => {
  await script(server + API + '/echarts.js');
  for (const n of ENHANCERS) { await script(server + API + '/js/' + n + '.js'); }
});
