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
/* drishti-tree's drawings in SVG (g6 A and C): the org chart (boxes with the kind, the label, the first figures with ◆ on a non-additive
   one, a bar for a ratio, the ▸/▾ toggle with the children's count; links curved, a further parent's dashed), the sunburst (rings from
   the focus) and the icicle (rows of the same partition). Colours are the theme's values (drishti-core's visualTheme), so a drawing
   exported as SVG keeps them; states (selected, found, proposed, a drop target) are classes the style sheet draws. Each node is a
   focusable treeitem (org) or a labelled slice; the sunburst and icicle also give a table of the same figures (the text alternative). */
import { h, toneColour } from '../../drishti-core/src/drishti_index.js';
import { orgLayout, partition, arcPath, statusTone } from './drishti_tree-layout.js';

const NS = 'http://www.w3.org/2000/svg';

/**
 * An SVG element.
 * @param {Document} doc
 * @param {string} tag
 * @param {Object<string, *>} [attrs]
 * @param {Array<(Node|string)>} [kids]
 * @returns {SVGElement}
 */
export function svg(doc, tag, attrs, kids) {
  const el = doc.createElementNS(NS, tag);
  Object.keys(attrs || {}).forEach((k) => { const v = attrs[k]; if (v !== null && v !== undefined && v !== false) { el.setAttribute(k, String(v)); } });
  (kids || []).forEach((c) => { if (c !== null && c !== undefined) { el.appendChild(typeof c === 'string' ? doc.createTextNode(c) : c); } });
  return el;
}

/**
 * What a drawing needs: the model, the theme, the formatter of each measure, the view's state.
 * @typedef {Object} DrawContext
 * @property {import('./drishti_tree-model.js').HierarchyModel} model
 * @property {import('../../drishti-core/src/drishti_visual.js').VisualTheme} theme
 * @property {function(string, *): string} text a measure's figure as text (field, value)
 * @property {?string} selected
 * @property {Set<string>} hits the search's matches
 * @property {?string} colourBy the measure whose bar's status colours a node (else its kind)
 * @property {string[]} shown the measures a box shows
 * @property {?string} sizeBy the additive measure of the partition
 * @property {?string} focus the partition's centre
 * @property {boolean} editing
 * @property {Object} [sizes] OrgSizes
 */

/**
 * A node's fill: the tone of its status on the colourBy measure's bar (none: the no-data colour), else its kind's colour of the palette.
 * @param {DrawContext} c
 * @param {Object} n a TreeNode
 * @returns {string}
 */
export function nodeColour(c, n) {
  const m = c.colourBy && c.model.measure(c.colourBy);
  if (m && m.bar && m.bar.status) { const t = statusTone(n, m.bar); if (t) { return toneColour(c.theme, t); } return c.theme.nodata; }
  const kinds = c.kinds || (c.kinds = Array.from(new Set(c.model.all().map((x) => x.kind))));
  return toneColour(c.theme, null, Math.max(0, kinds.indexOf(n.kind)));
}

function stateClass(c, n, base) {
  return base + (c.selected === n.id ? ' dt-sel' : '') + (c.hits.has(n.id) ? ' dt-hit' : '') + (n.moved ? ' dt-moved' : '');
}

function cut(t, k) { t = String(t); return t.length > k ? t.slice(0, k - 1) + '…' : t; }

/**
 * The org chart (g6 A): an <svg> holding a group of boxes (role tree) and their links.
 * @param {Document} doc
 * @param {DrawContext} c
 * @returns {{svg: SVGSVGElement, width: number, height: number, layer: SVGGElement}}
 */
