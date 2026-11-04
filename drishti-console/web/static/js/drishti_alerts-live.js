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
/* Wave B (docs/architecture/BI_DESIGN.md section 4.6, console task 4): the Alerts page shows each alert's number now, pushed by the server. An alert on a model
   follows its own model query; an alert on a report visual follows the query that visual runs for you (asked once through the report). The value comes as
   a standing query's `value` delivery over the console's one channel (nothing polls); the chip says Live, Throttled, Every few seconds, Stale or Not
   updating. The alert itself is still judged by the server, on its own trigger: this is what you see while you look. */
(function () {
  'use strict';
  const B = window.drishtiBi;
  if (!B || !B.consume) { return; }
  const cells = B.qa('[data-alert-now]');
  const follows = [];
  const show = (cell, v) => {
    const out = B.q('[data-alert-value]', cell) || cell.insertBefore(B.h('span', { class: 'mono', 'data-alert-value': '' }), cell.firstChild);
    out.textContent = v == null ? '—' : typeof v === 'number' ? (B.fmt ? B.fmt(v) : String(v)) : String(v);
    cell.setAttribute('data-alert-pushed', String((Number(cell.getAttribute('data-alert-pushed')) || 0) + 1));
  };
  function mqOf(src) {
    if (!src) { return Promise.resolve(null); }
    if (src.type === 'model' && src.model) { return Promise.resolve(Object.assign({ mq: 1 }, src.query || {}, { model: src.model })); }
    if (src.type === 'visual' && src.report && src.visual) {
      return B.api('POST', '/reports/' + B.id2path(src.report) + '/query', { page: src.page || '', visual: src.visual, state: src.state || {} })
        .then((res) => { if (res.rows && res.rows.length) { show(cellFor(src), firstNumber(res)); } return res.mq || null; });
    }
    return Promise.resolve(null);
  }
  const bySrc = new Map();
  const cellFor = (src) => bySrc.get(src);
  const firstNumber = (res) => { const row = (res.rows || [])[0] || []; const i = (res.columns || []).findIndex((c) => c.type === 'decimal' || c.type === 'integer'); return i >= 0 ? row[i] : null; };
  cells.forEach((cell) => {
    let src = null;
    try { src = JSON.parse(cell.getAttribute('data-alert-source') || 'null'); } catch (e) { src = null; }
    if (!src) { return; }
    bySrc.set(src, cell);
    const chip = B.consume.chip({ foot: cell, el: cell });
    mqOf(src).then((mq) => {
      if (!mq) { chip.el.remove(); return; }
      chip.set('connecting');
      follows.push(B.consume.follow(mq, { throttle: '1s', deliver: 'value', onAnswer: (d) => show(cell, d.value !== undefined ? d.value : d.rows && d.rows[0] ? d.rows[0][d.rows[0].length - 1] : null),
        onState: (s, why, info) => chip.set(s, why, info) }));
    }, (e) => { chip.set('failed', e.detail || e.message); });
  });
  window.addEventListener('pagehide', () => follows.forEach((f) => f.close()));
})();
