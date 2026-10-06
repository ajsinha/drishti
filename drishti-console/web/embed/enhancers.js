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
/* The ES-module face of the console's enhancer scripts (static/js: view, charts, tables, tree-rows, pivot, about, about-hints). Each is
   a script that registers `window.drishtiModules.<name> = {init(root, options)}`; this module loads them once per page (assets.js) and
   starts them on one element's shadow root, with fetches that carry the bearer and go to the embed API, and writes that are sanitised.
   The console's own pages keep loading the same scripts as classic scripts and start them on `document` (nothing there changes). */
import { API, esc } from './config.js';
import { authed } from './tokens.js';
import { setHtml } from './sanitize.js';

const ID_PATH = (id) => id.split('/').map(encodeURIComponent).join('/');

/** The About drawer's markup for a view (the element renders it; the console's about.js only finds and fills it). */
function drawerHtml(base, kind, id) {
  return '<aside class="about side" id="aboutDrawer" role="dialog" aria-modal="false" aria-labelledby="aboutTitle" hidden data-tab="about" data-about-url="'
    + esc(base + '/views/' + encodeURIComponent(kind) + '/' + ID_PATH(id) + '/about') + '"><div class="about-grip" aria-hidden="true"></div>'
    + '<header><h2 id="aboutTitle" tabindex="-1">About this page</h2><button type="button" class="raw-x" data-about-close aria-label="Close the side drawer">×</button></header>'
    + '<div class="about-body" id="aboutPanel" data-tab-panel="about" data-about-body aria-live="polite"><p class="about-wait">Loading…</p></div>'
    + '<p class="about-live" role="status" data-about-status></p></aside>';
}

/**
 * Starts the enhancers on `host`'s shadow root for a freshly painted `.view`; returns the handles (for dispose) or {}.
 * @param host the <drishti-view>; @param view its `.view` element; @param server the console's base URL; @param reload a function that reloads the host
 */
export function boot(host, view, server, reload) {
  const root = host.shadowRoot, reg = window.drishtiModules || {};
  const kind = view.getAttribute('data-kind'), id = view.getAttribute('data-id');
  const base = server + API;
  const fetcher = (u, o) => authed(u, o, undefined);
  const toUrl = (p) => {                          // the console's relative paths, as embed API calls
    const m = /^\/api\/pivot\/records\/(.+)\/([^/]+)$/.exec(p);
    return m ? base + '/views/' + m[1] + '/panels/' + m[2] + '/records' : p;
  };
  const holder = document.createElement('div');
  setHtml(holder, drawerHtml(base, kind, id));
  root.appendChild(holder.firstChild);
  if (!view.querySelector('[data-about-open]')) {
    const b = document.createElement('button');
    b.type = 'button'; b.className = 'tbar-link vhead-alert'; b.setAttribute('data-about-open', ''); b.setAttribute('aria-controls', 'aboutDrawer'); b.setAttribute('aria-expanded', 'false');
    b.title = 'About this page: where the data came from and why it looks like this (?)'; setHtml(b, '<i class="bi bi-info-circle" aria-hidden="true"></i> About');
    view.insertBefore(b, view.firstChild);
  }
  host.absolutise(view);
  const m = {};
  const start = (name, ...args) => { try { return reg[name] && reg[name].init(...args); } catch (e) { if (window.console) { console.warn('drishti: ' + name + ' not started', e); } return null; } };
  m.view = start('view', root, { glUrl: null });
  m.charts = start('charts', root);        // an entity click is a hidden link click: it reaches the element's own link handler
  m.tables = start('tables', root, { scope: server + '|' + kind + '/' + id });
  m.tree = start('treeRows', root);
  m.pivot = start('pivot', root, { fetch: fetcher, url: toUrl, save: false, exports: false });
  m.about = start('about', root, { fetch: fetcher, guideKey: false, reload, setHtml });
  m.hints = m.about ? start('aboutHints', root, { about: m.about, open: (h) => window.open(h.startsWith('/') ? server + h : h, '_blank', 'noopener') }) : null;
  root.addEventListener('drishti:about', () => {
    const d = root.getElementById('aboutDrawer');
    if (!d) { return; }
    host.absolutise(d);
    if (!d.hidden && host.clientWidth <= 640) {     // narrow: the drawer is a bottom sheet of the element, which may be below the fold
      const r = host.getBoundingClientRect(), vh = window.innerHeight;
      if (r.bottom > vh || r.bottom < 0) { host.scrollIntoView({ block: 'end', inline: 'nearest' }); }      // a fixed box cannot be scrolled to: the element's bottom edge is where the sheet sits
    }
  });
  return m;
}

/** Ends what `boot` started: observers, listeners and chart instances. */
export function dispose(mods, root) {
  if (mods) { for (const k of ['pivot', 'tree', 'tables', 'about', 'hints', 'charts', 'view']) { mods[k] && mods[k].dispose && mods[k].dispose(); } }
  const e = window.echarts;
  if (e && root) { root.querySelectorAll('.chart, .xchart, .surface').forEach((el) => { const c = e.getInstanceByDom(el); c && !c.isDisposed() && c.dispose(); }); }
}
