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
/* What the renderer loads, once per page, all from the console that serves the library (CLIENT_RENDERER.md section 5): its sheet
   (adopted by each shadow root, so a host with style-src 'self' needs nothing more), the icon font (FontFace: an @font-face inside a
   shadow root is not used), the vendored ECharts (and ECharts GL for a 3D surface, and the map shapes' helper) as scripts. Nothing comes
   from a CDN. Script URLs go through one Trusted Types policy, `drishti-render`, that accepts only this console's /static/ scripts. */

/** The library's own folder and the console's /static/ folder, from this module's URL (…/static/render/<version>/m/assets.js). */
export const LIB = new URL('../', import.meta.url);
export const STATIC = new URL('../../../', import.meta.url);

const once = new Map();
const memo = (key, fn) => {
  if (!once.has(key)) { once.set(key, fn().catch((e) => { once.delete(key); throw e; })); }
  return once.get(key);
};

let policy = null;
try {
  if (window.trustedTypes && window.trustedTypes.createPolicy) {
    policy = window.trustedTypes.createPolicy('drishti-render', {
      createScriptURL: (u) => {
        const url = new URL(u, document.baseURI);
        if (url.origin === STATIC.origin && url.pathname.startsWith(STATIC.pathname)) { return u; }
        throw new TypeError('drishti-render: not a script of this console: ' + u);
      }
    });
  }
} catch (e) { policy = null; }                // the host's trusted-types list does not name drishti-render: plain strings

/** Loads a classic script of this console (ECharts, ECharts GL, geomap.js), once. */
export function script(rel) {
  const url = new URL(rel, STATIC).href;
  return memo('s:' + url, () => new Promise((resolve, reject) => {
    const s = document.createElement('script');
    s.src = policy ? policy.createScriptURL(url) : url;
    s.async = false;
    s.setAttribute('data-manual', '');
    s.onload = () => resolve(); s.onerror = () => reject(new Error('cannot load ' + url));
    document.head.appendChild(s);
  }));
}

/** ECharts: the page's own when it has one, else the vendored copy. */
export async function echarts() {
  if (!window.echarts) { await script('vendor/echarts/echarts.min.js'); }
  return window.echarts;
}
/** ECharts GL for a 3D surface, only when first asked for. */
export async function echartsGl() {
  await echarts();
  if (!(window.echarts && window.echarts.graphicGL)) { await script('vendor/echarts-gl/echarts-gl.min.js'); }
  return window.echarts;
}
/** The map shapes' helper (window.drishtiGeo): it fetches the vendored Natural Earth files from beside itself. */
export async function geo() {
  if (!window.drishtiGeo) { await script('js/geomap.js'); }
  return window.drishtiGeo;
}

/** The library's sheet as a constructable sheet (adopted by shadow roots; style-src is not asked). */
export const sheet = () => memo('sheet', async () => {
  const res = await fetch(new URL('drishti-render.css', LIB), { credentials: 'omit' });
  if (!res.ok) { throw new Error('drishti-render.css: HTTP ' + res.status); }
  const s = new CSSStyleSheet();
  s.replaceSync(await res.text());
  return s;
});

/** The icon font, registered with the document (shadow roots use it). */
export const font = () => memo('font', async () => {
  if ([...document.fonts].some((f) => f.family.replace(/"/g, '') === 'bootstrap-icons')) { return; }
  const face = new FontFace('bootstrap-icons', 'url(' + new URL('vendor/bootstrap-icons/fonts/bootstrap-icons.woff2', STATIC).href + ') format("woff2")');
  document.fonts.add(await face.load());
});

export const trustedTypesActive = () => !!policy;