export function drawOrg(doc, c) {
  const L = orgLayout(c.model, c.sizes), t = c.theme, pad = 16;
  const layer = svg(doc, 'g', { class: 'dt-layer', role: 'tree', 'aria-label': 'Hierarchy' });
  const links = svg(doc, 'g', { class: 'dt-links', 'aria-hidden': 'true' });
  L.links.forEach((l) => links.appendChild(svg(doc, 'path', { class: 'dt-lnk' + (l.shared ? ' dt-lnk-shared' : ''), d: l.d, fill: 'none', stroke: l.shared ? t.tones.accent : t.border, 'stroke-width': 1.4,
    'stroke-dasharray': l.shared ? '4 3' : null })));
  layer.appendChild(links);
  const bar = c.model.measures.find((m) => m.bar);
  L.boxes.forEach((b) => {
    const n = c.model.node(b.id), open = c.model.isOpen(n.id), kids = n.children.length;
    const g = svg(doc, 'g', { class: stateClass(c, n, 'dt-box'), transform: 'translate(' + b.x + ',' + b.y + ')', role: 'treeitem', tabindex: '-1', 'data-id': n.id,
      'aria-level': n.depth + 1, 'aria-expanded': kids ? String(open) : null, 'aria-selected': String(c.selected === n.id),
      'aria-label': n.label + (n.kind ? ', ' + n.kind : '') + c.shown.map((f) => { const m = c.model.measure(f), v = n.values[f]; return v === undefined || v === null ? '' : ', ' + m.label + ' ' + c.text(f, v); }).join('') });
    g.appendChild(svg(doc, 'rect', { class: 'dt-b', width: b.w, height: b.h, rx: 7, fill: t.surface, stroke: t.border }));
    g.appendChild(svg(doc, 'rect', { class: 'dt-kindbar', width: 4, height: b.h, rx: 2, fill: nodeColour(c, n) }));
    g.appendChild(svg(doc, 'text', { class: 'dt-kd', x: 12, y: 15, fill: t.faint }, [cut((n.kind || '').toUpperCase(), 26)]));
    g.appendChild(svg(doc, 'text', { class: 'dt-nm', x: 12, y: 31, fill: t.ink }, [cut(n.label, 24)]));
    c.shown.slice(0, 2).forEach((f, i) => {
      const m = c.model.measure(f), v = n.values[f];
      const words = m.label + ' ' + (v === undefined || v === null ? (c.model.isPending(n.id, f) ? '…' : '–') : c.text(f, v)) + (m.additive === false ? ' ◆' : '');
      g.appendChild(svg(doc, 'text', { class: 'dt-mv', x: 12, y: 47 + i * 13, fill: t.muted }, [cut(words, 30)]));
    });
    if (bar && typeof n.values[bar.field] === 'number') {
      const v = n.values[bar.field], max = bar.bar.max || 1, w = Math.max(0, Math.min(1, v / max)) * (b.w - 24);
      g.appendChild(svg(doc, 'rect', { class: 'dt-barbg', x: 12, y: b.h - 9, width: b.w - 24, height: 4, rx: 2, fill: t.border }));
      g.appendChild(svg(doc, 'rect', { class: 'dt-barfg', x: 12, y: b.h - 9, width: Math.round(w * 10) / 10, height: 4, rx: 2, fill: toneColour(t, statusTone(n, bar.bar) || 'faint') }));
    }
    if (kids) {
      const tg = svg(doc, 'g', { class: 'dt-tg', transform: 'translate(' + (b.w / 2) + ',' + b.h + ')', 'data-toggle': n.id, 'aria-hidden': 'true' });
      tg.appendChild(svg(doc, 'circle', { r: 9, fill: t.surface2, stroke: t.faint }));
      tg.appendChild(svg(doc, 'text', { y: 4, 'text-anchor': 'middle', fill: t.ink }, [open ? '−' : String(kids)]));
      g.appendChild(tg);
    }
    layer.appendChild(g);
  });
  const root = svg(doc, 'svg', { class: 'dt-svg', xmlns: NS, width: L.width + 2 * pad, height: L.height + 2 * pad + 12, viewBox: (-pad) + ' ' + (-pad) + ' ' + (L.width + 2 * pad) + ' ' + (L.height + 2 * pad + 12) }, [layer]);
  return { svg: root, width: L.width + 2 * pad, height: L.height + 2 * pad + 12, layer: layer };
}

/**
 * The sunburst or the icicle (g6 C) of the partition by an additive measure.
 * @param {Document} doc
 * @param {DrawContext} c
 * @param {('sunburst'|'icicle')} shape
 * @param {{width: number, height: number}} size
 * @returns {{svg: SVGSVGElement, slices: Array<Object>, layer: SVGGElement}}
 */
