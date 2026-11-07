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
/* A flow layout's drop places: under a point, the item it would land before or after (side by side when items share a row, else
   above or below), the end of an area when the point is in an area's empty part, or "on" an item (its middle, away from its rim) for
   a payload the host may drop onto an item (a field onto a panel). The line or box that shows it is one element, placed through
   custom properties. */

const EDGE_PX = 18;

/**
 * Where a drop at a point lands in a flow canvas.
 * @typedef {Object} FlowTarget
 * @property {string} area
 * @property {?string} id the item it lands next to (null: the area's end)
 * @property {('before'|'after')} [rel]
 * @property {boolean} [side] items share a row there (the line is upright)
 * @property {boolean} [edge] the point is on the item's rim (between items)
 * @property {boolean} [gap] the point is past the area's last item
 * @property {DOMRect} rect the item's (or the area's) box
 */

/**
 * The flow target under a point, or null outside every area.
 * @param {import('./drishti_layout-canvas.js').LayoutCanvas} canvas
 * @param {number} x
 * @param {number} y
 * @returns {?FlowTarget}
 */
export function flowHit(canvas, x, y) {
  const doc = canvas.doc, areas = canvas.o.areas || {}, names = Object.keys(areas).filter((k) => areas[k]);
  const el = doc.elementFromPoint(x, y);
  if (!el || !canvas.el.contains(el)) { return null; }
  const itemEl = el.closest('[data-dd-id]');
  if (itemEl && canvas.els.get(itemEl.getAttribute('data-dd-id')) === itemEl) {
    const r = itemEl.getBoundingClientRect(), cr = itemEl.parentNode.getBoundingClientRect(), side = r.width < cr.width * 0.9;
    const before = side ? x < r.left + r.width / 2 : y < r.top + r.height / 2;
    const area = names.find((n) => areas[n] === itemEl.parentNode) || names[0];
    return { area, id: itemEl.getAttribute('data-dd-id'), rel: before ? 'before' : 'after', side, rect: r, edge: Math.min(x - r.left, r.right - x, y - r.top, r.bottom - y) < EDGE_PX };
  }
  const name = names.find((n) => areas[n].contains(el)) || names.find((n) => { const r = areas[n].getBoundingClientRect(); return x >= r.left && x <= r.right && y >= r.top - 40 && y <= r.bottom + 40; });
  if (!name) { return null; }
  const list = canvas.model.inArea(name), last = list[list.length - 1], lastEl = last && canvas.els.get(last.id);
  if (lastEl) { return { area: name, id: last.id, rel: 'after', side: false, rect: lastEl.getBoundingClientRect(), gap: true }; }
  return { area: name, id: null, rect: areas[name].getBoundingClientRect() };
}

/**
 * Shows where a drop lands: a line between items, a box over an empty area, or the item marked when the payload would go onto it.
 * @param {import('./drishti_layout-canvas.js').LayoutCanvas} canvas
 * @param {?FlowTarget} t
 * @param {Object} payload ({item} when an item of the canvas moves)
 * @returns {void}
 */
export function showFlowDrop(canvas, t, payload) {
  clearFlowDrop(canvas);
  if (!t) { return; }
  if (t.id && !payload.item && !t.edge && !t.gap) {
    const on = canvas.els.get(t.id);
    if (on) { on.classList.add('dd-drop-on'); canvas.dropMark = on; }
    return;
  }
  const doc = canvas.doc, box = doc.createElement('div'), r = t.rect;
  box.className = 'dd-drop-line';
  box.setAttribute('aria-hidden', 'true');
  const put = (l, tp, w, hh) => { box.style.setProperty('--l', l + 'px'); box.style.setProperty('--t', tp + 'px'); box.style.setProperty('--w', w + 'px'); box.style.setProperty('--h', hh + 'px'); };
  if (!t.id) { box.classList.add('dd-drop-box'); put(r.left, r.top, r.width, Math.max(r.height, 60)); }
  else if (t.side) { put(t.rel === 'before' ? r.left - 4 : r.right - 2, r.top, 6, r.height); }
  else { put(r.left, t.rel === 'before' ? r.top - 4 : r.bottom - 2, r.width, 6); }
  doc.body.appendChild(box);
  canvas.dropMark = box;
}

/**
 * Takes the drop's mark away.
 * @param {import('./drishti_layout-canvas.js').LayoutCanvas} canvas
 * @returns {void}
 */
export function clearFlowDrop(canvas) {
  const m = canvas.dropMark;
  if (!m) { return; }
  if (m.classList.contains('dd-drop-line')) { m.remove(); } else { m.classList.remove('dd-drop-on'); }
  canvas.dropMark = null;
}
