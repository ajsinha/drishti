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
/* Wave C (2.4.0): the drawing half of the Transform editor (docs/guides/PYTHON_BI.md section 17) and its profiling panel (BI_PARITY_PLAN.md P3): a frame's
   first rows as a table, and one card per column with its type, what its text looks like, nulls, distinct values, a key hint, min and max, the most common
   values and a histogram (an SVG, so the strict policy holds: no inline style). Text only, never markup. Exposes drishtiBi.txView. */
(function () {
  'use strict';
  const B = (window.drishtiBi = window.drishtiBi || {});
  const h = (...a) => B.h(...a);
  const SVG = 'http://www.w3.org/2000/svg';

  function cell(v) {
    if (v == null) { return ''; }
    if (typeof v === 'number') { return Math.abs(v) >= 1e6 || Number.isInteger(v) ? v.toLocaleString('en-US') : v.toLocaleString('en-US', { maximumFractionDigits: 6 }); }
    return String(v);
  }

  /** The first rows of a frame: {columns, rows} as the runner sent them, with a line saying how many rows there are in all. */
  function rows(host, view, masked) {
    B.clear(host);
    if (!view || !view.head) { host.appendChild(h('p', { class: 'text-muted-d', text: view && view.note ? view.note : 'No table here.' })); return; }
    const m = new Set(masked || []);
    const types = {};
    (view.columns || []).forEach((c) => { types[c.name] = c.type; });
    host.appendChild(h('table', { class: 'tbl bi-tbl tx-tbl', 'data-tx-table': '' },
      h('thead', {}, h('tr', {}, view.head.columns.map((c) => h('th', { scope: 'col', title: (types[c] || '') + (m.has(c) ? ' · masked for you' : '') },
        h('span', { text: c }), h('small', { class: 'tx-type', text: ' ' + (types[c] || '') }))))),
      h('tbody', {}, view.head.rows.map((r) => h('tr', {}, r.map((v) => h('td', { class: typeof v === 'number' ? 'num' : v == null ? 'tx-null' : '', text: v == null ? '—' : cell(v) })))))));
    if (view.rows > view.head.rows.length) {
      host.appendChild(h('p', { class: 'text-muted-d', text: 'The first ' + view.head.rows.length + ' of ' + view.rows.toLocaleString('en-US') + ' rows.' }));
    }
  }

  function histogram(p) {
    const hist = p.histogram;
    const svg = document.createElementNS(SVG, 'svg');
    svg.setAttribute('viewBox', '0 0 100 24');
    svg.setAttribute('class', 'tx-hist');
    svg.setAttribute('role', 'img');
    const max = Math.max(1, ...hist.counts);
    const w = 100 / hist.counts.length;
    hist.counts.forEach((c, i) => {
      const r = document.createElementNS(SVG, 'rect');
      const ht = Math.max(c ? 1.5 : 0, 22 * c / max);
      r.setAttribute('x', String(i * w + 0.4)); r.setAttribute('width', String(Math.max(0.5, w - 0.8)));
      r.setAttribute('y', String(24 - ht)); r.setAttribute('height', String(ht));
      const t = document.createElementNS(SVG, 'title');
      t.textContent = cell(hist.edges[i]) + ' to ' + cell(hist.edges[i + 1]) + ': ' + c;
      r.appendChild(t);
      svg.appendChild(r);
    });
    svg.setAttribute('aria-label', 'Histogram of ' + p.name + ': ' + hist.counts.join(', '));
    return svg;
  }

  function nullBar(pct) {
    const svg = document.createElementNS(SVG, 'svg');
    svg.setAttribute('viewBox', '0 0 100 4');
    svg.setAttribute('class', 'tx-nullbar');
    svg.setAttribute('aria-hidden', 'true');
    const bg = document.createElementNS(SVG, 'rect');
    bg.setAttribute('width', '100'); bg.setAttribute('height', '4'); bg.setAttribute('class', 'tx-nullbar-bg');
    const fg = document.createElementNS(SVG, 'rect');
    fg.setAttribute('width', String(Math.min(100, pct))); fg.setAttribute('height', '4'); fg.setAttribute('class', 'tx-nullbar-fg');
    svg.appendChild(bg); svg.appendChild(fg);
    return svg;
  }

  /**
   * The profiling panel: one card per column of `profile` ({rows, columns[...]}) from the runner. `opts.onFix(column, type)` draws a "Fix the type" button on a
   * column whose text looks like numbers or dates; `opts.masked` marks the masked columns.
   */
  function profile(host, prof, opts) {
    opts = opts || {};
    B.clear(host);
    if (!prof || !prof.columns) { host.appendChild(h('p', { class: 'text-muted-d', text: 'No profile here.' })); return; }
    const masked = new Set(opts.masked || []);
    host.appendChild(h('p', { class: 'text-muted-d', text: prof.rows.toLocaleString('en-US') + ' rows · ' + prof.columns.length + ' columns' }));
    const grid = h('div', { class: 'tx-cards', 'data-profile': '' });
    prof.columns.forEach((p) => {
      const facts = [
        ['Empty', p.nulls.toLocaleString('en-US') + ' (' + p.nullPct + '%)'],
        ['Distinct', p.distinct.toLocaleString('en-US')]
      ];
      if (p.min != null) { facts.push(['Min', cell(p.min)]); facts.push(['Max', cell(p.max)]); }
      if (p.mean != null) { facts.push(['Mean', cell(Math.round(p.mean * 1e6) / 1e6)]); }
      const card = h('article', { class: 'tx-card', 'data-profile-col': p.name },
        h('header', {}, h('strong', { class: 'mono', text: p.name }), h('span', { class: 'bi-badge', text: p.type }),
          p.unique ? h('span', { class: 'bi-badge tx-key', title: 'Every row has its own value: it could be a key', text: 'key?' }) : null,
          masked.has(p.name) ? h('span', { class: 'bi-badge tx-masked', text: 'masked for you' }) : null),
        nullBar(p.nullPct),
        h('dl', { class: 'tx-facts' }, facts.map((f) => [h('dt', { text: f[0] }), h('dd', { class: 'mono', text: f[1] })])),
        p.histogram ? histogram(p) : null,
        p.top && p.top.length ? h('ol', { class: 'tx-top', 'aria-label': 'Most common values' }, p.top.map((t) => h('li', {}, h('span', { class: 'mono', text: t[0] == null ? '—' : cell(t[0]) }), h('small', { text: ' ×' + t[1] })))) : null,
        p.looksLike ? h('p', { class: 'tx-looks' }, h('span', { text: 'Text that looks like ' + (p.looksLike === 'date' ? 'dates' : 'numbers') + '. ' }),
          opts.onFix ? h('button', { type: 'button', class: 'btn-pill btn-ghost bx-small', 'data-fix': p.name, text: 'Fix the type' }) : null) : null);
      const fix = card.querySelector('[data-fix]');
      if (fix) { fix.addEventListener('click', () => opts.onFix(p.name, p.looksLike)); }
      grid.appendChild(card);
    });
    host.appendChild(grid);
  }

  /** "Looks like a key of" hints between the inputs. */
  function keys(host, list) {
    B.clear(host);
    host.hidden = !list || !list.length;
    if (host.hidden) { return; }
    host.appendChild(h('h3', { text: 'Keys between the inputs' }));
    host.appendChild(h('ul', {}, list.map((k) => h('li', {}, h('code', { text: k.input + '.' + k.column }), ' looks like a key of ', h('code', { text: k.keyOf + '.' + k.key }),
      h('small', { class: 'text-muted-d', text: ' (' + k.matched + ' of ' + k.of + ' values match): the Join snippet joins them' })))));
  }

  B.txView = { rows, profile, keys, cell };
})();
