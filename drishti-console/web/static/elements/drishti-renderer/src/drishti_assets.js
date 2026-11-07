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
/* What the renderer loads, once per page, all from the console that serves it (CLIENT_RENDERER.md section 5): its sheet (adopted by each
   shadow root, so a host with style-src 'self' needs nothing more), the icon font (FontFace: an @font-face inside a shadow root is not
   used), the vendored ECharts (and ECharts GL for a 3D surface, and the map shapes' helper) as scripts, which drishti-chart's panel charts
   draw with. Nothing comes from a CDN. Script URLs go through one Trusted Types policy, `drishti-render`, that accepts only this console's
   static scripts. Where the files are: the static root drishti-core's loader knows (the folder its bundle came from, or configure()), or
   what the public entry (static/render/<version>/drishti-render.js) says from its own address (setAssetBase). */
import { staticUrl } from '../../drishti-core/src/drishti_index.js';

const base = { statics: null, sheet: null };

/**
 * Where the console's static files and the renderer's sheet are (the public entry calls it with addresses made from its own URL).
 * @param {{statics: string, sheet: string}} o statics: the console's static root ("https://drishti.example/static/"); sheet: the sheet's address
 * @returns {void}
 */
export function setAssetBase(o) {
  if (o && o.statics) { base.statics = String(o.statics).replace(/\/?$/, '/'); }
  if (o && o.sheet) { base.sheet = String(o.sheet); }
}

/**
 * The address of a file under the console's static root.
 * @param {string} rel for example "vendor/echarts/echarts.min.js"
 * @returns {string}
 */
export function statics(rel) {
  const root = base.statics || new URL(staticUrl(''), document.baseURI).href;
  return new URL(rel, root).href;
}

const once = new Map();
/**
 * Runs fn once per page for key (a failure is forgotten, so the next call tries again).
 * @param {string} key
 * @param {function(): Promise<*>} fn
 * @returns {Promise<*>}
 */
export function memoised(key, fn) {
  if (!once.has(key)) { once.set(key, fn().catch((e) => { once.delete(key); throw e; })); }
  return once.get(key);
}

let policy;
/** @private the Trusted Types policy, made the first time a script is loaded (null when the host's policy list does not name it) */
function trusted() {
  if (policy !== undefined) { return policy; }
  policy = null;
  try {
    if (window.trustedTypes && window.trustedTypes.createPolicy) {
      policy = window.trustedTypes.createPolicy('drishti-render', {
        createScriptURL: (u) => {
          const url = new URL(u, document.baseURI), root = new URL(statics(''));
          if (url.origin === root.origin && url.pathname.startsWith(root.pathname)) { return u; }
          throw new TypeError('drishti-render: not a script of this console: ' + u);
        }
      });
    }
  } catch (e) { policy = null; }
  return policy;
}

/**
 * Loads a classic script of this console (ECharts, ECharts GL, the map shapes' helper, a console behaviour), once.
 * @param {string} rel its path under the static root
 * @returns {Promise<void>}
 */
export function script(rel) {
  const url = statics(rel);
  return memoised('s:' + url, () => new Promise((resolve, reject) => {
    const s = document.createElement('script'), p = trusted();
    s.src = p ? p.createScriptURL(url) : url;
    s.async = false;
    s.setAttribute('data-manual', '');
    s.onload = () => resolve(); s.onerror = () => reject(new Error('cannot load ' + url));
    document.head.appendChild(s);
  }));
}

/**
 * ECharts: the page's own when it has one, else the vendored copy.
 * @returns {Promise<Object>}
 */
export async function echarts() {
  if (!window.echarts) { await script('vendor/echarts/echarts.min.js'); }
  return window.echarts;
}

/**
 * ECharts GL for a 3D surface, only when first asked for.
 * @returns {Promise<void>}
 */
export async function echartsGl() {
  await echarts();
  if (!(window.echarts && window.echarts.graphicGL)) { await script('vendor/echarts-gl/echarts-gl.min.js'); }
}

/**
 * The map shapes' helper (window.drishtiGeo): it fetches the vendored Natural Earth files from beside itself.
 * @returns {Promise<Object>}
 */
export async function geo() {
  if (!window.drishtiGeo) { await script('js/geomap.js'); }
  return window.drishtiGeo;
}

/**
 * The renderer's sheet as a constructable sheet (adopted by shadow roots; style-src is not asked).
 * @returns {Promise<CSSStyleSheet>}
 */
export function sheet() {
  return memoised('sheet', async () => {
    const res = await fetch(base.sheet || statics('render/' + RENDERER_SHEET_VERSION + '/drishti-render.css'), { credentials: 'omit' });
    if (!res.ok) { throw new Error('drishti-render.css: HTTP ' + res.status); }
    const s = new CSSStyleSheet();
    s.replaceSync(await res.text());
    return s;
  });
}

/** The renderer version whose folder holds the sheet when no entry said where it is. */
export const RENDERER_SHEET_VERSION = '2.0.0';

/**
 * The icon font, registered with the document (shadow roots use it).
 * @returns {Promise<void>}
 */
export function font() {
  return memoised('font', async () => {
    if ([...document.fonts].some((f) => f.family.replace(/"/g, '') === 'bootstrap-icons')) { return; }
    const face = new FontFace('bootstrap-icons', 'url(' + statics('vendor/bootstrap-icons/fonts/bootstrap-icons.woff2') + ') format("woff2")');
    document.fonts.add(await face.load());
  });
}

/**
 * Whether script URLs go through the renderer's Trusted Types policy.
 * @returns {boolean}
 */
export const trustedTypesActive = () => !!trusted();
