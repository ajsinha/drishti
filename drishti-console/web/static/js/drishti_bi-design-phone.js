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
(function (g) { g.Drishti = g.Drishti || {}; })(typeof window !== 'undefined' ? window : globalThis); /* one namespace for everything the console puts on window (tools/dev/asset_prefix.py) */
/* Rupaka phase 7 (HOW_IT_FITS.md §A28 › Rūpaka: the original design and every phase as built, section 20.6): the designer's Phone and Tablet tabs. For the page being edited each lists the visuals in the order that device shows
   them; a row is dragged (or moved with its arrows) to change the order, hidden with its checkbox, and given a height in grid rows: the
   order, the hidden visuals, the heights and the wide ones are a breakpoint of drishti-designer's LayoutModel over the page's visuals. A tablet row can also be made
   wide (the whole width instead of half). Beside the list a preview shows the result to scale: a 390 px screen for a phone, a 768 px one for a tablet. A change is
   one step of the designer's history and is written to `layouts.phone.<page id>` or `layouts.tablet.<page id>` of the draft; arranging nothing removes the entry, so
   the page is the automatic arrangement again. */
(function () {
  'use strict';
  const B = window.drishtiBi;
  const D = B.design;
  if (!D) { return; }
  const h = B.h;
  const AUTO_PX = { slicer: 192, card: 120, kpi: 120, gauge: 160, 'multirow-card': 160, table: 320, matrix: 320, text: 72, image: 160, button: 56 };   // drishti_bi-report.css's heights at a phone width

  /** One device's tab: `device` is 'phone' or 'tablet'; the elements of the tab carry data-<device>-... attributes. */
  function deviceTab(device) {
    const tablet = device === 'tablet';
    const word = tablet ? 'a tablet' : 'a phone';
    const q = D.q;
    const list = q('[data-' + device + '-list]'), screen = q('[data-' + device + '-preview]');
    let tidying = false;

    const layouts = () => (D.model.layouts && D.model.layouts[device]) || {};
    const shownRow = (li) => !D.readOnly && li.getAttribute('data-hidden') === 'no';
    window.Drishti.elements.designer.sortableList(list, { row: '.bi-phone-row', canDrag: shownRow, canDrop: shownRow, dragClass: 'bi-phone-drag', overClass: 'bi-phone-over',      // a row dragged to another place (drishti-designer)
      onMove: (from, to) => move(rowsOf(), from, to, 'Reorder on ' + word) });
    const titleOf = (v) => v.title || v.id;

    /** The page's arrangement on this device as drishti-designer's LayoutModel: the page's visuals as grid items (the reading order is
        the model's), the device a breakpoint of it (its order, hidden visuals, heights in rows and, on a tablet, the wide ones). The
        designer keeps its own undo (stack: null); each change is written back to the draft as one step of its history. */
    function layoutModel() {
      const page = D.page(), lay = B.phone.layoutOf(D.model, page.id, device);
      const lm = new window.Drishti.elements.designer.LayoutModel({ stack: null });
      const shown = B.phone.arrange(page, lay).map((x) => x.spec.id), hidden = (lay && lay.hide) || [];
      const bp = lay ? { order: shown.concat(B.phone.reading(page).map((v) => v.id).filter((id) => hidden.indexOf(id) >= 0)), hidden: hidden.slice(),
        heights: Object.assign({}, lay.span), wide: ((lay && lay.wide) || []).slice() } : null;
      lm.load(page.visuals.map((v) => ({ id: v.id, x: v.at.x, y: v.at.y, w: v.at.w, h: v.at.h, label: titleOf(v) })), bp ? { [device]: bp } : {});
      return lm;
    }

    /** The rows of the list: the shown visuals in the device's order, then the hidden ones (in the page's reading order). */
    function rowsOf() {
      const page = D.page(), lm = layoutModel(), bp = lm.breakpoint(device) || { hidden: [], heights: {} };
      const spec = (id) => page.visuals.find((v) => v.id === id), wide = new Set(bp.wide || []), hidden = new Set(bp.hidden);
      const row = (id, isHidden) => ({ spec: spec(id), rows: bp.heights[id] || null, hidden: isHidden, wide: wide.has(id) });
      return lm.order(device).map((id) => row(id, false)).concat(lm.order(device, { withHidden: true }).filter((id) => hidden.has(id)).map((id) => row(id, true)));
    }

    /** One change of the device's arrangement (the LayoutModel's breakpoint), written as the page's layout for this device: the shown
        visuals in order, the hidden ones, the heights and the wide ones; the automatic arrangement again removes the entry. */
    function arrange(change, label) {
      const lm = layoutModel();
      if (!lm.arrange(device, change, label)) { return; }
      const bp = lm.breakpoint(device);
      D.change(label, (m) => {
        const page = D.page();
        let lay = null;
        if (bp) {
          const shown = bp.order.filter((id) => bp.hidden.indexOf(id) < 0);
          const wide = tablet ? shown.concat(bp.hidden).filter((id) => (bp.wide || []).indexOf(id) >= 0) : [];
          lay = B.phone.tidy({ order: shown, hide: bp.hidden, span: bp.heights, wide });
        }
        const all = (m.layouts = m.layouts || {});
        const dev = (all[device] = all[device] || {});
        if (lay) { dev[page.id] = lay; } else { delete dev[page.id]; }
        if (!Object.keys(dev).length) { delete all[device]; }
        if (!Object.keys(all).length) { delete m.layouts; }
      });
    }

    function move(rows, from, to, label) {
      const next = rows.map((r) => r.spec.id);
      const [it] = next.splice(from, 1);
      next.splice(to, 0, it);
      arrange({ order: next }, label);
    }

    function draw() {
      const rows = rowsOf();
      B.clear(list);
      const shown = rows.filter((r) => !r.hidden).length;
      rows.forEach((r, i) => {
        const v = r.spec;
        const up = h('button', { type: 'button', class: 'bi-phone-move', ['data-' + device + '-up']: v.id, 'aria-label': 'Move ' + titleOf(v) + ' up', disabled: r.hidden || i === 0 }, B.icon('arrow-up'));
        const down = h('button', { type: 'button', class: 'bi-phone-move', ['data-' + device + '-down']: v.id, 'aria-label': 'Move ' + titleOf(v) + ' down', disabled: r.hidden || i >= shown - 1 }, B.icon('arrow-down'));
        // hide, whole width (a tablet) and the height in rows: a live drishti-inputs Form per row, its controls keeping their data-<device>-* hooks
        const settings = h('div', { class: 'bi-fform bi-phone-settings' });
        const li = h('li', { class: 'bi-phone-row' + (r.hidden ? ' bi-phone-hidden' : ''), ['data-' + device + '-visual']: v.id, 'data-hidden': r.hidden ? 'yes' : 'no' },
          h('span', { class: 'bi-phone-grip', 'aria-hidden': 'true' }, B.icon('grip-vertical')),
          h('span', { class: 'bi-phone-name' }, h('strong', { text: titleOf(v) }), ' ', h('span', { class: 'text-muted-d', text: v.type })),
          up, down, settings);
        up.addEventListener('click', () => { arrange({ move: [v.id, -1] }, 'Move up on ' + word); focusAfter('[data-' + device + '-up="' + v.id + '"]', '[data-' + device + '-down="' + v.id + '"]'); });
        down.addEventListener('click', () => { arrange({ move: [v.id, 1] }, 'Move down on ' + word); focusAfter('[data-' + device + '-down="' + v.id + '"]', '[data-' + device + '-up="' + v.id + '"]'); });
        const fields = { hide: { type: 'boolean', title: 'Hide', hook: { ['data-' + device + '-hide']: v.id, 'aria-label': 'Hide ' + titleOf(v) + ' on ' + word } } };
        if (tablet) { fields.wide = { type: 'boolean', title: 'Wide', readOnly: r.hidden, hook: { 'data-tablet-wide': v.id, 'aria-label': 'Whole width for ' + titleOf(v) + ' on a tablet' } }; }
        fields.rows = { type: 'integer', title: 'Rows', minimum: 1, maximum: 40, lazy: true, placeholder: 'auto', hook: { ['data-' + device + '-rows']: v.id, class: 'bi-phone-rows', 'aria-label': 'Height of ' + titleOf(v) + ' in rows' } };
        B.formatPane.form(settings, { fields, value: { hide: !!r.hidden, wide: !!r.wide, rows: r.rows || undefined }, label: titleOf(v) + ' on ' + word, on: {
          hide: (on) => arrange({ hide: [v.id, !!on] }, on ? 'Hide on ' + word : 'Show on ' + word),
          wide: (on) => arrange({ wide: [v.id, !!on] }, on ? 'Whole width on a tablet' : 'Half width on a tablet'),
          rows: (x) => {
            if (x !== undefined && isNaN(x)) { return; }
            arrange({ height: [v.id, Math.max(0, Math.min(40, Math.round(Number(x) || 0)))] }, (tablet ? 'Tablet' : 'Phone') + ' height');
          }
        } });
        list.appendChild(li);
      });
      q('[data-' + device + '-reset]').disabled = D.readOnly || !B.phone.layoutOf(D.model, D.page().id, device);
      preview(rows);
    }

    /** After a move the list is drawn again: keep the keyboard where the person was (the same button, or its neighbour when that one is now disabled). */
    function focusAfter(sel, other) {
      setTimeout(() => { const b = q(sel, list); if (b && !b.disabled) { b.focus(); } else { const o = q(other, list); if (o) { o.focus(); } } }, 0);
    }

    /** The screen to scale: a block per shown visual, its title and type, at the height the device gives it (a tablet puts two half-width blocks side by side). */
    function preview(rows) {
      B.clear(screen);
      const shown = rows.filter((r) => !r.hidden);
      shown.forEach((r) => {
        const px = r.rows ? B.phone.height(r.rows) : tablet ? B.phone.height(r.spec.at.h) : (AUTO_PX[r.spec.type] || 288);
        const card = h('div', { class: 'bi-phone-card', ['data-' + device + '-card']: r.spec.id, 'data-rows': r.rows ? String(r.rows) : 'auto', 'data-wide': tablet && r.wide ? 'yes' : 'no' },
          h('strong', { text: titleOf(r.spec) }), h('span', { class: 'text-muted-d', text: r.spec.type + (r.rows ? ' · ' + r.rows + ' rows' : ' · auto') + (tablet && r.wide ? ' · wide' : '') }));
        card.style.height = px + 'px';
        if (tablet) { card.style.gridColumn = r.wide ? '1 / -1' : 'span 1'; }
        screen.appendChild(card);
      });
      if (!shown.length) { screen.appendChild(h('p', { class: 'bi-vis-empty', text: 'Every visual of this page is hidden on ' + word + '.' })); }
    }

    /** A visual or page that no longer exists must not stay named in a layout (the server refuses such a report): drop it, as one step of the history. */
    function tidy() {
      if (tidying || D.readOnly || !layouts() || !Object.keys(layouts()).length) { return; }
      const stale = Object.keys(layouts()).some((pid) => {
        const page = D.model.pages.find((p) => p.id === pid);
        if (!page) { return true; }
        const ids = new Set(page.visuals.map((v) => v.id)), lay = layouts()[pid];
        return [].concat(lay.order || [], lay.hide || [], lay.wide || [], Object.keys(lay.span || {})).some((id) => !ids.has(id));
      });
      if (!stale) { return; }
      tidying = true;
      D.change('Tidy the ' + device + ' layouts', (m) => {
        const dev = m.layouts[device];
        Object.keys(dev).forEach((pid) => {
          const page = m.pages.find((p) => p.id === pid);
          if (!page) { delete dev[pid]; return; }
          const ids = new Set(page.visuals.map((v) => v.id)), lay = dev[pid];
          const span = {};
          Object.keys(lay.span || {}).forEach((k) => { if (ids.has(k)) { span[k] = lay.span[k]; } });
          const fixed = B.phone.tidy({ order: (lay.order || []).filter((id) => ids.has(id)), hide: (lay.hide || []).filter((id) => ids.has(id)), wide: (lay.wide || []).filter((id) => ids.has(id)), span });
          if (fixed) { dev[pid] = fixed; } else { delete dev[pid]; }
        });
        if (!Object.keys(dev).length) { delete m.layouts[device]; }
        if (!Object.keys(m.layouts).length) { delete m.layouts; }
      });
      tidying = false;
    }

    q('[data-' + device + '-reset]').addEventListener('click', () => {
      D.change('Back to the automatic ' + device + ' layout', (m) => {
        if (!m.layouts || !m.layouts[device]) { return; }
        delete m.layouts[device][D.page().id];
        if (!Object.keys(m.layouts[device]).length) { delete m.layouts[device]; }
        if (!Object.keys(m.layouts).length) { delete m.layouts; }
      });
    });
    const visible = () => !q('[data-view="' + device + '"]').hidden;
    D.on('model', () => { tidy(); if (visible()) { draw(); } });
    D.on('page', () => { if (visible()) { draw(); } });
    D.on(device, () => draw());
  }

  D.modules.push(function () {
    deviceTab('phone');
    if (D.q('[data-view="tablet"]')) { deviceTab('tablet'); }
  });
})();