export function drawPartition(doc, c, shape, size) {
  const P = partition(c.model, { sizeBy: c.sizeBy, focus: c.focus, depth: 4 }), t = c.theme, W = Math.max(200, size.width), H = Math.max(200, size.height);
  const rings = Math.max(1, P.reduce((a, s) => Math.max(a, s.depth), 0) + 1);
  const layer = svg(doc, 'g', { class: 'dt-layer', role: 'group', 'aria-label': (shape === 'sunburst' ? 'Sunburst' : 'Icicle') + ' sized by ' + c.model.measure(c.sizeBy).label });
  const R = Math.min(W, H) / 2 - 10, ring = R / rings, cx = W / 2, cy = H / 2, rowH = H / rings;
  P.forEach((s) => {
    if (s.a1 - s.a0 < 0.0005) { return; }
    const n = c.model.node(s.id), fill = s.depth === 0 && shape === 'sunburst' ? t.surface2 : nodeColour(c, n);
    const label = n.label + ': ' + c.model.measure(c.sizeBy).label + ' ' + c.text(c.sizeBy, s.value) + ' (' + Math.round((s.a1 - s.a0) * 1000) / 10 + '% of ' + c.model.node(P[0].id).label + ')';
    const common = { class: stateClass(c, n, 'dt-slice'), 'data-id': n.id, fill: fill, 'fill-opacity': s.depth === 0 ? (shape === 'sunburst' ? 1 : 0.35) : Math.max(0.5, 0.92 - s.depth * 0.08), stroke: t.surface, 'stroke-width': 1,
      tabindex: '-1', role: 'img', 'aria-label': label, 'stroke-dasharray': n.moved ? '4 3' : null };
    let el, lx, ly, room;
    if (shape === 'sunburst') {
      const r0 = s.depth === 0 ? 0 : s.depth * ring, r1 = (s.depth + 1) * ring - 1;
      el = svg(doc, 'path', Object.assign({ d: arcPath(cx, cy, r0, r1, s.a0, s.a1) }, common));
      const mid = (s.a0 + s.a1) * Math.PI - Math.PI / 2, rr = s.depth === 0 ? 0 : (r0 + r1) / 2;
      lx = cx + rr * Math.cos(mid); ly = cy + rr * Math.sin(mid) + 4; room = s.depth === 0 ? 110 : (s.a1 - s.a0) * 2 * Math.PI * rr;
    } else {
      const x = s.a0 * W, w = (s.a1 - s.a0) * W, y = s.depth * rowH;
      el = svg(doc, 'rect', Object.assign({ x: Math.round(x * 10) / 10, y: Math.round(y * 10) / 10, width: Math.max(0.5, Math.round((w - 1) * 10) / 10), height: Math.max(1, rowH - 1) }, common));
      lx = x + 6; ly = y + 16; room = w - 8;
    }
    el.appendChild(svg(doc, 'title', {}, [label]));
    layer.appendChild(el);
    if (room > 46) {
      layer.appendChild(svg(doc, 'text', { class: 'dt-sl', x: Math.round(lx * 10) / 10, y: Math.round(ly * 10) / 10, 'text-anchor': shape === 'sunburst' ? 'middle' : 'start', fill: s.depth === 0 ? t.ink : (t.tokens.bg || t.surface),
        'aria-hidden': 'true' }, [cut(n.label, Math.max(4, Math.floor(room / 7)))]));
    }
  });
  const root = svg(doc, 'svg', { class: 'dt-svg', xmlns: NS, width: W, height: H, viewBox: '0 0 ' + W + ' ' + H }, [layer]);
  return { svg: root, slices: P, layer: layer };
}

/**
 * The text alternative of a partition: a table of each slice (its breadcrumbs, its size and share, and every measure).
 * @param {Document} doc
 * @param {DrawContext} c
 * @param {Array<Object>} slices TreeSlice[]
 * @returns {HTMLTableElement}
 */
export function partitionTable(doc, c, slices) {
  const ms = c.model.measures, total = slices.length ? slices[0].value : 0;
  const head = h(doc, 'tr', {}, [h(doc, 'th', { text: 'Node', attrs: { scope: 'col' } }), h(doc, 'th', { text: 'Share', attrs: { scope: 'col' } })]
    .concat(ms.map((m) => h(doc, 'th', { text: m.label + (m.additive === false ? ' ◆' : ''), attrs: { scope: 'col' } }))));
  const rows = slices.map((s) => {
    const n = c.model.node(s.id);
    return h(doc, 'tr', { data: { id: n.id } }, [h(doc, 'th', { text: c.model.breadcrumbs(n.id).join(' › '), attrs: { scope: 'row' } }),
      h(doc, 'td', { cls: 'dt-n', text: total ? (Math.round((s.value / total) * 1000) / 10) + '%' : '' })]
      .concat(ms.map((m) => h(doc, 'td', { cls: 'dt-n', text: n.values[m.field] === undefined || n.values[m.field] === null ? '' : c.text(m.field, n.values[m.field]) }))));
  });
  return h(doc, 'table', { cls: 'dt-alt' }, [h(doc, 'caption', { text: 'The figures drawn, as a table' }), h(doc, 'thead', {}, [head]), h(doc, 'tbody', {}, rows)]);
}
