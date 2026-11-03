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
/* Draws the chart containers kinds.js made (data-r-chart), keeps them sized and themed, and hands a click on a point, a node or a place
   that names an entity to the in-place navigation. One chart that cannot be drawn says "No data available" and leaves the others. */
import * as assets from './assets.js';
import { BUILD, tokens, drawMap } from './charts.js';
import { compact } from './format.js';
import { entityLink } from './cells.js';

function broken(E, el, words) {
  const c = E && E.getInstanceByDom(el);
  if (c) { c.dispose(); }
  el.removeAttribute('data-r-chart');
  el.className = 'pnl-empty';
  el.textContent = words || 'No data available';
}

/** A charts manager for one rendered view: draw(scope), redraw(), dispose(). */
export function charts(R, root) {
  let all = [], ro = null;
  const live = () => (all = all.filter((c) => !c.isDisposed()));

  async function drawOne(E, el, t) {
    const spec = el.__drishti;
    if (!spec) { return; }
    const { kind, data } = spec;
    if (kind === 'map') {
      try {
        const G = await assets.geo();
        const got = await drawMap(E, G, el, data || {}, t, compact, (l) => R.open(l, { source: 'chart', panel: panelOf(el) }), all);
        if (got) { got.list(got.places, (p) => entityLink(R, p.link, p.name)); got.setNote(got.note); }
      } catch (e) { broken(E, el, data && data.mode === 'region' ? 'No regions available for this country' : 'The map did not load'); }
      return;
    }
    if (kind === 'surface') { return surface(E, el, t, el.getAttribute('data-view') || 'heatmap'); }
    try {
      const c = E.getInstanceByDom(el) || E.init(el, null, { renderer: 'svg' });
      c.setOption(BUILD[kind](data || {}, t, { width: el.clientWidth || 800, height: el.clientHeight || 360 }), true);
      if (!all.includes(c)) { all.push(c); }
      if (!el.__clicks) {
        el.__clicks = true;
        c.on('click', (p) => {
          const link = p.data && ((p.data.point && p.data.point.link) || (p.data.node && p.data.node.link));
          if (link && link.kind && link.id) { R.open(link, { source: 'chart', panel: panelOf(el) }); }
        });
      }
    } catch (e) { broken(E, el); }
  }

  async function surface(E, el, t, view) {
    const d = el.__drishti.data || {};
    const draw = (mode) => {
      const old = E.getInstanceByDom(el); if (old) { old.dispose(); }
      const c = E.init(el, null, { renderer: mode === '3d' ? 'canvas' : 'svg' });
      c.setOption(BUILD.surface(d, t, null, mode), true);
      all.push(c);
    };
    let mode = view;
    if (view === '3d') { try { await assets.echartsGl(); } catch (e) { mode = 'heatmap'; } }
    try { draw(mode); } catch (e) {
      el.setAttribute('data-error', String((e && e.message) || e));
      try { draw('heatmap'); } catch (e2) { broken(E, el); }
    }
  }

  async function draw(scope) {
    const els = [...(scope || root).querySelectorAll('[data-r-chart]')];
    if (!els.length) { return; }
    let E;
    try { E = await assets.echarts(); } catch (e) { els.forEach((el) => broken(null, el, 'Charts are not available')); return; }
    const t = tokens(root.querySelector('.view') || root.host || document.documentElement);
    await Promise.all(els.map((el) => drawOne(E, el, t)));
    if (!ro && window.ResizeObserver) {
      ro = new ResizeObserver(() => requestAnimationFrame(() => { live().forEach((c) => c.resize()); }));
      ro.observe(root.host || root.firstElementChild || document.body);
    }
  }

  /** The surface's Heatmap | 3D buttons. */
  function onClick(e) {
    const b = e.target.closest && e.target.closest('[data-surface-view]');
    if (!b) { return; }
    const box = b.closest('.pnl-b'), el = box && box.querySelector('.surface[data-r-chart]');
    if (!el) { return; }
    box.querySelectorAll('[data-surface-view]').forEach((x) => x.setAttribute('aria-pressed', x === b ? 'true' : 'false'));
    el.setAttribute('data-view', b.getAttribute('data-surface-view'));
    assets.echarts().then((E) => surface(E, el, tokens(el), b.getAttribute('data-surface-view')));
  }
  root.addEventListener('click', onClick);

  return {
    draw,
    redraw: () => draw(root),
    instances: () => live().slice(),
    dispose() {
      root.removeEventListener('click', onClick);
      if (ro) { ro.disconnect(); ro = null; }
      live().forEach((c) => c.dispose());
      all = [];
    }
  };
}

const panelOf = (el) => { const p = el.closest('.pnl'); return p ? p.getAttribute('data-panel') : null; };
