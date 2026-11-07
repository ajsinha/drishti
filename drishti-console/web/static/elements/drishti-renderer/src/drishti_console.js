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
/* behaviours: 'console' (CLIENT_RENDERER.md section 7.4): the console's richer behaviours inside the container. From the libraries:
   the tree rows and the drill-down (drishti-grid's document table, behave.js) and the nested pivot, drawn by drishti-grid's own grid
   with tree data (nested.js). From the console, until their screens move onto the libraries (ELEMENTS.md section 12): the per-column
   filter funnels and Chart any table (drishti_tables.js, drishti_colfilter.js), Zoom (drishti_zoom.js) and the controls
   (drishti_multiselect.js, drishti_controls.js), each ONE implementation, loaded once per page from the console that serves this
   library (vendored, never a CDN), with data-manual so they do not start on the host's document, and started here on this screen's
   root. Each keeps its state inside the container: a table's filters never touch the host's address. */
import * as assets from './drishti_assets.js';
import { strip } from './drishti_view.js';
import { personFetch } from './drishti_net.js';
import { drawNestedPivots } from './drishti_nested.js';
import { consoleTables } from '../../drishti-grid/src/drishti_index.js';
import { textSortKey, compareTextKeys } from '../../drishti-core/src/drishti_index.js';

/** The console's scripts still loaded, in the order they need each other (colfilter before tables). */
export const SCRIPTS = ['colfilter', 'tables', 'zoom', 'multiselect', 'controls'];

const loaded = () => assets.memoised('console-behaviours', async () => {
  for (const n of SCRIPTS) { await assets.script('js/drishti_' + n + '.js'); }
});

const enc = (s) => encodeURIComponent(String(s));
const idPath = (id) => String(id).split('/').map(enc).join('/');

/**
 * Starts the console behaviours on a painted screen.
 * @param {Object} s the Screen (its options, its ViewModel)
 * @param {Element} view the element that holds the painted parts
 * @returns {Promise<{mods: Object, enhance: function(Element): void, dispose: function(): void}>}
 */
export async function startBehaviours(s, view) {
  const grids = drawNestedPivots(s.root, s.o);
  try { await loaded(); } catch (e) {
    s.emit('error', { code: 'DRS-9107', detail: 'the console\'s behaviours could not be loaded: ' + ((e && e.message) || e), fix: 'Check that this console serves its static scripts (or use behaviours: "library").', fatal: false });
    return { mods: {}, enhance(fresh) { grids.add(fresh); }, dispose() { grids.dispose(); } };
  }
  const reg = window.drishtiModules || {}, o = s.o, root = s.root, ref = (s.vm || {}).ref || {};
  const fetcher = personFetch(o);
  const scope = (o.server || location.origin) + '|' + ref.kind + '/' + ref.id;
  const mods = {};
  const start = (name, ...args) => { try { return reg[name] && reg[name].init(...args); } catch (e) { if (window.console) { console.warn('drishti-renderer: ' + name + ' not started', e); } return null; } };
  if (reg.colFilter && reg.colFilter.useSortKeys) { reg.colFilter.useSortKeys(textSortKey, compareTextKeys); }
  mods.tables = start('tables', root, { scope, chart: true, grid: { consoleTables } });   // the tables are drishti-grid's; the console's host adds the funnels and the chart
  mods.zoom = start('zoom', root);
  if (view && (s.vm || {}).controls && (s.vm.controls || []).length && typeof o.panelsUrl === 'function') {
    view.setAttribute('data-controls', JSON.stringify(s.vm.controls)); view.setAttribute('data-selects', JSON.stringify(s.vm.selects || []));
    mods.controls = start('controls', root, {
      view, urlState: false, scope: ref.kind, initial: '',
      panelsUrl: o.panelsUrl, fetchJson: async (u) => { const r = await fetcher(u); if (!r.ok) { throw new Error('HTTP ' + r.status); } return r.json(); },
      panelNode: (p) => (p.panel ? s.draw(p.panel) : null), stripNode: (cells) => strip(s.R, { strip: cells || [] }),
      afterSwap: (fresh) => { if (s.charts) { s.charts.draw(fresh); } grids.add(fresh); }
    });
  }
  return {
    mods,
    /** A panel replaced by a live frame: what has no observer of its own sees it (the console's tables observe the root). */
    enhance(fresh) { if (mods.zoom && mods.zoom.scan) { mods.zoom.scan(fresh); } grids.add(fresh); },
    dispose() {
      grids.dispose();
      for (const k of ['controls', 'zoom', 'tables']) { const m = mods[k]; if (m && m.dispose) { try { m.dispose(); } catch (e) { /* gone */ } } }
    }
  };
}

/**
 * The embed API's record address for a view's panel (Elements 2.x and other cross-origin hosts).
 * @param {string} server
 * @param {string} base
 * @returns {function({kind: string, id: string, panel: string}): string}
 */
export const embedRecords = (server, base) => (r) => server + base + '/views/' + enc(r.kind) + '/' + idPath(r.id) + '/panels/' + enc(r.panel) + '/records';
