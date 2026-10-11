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
/* Rupaka phase 3: the frame around one visual - title, the buttons that belong to the visual, the body the renderer draws in, and the line under it that says
   how many rows came back, how long the query took and which layer answered. The viewer and the designer's canvas both use it, so a visual looks and reads the
   same in both. A visual whose query was refused shows the server's code and words in place of the picture, with a Retry button. */
(function () {
  'use strict';
  const B = window.drishtiBi;
  const h = B.h;

  /**
   * @param spec the visual as the report model has it
   * @param o { onRetry(), buttons: [element] }
   * @returns {el, body, head, foot, busy(), result(res, ctx, draw), error(e), dispose()}
   */
  B.frame = function (spec, o) {
    o = o || {};
    const title = h('h3', { class: 'bi-vis-title', text: spec.title || '' });
    const tools = h('div', { class: 'bi-vis-tools' }, o.buttons || []);
    const head = h('header', { class: 'bi-vis-head' }, title, tools);
    const body = h('div', { class: 'bi-vis-body' });
    const foot = h('footer', { class: 'bi-vis-foot', 'aria-live': 'polite' });
    const el = h('section', { class: 'bi-vis bi-vis-' + spec.type, 'data-visual': spec.id, 'data-type': spec.type, 'aria-label': spec.title || spec.type + ' ' + spec.id }, spec.title || o.buttons ? head : null, body, foot);
    if (!spec.title && !(o.buttons && o.buttons.length)) { el.classList.add('bi-vis-bare'); }
    let handle = null;
    const drop = () => { if (handle) { handle.dispose(); handle = null; } };
    const api = {
      el, body, head, foot, tools, title,
      busy() { el.classList.add('bi-vis-busy'); el.setAttribute('aria-busy', 'true'); },
      /** Draws `res` with `draw(body, res)` (which returns a handle) and fills the line under the visual. */
      result(res, draw) {
        el.classList.remove('bi-vis-busy', 'bi-vis-failed');
        el.removeAttribute('aria-busy');
        drop();
        handle = draw(body, res);
        if (res && res.meta) {
          const m = res.meta;
          foot.textContent = '';
          foot.appendChild(h('span', { class: 'bi-vis-ms', 'data-ms': String(m.millis), text: res.rowCount + (res.rowCount === 1 ? ' row' : ' rows') + ' · ' + (m.cached ? 'from the cache' : (Math.round(m.millis * 10) / 10) + ' ms') + ' · ' + m.layer + (m.rollup ? ' (' + m.rollup + ')' : '') }));
          if (m.masked && m.masked.length) { foot.appendChild(h('span', { class: 'bi-vis-masked', title: 'Masked for you: ' + m.masked.join(', '), text: ' · masked' })); }
          if (res.rowCount >= (res.limit || 0) && res.limit) { foot.appendChild(h('span', { text: ' · cut at ' + res.limit })); }
        }
      },
      error(e) {
        drop();
        el.classList.remove('bi-vis-busy');
        el.classList.add('bi-vis-failed');
        el.removeAttribute('aria-busy');
        B.clear(body);
        const retry = h('button', { type: 'button', class: 'btn-pill btn-ghost bi-retry', text: 'Retry' });
        retry.addEventListener('click', () => { if (o.onRetry) { o.onRetry(); } });
        body.appendChild(h('div', { class: 'bi-vis-error', role: 'status' }, h('strong', { text: e.code || 'Error' }), h('p', { text: e.detail || e.message }), o.onRetry ? retry : null));
        foot.textContent = '';
      },
      resize() { if (handle) { handle.resize(); } },
      dispose() { drop(); }
    };
    return api;
  };
})();
