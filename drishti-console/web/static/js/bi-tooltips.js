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
/* Tooltip pages (R6, 2.4.0; docs/archive/BI_PARITY_PLAN.md gap 13): a page with `tooltip: true` is drawn in a popover over a hovered mark of a visual that names it
   (`tooltip: <page id>`), each of its visuals asked of the server under the page's state with the mark's value carried as a filter (the same `carry` a
   drill-through uses), as the person looking. The popover opens after a short pause on a mark, stays while the pointer is over it, and closes on Escape or
   when the pointer leaves; answers are kept per mark while the page is open. */
(function () {
  'use strict';
  const B = window.drishtiBi;
  const h = B.h;
  const ROW = 26, WIDTH = 380, WAIT = 350;

  /**
   * @param o { reportId, page(id) (the report's page), stateFor(spec) (the state a visual is asked under), ctxFor(spec) (a drawing context), yaml() (draft YAML in the
   *            designer, else null) }
   */
  B.tooltips = function (o) {
    let pop = null, shownKey = '', timer = 0, closeTimer = 0, over = false;
    const cache = new Map();

    function close() {
      clearTimeout(timer);
      clearTimeout(closeTimer);
      if (pop) { pop.remove(); pop = null; }
      shownKey = '';
    }
    document.addEventListener('keydown', (e) => { if (e.key === 'Escape' && pop) { close(); } });

    function place(el, at) {
      const vw = window.innerWidth, vh = window.innerHeight;
      const x = Math.min(Math.max(8, (at.x || 0) + 16), vw - WIDTH - 8);
      const y = Math.min(Math.max(8, (at.y || 0) + 16), Math.max(8, vh - el.offsetHeight - 8));
      el.style.left = x + 'px';
      el.style.top = y + 'px';
    }

    function show(src, mark, at) {
      const tip = o.page(src.tooltip);
      if (!tip || !tip.tooltip) { return; }
      const key = src.id + '|' + mark.field + '|' + String(mark.value);
      if (pop && shownKey === key) { return; }
      close();
      shownKey = key;
      const rows = Math.max(1, tip.visuals.reduce((m, v) => Math.max(m, v.at.y + v.at.h), 0));
      const grid = h('div', { class: 'bi-tip-grid' });
      grid.style.gridTemplateRows = 'repeat(' + rows + ', ' + ROW + 'px)';
      pop = h('div', { class: 'bi-tip-pop', role: 'tooltip', 'data-tooltip-page': tip.id, 'data-tooltip-for': String(mark.value) },
        h('p', { class: 'bi-tip-head' }, h('strong', { text: tip.title }), ' · ' + B.label(mark.field) + ': ' + (mark.label == null ? String(mark.value) : mark.label)), grid);
      pop.addEventListener('mouseenter', () => { over = true; clearTimeout(closeTimer); });
      pop.addEventListener('mouseleave', () => { over = false; close(); });
      document.body.appendChild(pop);
      place(pop, at);
      tip.visuals.forEach((v) => {
        const frame = B.frame(v, { buttons: [] });
        frame.el.style.gridColumn = (v.at.x + 1) + ' / span ' + v.at.w;
        frame.el.style.gridRow = (v.at.y + 1) + ' / span ' + v.at.h;
        grid.appendChild(frame.el);
        if (['text', 'image', 'button'].indexOf(v.type) >= 0) { frame.result(null, (host) => B.visuals.draw(host, v, o.ctxFor(v), null)); return; }
        const st = Object.assign({}, o.stateFor(src));
        st.selections = [];
        st.carry = (st.carry || []).concat([{ field: mark.field, values: [mark.value] }]);
        delete st.rows;
        delete st.drill;
        const ck = key + '|' + v.id + '|' + JSON.stringify(st);
        frame.busy();
        const yaml = o.yaml ? o.yaml() : null;
        const asked = cache.get(ck) || B.enqueue(() => B.api('POST', '/reports/' + B.id2path(o.reportId) + '/query', Object.assign({ page: tip.id, visual: v.id, state: st }, yaml ? { yaml } : {})));
        cache.set(ck, asked);
        asked.then((res) => {
          if (!pop || shownKey !== key) { return; }
          frame.result(res, (host) => B.visuals.draw(host, v, Object.assign(o.ctxFor(v), { interactive: false }), res));
          place(pop, at);
        }, (e) => { cache.delete(ck); if (pop && shownKey === key) { frame.error(e); } });
      });
    }

    return {
      /** A mark is under the pointer: the visual's tooltip page opens over it after a short pause. */
      hover(spec, mark, at) {
        if (!spec.tooltip) { return; }
        clearTimeout(timer);
        clearTimeout(closeTimer);
        timer = setTimeout(() => show(spec, mark, at), WAIT);
      },
      /** The pointer left the mark: the popover closes unless the pointer went onto it. */
      end() { clearTimeout(timer); closeTimer = setTimeout(() => { if (!over) { close(); } }, 250); },
      close
    };
  };
})();
