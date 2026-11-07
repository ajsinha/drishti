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
/* Drawing the graph on a canvas, for thousands of nodes: the edges in a few batched paths (one per width and state), arrows only where
   they can be seen, the nodes outside the view skipped, labels only where they fit (or for the selected, the path and the node under the
   pointer), group frames, status badges, pins. Everything else is dimmed while a selection or a path is shown. The hit index (a grid of
   buckets over the layout) finds the node under the pointer without a scan; the minimap is the same scene drawn small with the view's
   window. A point in a lasso is pure geometry (pointInPolygon). */

/**
 * The view's transform: screen = layout × k + (tx, ty).
 * @typedef {Object} Transform
 * @property {number} k
 * @property {number} tx
 * @property {number} ty
 */

/**
 * Whether a point is inside a polygon (even-odd rule).
 * @param {number} x
 * @param {number} y
 * @param {Array<{x: number, y: number}>} poly
 * @returns {boolean}
 */
export function pointInPolygon(x, y, poly) {
  let inside = false;
  for (let i = 0, j = poly.length - 1; i < poly.length; j = i++) {
    const a = poly[i], b = poly[j];
    if ((a.y > y) !== (b.y > y) && x < ((b.x - a.x) * (y - a.y)) / (b.y - a.y || 1e-12) + a.x) { inside = !inside; }
  }
  return inside;
}

/**
 * The buckets of node positions, to find a node under a point without looking at every node.
 */
export class HitIndex {
  /**
   * @param {Array<{id: string, x: number, y: number, r: number}>} items
   * @param {number} [cell=40] the bucket's side in layout units
   */
  constructor(items, cell) {
    this.cell = cell || 40;
    this.map = new Map();
    items.forEach((it) => { const k = this.key(it.x, it.y); if (!this.map.has(k)) { this.map.set(k, []); } this.map.get(k).push(it); });
  }

  /** @private */
  key(x, y) { return Math.floor(x / this.cell) + ',' + Math.floor(y / this.cell); }

  /**
   * The nearest item whose circle (grown by slack) holds the point.
   * @param {number} x layout units
   * @param {number} y
   * @param {number} [slack=0]
   * @returns {?{id: string, x: number, y: number, r: number}}
   */
  at(x, y, slack) {
    const s = slack || 0, cx = Math.floor(x / this.cell), cy = Math.floor(y / this.cell);
    let best = null, bd = Infinity;
    const reach = 1 + Math.ceil(s / this.cell);
    for (let i = -reach; i <= reach; i++) {
      for (let j = -reach; j <= reach; j++) {
        (this.map.get((cx + i) + ',' + (cy + j)) || []).forEach((it) => {
          const d = Math.hypot(it.x - x, it.y - y);
          if (d <= it.r + s && d < bd) { best = it; bd = d; }
        });
      }
    }
    return best;
  }
}

/**
 * A node's outline as a path on a 2D context (the caller fills or strokes it).
 * @param {CanvasRenderingContext2D} ctx
 * @param {string} shape one of SHAPES
 * @param {number} x
 * @param {number} y
 * @param {number} r
 * @returns {void}
 */
export function shapePath(ctx, shape, x, y, r) {
  ctx.beginPath();
  switch (shape) {
    case 'square': ctx.rect(x - r * 0.88, y - r * 0.88, r * 1.76, r * 1.76); break;
    case 'diamond': ctx.moveTo(x, y - r * 1.15); ctx.lineTo(x + r * 1.15, y); ctx.lineTo(x, y + r * 1.15); ctx.lineTo(x - r * 1.15, y); ctx.closePath(); break;
    case 'triangle': ctx.moveTo(x, y - r * 1.15); ctx.lineTo(x + r * 1.1, y + r * 0.8); ctx.lineTo(x - r * 1.1, y + r * 0.8); ctx.closePath(); break;
    case 'hexagon': for (let i = 0; i < 6; i++) { const a = Math.PI / 3 * i + Math.PI / 6; if (i) { ctx.lineTo(x + r * Math.cos(a), y + r * Math.sin(a)); } else { ctx.moveTo(x + r * Math.cos(a), y + r * Math.sin(a)); } } ctx.closePath(); break;
    default: ctx.arc(x, y, r, 0, Math.PI * 2);
  }
}

