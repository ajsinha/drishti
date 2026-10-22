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
/* "A board should look like a circuit" (the product owner): the traces of Board mode (and of a composite chip's inner board) routed as on a
   schematic. Pure geometry, no DOM: ui-board-canvas.js measures the pins and boxes and draws what this returns.
   - Manhattan routing: a wire leaves its out pin to the right and enters its in pin from the left, in straight segments with 90° bends, a
     clearance of CLEAR px from the boxes; the vertical run takes a free channel between the boxes when there is one; a wire that goes
     backwards runs round, below both boxes.
   - Fan-out: the wires of one out pin share their first segment and their vertical channel; a junction dot marks where they split.
   - Crossings are allowed: where a wire's horizontal run crosses an earlier wire's vertical run, it hops over it (a small semicircle, an SVG
     arc), so two crossing wires are never read as joined.
   U.route.plan(traces, boxes) -> {wires: [{i, points, hops: [x...], d}], junctions: [[x, y]]}
     traces: [{i, a: [x, y], b: [x, y], from, fromBox, toBox}], boxes: {instance: {left, top, right, bottom}}. Fine for 40 chips, 120 traces. */
(function () {
  'use strict';
  const U = (window.drishtiUiBoard = window.drishtiUiBoard || {});
  const CLEAR = 12, HOP = 5;

  const inside = (x, y1, y2, b) => x > b.left - 2 && x < b.right + 2 && Math.max(y1, y2) > b.top - 2 && Math.min(y1, y2) < b.bottom + 2;
  /** A vertical channel between x1 and x2 that no box (but the two ends') crosses from y1 to y2; the middle when none is free. */
  function channel(x1, x2, y1, y2, boxes, skip) {
    const mid = Math.round((x1 + x2) / 2), others = Object.keys(boxes).filter((k) => skip.indexOf(k) < 0).map((k) => boxes[k]);
    const free = (x) => others.every((b) => !inside(x, y1, y2, b));
    if (free(mid)) { return mid; }
    const cands = [];
    others.forEach((b) => { cands.push(Math.round(b.left - CLEAR), Math.round(b.right + CLEAR)); });
    const ok = cands.filter((x) => x >= x1 && x <= x2 && free(x)).sort((a, b) => Math.abs(a - mid) - Math.abs(b - mid));
    return ok.length ? ok[0] : mid;
  }
  /** Two wires of different sources never share a vertical run: the later one moves over by a step (still within its span). */
  function apart(x, x1, x2, y1, y2, used, src) {
    const clash = (v) => used.some((u) => u.src !== src && Math.abs(u.x - v) < 6 && Math.max(y1, y2) > Math.min(u.y1, u.y2) && Math.min(y1, y2) < Math.max(u.y1, u.y2));
    for (let k = 0; k < 24 && clash(x); k++) { const n = x + (k % 2 ? -1 : 1) * 8 * (Math.floor(k / 2) + 1); if (n >= x1 && n <= x2) { x = n; } }
    return x;
  }
  function route(t, boxes, shared, used) {
    const [ax, ay] = t.a, [bx, by] = t.b;
    let x1 = ax + CLEAR, x2 = bx - CLEAR;
    if (x1 > x2 && bx - ax >= 6) { x1 = x2 = Math.round((ax + bx) / 2); }          // boxes side by side: the gap between them is the channel
    if (x1 <= x2) {
      const mx = shared != null && shared >= x1 && shared <= x2 ? shared : apart(channel(x1, x2, ay, by, boxes, [t.fromBox, t.toBox]), x1, x2, ay, by, used, t.from);
      used.push({ x: mx, y1: ay, y2: by, src: t.from });
      return Math.abs(ay - by) < 0.5 ? [[ax, ay], [bx, by]] : [[ax, ay], [mx, ay], [mx, by], [bx, by]];
    }
    const fb = boxes[t.fromBox] || { top: ay, bottom: ay }, tb = boxes[t.toBox] || { top: by, bottom: by };
    const below = Math.round(Math.max(fb.bottom, tb.bottom, ay, by) + CLEAR), above = Math.round(Math.min(fb.top == null ? ay : fb.top, tb.top == null ? by : tb.top, ay, by) - CLEAR);
    const ym = Math.abs(ay - above) + Math.abs(by - above) < Math.abs(ay - below) + Math.abs(by - below) && above > 0 ? above : below;   // round the shorter way
    return [[ax, ay], [x1, ay], [x1, ym], [x2, ym], [x2, by], [bx, by]];
  }
  const segs = (pts) => pts.slice(1).map((p, k) => [pts[k], p]);
  const vertical = (s) => Math.abs(s[0][0] - s[1][0]) < 0.5 && Math.abs(s[0][1] - s[1][1]) >= 0.5;
  const horizontal = (s) => Math.abs(s[0][1] - s[1][1]) < 0.5 && Math.abs(s[0][0] - s[1][0]) >= 0.5;

  /** The path text: straight segments (L), and a hop (A) over every crossing x of a horizontal run. */
  function pathOf(pts, hopsBySeg) {
    let d = 'M' + pts[0][0] + ',' + pts[0][1];
    segs(pts).forEach((s, k) => {
      const hops = (hopsBySeg[k] || []).slice();
      if (hops.length && horizontal(s)) {
        const dir = s[1][0] > s[0][0] ? 1 : -1, y = s[0][1];
        hops.sort((a, b) => (a - b) * dir).forEach((x) => {
          d += ' L' + (x - dir * HOP) + ',' + y + ' A' + HOP + ',' + HOP + ' 0 0 ' + (dir > 0 ? 1 : 0) + ' ' + (x + dir * HOP) + ',' + y;
        });
      }
      d += ' L' + s[1][0] + ',' + s[1][1];
    });
    return d;
  }

  function plan(traces, boxes) {
    const bySource = {}, shared = {}, wires = [], junctions = [], used = [];
    traces.forEach((t) => { (bySource[t.from] = bySource[t.from] || []).push(t); });
    traces.forEach((t) => {
      const pts = route(t, boxes, bySource[t.from].length > 1 ? shared[t.from] : null, used);
      if (pts.length === 4 && shared[t.from] == null) { shared[t.from] = pts[1][0]; }
      wires.push({ i: t.i, from: t.from, points: pts, hopsBySeg: {} });
    });
    Object.keys(bySource).forEach((src) => {                                          // where a fan-out splits: a junction dot
      const ws = wires.filter((w) => w.from === src);
      if (ws.length < 2) { return; }
      const first = ws[0].points[0], splits = {};
      ws.forEach((w) => { const p = w.points.length === 4 ? w.points[1] : w.points[1]; splits[p[0] + ',' + p[1]] = p; });
      Object.values(splits).forEach((p) => { if (p[0] !== first[0] || p[1] !== first[1]) { junctions.push(p); } });
    });
    // crossings: a later wire's horizontal run hops over an earlier wire's vertical run (not over its own source's wires: they are joined)
    wires.forEach((w, wi) => segs(w.points).forEach((s, k) => {
      if (!horizontal(s)) { return; }
      const y = s[0][1], lo = Math.min(s[0][0], s[1][0]), hi = Math.max(s[0][0], s[1][0]);
      wires.slice(0, wi).forEach((o) => {
        if (o.from === w.from) { return; }
        segs(o.points).forEach((v) => {
          if (!vertical(v)) { return; }
          const x = v[0][0], vlo = Math.min(v[0][1], v[1][1]), vhi = Math.max(v[0][1], v[1][1]);
          if (x > lo + HOP + 1 && x < hi - HOP - 1 && y > vlo + 1 && y < vhi - 1) { (w.hopsBySeg[k] = w.hopsBySeg[k] || []).push(x); }
        });
      });
    }));
    wires.forEach((w) => { w.hops = [].concat(...Object.values(w.hopsBySeg)); w.d = pathOf(w.points, w.hopsBySeg); });
    return { wires, junctions };
  }

  U.route = { plan, CLEAR, HOP };
})();
