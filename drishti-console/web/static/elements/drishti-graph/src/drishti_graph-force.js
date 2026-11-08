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
/* The force layout, for thousands of nodes: every node pushes every other away (Barnes-Hut: a quadtree, far cells as one body, so a step
   costs n log n), every edge pulls its ends to a rest length, a weak pull holds the picture at the centre, and the movement cools step
   by step. It is deterministic: the start is a sunflower spiral and the only randomness, a nudge that separates two nodes on the same
   spot, comes from a seeded generator, so the same graph and seed always give the same picture. Pinned nodes do not move. Typed arrays,
   no allocation inside a step. */

/**
 * A seeded random generator (mulberry32): the same seed, the same numbers.
 * @param {number} seed
 * @returns {function(): number} numbers in [0, 1)
 */
export function seededRandom(seed) {
  let a = (seed >>> 0) || 1;
  return () => {
    a = (a + 0x6D2B79F5) >>> 0;
    let t = a;
    t = Math.imul(t ^ (t >>> 15), t | 1);
    t ^= t + Math.imul(t ^ (t >>> 7), t | 61);
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
}

/** The quadtree of one step: flat arrays of cells (mass, centre, bounds, four children). */
class QuadTree {
  constructor(cap) { this.grow(cap); }
  grow(cap) {
    this.cap = cap;
    this.mass = new Float64Array(cap); this.cx = new Float64Array(cap); this.cy = new Float64Array(cap);
    this.x0 = new Float64Array(cap); this.y0 = new Float64Array(cap); this.size = new Float64Array(cap);
    this.kids = new Int32Array(cap * 4); this.body = new Int32Array(cap);
  }
}

/**
 * Builds the tree, adds the bodies and sums the masses; returns false when the arrays were too small (the caller grows and asks again).
 * @private
 */
function buildTree(T, X, Y, n) {
  let x0 = Infinity, y0 = Infinity, x1 = -Infinity, y1 = -Infinity;
  for (let i = 0; i < n; i++) { if (X[i] < x0) { x0 = X[i]; } if (Y[i] < y0) { y0 = Y[i]; } if (X[i] > x1) { x1 = X[i]; } if (Y[i] > y1) { y1 = Y[i]; } }
  const size = Math.max(x1 - x0, y1 - y0, 1) + 1;
  T.n = 0;
  const mk = (cx0, cy0, s) => {
    if (T.n >= T.cap) { return -1; }
    const i = T.n++;
    T.mass[i] = 0; T.cx[i] = 0; T.cy[i] = 0; T.x0[i] = cx0; T.y0[i] = cy0; T.size[i] = s; T.body[i] = -1;
    T.kids[i * 4] = T.kids[i * 4 + 1] = T.kids[i * 4 + 2] = T.kids[i * 4 + 3] = -1;
    return i;
  };
  mk(x0, y0, size);
  const quad = (c, i) => { const h = T.size[c] / 2; return (Y[i] >= T.y0[c] + h ? 2 : 0) + (X[i] >= T.x0[c] + h ? 1 : 0); };
  const child = (c, q) => { const h = T.size[c] / 2; return mk(T.x0[c] + (q & 1) * h, T.y0[c] + (q >> 1) * h, h); };
  for (let b = 0; b < n; b++) {
    let c = 0, depth = 0;
    for (;;) {
      T.mass[c] += 1; T.cx[c] += X[b]; T.cy[c] += Y[b];
      const k4 = c * 4, leaf = T.kids[k4] < 0 && T.kids[k4 + 1] < 0 && T.kids[k4 + 2] < 0 && T.kids[k4 + 3] < 0;
      if (leaf) {
        if (T.mass[c] === 1) { T.body[c] = b; break; }
        const lb = T.body[c];
        if (lb < 0 || depth > 40) { T.body[c] = -1; break; }                // the same spot: the bodies share the cell
        T.body[c] = -1;
        const q = quad(c, lb), k = child(c, q);
        if (k < 0) { return false; }
        T.kids[k4 + q] = k; T.mass[k] = 1; T.cx[k] = X[lb]; T.cy[k] = Y[lb]; T.body[k] = lb;
      }
      const q = quad(c, b);
      let k = T.kids[k4 + q];
      if (k < 0) { k = child(c, q); if (k < 0) { return false; } T.kids[k4 + q] = k; }
      c = k; depth++;
    }
  }
  for (let i = 0; i < T.n; i++) { if (T.mass[i] > 0) { T.cx[i] /= T.mass[i]; T.cy[i] /= T.mass[i]; } }
  return true;
}

/**
 * The force layout's positions.
 * @param {{nodes: Object[], edges: Object[]}} g
 * @param {{seed: number, iterations: number, start: Map<string, {x: number, y: number}>, fixed: Map<string, {x: number, y: number}>, spacing: number, theta: number}} [o]
 *   theta: Barnes-Hut's accuracy (0.9; lower is slower and closer)
 * @returns {Map<string, {x: number, y: number}>}
 */
export function forceLayout(g, o) {
  const run = forceSteps(g, o);
  let r = run.next();
  while (!r.done) { r = run.next(); }
  return r.value;
}

/**
 * The force layout step by step (a generator), for a view that lays out a large graph a few steps a frame (the page stays responsive,
 * and the picture settles in front of the user unless motion is reduced): each yield is {step, steps, positions()}; the return value is
 * the final positions, as forceLayout's.
 * @param {{nodes: Object[], edges: Object[]}} g
 * @param {Object} [o] as forceLayout's
 * @returns {Generator<{step: number, steps: number, positions: function(): Map<string, {x: number, y: number}>}, Map<string, {x: number, y: number}>>}
 */
export function* forceSteps(g, o) {
  o = o || {};
  const n = g.nodes.length, pos = new Map();
  if (!n) { return pos; }
  const rnd = seededRandom(o.seed === undefined ? 7 : o.seed), idx = new Map(g.nodes.map((x, i) => [x.id, i]));
  const X = new Float64Array(n), Y = new Float64Array(n), VX = new Float64Array(n), VY = new Float64Array(n), fixed = new Uint8Array(n);
  const s = o.spacing || 1, rest = 46 * s, charge = -42 * s * s, theta2 = Math.pow(o.theta || 0.9, 2);
  let started = 0;
  g.nodes.forEach((x, i) => {
    const f = o.fixed && o.fixed.get(x.id), st = f || (o.start && o.start.get(x.id));
    if (st) { X[i] = st.x; Y[i] = st.y; started++; if (f || x.pinned) { fixed[i] = 1; } return; }
    const r = 10 * s * Math.sqrt(0.5 + i), a = i * Math.PI * (3 - Math.sqrt(5));     // a sunflower: even, deterministic
    X[i] = r * Math.cos(a); Y[i] = r * Math.sin(a);
  });
  // a node new to a picture starts next to a neighbour already placed
  if (started && started < n) {
    g.edges.forEach((e) => {
      const a = idx.get(e.source), b = idx.get(e.target);
      if (a === undefined || b === undefined) { return; }
      const placed = (i) => o.start.has(g.nodes[i].id) || (o.fixed && o.fixed.has(g.nodes[i].id));
      if (placed(a) && !placed(b)) { X[b] = X[a] + (rnd() - 0.5) * rest; Y[b] = Y[a] + (rnd() - 0.5) * rest; }
      else if (placed(b) && !placed(a)) { X[a] = X[b] + (rnd() - 0.5) * rest; Y[a] = Y[b] + (rnd() - 0.5) * rest; }
    });
  }
  const SRC = [], DST = [];
  g.edges.forEach((e) => { const a = idx.get(e.source), b = idx.get(e.target); if (a !== undefined && b !== undefined && a !== b) { SRC.push(a); DST.push(b); } });
  const deg = new Float64Array(n);
  for (let k = 0; k < SRC.length; k++) { deg[SRC[k]]++; deg[DST[k]]++; }
  const iters = o.iterations || (started > n * 0.8 ? 60 : n > 3000 ? 140 : n > 800 ? 220 : 300);
  let T = new QuadTree(Math.max(64, n * 4));
  let alpha = 1;
  const decay = 1 - Math.pow(0.001, 1 / iters), velocityDecay = 0.6;
  const stack = new Int32Array(4096);
  for (let it = 0; it < iters; it++) {
    while (!buildTree(T, X, Y, n)) { T = new QuadTree(T.cap * 2); }
    // repulsion
    for (let i = 0; i < n; i++) {
      let sp = 0, fx = 0, fy = 0;
      stack[sp++] = 0;
      while (sp) {
        const c = stack[--sp], m = T.mass[c];
        if (!m) { continue; }
        let dx = T.cx[c] - X[i], dy = T.cy[c] - Y[i];
        let d2 = dx * dx + dy * dy;
        const leaf = T.kids[c * 4] < 0 && T.kids[c * 4 + 1] < 0 && T.kids[c * 4 + 2] < 0 && T.kids[c * 4 + 3] < 0;
        if (leaf || (T.size[c] * T.size[c]) / (d2 || 1e-9) < theta2) {
          if (leaf && T.body[c] === i && m === 1) { continue; }
          if (d2 < 1e-6) { dx = (rnd() - 0.5) * 1e-3; dy = (rnd() - 0.5) * 1e-3; d2 = dx * dx + dy * dy; }
          if (d2 < 1) { d2 = 1; }
          const w = (charge * (leaf && T.body[c] === i ? m - 1 : m) * alpha) / d2;
          fx += dx * w; fy += dy * w;
        } else {
          for (let q = 0; q < 4; q++) { const k = T.kids[c * 4 + q]; if (k >= 0 && sp < stack.length) { stack[sp++] = k; } }
        }
      }
      VX[i] += fx; VY[i] += fy;
    }
    // springs
    for (let k = 0; k < SRC.length; k++) {
      const a = SRC[k], b = DST[k];
      let dx = X[b] + VX[b] - X[a] - VX[a], dy = Y[b] + VY[b] - Y[a] - VY[a];
      const l = Math.sqrt(dx * dx + dy * dy) || 1e-6, strength = 1 / Math.min(deg[a], deg[b]), f = ((l - rest) / l) * alpha * strength;
      dx *= f; dy *= f;
      const bias = deg[a] / (deg[a] + deg[b]);
      VX[b] -= dx * bias; VY[b] -= dy * bias; VX[a] += dx * (1 - bias); VY[a] += dy * (1 - bias);
    }
    // the centre, then move
    for (let i = 0; i < n; i++) {
      VX[i] -= X[i] * 0.02 * alpha; VY[i] -= Y[i] * 0.02 * alpha;
      if (fixed[i]) { VX[i] = VY[i] = 0; continue; }
      VX[i] *= velocityDecay; VY[i] *= velocityDecay;
      X[i] += VX[i]; Y[i] += VY[i];
    }
    alpha += (0 - alpha) * decay;
    yield { step: it + 1, steps: iters, positions: () => new Map(g.nodes.map((x, i) => [x.id, { x: X[i], y: Y[i] }])) };
  }
  g.nodes.forEach((x, i) => pos.set(x.id, { x: X[i], y: Y[i] }));
  return pos;
}