/**
 * What one frame draws.
 * @typedef {Object} Frame
 * @property {Object[]} nodes the visible nodes (id, label, status, pinned, _group)
 * @property {Object[]} edges the visible edges (id, source, target, label)
 * @property {Map<string, {x: number, y: number}>} pos
 * @property {Map<string, {r: number, colour: string, shape: string}>} look
 * @property {Map<string, {width: number}>} edgeLook
 * @property {Array<{id: string, label: string, x: number, y: number, w: number, h: number}>} frames group frames
 * @property {Set<string>} selected
 * @property {?{nodes: Set<string>, edges: Set<string>}} path
 * @property {?string} hover
 * @property {?string} focus the keyboard's node
 * @property {boolean} directed
 * @property {Object} theme drishti-core's VisualTheme
 * @property {number} labelSize
 * @property {?Array<{x: number, y: number}>} lasso screen points
 * @property {?Set<string>} [highlight] nodes a search matched: the others dimmed
 */

/** The tones of a status badge. */
const STATUS = { ok: 'good', good: 'good', warn: 'warn', stale: 'warn', bad: 'bad', failed: 'bad', error: 'bad', running: 'link' };

/**
 * Draws frames of the graph on a canvas.
 */
export class CanvasRenderer {
  /**
   * @param {HTMLCanvasElement} canvas
   */
  constructor(canvas) { this.canvas = canvas; this.ctx = canvas.getContext('2d'); this.stats = { nodes: 0, edges: 0, labels: 0, ms: 0 }; }

  /**
   * Sizes the canvas to its box (and the screen's pixel ratio).
   * @param {number} w css pixels
   * @param {number} h
   * @param {number} [ratio]
   * @returns {void}
   */
  resize(w, h, ratio) {
    const r = ratio || 1;
    this.w = w; this.h = h; this.ratio = r;
    this.canvas.width = Math.max(1, Math.round(w * r)); this.canvas.height = Math.max(1, Math.round(h * r));
    this.canvas.style.width = w + 'px'; this.canvas.style.height = h + 'px';
  }

