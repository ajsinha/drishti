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
/* Rupaka phase 7 (RUPAKA.md section 20.6): per-report phone layouts. A report may say, per page, how it is arranged at 640 px and narrower:
   `layouts: { phone: { <page id>: { order: [visual ids], hide: [visual ids], span: { <visual id>: rows } } } }`. Without one the page keeps the automatic
   single column in the grid's reading order. This file only decides WHAT to draw and in which order and height; the viewer, the embedded element and the
   designer's 390 px preview all use it, so they agree. Exposes window.drishtiBi.phone. */
(function () {
  'use strict';
  const B = (window.drishtiBi = window.drishtiBi || {});
  const P = (B.phone = {});

  P.MAX = 640;                                   // a screen or element this narrow (or narrower) uses the phone layout

  /** The phone layout the report declares for a page, or null. */
  P.layoutOf = function (report, pageId) {
    const l = report && report.layouts && report.layouts.phone && report.layouts.phone[pageId];
    return l && (((l.order || []).length) || ((l.hide || []).length) || Object.keys(l.span || {}).length) ? l : null;
  };

  /** The page's visuals in the grid's reading order (top to bottom, then left to right). */
  P.reading = (page) => page.visuals.slice().sort((a, b) => a.at.y - b.at.y || a.at.x - b.at.x);

  /**
   * What a phone shows for a page: `[{spec, rows}]` in order, hidden visuals left out; `rows` is the height in grid rows or null (the automatic height).
   * The visuals named in `order` come first, in that order; the others follow in reading order. With no layout it is the reading order, all visible.
   */
  P.arrange = function (page, lay) {
    const reading = P.reading(page);
    if (!lay) { return reading.map((spec) => ({ spec, rows: null })); }
    const hide = new Set(lay.hide || []);
    const first = (lay.order || []).map((id) => reading.find((v) => v.id === id)).filter((v, i, a) => v && a.indexOf(v) === i);
    const rest = reading.filter((v) => first.indexOf(v) < 0);
    return first.concat(rest).filter((v) => !hide.has(v.id)).map((spec) => ({ spec, rows: (lay.span || {})[spec.id] || null }));
  };

  /** The height in pixels of `rows` grid rows. */
  P.height = (rows) => rows * B.ROW_PX + (rows - 1) * B.GAP_PX;

  /** Whether `px` is a phone width. */
  P.narrow = (px) => px > 0 && px <= P.MAX;

  /** A layout as the model stores it: empty parts left out; null when nothing is left. */
  P.tidy = function (lay) {
    if (!lay) { return null; }
    const out = {};
    if ((lay.order || []).length) { out.order = lay.order.slice(); }
    if ((lay.hide || []).length) { out.hide = lay.hide.slice(); }
    const span = {};
    Object.keys(lay.span || {}).forEach((k) => { if (lay.span[k]) { span[k] = lay.span[k]; } });
    if (Object.keys(span).length) { out.span = span; }
    return Object.keys(out).length ? out : null;
  };
})();
