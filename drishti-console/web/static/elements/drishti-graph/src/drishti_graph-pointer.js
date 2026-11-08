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
/* The graph's pointer and keys. Pointer: the wheel zooms at the pointer, a drag on the background pans, a drag on a node moves it and
   pins it, a click selects (Shift or Ctrl adds), a double-click expands, Alt-drag (or the lasso tool) draws a lasso that selects what it
   rounds, hovering shows the tooltip; the minimap moves the view. Keys on the canvas: the arrows move to the neighbour that lies in that
   direction (or the nearest node when nothing has the focus), Enter selects, Shift+Enter adds, E or F2 explains, X expands, C collapses,
   P pins or unpins, Delete hides, + and − zoom, 0 fits, Escape clears; each move is announced. */
import { announce } from '../../drishti-core/src/drishti_index.js';
import { pointInPolygon } from './drishti_graph-canvas.js';

/**
 * The neighbour of a node that lies best in a direction: the smallest angle off the direction, then the nearest.
 * @param {{x: number, y: number}} from
 * @param {Array<{id: string, x: number, y: number}>} candidates
 * @param {('left'|'right'|'up'|'down')} dir
 * @returns {?string}
 */
export function neighbourInDirection(from, candidates, dir) {
  const want = { right: 0, down: Math.PI / 2, left: Math.PI, up: -Math.PI / 2 }[dir];
  let best = null, score = Infinity;
  candidates.forEach((c) => {
    const dx = c.x - from.x, dy = c.y - from.y, d = Math.hypot(dx, dy);
    if (!d) { return; }
    let off = Math.abs(Math.atan2(dy, dx) - want);
    if (off > Math.PI) { off = 2 * Math.PI - off; }
    if (off > Math.PI / 2.2) { return; }
    const s = off * 3 + d / 400;
    if (s < score) { score = s; best = c.id; }
  });
  return best;
}

/**
 * Installs the pointer and keys on the graph's canvas and minimap; returns the function that removes them.
 * @param {Object} G the Graph
 * @param {Object<string, HTMLElement>} P the shell's parts
 * @returns {function(): void}
 */
export function installPointer(G, P) {
  const cv = P.canvas, offs = [];
  const on = (el, type, fn, opt) => { el.addEventListener(type, fn, opt); offs.push(() => el.removeEventListener(type, fn, opt)); };
  const local = (e) => { const r = cv.getBoundingClientRect(); return { x: e.clientX - r.left, y: e.clientY - r.top }; };
  let drag = null, lastClick = { t: 0, id: null };
  on(cv, 'wheel', (e) => { e.preventDefault(); const p = local(e); G.zoomAt(p.x, p.y, Math.pow(1.0015, -e.deltaY)); }, { passive: false });
  on(cv, 'pointerdown', (e) => {
    if (e.button !== 0) { return; }
    const p = local(e), hit = G.nodeAt(p.x, p.y);
    cv.setPointerCapture && cv.setPointerCapture(e.pointerId);
    if (e.altKey || G.lassoOn) { drag = { kind: 'lasso', pts: [p] }; return; }
    drag = hit ? { kind: 'node', id: hit.id, start: p, moved: false, mods: e.shiftKey || e.ctrlKey || e.metaKey } : { kind: 'pan', start: p, t0: { tx: G.t.tx, ty: G.t.ty }, moved: false };
    cv.classList.add('dn-grabbing');
  });
  on(cv, 'pointermove', (e) => {
    const p = local(e);
    if (!drag) { G.hoverAt(p.x, p.y); return; }
    if (drag.kind === 'lasso') { drag.pts.push(p); G.drawLasso(drag.pts); return; }
    const d = Math.hypot(p.x - drag.start.x, p.y - drag.start.y);
    if (d > 3) { drag.moved = true; }
    if (!drag.moved) { return; }
    if (drag.kind === 'pan') { G.panTo(drag.t0.tx + p.x - drag.start.x, drag.t0.ty + p.y - drag.start.y); } else { G.moveNode(drag.id, p.x, p.y); }
  });
  const end = (e) => {
    cv.classList.remove('dn-grabbing');
    if (!drag) { return; }
    const d = drag;
    drag = null;
    if (d.kind === 'lasso') {
      const ids = d.pts.length > 2 ? G.visibleNodes().filter((n) => { const s = G.toScreen(n.id); return s && pointInPolygon(s.x, s.y, d.pts); }).map((n) => n.id) : [];
      G.drawLasso(null);
      if (ids.length) { G.select(ids, { add: e.shiftKey || e.ctrlKey || e.metaKey }); announce(ids.length + ' nodes selected by the lasso'); }
      return;
    }
    if (d.kind === 'node' && d.moved) { G.pin([d.id], { keep: true }); return; }
    if (d.moved) { return; }
    const now = Date.now(), id = d.kind === 'node' ? d.id : null;
    if (id && lastClick.id === id && now - lastClick.t < 350) { lastClick = { t: 0, id: null }; G.nodeDoubleClicked(id); return; }
    lastClick = { t: now, id };
    if (id) { G.nodeClicked(id, { add: d.mods }); } else if (!(e.shiftKey || e.ctrlKey || e.metaKey)) { G.clearSelection(); }
  };
  on(cv, 'pointerup', end);
  on(cv, 'pointercancel', () => { drag = null; G.drawLasso(null); });
  on(cv, 'pointerleave', () => { if (!drag) { G.hoverAt(-1e6, -1e6); } });
  // the minimap: a click or a drag centres the view there
  let miniDrag = false;
  const miniGo = (e) => { const r = P.mini.getBoundingClientRect(); G.centerOnMinimap(e.clientX - r.left, e.clientY - r.top); };
  on(P.mini, 'pointerdown', (e) => { miniDrag = true; miniGo(e); });
  on(P.mini, 'pointermove', (e) => { if (miniDrag) { miniGo(e); } });
  on(P.mini, 'pointerup', () => { miniDrag = false; });
  on(cv, 'keydown', (e) => keys(G, e));
  on(cv, 'focus', () => { if (!G.focusId && G.visibleNodes().length) { G.setFocus(G.selection()[0] || centreNode(G)); } });
  return () => offs.forEach((f) => f());
}