  /**
   * Draws one frame.
   * @param {Frame} f
   * @param {Transform} t
   * @returns {void}
   */
  draw(f, t) {
    const t0 = Date.now(), ctx = this.ctx, th = f.theme, k = t.k, w = this.w, h = this.h, ratio = this.ratio || 1;
    ctx.setTransform(ratio, 0, 0, ratio, 0, 0);
    ctx.clearRect(0, 0, w, h);
    const X = (p) => p.x * k + t.tx, Y = (p) => p.y * k + t.ty, pad = 40;
    const onScreen = (p, r) => { const x = X(p), y = Y(p); return x > -pad - r && y > -pad - r && x < w + pad + r && y < h + pad + r; };
    const focusing = f.selected.size > 0 || !!f.path || !!f.highlight;
    const near = new Set();
    if (f.selected.size && !f.path) { f.edges.forEach((e) => { if (f.selected.has(e.source)) { near.add(e.target); } if (f.selected.has(e.target)) { near.add(e.source); } }); }
    const marked = (id) => f.selected.has(id) || near.has(id) || (f.path && f.path.nodes.has(id));
    const lit = (id) => !focusing || (f.highlight ? f.highlight.has(id) || f.selected.has(id) : marked(id));
    // group frames
    ctx.font = '600 ' + Math.max(9, Math.min(13, 11 * Math.sqrt(k))) + 'px ' + th.font;
    (f.frames || []).forEach((fr) => {
      ctx.fillStyle = th.ground; ctx.strokeStyle = th.border; ctx.lineWidth = 1;
      ctx.beginPath(); roundRect(ctx, fr.x * k + t.tx, fr.y * k + t.ty, fr.w * k, fr.h * k, 10 * Math.min(1, k)); ctx.fill(); ctx.stroke();
      if (k > 0.25) { ctx.fillStyle = th.muted; ctx.fillText(String(fr.label).toUpperCase(), fr.x * k + t.tx + 12 * k, fr.y * k + t.ty + 18 * k); }
    });
    // edges: batched by state
    let drawnE = 0;
    const arrows = f.directed && k > 0.45 && f.edges.length < 4000;
    const batch = (filter, colour, alpha, wmul) => {
      const byWidth = new Map();
      f.edges.forEach((e) => {
        if (!filter(e)) { return; }
        const a = f.pos.get(e.source), b = f.pos.get(e.target);
        if (!a || !b) { return; }
        const ax = X(a), ay = Y(a), bx = X(b), by = Y(b);
        if ((ax < -pad && bx < -pad) || (ay < -pad && by < -pad) || (ax > w + pad && bx > w + pad) || (ay > h + pad && by > h + pad)) { return; }
        const lw = Math.max(0.5, Math.min(6, ((f.edgeLook.get(e.id) || {}).width || 1) * wmul * Math.min(1.6, Math.max(0.5, k))));
        const key = Math.round(lw * 4) / 4;
        if (!byWidth.has(key)) { byWidth.set(key, []); }
        byWidth.get(key).push([ax, ay, bx, by, e]);
      });
      ctx.globalAlpha = alpha; ctx.strokeStyle = colour; ctx.fillStyle = colour;
      byWidth.forEach((segs, lw) => {
        ctx.lineWidth = lw; ctx.beginPath();
        segs.forEach(([ax, ay, bx, by]) => { ctx.moveTo(ax, ay); ctx.lineTo(bx, by); });
        ctx.stroke(); drawnE += segs.length;
        if (arrows) {
          ctx.beginPath();
          segs.forEach(([ax, ay, bx, by, e]) => {
            const r = ((f.look.get(e.target) || {}).r || 6) * Math.min(1.4, Math.max(0.6, k)) + 2, d = Math.hypot(bx - ax, by - ay);
            if (d < r * 2.5) { return; }
            const ux = (bx - ax) / d, uy = (by - ay) / d, tx = bx - ux * r, ty = by - uy * r, s = 4 + lw;
            ctx.moveTo(tx, ty); ctx.lineTo(tx - ux * s - uy * s * 0.55, ty - uy * s + ux * s * 0.55); ctx.lineTo(tx - ux * s + uy * s * 0.55, ty - uy * s - ux * s * 0.55); ctx.closePath();
          });
          ctx.fill();
        }
      });
      ctx.globalAlpha = 1;
    };
    const onPath = (e) => f.path && f.path.edges.has(e.id);
    const touches = (e) => !f.path && (f.selected.has(e.source) || f.selected.has(e.target));
    batch((e) => !onPath(e) && !touches(e), th.muted, focusing ? 0.12 : (f.edges.length > 3000 ? 0.22 : 0.42), 1);
    batch(touches, th.tones.accent, 0.85, 1.3);
    batch(onPath, th.tones.accent, 1, 2.2);
    // edge labels, where there are few edges and room for them
    if (f.edges.length <= 80 && k > 0.5) {
      ctx.font = Math.max(9, f.labelSize - 1.5) + 'px ' + th.font; ctx.textAlign = 'center'; ctx.textBaseline = 'bottom'; ctx.fillStyle = th.muted;
      f.edges.forEach((e) => {
        const a = f.pos.get(e.source), b = f.pos.get(e.target);
        if (!e.label || !a || !b || (focusing && !lit(e.source) && !lit(e.target))) { return; }
        ctx.fillText(e.label.length > 22 ? e.label.slice(0, 21) + '…' : e.label, (X(a) + X(b)) / 2, (Y(a) + Y(b)) / 2 - 2);
      });
    }
    // nodes
    let drawnN = 0, labels = 0;
    const scale = Math.min(1.4, Math.max(0.6, k));
    const order = f.nodes.slice().sort((a, b) => (lit(a.id) ? 1 : 0) - (lit(b.id) ? 1 : 0));
    order.forEach((n) => {
      const p = f.pos.get(n.id), L = f.look.get(n.id);
      if (!p || !L) { return; }
      const r = L.r * scale;
      if (!onScreen(p, r)) { return; }
      const x = X(p), y = Y(p);
      ctx.globalAlpha = lit(n.id) ? 1 : 0.22;
      shapePath(ctx, L.shape, x, y, r);
      ctx.fillStyle = L.colour; ctx.fill();
      const sel = f.selected.has(n.id), onP = f.path && f.path.nodes.has(n.id);
      ctx.lineWidth = sel || onP ? 2.5 : n._group ? 1.6 : 1;
      ctx.strokeStyle = sel || onP ? th.tones.accent : n.id === f.focus ? th.tones.link : th.surface;
      ctx.stroke();
      if (n.id === f.focus) { ctx.setLineDash([3, 2]); ctx.lineWidth = 1.5; ctx.strokeStyle = th.tones.link; shapePath(ctx, L.shape, x, y, r + 4); ctx.stroke(); ctx.setLineDash([]); }
      if (n.pinned) { ctx.fillStyle = th.ink; ctx.beginPath(); ctx.arc(x + r * 0.75, y - r * 0.75, 2.4, 0, Math.PI * 2); ctx.fill(); }
      if (n.status && STATUS[n.status]) { ctx.fillStyle = th.tones[STATUS[n.status]]; ctx.strokeStyle = th.surface; ctx.lineWidth = 1.5; ctx.beginPath(); ctx.arc(x + r * 0.85, y + r * 0.7, Math.max(3, r * 0.38), 0, Math.PI * 2); ctx.fill(); ctx.stroke(); }
      drawnN++;
    });
    // labels last, where they fit
    ctx.globalAlpha = 1;
    ctx.font = f.labelSize + 'px ' + th.font; ctx.textAlign = 'center'; ctx.textBaseline = 'top';
    const many = f.nodes.length > 400, room = k * 1;
    f.nodes.forEach((n) => {
      const p = f.pos.get(n.id), L = f.look.get(n.id);
      if (!p || !L) { return; }
      const special = f.selected.has(n.id) || n.id === f.hover || n.id === f.focus || (f.path && f.path.nodes.has(n.id)) || n._group;
      if (!special && (many ? room < 1.6 : room < 0.55)) { return; }
      if (!special && focusing && !lit(n.id)) { return; }
      if (!onScreen(p, 0)) { return; }
      const r = L.r * scale, text = n.label.length > 28 ? n.label.slice(0, 27) + '…' : n.label;
      ctx.fillStyle = special ? th.ink : th.muted;
      ctx.fillText(text, X(p), Y(p) + r + 3);
      labels++;
    });
    if (f.lasso && f.lasso.length > 1) {
      ctx.strokeStyle = th.tones.accent; ctx.fillStyle = th.tones.accent; ctx.lineWidth = 1.2; ctx.setLineDash([4, 3]);
      ctx.beginPath(); f.lasso.forEach((q, i) => (i ? ctx.lineTo(q.x, q.y) : ctx.moveTo(q.x, q.y))); ctx.closePath(); ctx.stroke();
      ctx.globalAlpha = 0.08; ctx.fill(); ctx.globalAlpha = 1; ctx.setLineDash([]);
    }
    this.stats = { nodes: drawnN, edges: drawnE, labels, ms: Date.now() - t0 };
  }

