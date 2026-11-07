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
/* drishti-tree's export and keys. Export: the drawing as SVG (self-contained: its colours are values) or PNG (the SVG on a canvas at
   2×), and the data as CSV (one row per node with its path: drishti-core's writer) or JSON (nested). The keys of the drawn modes (the
   org chart and the partitions; the tree grid keeps drishti-grid's own): ↑ ↓ the previous and next node shown, → open or the first
   child, ← close or the parent, Home and End, + and − open and close, * everything below, Enter or E explains, Space selects. */
import { toCsv, download } from '../../drishti-core/src/drishti_index.js';

/** The formats a tree exports. */
export const TREE_EXPORTS = Object.freeze(['png', 'svg', 'csv', 'json']);

/**
 * A drawing as SVG text.
 * @param {SVGSVGElement} el
 * @param {{font: string, ground: string}} [o] the font family and the background drawn under it
 * @returns {string}
 */
export function svgText(el, o) {
  const c = el.cloneNode(true);
  c.setAttribute('xmlns', 'http://www.w3.org/2000/svg');
  if (o && o.font) { c.setAttribute('font-family', o.font); }
  if (o && o.ground) {
    const doc = el.ownerDocument, bg = doc.createElementNS('http://www.w3.org/2000/svg', 'rect'), vb = (c.getAttribute('viewBox') || '0 0 0 0').split(/\s+/).map(Number);
    bg.setAttribute('x', String(vb[0])); bg.setAttribute('y', String(vb[1])); bg.setAttribute('width', String(vb[2])); bg.setAttribute('height', String(vb[3])); bg.setAttribute('fill', o.ground);
    c.insertBefore(bg, c.firstChild);
  }
  c.querySelectorAll('[tabindex]').forEach((x) => x.removeAttribute('tabindex'));
  return new el.ownerDocument.defaultView.XMLSerializer().serializeToString(c);
}

/**
 * A drawing as a PNG data URL (2×).
 * @param {SVGSVGElement} el
 * @param {{font: string, ground: string}} [o]
 * @returns {Promise<string>}
 */
export function pngOf(el, o) {
  const win = el.ownerDocument.defaultView, text = svgText(el, o);
  const w = Number(el.getAttribute('width')) || 800, hgt = Number(el.getAttribute('height')) || 600;
  return new Promise((resolve, reject) => {
    const img = new win.Image();
    img.onload = () => {
      const cv = el.ownerDocument.createElement('canvas');
      cv.width = w * 2; cv.height = hgt * 2;
      const g = cv.getContext('2d');
      g.scale(2, 2);
      g.drawImage(img, 0, 0, w, hgt);
      resolve(cv.toDataURL('image/png'));
    };
    img.onerror = () => reject(new Error('the drawing could not be made a picture'));
    img.src = 'data:image/svg+xml;charset=utf-8,' + encodeURIComponent(text);
  });
}

/**
 * The data of a hierarchy as text: CSV (a row per node) or JSON (nested).
 * @param {import('./drishti_tree-model.js').HierarchyModel} model
 * @param {('csv'|'json')} kind
 * @returns {string}
 */
export function dataText(model, kind) { return kind === 'json' ? JSON.stringify(model.toJSON(), null, 1) : toCsv(model.toTable()); }

/**
 * Where a key moves in a drawn mode: the next node to focus, or an action.
 * @param {string} key the KeyboardEvent's key
 * @param {Object} model the HierarchyModel
 * @param {string[]} order the nodes in the order shown
 * @param {?string} at the node focused
 * @param {boolean} [tree=true] the org chart (→ and ← open, close and walk the tree); false: a partition (they move like ↓ and ↑)
 * @returns {?{to: string, act: string}} act: focus, open, close, expand-below, explain, select; null: not a key of the tree
 */
export function keyMove(key, model, order, at, tree) {
  if (!order.length) { return null; }
  const i = Math.max(0, order.indexOf(at)), n = model.node(order[i]), go = (j) => ({ to: order[Math.max(0, Math.min(order.length - 1, j))], act: 'focus' });
  switch (key) {
    case 'ArrowDown': return go(i + 1);
    case 'ArrowUp': return go(i - 1);
    case 'Home': return go(0);
    case 'End': return go(order.length - 1);
    case 'ArrowRight':
      if (tree === false) { return go(i + 1); }
      if (n.children.length && !model.isOpen(n.id)) { return { to: n.id, act: 'open' }; }
      return n.children.length && order.indexOf(n.children[0].id) >= 0 ? { to: n.children[0].id, act: 'focus' } : { to: n.id, act: 'focus' };
    case 'ArrowLeft':
      if (tree === false) { return go(i - 1); }
      if (n.children.length && model.isOpen(n.id)) { return { to: n.id, act: 'close' }; }
      return n.parent ? { to: n.parent.id, act: 'focus' } : { to: n.id, act: 'focus' };
    case '+': return { to: n.id, act: 'open' };
    case '-': return { to: n.id, act: 'close' };
    case '*': return { to: n.id, act: 'expand-below' };
    case 'Enter': case 'e': case 'E': return { to: n.id, act: 'explain' };
    case ' ': return { to: n.id, act: 'select' };
    default: return null;
  }
}

/**
 * Exports a Tree's drawing (png, svg: the org chart and the partitions) or its data (csv, json), through its download option (default
 * drishti-core's download); the tree emits exported.
 * @param {Object} t the Tree
 * @param {('png'|'svg'|'csv'|'json')} kind
 * @returns {Promise<{name: string, content: string, type: string}>}
 */
export function exportTree(t, kind) {
  const name = 'drishti-tree' + (t.o.title ? '-' + t.o.title.toLowerCase().replace(/[^a-z0-9]+/g, '-').replace(/^-|-$/g, '') : '');
  const save = (file, content, type) => { (t.o.download || ((n, c, ty) => download(t.doc, n, c, ty)))(file, content, type); t.emit('exported', { kind: kind, name: file }); return { name: file, content: content, type: type }; };
  if (kind === 'csv' || kind === 'json') { return Promise.resolve(save(name + '.' + kind, dataText(t.model, kind), kind === 'csv' ? 'text/csv' : 'application/json')); }
  const el = t.drawn && t.drawn.svg;
  if (!el) { return Promise.reject(new Error('the tree grid exports its data (CSV, JSON); PNG and SVG draw the org chart, the sunburst and the icicle')); }
  const look = { font: t.theme.font, ground: t.theme.surface };
  if (kind === 'svg') { return Promise.resolve(save(name + '.svg', svgText(el, look), 'image/svg+xml')); }
  return pngOf(el, look).then((url) => save(name + '.png', url, 'image/png'));
}