/** The node nearest the middle of the view. */
function centreNode(G) {
  const c = G.toLayout(G.size.w / 2, G.size.h / 2);
  let best = null, bd = Infinity;
  G.visibleNodes().forEach((n) => { const p = G.positionOf(n.id); if (p) { const d = Math.hypot(p.x - c.x, p.y - c.y); if (d < bd) { bd = d; best = n.id; } } });
  return best;
}

/** @private the canvas's keys */
function keys(G, e) {
  const k = e.key, f = G.focusId, dirs = { ArrowLeft: 'left', ArrowRight: 'right', ArrowUp: 'up', ArrowDown: 'down' };
  if (G.state().mode === 'terminal' && /^F\d+$/.test(k)) { e.preventDefault(); G.functionKey(k); return; }
  if (dirs[k]) {
    e.preventDefault();
    if (!f) { G.setFocus(centreNode(G)); return; }
    const from = G.positionOf(f), near = G.neighbourList(f).map((x) => Object.assign({ id: x.node.id }, G.positionOf(x.node.id) || {})).filter((c) => typeof c.x === 'number');
    let next = neighbourInDirection(from, near, dirs[k]);
    if (!next) {                                   // no neighbour that way: the nearest node that way
      const all = G.visibleNodes().map((n) => Object.assign({ id: n.id }, G.positionOf(n.id) || {})).filter((c) => typeof c.x === 'number');
      next = neighbourInDirection(from, all, dirs[k]);
    }
    if (next) { G.setFocus(next); } else { announce('Nothing further ' + dirs[k]); }
    return;
  }
  const act = {
    Enter: () => (f ? G.select([f], { add: e.shiftKey }) : null), ' ': () => (f ? G.select([f], { add: true }) : null),
    e: () => f && G.explain(f), E: () => f && G.explain(f), F2: () => f && G.explain(f), x: () => f && G.expand(f), c: () => f && G.collapse(f),
    p: () => f && (G.model.node(f) && G.model.node(f).pinned ? G.unpin([f]) : G.pin([f])), Delete: () => f && G.hide([f]),
    '+': () => G.zoomBy(1.3), '=': () => G.zoomBy(1.3), '-': () => G.zoomBy(1 / 1.3), 0: () => G.fit(), Escape: () => { G.clearPath(); G.clearSelection(); }
  }[k];
  if (act) { e.preventDefault(); act(); }
}