  /**
   * Draws the minimap: every node as a dot, and the view's window.
   * @param {Frame} f
   * @param {{x: number, y: number, w: number, h: number}} box the layout's bounds
   * @param {Transform} view the main view's transform
   * @param {{w: number, h: number}} viewSize the main view's size in css pixels
   * @returns {Transform} the minimap's own transform (its clicks are read with it)
   */
  drawMinimap(f, box, view, viewSize) {
    const ctx = this.ctx, w = this.w, h = this.h, ratio = this.ratio || 1, th = f.theme;
    ctx.setTransform(ratio, 0, 0, ratio, 0, 0);
    ctx.clearRect(0, 0, w, h);
    const k = Math.min((w - 8) / Math.max(1, box.w), (h - 8) / Math.max(1, box.h)), tx = (w - box.w * k) / 2 - box.x * k, ty = (h - box.h * k) / 2 - box.y * k;
    f.nodes.forEach((n) => {
      const p = f.pos.get(n.id), L = f.look.get(n.id);
      if (!p || !L) { return; }
      ctx.fillStyle = f.selected.has(n.id) ? th.tones.accent : L.colour;
      ctx.fillRect(p.x * k + tx - 1, p.y * k + ty - 1, 2, 2);
    });
    const x0 = (-view.tx / view.k) * k + tx, y0 = (-view.ty / view.k) * k + ty;
    ctx.strokeStyle = th.tones.accent; ctx.lineWidth = 1.2;
    ctx.strokeRect(x0, y0, (viewSize.w / view.k) * k, (viewSize.h / view.k) * k);
    return { k, tx, ty };
  }
}

/** A rounded rectangle path (the context's roundRect where there is none). */
function roundRect(ctx, x, y, w, h, r) {
  ctx.moveTo(x + r, y); ctx.arcTo(x + w, y, x + w, y + h, r); ctx.arcTo(x + w, y + h, x, y + h, r); ctx.arcTo(x, y + h, x, y, r); ctx.arcTo(x, y, x + w, y, r); ctx.closePath();
}
