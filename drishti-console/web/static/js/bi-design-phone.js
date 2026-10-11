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
/* Rupaka phase 7 (RUPAKA.md section 20.6): the designer's Phone tab. For the page being edited it lists the visuals in the order a phone shows them; a row is
   dragged (or moved with its arrows) to change the order, hidden with its checkbox, and given a height in grid rows. Beside it a 390 px screen shows the result
   to scale. A change is one step of the designer's history and is written to `layouts.phone.<page id>` of the draft; arranging nothing removes the entry, so
   the page is the automatic single column again. */
(function () {
  'use strict';
  const B = window.drishtiBi;
  const D = B.design;
  if (!D) { return; }
  const h = B.h;
  const AUTO_PX = { slicer: 192, card: 120, kpi: 120, gauge: 160, 'multirow-card': 160, table: 320, matrix: 320, text: 72, image: 160, button: 56 };   // bi-report.css's heights at a phone width

  D.modules.push(function () {
    const q = D.q;
    const list = q('[data-phone-list]'), screen = q('[data-phone-preview]');
    let dragging = null, tidying = false;

    const layouts = () => (D.model.layouts && D.model.layouts.phone) || {};
    const titleOf = (v) => v.title || v.id;

    /** The rows of the list: the shown visuals in phone order, then the hidden ones. */
    function rowsOf() {
      const page = D.page(), lay = B.phone.layoutOf(D.model, page.id);
      const shown = B.phone.arrange(page, lay).map(({ spec, rows }) => ({ spec, rows, hidden: false }));
      const hide = new Set((lay && lay.hide) || []);
      const hidden = B.phone.reading(page).filter((v) => hide.has(v.id)).map((spec) => ({ spec, rows: ((lay && lay.span) || {})[spec.id] || null, hidden: true }));
      return shown.concat(hidden);
    }

    /** Writes the arrangement `rows` (shown first, in order; then hidden) as the page's phone layout. */
    function commit(rows, label) {
      D.change(label, (m) => {
        const page = D.page();
        const reading = B.phone.reading(page).map((v) => v.id);
        const order = rows.filter((r) => !r.hidden).map((r) => r.spec.id);
        const hide = rows.filter((r) => r.hidden).map((r) => r.spec.id);
        const span = {};
        rows.forEach((r) => { if (r.rows) { span[r.spec.id] = r.rows; } });
        const plain = !hide.length && !Object.keys(span).length && order.join() === reading.join();
        const all = (m.layouts = m.layouts || {});
        const phone = (all.phone = all.phone || {});
        const lay = plain ? null : B.phone.tidy({ order, hide, span });
        if (lay) { phone[page.id] = lay; } else { delete phone[page.id]; }
        if (!Object.keys(phone).length) { delete all.phone; }
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
        const up = h('button', { type: 'button', class: 'bi-phone-move', 'data-phone-up': v.id, 'aria-label': 'Move ' + titleOf(v) + ' up', disabled: r.hidden || i === 0 }, B.icon('arrow-up'));
        const down = h('button', { type: 'button', class: 'bi-phone-move', 'data-phone-down': v.id, 'aria-label': 'Move ' + titleOf(v) + ' down', disabled: r.hidden || i >= shown - 1 }, B.icon('arrow-down'));
        const hide = h('input', { type: 'checkbox', 'data-phone-hide': v.id, 'aria-label': 'Hide ' + titleOf(v) + ' on a phone' });
        hide.checked = r.hidden;
        const span = h('input', { type: 'number', class: 'studio-in bi-phone-rows', min: '1', max: '40', 'data-phone-rows': v.id, placeholder: 'auto', 'aria-label': 'Height of ' + titleOf(v) + ' in rows', value: r.rows ? String(r.rows) : '' });
        const li = h('li', { class: 'bi-phone-row' + (r.hidden ? ' bi-phone-hidden' : ''), draggable: D.readOnly ? false : true, 'data-phone-visual': v.id, 'data-hidden': r.hidden ? 'yes' : 'no' },
          h('span', { class: 'bi-phone-grip', 'aria-hidden': 'true' }, B.icon('grip-vertical')),
          h('span', { class: 'bi-phone-name' }, h('strong', { text: titleOf(v) }), ' ', h('span', { class: 'text-muted-d', text: v.type })),
          up, down, h('label', { class: 'bi-checkrow' }, hide, h('span', { text: 'Hide' })), h('label', { class: 'bi-phone-rowlabel' }, h('span', { text: 'Rows' }), span));
        up.addEventListener('click', () => { move(rows, i, i - 1, 'Move up on the phone'); focusAfter('[data-phone-up="' + v.id + '"]', '[data-phone-down="' + v.id + '"]'); });
        down.addEventListener('click', () => { move(rows, i, i + 1, 'Move down on the phone'); focusAfter('[data-phone-down="' + v.id + '"]', '[data-phone-up="' + v.id + '"]'); });
        hide.addEventListener('change', () => {
          const next = rows.slice();
          const [it] = next.splice(i, 1);
          const row = { spec: it.spec, rows: it.rows, hidden: hide.checked };
          next.splice(hide.checked ? next.length : next.filter((x) => !x.hidden).length, 0, row);
          commit(next, hide.checked ? 'Hide on the phone' : 'Show on the phone');
        });
        span.addEventListener('change', () => {
          const n = Math.max(0, Math.min(40, Math.round(Number(span.value) || 0)));
          const next = rows.slice();
          next[i] = { spec: r.spec, rows: n || null, hidden: r.hidden };
          commit(next, 'Phone height');
        });
        li.addEventListener('dragstart', (e) => { dragging = i; li.classList.add('bi-phone-drag'); e.dataTransfer.effectAllowed = 'move'; e.dataTransfer.setData('text/plain', v.id); });
        li.addEventListener('dragend', () => { dragging = null; li.classList.remove('bi-phone-drag'); });
        li.addEventListener('dragover', (e) => { if (dragging != null && !r.hidden) { e.preventDefault(); li.classList.add('bi-phone-over'); } });
        li.addEventListener('dragleave', () => li.classList.remove('bi-phone-over'));
        li.addEventListener('drop', (e) => {
          e.preventDefault();
          li.classList.remove('bi-phone-over');
          if (dragging != null && !r.hidden && dragging !== i) { const from = dragging; dragging = null; move(rows, from, i, 'Reorder on the phone'); }
        });
        list.appendChild(li);
      });
      q('[data-phone-reset]').disabled = D.readOnly || !B.phone.layoutOf(D.model, D.page().id);
      preview(rows);
    }

    /** After a move the list is drawn again: keep the keyboard where the person was (the same button, or its neighbour when that one is now disabled). */
    function focusAfter(sel, other) {
      setTimeout(() => { const b = q(sel, list); if (b && !b.disabled) { b.focus(); } else { const o = q(other, list); if (o) { o.focus(); } } }, 0);
    }

    /** The 390 px screen: a block per shown visual, its title and type, at the height the phone gives it. */
    function preview(rows) {
      B.clear(screen);
      const shown = rows.filter((r) => !r.hidden);
      shown.forEach((r) => {
        const px = r.rows ? B.phone.height(r.rows) : (AUTO_PX[r.spec.type] || 288);
        const card = h('div', { class: 'bi-phone-card', 'data-phone-card': r.spec.id, 'data-rows': r.rows ? String(r.rows) : 'auto' }, h('strong', { text: titleOf(r.spec) }), h('span', { class: 'text-muted-d', text: r.spec.type + (r.rows ? ' · ' + r.rows + ' rows' : ' · auto') }));
        card.style.height = px + 'px';
        screen.appendChild(card);
      });
      if (!shown.length) { screen.appendChild(h('p', { class: 'bi-vis-empty', text: 'Every visual of this page is hidden on a phone.' })); }
    }

    /** A visual or page that no longer exists must not stay named in a phone layout (the server refuses such a report): drop it, as one step of the history. */
    function tidy() {
      if (tidying || D.readOnly || !layouts() || !Object.keys(layouts()).length) { return; }
      const stale = Object.keys(layouts()).some((pid) => {
        const page = D.model.pages.find((p) => p.id === pid);
        if (!page) { return true; }
        const ids = new Set(page.visuals.map((v) => v.id)), lay = layouts()[pid];
        return [].concat(lay.order || [], lay.hide || [], Object.keys(lay.span || {})).some((id) => !ids.has(id));
      });
      if (!stale) { return; }
      tidying = true;
      D.change('Tidy the phone layouts', (m) => {
        const phone = m.layouts.phone;
        Object.keys(phone).forEach((pid) => {
          const page = m.pages.find((p) => p.id === pid);
          if (!page) { delete phone[pid]; return; }
          const ids = new Set(page.visuals.map((v) => v.id)), lay = phone[pid];
          const span = {};
          Object.keys(lay.span || {}).forEach((k) => { if (ids.has(k)) { span[k] = lay.span[k]; } });
          const fixed = B.phone.tidy({ order: (lay.order || []).filter((id) => ids.has(id)), hide: (lay.hide || []).filter((id) => ids.has(id)), span });
          if (fixed) { phone[pid] = fixed; } else { delete phone[pid]; }
        });
        if (!Object.keys(phone).length) { delete m.layouts.phone; }
        if (!Object.keys(m.layouts).length) { delete m.layouts; }
      });
      tidying = false;
    }

    q('[data-phone-reset]').addEventListener('click', () => {
      D.change('Back to the automatic phone layout', (m) => {
        if (!m.layouts || !m.layouts.phone) { return; }
        delete m.layouts.phone[D.page().id];
        if (!Object.keys(m.layouts.phone).length) { delete m.layouts.phone; }
        if (!Object.keys(m.layouts).length) { delete m.layouts; }
      });
    });
    const visible = () => !q('[data-view="phone"]').hidden;
    D.on('model', () => { tidy(); if (visible()) { draw(); } });
    D.on('page', () => { if (visible()) { draw(); } });
    D.on('phone', () => draw());
  });
})();
