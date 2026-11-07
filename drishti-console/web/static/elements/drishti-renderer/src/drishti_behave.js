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
/* What a rendered view does (CLIENT_RENDERER.md section 7.4): bar and gauge widths, tabs (click, arrows, Home, End), and clicks on rows
   and bars that carry an event, which become drishti:row-click and drishti:select events. The tables' own behaviours (tree rows that open
   and close, drill-down levels with their breadcrumb, and unless the host turns them off (tables: false) sorting, a filter box and
   paging) are drishti-grid's (enhanceDocTables). Everything happens inside the container: no address change, no new window. */
import { enhanceDocTables } from '../../drishti-grid/src/drishti_index.js';

/**
 * Widths from data-w (the CSP forbids style attributes; CSSOM is allowed).
 * @param {ParentNode} scope
 * @returns {void}
 */
export function widths(scope) {
  scope.querySelectorAll('[data-w]').forEach((x) => { const w = parseFloat(x.getAttribute('data-w')) || 0; x.style.width = Math.max(0, Math.min(100, w)) + '%'; });
}

/** @private tabs: click, and the arrows, Home and End */
function tabs(scope) {
  scope.querySelectorAll('[data-tabs]').forEach((box) => {
    if (box.__r) { return; } box.__r = true;
    const heads = [...box.querySelectorAll(':scope > .tab-heads > [role="tab"]')];
    const pick = (i, focus) => heads.forEach((x, k) => {
      x.classList.toggle('on', k === i); x.setAttribute('aria-selected', k === i ? 'true' : 'false');
      const pane = box.querySelector('[id="' + x.getAttribute('aria-controls') + '"]');
      if (pane) { pane.hidden = k !== i; }
      if (k === i && focus) { x.focus(); }
    });
    heads.forEach((hd, i) => {
      hd.addEventListener('click', () => pick(i));
      hd.addEventListener('keydown', (e) => {
        const j = e.key === 'ArrowRight' ? i + 1 : e.key === 'ArrowLeft' ? i - 1 : e.key === 'Home' ? 0 : e.key === 'End' ? heads.length - 1 : null;
        if (j !== null) { e.preventDefault(); pick((j + heads.length) % heads.length, true); }
      });
    });
  });
}

/** @private rows and bars that carry an event (ev) or a control value (pick): drishti:row-click and drishti:select, cancelable */
function events(scope, R) {
  scope.querySelectorAll('tr[data-ev], tr[data-pick], li[data-ev]').forEach((x) => {
    if (x.__r) { return; } x.__r = true;
    const fire = () => {
      const pnl = x.closest('.pnl'), panel = pnl ? pnl.getAttribute('data-panel') : null;
      const cells = x.tagName === 'TR' ? [...x.cells].map((c) => c.textContent.trim()) : [x.textContent.trim()];
      const detail = { panel, event: x.getAttribute('data-ev'), row: [...x.parentNode.children].indexOf(x), pick: x.getAttribute('data-pick'), cells };
      if (R.emit('row-click', detail)) {
        x.parentNode.querySelectorAll('[aria-selected="true"],[aria-pressed="true"]').forEach((y) => { y.setAttribute(y.hasAttribute('aria-pressed') ? 'aria-pressed' : 'aria-selected', 'false'); });
        x.setAttribute(x.tagName === 'TR' ? 'aria-selected' : 'aria-pressed', 'true');
      }
      if (detail.pick !== null) {
        const sel = (R.vm().selects || []).find((s) => s.panel === panel);
        R.emit('select', { panel, control: sel ? sel.by : null, value: detail.pick });
      }
    };
    x.addEventListener('click', (e) => { if (!(e.target.closest && e.target.closest('a'))) { fire(); } });
    x.addEventListener('keydown', (e) => { if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); fire(); } });
  });
}

/**
 * Everything above on a freshly rendered scope (a whole view, or one panel a patch replaced), by the behaviours the host chose:
 *   'library' (the default) and 'console': tabs, the tables' behaviours (drishti-grid: trees, drills, and sorting, a filter box and paging
 *           unless tables: false; in 'console' the console's column funnels and Chart any table come on top, console.js), the events;
 *   'host': none but the widths: the host page's own scripts enhance the DOM.
 * @param {ParentNode} scope
 * @param {Object} R the screen's rendering context
 * @returns {void}
 */
export function enhance(scope, R) {
  widths(scope);
  if (R.behaviours === 'host') { return; }
  tabs(scope);
  // in 'console' the console's funnels sort and filter the plain tables (drishti_tables.js): the grid keeps the trees and drills
  enhanceDocTables(scope, { tables: R.tables !== false, sort: R.behaviours !== 'console' });
  events(scope, R);
}
