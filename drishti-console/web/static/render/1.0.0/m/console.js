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
/* The console's behaviours, as a library module (CLIENT_RENDERER.md section 7.4; behaviours: 'console'). The per-column filter funnels
   and Chart any table (tables.js, colfilter.js), the Pivot tab's grid (pivot-engine.js, pivot-grid.js, pivot.js), the nested-pivot grid
   (tree-rows.js), the drill-down (drill.js), Zoom (zoom.js) and the controls (multiselect.js, controls.js) are each ONE implementation:
   the console's own scripts, loaded once per page from the console that serves this library (vendored, never a CDN), with data-manual so
   they do not start on the host's document, and started here on this screen's root. Each keeps its state inside the container: a drill's
   level and a table's filters never touch the host's address. Records for the Pivot tab are read as the person (the session on the
   console's origin, or the host's bearer token), from the console or the embed API. */
import * as assets from './assets.js';

/** The scripts, in the order they need each other (the engine before the grid before the pivot; colfilter before tables). */
export const SCRIPTS = ['pivot-engine', 'pivot-grid', 'colfilter', 'tables', 'tree-rows', 'pivot', 'drill', 'zoom', 'multiselect', 'controls'];

const loaded = () => assets.memoised('console-behaviours', async () => {
  for (const n of SCRIPTS) { await assets.script('js/' + n + '.js'); }
});

const enc = (s) => encodeURIComponent(String(s));
const idPath = (id) => String(id).split('/').map(enc).join('/');

/** A fetch as the person: the host's bearer token when it gave a token provider, the console's session otherwise. */
export function personFetch(o) {
  return async (url, init) => {
    const hd = Object.assign({}, (init && init.headers) || {});
    if (typeof o.token === 'function') { const t = await o.token(); const tok = typeof t === 'string' ? t : t && t.token; if (tok) { hd.Authorization = 'Bearer ' + tok; } }
    return fetch(url, Object.assign({}, init, { headers: hd, credentials: o.credentials || (o.server ? 'include' : 'same-origin'), cache: 'no-store' }));
  };
}

/**
 * Starts the console's behaviours on a painted screen. Returns {enhance(scope), dispose()}.
 * @param s the Screen (its options, its ViewModel); @param view the element that holds the painted parts
 */
export async function startBehaviours(s, view) {
  try { await loaded(); } catch (e) {
    s.emit('error', { code: 'DRS-9107', detail: 'the console\'s behaviours could not be loaded: ' + ((e && e.message) || e), fix: 'Check that this console serves its static scripts (or use behaviours: "library").', fatal: false });
    return { enhance() {}, dispose() {} };
  }
  const reg = window.drishtiModules || {}, o = s.o, root = s.root, ref = (s.vm || {}).ref || {};
  const fetcher = personFetch(o);
  const records = (path) => {                            // the console's relative /api/pivot/records/<kind>/<id>/<panel>, as this host reads it
    const m = /^\/api\/pivot\/records\/(.+)\/([^/]+)$/.exec(path);
    if (!m) { return path; }
    if (typeof o.recordsUrl === 'function') { return o.recordsUrl({ kind: ref.kind, id: ref.id, panel: decodeURIComponent(m[2]) }); }
    return (o.server || '') + '/api/pivot/records/' + m[1] + '/' + m[2];
  };
  const scope = (o.server || location.origin) + '|' + ref.kind + '/' + ref.id;
  const mods = {};
  const start = (name, ...args) => { try { return reg[name] && reg[name].init(...args); } catch (e) { if (window.console) { console.warn('drishti-render: ' + name + ' not started', e); } return null; } };
  mods.tables = start('tables', root, { scope });
  mods.tree = start('treeRows', root, { scope });
  mods.pivot = start('pivot', root, { fetch: fetcher, url: records, save: false, exports: false });
  mods.drill = start('drill', root, { address: false });
  mods.zoom = start('zoom', root);
  if (view && (s.vm || {}).controls && (s.vm.controls || []).length && typeof o.panelsUrl === 'function') {
    view.setAttribute('data-controls', JSON.stringify(s.vm.controls)); view.setAttribute('data-selects', JSON.stringify(s.vm.selects || []));
    mods.controls = start('controls', root, {
      view, urlState: false, scope: ref.kind, initial: '',
      panelsUrl: o.panelsUrl, fetchJson: async (u) => { const r = await fetcher(u); if (!r.ok) { throw new Error('HTTP ' + r.status); } return r.json(); },
      panelNode: (p) => (p.panel ? s.draw(p.panel) : null),
      afterSwap: (fresh) => { s.charts && s.charts.draw(fresh); }
    });
  }
  return {
    mods,
    /** A panel replaced by a live frame: what has no observer of its own sees it (tables, tree rows, pivots and drills observe the root). */
    enhance(fresh) { if (mods.zoom && mods.zoom.scan) { mods.zoom.scan(fresh); } },
    dispose() {
      for (const k of ['controls', 'zoom', 'drill', 'pivot', 'tree', 'tables']) { const m = mods[k]; if (m && m.dispose) { try { m.dispose(); } catch (e) { /* gone */ } } }
    }
  };
}

/** The embed API's record URL for a view's panel (Elements 2.0 and other cross-origin hosts). */
export const embedRecords = (server, base) => (r) => server + base + '/views/' + enc(r.kind) + '/' + idPath(r.id) + '/panels/' + enc(r.panel) + '/records';
