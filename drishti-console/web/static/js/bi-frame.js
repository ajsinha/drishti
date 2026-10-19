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
  /** A refusal that means "hidden for your role" (DRS-9163, hidden-for): said as such, with no Retry (trying again gives the same answer). */
  B.notForRole = (e) => !!e && (e.code === 'DRS-9163' || /not available to (your|their|the reader's) role/i.test(String(e.detail || e.message || '')));

  /** The datasets (model aliases) the reader's role has no row of: the server's meta.noAccess, else read from its notes ("you have no access to rows of a, b in this model"). */
  B.noAccess = (meta) => {
    const out = [].concat((meta && meta.noAccess) || []);
    if (!out.length) {
      [].concat((meta && meta.warnings) || []).forEach((w) => {
        const m = /^you have no access to rows of (.+?) in this model/.exec(String(w));
        if (m) { m[1].split(/,\s*/).forEach((a) => { if (out.indexOf(a) < 0) { out.push(a); } }); }
      });
    }
    return out;
  };

  B.frame = function (spec, o) {
    o = o || {};
    const title = h('h3', { class: 'bi-vis-title', text: spec.title || '' });
    const tools = h('div', { class: 'bi-vis-tools' }, o.buttons || []);
    const head = h('header', { class: 'bi-vis-head' }, title, tools);
    const body = h('div', { class: 'bi-vis-body' });
    const foot = h('footer', { class: 'bi-vis-foot', 'aria-live': 'polite' });
    const hasHead = !!spec.title || !!(o.buttons && o.buttons.length);                       // no empty heading for a visual with neither a title nor a button
    const el = h('section', { class: 'bi-vis bi-vis-' + spec.type, 'data-visual': spec.id, 'data-type': spec.type, 'aria-label': spec.title || spec.type + ' ' + spec.id }, hasHead ? head : null, body, foot);
    if (!hasHead) { el.classList.add('bi-vis-bare'); }
    let handle = null;
    const drop = () => { if (handle) { handle.dispose(); handle = null; } };
    const api = {
      el, body, head, foot, tools, title,
      busy() { el.classList.add('bi-vis-busy'); el.setAttribute('aria-busy', 'true'); },
      /** Draws `res` with `draw(body, res)` (which returns a handle) and fills the line under the visual. */
      result(res, draw, opts) {
        el.classList.remove('bi-vis-busy', 'bi-vis-failed');
        el.removeAttribute('aria-busy');
        if (opts && opts.keep && handle) {                                   // a live redraw: keep what is there, draw over it, keep the scroll position
          const before = handle;
          const scrolls = [body].concat(B.qa('*', body)).map((n) => [n.scrollTop, n.scrollLeft]);
          handle = draw(body, res, before);
          if (before !== handle) { before.dispose(); }
          [body].concat(B.qa('*', body)).forEach((n, i) => { if (scrolls[i] && (scrolls[i][0] || scrolls[i][1])) { n.scrollTop = scrolls[i][0]; n.scrollLeft = scrolls[i][1]; } });
        } else {
          drop();
          handle = draw(body, res);
        }
        if (res && res.meta) {
          const m = res.meta;
          foot.textContent = '';
          foot.appendChild(h('span', { class: 'bi-vis-ms', 'data-ms': String(m.millis), text: res.rowCount + (res.rowCount === 1 ? ' row' : ' rows') + ' · ' + (m.cached ? 'from the cache' : (Math.round(m.millis * 10) / 10) + ' ms') + ' · ' + m.layer + (m.rollup ? ' (' + m.rollup + ')' : '') }));
          if (m.masked && m.masked.length) { foot.appendChild(h('span', { class: 'bi-vis-masked', title: 'Masked for you: ' + m.masked.join(', '), text: ' · masked' })); }
          if (res.period) {                                                     // a time series asked by period so the whole range fits
            foot.appendChild(h('span', { class: 'bi-vis-period', 'data-period': res.period, title: 'Too many days to draw one by one: each point is a ' + res.period + ' (the whole range is here).', text: ' · by ' + res.period }));
          }
          if (res.truncated) {                                                  // never cut silently: how many of how many, and a way to see them all
            const all = Number(res.total) || 0;
            foot.appendChild(h('span', { class: 'bi-vis-cut', 'data-total': String(all), text: ' · first ' + B.fmt(res.rowCount, 'int') + ' of ' + (res.totalMore ? 'more than ' : '') + B.fmt(all, 'int') }));
            if (o.onShowAll && !res.allShown) {
              const more = h('button', { type: 'button', class: 'bi-vis-showall', 'data-show-all': '', text: res.totalMore || all > 5000 ? 'Show 5,000' : 'Show all' });
              more.addEventListener('click', () => o.onShowAll());
              foot.appendChild(document.createTextNode(' '));
              foot.appendChild(more);
            }
          }
          const none = B.noAccess(m);
          if (none.length) {                                                   // the reader's role has no row rule on a dataset: said, never "no rows for this selection"
            const words = 'Your role has no rows of ' + none.join(', ') + ' in this model';
            foot.appendChild(h('span', { class: 'bi-vis-noaccess-note', 'data-no-access': none.join(','), text: ' · ' + words }));
            if (!res.rowCount) {
              drop();
              B.clear(body).appendChild(h('div', { class: 'bi-vis-empty bi-vis-noaccess', role: 'status', 'data-no-access-why': none.join(',') }, B.icon('shield-lock'),
                h('p', { text: words + ': no rule of the model gives it any, so this visual is empty for you. The model\'s owner can add a rule for your role (its Security tab).' })));
            }
          }
        }
      },
      error(e) {
        drop();
        el.classList.remove('bi-vis-busy');
        el.classList.add('bi-vis-failed');
        el.removeAttribute('aria-busy');
        B.clear(body);
        if (B.notForRole(e)) {                                                 // hidden for the reader's role (DRS-9163): an answer, not a failure, so no Retry
          el.classList.add('bi-vis-noaccess-frame');
          body.appendChild(h('div', { class: 'bi-vis-error bi-vis-noaccess', role: 'status', 'data-not-for-role': e.code || '' }, B.icon('eye-slash'),
            h('strong', { text: ' Not available to your role' }), h('p', { text: 'This visual uses a field or measure the model keeps from your role. Ask the model\'s owner if you need it.' })));
          foot.textContent = '';
          return;
        }
        const retry = h('button', { type: 'button', class: 'btn-pill btn-ghost bi-retry', text: 'Retry' });
        retry.addEventListener('click', () => { if (o.onRetry) { o.onRetry(); } });
        body.appendChild(h('div', { class: 'bi-vis-error', role: 'status' }, h('strong', { text: e.code || 'Error' }), h('p', { text: e.detail || e.message }), o.onRetry ? retry : null));
        foot.textContent = '';
      },
      resize() { if (handle) { handle.resize(); } },
      dispose() { drop(); }
    };
    if (B.visuals && B.visuals.frameFormat) { B.visuals.frameFormat(api, spec); }          // the frame of the typed format (2.4.0): subtitle, border, shadow, background, padding
    return api;
  };
})();
