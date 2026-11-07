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
/* The loader: a page (or a domain module) loads a library only when it needs it. drishti-core is on the page already (it carries the
   loader); load('drishti-grid') adds the libraries it requires first, then its style sheets, its vendored engine's scripts (ECharts for
   drishti-chart: once, and not when the page has them) and its bundle (?v=<version>, so a new
   version is never read from a stale cache), once each, and resolves with the library's API. Where the files are: the folder
   drishti-core's bundle was loaded from, or configure({base}). The manifest is generated from drishti_elements.json by the build. */
import { MANIFEST } from './drishti_manifest.js';

const state = { base: null, staticBase: null, pending: {} };

function root() { return typeof window !== 'undefined' ? window : globalThis; }
function registry() {
  const r = root(), D = r.Drishti = r.Drishti || {}, E = D.elements = D.elements || {};
  E.libs = E.libs || {};
  return E;
}

/**
 * Where the libraries are: base, the elements folder ("/static/elements/"); staticBase, the static root the style sheets are named from
 * (default: the folder above base).
 * @param {{base: string, staticBase: string}} o
 * @returns {void}
 */
export function configure(o) {
  if (o.base) { state.base = o.base.replace(/\/?$/, '/'); }
  if (o.staticBase) { state.staticBase = o.staticBase.replace(/\/?$/, '/'); }
}

function base() { return state.base || registry().base || '/static/elements/'; }
function staticBase() { return state.staticBase || base().replace(/[^/]+\/$/, ''); }

/**
 * The manifest: every library by name, its version, files and requirements.
 * @returns {{elements: string, libraries: Object<string, Object>}}
 */
export function manifest() { return MANIFEST; }

/**
 * A library's version (null when there is no such library).
 * @param {string} name for example "drishti-grid"
 * @returns {?string}
 */
export function libraryVersion(name) { const l = MANIFEST.libraries[name]; return l ? l.version : null; }

/**
 * A library and every library it requires, deepest first, each once.
 * @param {string} name
 * @returns {string[]}
 */
export function requirements(name) {
  const out = [];
  const visit = (n, trail) => {
    const l = MANIFEST.libraries[n];
    if (!l) { throw new Error('there is no library ' + n + ' (it has ' + Object.keys(MANIFEST.libraries).join(', ') + ')'); }
    if (trail.indexOf(n) >= 0) { throw new Error('libraries require each other: ' + trail.concat([n]).join(' -> ')); }
    l.requires.forEach((r) => visit(r, trail.concat([n])));
    if (out.indexOf(n) < 0) { out.push(n); }
  };
  visit(name, []);
  return out;
}

/**
 * The library's API when it is loaded, else null.
 * @param {string} name
 * @returns {?Object}
 */
export function loaded(name) { return registry().libs[name] || null; }

function addCss(doc, href) {
  if (doc.querySelector('link[data-drishti-elements="' + href + '"]')) { return; }
  const plain = href.split('?')[0];
  const present = Array.prototype.some.call(doc.querySelectorAll('link[rel="stylesheet"]'), (l) => (l.getAttribute('href') || '').split('?')[0] === plain);
  if (present) { return; }
  const l = doc.createElement('link');
  l.rel = 'stylesheet';
  l.href = href;
  l.setAttribute('data-drishti-elements', href);
  doc.head.appendChild(l);
}

function loadOne(name) {
  if (loaded(name)) { return Promise.resolve(loaded(name)); }
  if (state.pending[name]) { return state.pending[name]; }
  const l = MANIFEST.libraries[name], r = root();
  const doc = r.document;
  if (!doc) { return Promise.reject(new Error('load: ' + name + ' is not loaded, and there is no page to load it into (under Node: require its bundle)')); }
  l.css.forEach((c) => addCss(doc, staticBase() + c + '?v=' + l.version));
  const engine = (l.scripts || []).reduce((p, src) => p.then(() => loadScript(staticBase() + src)), Promise.resolve());
  state.pending[name] = engine.then(() => new Promise((resolve, reject) => {
    const s = doc.createElement('script');
    s.src = base() + l.bundle + '?v=' + l.version;
    s.async = false;
    s.setAttribute('data-drishti-library', name);
    s.onload = () => { delete state.pending[name]; const api = loaded(name); if (api) { resolve(api); } else { reject(new Error(name + ' loaded but did not register itself')); } };
    s.onerror = () => { delete state.pending[name]; reject(new Error('Could not load ' + name + ' ' + l.version + ' from ' + s.src)); };
    doc.head.appendChild(s);
  }));
  state.pending[name].catch(() => { delete state.pending[name]; });
  return state.pending[name];
}

/**
 * The address of a file under the static root (the vendored engines: "vendor/codemirror/codemirror.js").
 * @param {string} path relative to the static root
 * @returns {string}
 */
export function staticUrl(path) { return staticBase() + String(path).replace(/^\//, ''); }

const scripts = {};

/**
 * Loads a classic script into the page once (a vendored engine a library wraps: CodeMirror, Arrow JS); resolves when it has run.
 * @param {string} src its address (staticUrl for a vendored file)
 * @returns {Promise<void>}
 */
export function loadScript(src) {
  if (scripts[src]) { return scripts[src]; }
  const doc = root().document;
  if (!doc) { return Promise.reject(new Error('loadScript: there is no page to load ' + src + ' into')); }
  // a script the page itself carries has run (classic scripts block); one this loader added has run once it says so
  const plain = src.split('?')[0];
  if (Array.prototype.some.call(doc.scripts, (s) => (s.getAttribute('src') || '').split('?')[0] === plain && (s.dataset.loaded === '1' || !s.hasAttribute('data-drishti-script')))) { return Promise.resolve(); }
  scripts[src] = new Promise((resolve, reject) => {
    const s = doc.createElement('script');
    s.src = src;
    s.async = false;
    s.setAttribute('data-drishti-script', '');
    s.onload = () => { s.dataset.loaded = '1'; resolve(); };
    s.onerror = () => { delete scripts[src]; reject(new Error('Could not load ' + src)); };
    doc.head.appendChild(s);
  });
  return scripts[src];
}

/**
 * Adds a style sheet to the page once (a vendored engine's).
 * @param {string} href
 * @returns {void}
 */
export function loadStyle(href) { const doc = root().document; if (doc) { addCss(doc, href); } }

/**
 * Loads libraries (and what they require) into the page, each once; resolves with the API of the one named, or the APIs in order.
 * @param {(string|string[])} names for example "drishti-grid"
 * @returns {Promise<(Object|Object[])>}
 * @example Drishti.elements.core.load('drishti-grid').then((grid) => grid.createGridModel({...}))
 */
export function load(names) {
  const list = Array.isArray(names) ? names : [names];
  let order;
  try { order = []; list.forEach((n) => requirements(n).forEach((x) => { if (order.indexOf(x) < 0) { order.push(x); } })); } catch (e) { return Promise.reject(e); }
  return order.reduce((p, n) => p.then(() => loadOne(n)), Promise.resolve()).then(() => Array.isArray(names) ? list.map(loaded) : loaded(names));
}
