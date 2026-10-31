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
/* Rupaka phase 7 (BI_DESIGN.md › Rūpaka: the original design and every phase as built, section 20.6): the designer's Phone and Tablet tabs. For the page being edited each lists the visuals in the order that device shows
   them; a row is dragged (or moved with its arrows) to change the order, hidden with its checkbox, and given a height in grid rows. A tablet row can also be made
   wide (the whole width instead of half). Beside the list a preview shows the result to scale: a 390 px screen for a phone, a 768 px one for a tablet. A change is
   one step of the designer's history and is written to `layouts.phone.<page id>` or `layouts.tablet.<page id>` of the draft; arranging nothing removes the entry, so
   the page is the automatic arrangement again. */
(function () {
  'use strict';
  const B = window.drishtiBi;
  const D = B.design;
  if (!D) { return; }
  const h = B.h;
  const AUTO_PX = { slicer: 192, card: 120, kpi: 120, gauge: 160, 'multirow-card': 160, table: 320, matrix: 320, text: 72, image: 160, button: 56 };   // bi-report.css's heights at a phone width

  /** One device's tab: `device` is 'phone' or 'tablet'; the elements of the tab carry data-<device>-... attributes. */
  function deviceTab(device) {
    const tablet = device === 'tablet';
    const word = tablet ? 'a tablet' : 'a phone';
    const q = D.q;
    const list = q('[data-' + device + '-list]'), screen = q('[data-' + device + '-preview]');
    let dragging = null, tidying = false;

    const layouts = () => (D.model.layouts && D.model.layouts[device]) || {};
    const titleOf = (v) => v.title || v.id;

    /** The rows of the list: the shown visuals in the device's order, then the hidden ones. */
    function rowsOf() {
      const page = D.page(), lay = B.phone.layoutOf(D.model, page.id, device);
      const wide = new Set((lay && lay.wide) || []);
      const shown = B.phone.arrange(page, lay).map(({ spec, rows }) => ({ spec, rows, hidden: false, wide: wide.has(spec.id) }));
      const hide = new Set((lay && lay.hide) || []);
      const hidden = B.phone.reading(page).filter((v) => hide.has(v.id)).map((spec) => ({ spec, rows: ((lay && lay.span) || {})[spec.id] || null, hidden: true, wide: wide.has(spec.id) }));
      return shown.concat(hidden);
    }

    /** Writes the arrangement `rows` (shown first, in order; then hidden) as the page's layout for this device. */
    function commit(rows, label) {
      D.change(label, (m) => {
        const page = D.page();
        const reading = B.phone.reading(page).map((v) => v.id);
        const order = rows.filter((r) => !r.hidden).map((r) => r.spec.id);
        const hide = rows.filter((r) => r.hidden).map((r) => r.spec.id);
        const wide = tablet ? rows.filter((r) => r.wide).map((r) => r.spec.id) : [];
        const span = {};
        rows.forEach((r) => { if (r.rows) { span[r.spec.id] = r.rows; } });
        const plain = !hide.length && !wide.length && !Object.keys(span).length && order.join() === reading.join();
        const all = (m.layouts = m.layouts || {});
        const dev = (all[device] = all[device] || {});
        const lay = plain ? null : B.phone.tidy({ order, hide, span, wide });
        if (lay) { dev[page.id] = lay; } else { delete dev[page.id]; }
        if (!Object.keys(dev).length) { delete all[device]; }
        if (!Object.keys(all).length) { delete m.layouts; }
      });
    }

    function move(rows, from, to, label) {
      const next = rows.slice();
      const [it] = next.splice(from, 1);
      next.splice(to, 0, it);
      commit(next, label);
    }

    function draw() {
      const rows = rowsOf();
      B.clear(list);
      const shown = rows.filter((r) => !r.hidden).length;
      rows.forEach((r, i) => {
        const v = r.spec;
        const up = h('button', { type: 'button', class: 'bi-phone-move', ['data-' + device + '-up']: v.id, 'aria-label': 'Move ' + titleOf(v) + ' up', disabled: r.hidden || i === 0 }, B.icon('arrow-up'));
        const down = h('button', { type: 'button', class: 'bi-phone-move', ['data-' + device + '-down']: v.id, 'aria-label': 'Move ' + titleOf(v) + ' down', disabled: r.hidden || i >= shown - 1 }, B.icon('arrow-down'));
        const hide = h('input', { type: 'checkbox', ['data-' + device + '-hide']: v.id, 'aria-label': 'Hide ' + titleOf(v) + ' on ' + word });
        hide.checked = r.hidden;
        const wide = tablet ? h('input', { type: 'checkbox', 'data-tablet-wide': v.id, 'aria-label': 'Whole width for ' + titleOf(v) + ' on a tablet' }) : null;
        if (wide) { wide.checked = r.wide; wide.disabled = r.hidden; }
        const span = h('input', { type: 'number', class: 'studio-in bi-phone-rows', min: '1', max: '40', ['data-' + device + '-rows']: v.id, placeholder: 'auto', 'aria-label': 'Height of ' + titleOf(v) + ' in rows', value: r.rows ? String(r.rows) : '' });
        const li = h('li', { class: 'bi-phone-row' + (r.hidden ? ' bi-phone-hidden' : ''), draggable: D.readOnly ? false : true, ['data-' + device + '-visual']: v.id, 'data-hidden': r.hidden ? 'yes' : 'no' },
          h('span', { class: 'bi-phone-grip', 'aria-hidden': 'true' }, B.icon('grip-vertical')),
          h('span', { class: 'bi-phone-name' }, h('strong', { text: titleOf(v) }), ' ', h('span', { class: 'text-muted-d', text: v.type })),
          up, down, h('label', { class: 'bi-checkrow' }, hide, h('span', { text: 'Hide' })),
          wide ? h('label', { class: 'bi-checkrow' }, wide, h('span', { text: 'Wide' })) : null,
          h('label', { class: 'bi-phone-rowlabel' }, h('span', { text: 'Rows' }), span));
        up.addEventListener('click', () => { move(rows, i, i - 1, 'Move up on ' + word); focusAfter('[data-' + device + '-up="' + v.id + '"]', '[data-' + device + '-down="' + v.id + '"]'); });
        down.addEventListener('click', () => { move(rows, i, i + 1, 'Move down on ' + word); focusAfter('[data-' + device + '-down="' + v.id + '"]', '[data-' + device + '-up="' + v.id + '"]'); });
        hide.addEventListener('change', () => {
          const next = rows.slice();
          const [it] = next.splice(i, 1);
          const row = { spec: it.spec, rows: it.rows, hidden: hide.checked, wide: it.wide };
          next.splice(hide.checked ? next.length : next.filter((x) => !x.hidden).length, 0, row);
          commit(next, hide.checked ? 'Hide on ' + word : 'Show on ' + word);
        });
        if (wide) {
          wide.addEventListener('change', () => {
            const next = rows.slice();
            next[i] = { spec: r.spec, rows: r.rows, hidden: r.hidden, wide: wide.checked };
            commit(next, wide.checked ? 'Whole width on a tablet' : 'Half width on a tablet');
          });
        }
        span.addEventListener('change', () => {
          const n = Math.max(0, Math.min(40, Math.round(Number(span.value) || 0)));
          const next = rows.slice();
          next[i] = { spec: r.spec, rows: n || null, hidden: r.hidden, wide: r.wide };
          commit(next, (tablet ? 'Tablet' : 'Phone') + ' height');
        });
        li.addEventListener('dragstart', (e) => { dragging = i; li.classList.add('bi-phone-drag'); e.dataTransfer.effectAllowed = 'move'; e.dataTransfer.setData('text/plain', v.id); });
        li.addEventListener('dragend', () => { dragging = null; li.classList.remove('bi-phone-drag'); });
        li.addEventListener('dragover', (e) => { if (dragging != null && !r.hidden) { e.preventDefault(); li.classList.add('bi-phone-over'); } });
        li.addEventListener('dragleave', () => li.classList.remove('bi-phone-over'));
        li.addEventListener('drop', (e) => {
          e.preventDefault();
          li.classList.remove('bi-phone-over');
          if (dragging != null && !r.hidden && dragging !== i) { const from = dragging; dragging = null; move(rows, from, i, 'Reorder on ' + word); }
        });
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
