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

/* The console's URL sub-path behind a reverse proxy (https://host/drishti/: server.base_path, DRISHTI_CONSOLE_BASE_PATH). The page names it
   once, <meta name="drishti-base" content="/drishti"> (blank: the console is at /), and every root-absolute URL a script builds goes
   through url() (window.drishti_url in a page): fetch(drishti_url('/api/x')), location.assign(drishti_url('/bi')). A host page that embeds
   the libraries without the meta names it with setBase(). routePath() is the other way: location.pathname without the base, for the
   comparisons the console makes against its own routes. tests/test_base_path_guard.py refuses a new root-absolute URL that skips it. */

let pinned = null;
let fromPage = null;

/**
 * The base: '' (served at /) or '/drishti' (never a trailing slash). setBase() wins over the page's meta (read once: it is in the head,
 * before every script); a worker reads globalThis.DRISHTI_BASE.
 * @returns {string}
 */
export function basePath() {
  if (pinned !== null) { return pinned; }
  if (fromPage === null) {
    const doc = typeof document !== 'undefined' ? document : null;
    const meta = doc && doc.querySelector ? doc.querySelector('meta[name="drishti-base"]') : null;
    const named = meta ? meta.getAttribute('content') : typeof globalThis.DRISHTI_BASE === 'string' ? globalThis.DRISHTI_BASE : '';
    fromPage = String(named || '').replace(/\/+$/, '');
  }
  return fromPage;
}

/**
 * Names the base for the libraries where the page has no meta (a host application, a worker); null goes back to the page's.
 * @param {?string} base for example '/drishti'
 * @returns {void}
 */
export function setBase(base) { pinned = base === null || base === undefined ? null : String(base).replace(/\/+$/, ''); }

/**
 * A root-absolute URL of the console under the base: '/api/x' -> '/drishti/api/x'. Anything else (relative, '//host', 'https:', a URL
 * that already carries the base, not a string) comes back as it is, so calling it twice is harmless.
 * @param {*} path
 * @returns {*}
 */
export function url(path) {
  if (typeof path !== 'string' || path.charAt(0) !== '/' || path.charAt(1) === '/' || path.charAt(1) === '\\') { return path; }
  const b = basePath();
  if (!b || path === b || path.indexOf(b + '/') === 0 || path.indexOf(b + '?') === 0 || path.indexOf(b + '#') === 0) { return path; }
  return b + path;
}

/**
 * A path of this page (location.pathname) as the console's routes name it: '/drishti/bi/x' -> '/bi/x'.
 * @param {string} [pathname] default location.pathname
 * @returns {string}
 */
export function routePath(pathname) {
  const p = pathname !== undefined ? String(pathname) : (typeof location !== 'undefined' ? location.pathname : '/');
  const b = basePath();
  if (b && (p === b || p.indexOf(b + '/') === 0)) { return p.slice(b.length) || '/'; }
  return p;
}

/**
 * In a page: window.drishti_url and window.drishti_route (and Drishti.url, Drishti.routePath, Drishti.basePath) for the console's classic
 * scripts; under a base, a link or form the server drew from data (a menu's href, a guide's link) with a root-absolute URL is put under
 * the base the moment it is used (pressed, focused, submitted), so nothing reaches the host's root.
 * @param {Window} root
 * @returns {void}
 */
export function installBase(root) {
  root.drishti_url = url;
  root.drishti_route = routePath;
  const D = root.Drishti = root.Drishti || {};
  D.url = url;
  D.routePath = routePath;
  D.basePath = basePath;
  const doc = root.document;
  if (!doc || !doc.addEventListener || !basePath()) { return; }
  const fix = (el, attr) => {
    const v = el && el.getAttribute ? el.getAttribute(attr) : null;
    const u = url(v);
    if (v && u !== v) { el.setAttribute(attr, u); }
  };
  const onLink = (e) => { fix(e.target && e.target.closest ? e.target.closest('a[href], area[href]') : null, 'href'); };
  ['pointerdown', 'click', 'auxclick', 'contextmenu', 'focusin'].forEach((t) => doc.addEventListener(t, onLink, true));
  doc.addEventListener('submit', (e) => { fix(e.target, 'action'); if (e.submitter) { fix(e.submitter, 'formaction'); } }, true);
}
