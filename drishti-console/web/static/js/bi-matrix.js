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
/* The matrix (2.4.0 matrix depth, BI_PARITY_PLAN gap 7): row fields at any depth as a tree that opens and closes at every level (Expand all, Collapse all, or
   one group at a time), a subtotal for every group at every level and a total per row and per column, a stepped layout (one indented column) or a tabular one
   (a column per row field), the measures across the columns or down the rows, banded rows, data bars and conditional formatting. A total of a measure that
   adds up (sum, count) is the sum of the figures shown; one that does not (an average, a ratio, a maximum) is the engine's, worked out over the rows of the
   whole group and sent with the answer (`totals`, one level per prefix of the row fields). A click on a row selects its value, which filters the other
   visuals like any mark. Everything is built with DOM calls and textContent. */
(function () {
  'use strict';
  const B = window.drishtiBi;
  const h = B.h;
  const V = B.visuals;
  const NONE = { dispose() {}, resize() {} };
  const SEP = '\u0001';

  V.matrix = function (host, spec, ctx, res) {
    const tb = V.table(res);
    const f = spec.format || {};
    const rowFields = spec.wells.rows || [], colField = (spec.wells.columns || [])[0], measures = spec.wells.values || [];
    const subtotals = !!f.subtotals;
    const rowSubs = subtotals && f.rowSubtotals !== false;
    const colSubs = subtotals && f.columnSubtotals !== false;
    const onRows = f.valuesOn === 'rows';
    const deep = rowFields.length > 1 && (subtotals || f.stepped === true || typeof f.expandTo === 'number');
    const stepped = f.stepped !== false;
    const state = host.__mx || (host.__mx = { toggled: new Map(), all: null });
    const ri = rowFields.map((x) => tb.at(x)), ci = colField ? tb.at(colField) : -1;
    const colKeys = [];
    if (ci >= 0) { tb.rows.forEach((r) => { const k = String(r[ci]); if (colKeys.indexOf(k) < 0) { colKeys.push(k); } }); colKeys.sort(); }
    const cols = ci >= 0 ? colKeys : [''];
    const tt = V.trueTotals(res);
    const styler = V.styler(spec, tb, ctx);
    const bars = new Set(f.dataBars || []);
    const keyOf = (path) => path.join(SEP);

    // the tree of the rows: each node holds its children in order, a leaf holds the cells of its row per column
    const root = { path: [], children: new Map(), cells: new Map(), rows: new Map() };
    tb.rows.forEach((r) => {
      let node = root;
      ri.forEach((i, d) => {
        const v = r[i] == null ? '–' : String(r[i]);
        if (!node.children.has(v)) { node.children.set(v, { path: node.path.concat([v]), children: new Map(), cells: new Map(), rows: new Map(), raw: r[i] }); }
        node = node.children.get(v);
      });
      const c = ci >= 0 ? String(r[ci]) : '';
      measures.forEach((m) => node.cells.set(c + SEP + m, r[tb.at(m)]));
      node.rows.set(c, r);
    });
    const sortTree = (n) => { n.children = new Map([...n.children.entries()].sort((a, b) => a[0].localeCompare(b[0]))); n.children.forEach(sortTree); };
    sortTree(root);

    /** The total of measure m over a node (null: everything) and a column (null: every column): summed when it adds up, else the engine's. */
    const total = (m, node, c) => {
      const path = node ? node.path : [];
      if (!V.additive(ctx, m)) {
        const by = rowFields.slice(0, path.length).concat(c !== null && colField ? [colField] : []);
        return tt.find(by, path.concat(c !== null && colField ? [c] : []), m);
      }
      let t = 0, any = false;
      const walk = (n) => {
        if (!n.children.size) { n.cells.forEach((v, k) => { const [cc, mm] = k.split(SEP); if (mm === m && (c === null || cc === c) && typeof v === 'number') { t += v; any = true; } }); }
        n.children.forEach(walk);
      };
      walk(node || root);
      return any ? t : null;
    };
    const maxBar = {};
    bars.forEach((m) => { let mx = 1e-9; const walk = (n) => { n.cells.forEach((v, k) => { if (k.split(SEP)[1] === m && typeof v === 'number') { mx = Math.max(mx, Math.abs(v)); } }); n.children.forEach(walk); }; walk(root); maxBar[m] = mx; });

    const isOpen = (node) => {
      const k = keyOf(node.path);
      if (state.toggled.has(k)) { return state.toggled.get(k); }
      if (state.all !== null) { return state.all; }
      return typeof f.expandTo === 'number' ? node.path.length <= f.expandTo : true;
    };

    const cell = (m, v, opts) => {
      const o = opts || {};
      const td = h('td', { class: 'num' + (o.bold ? ' bi-total' : ''), 'data-measure': m });
      if (tb.isMasked(m)) { td.textContent = '•••'; return td; }                                 // a masked measure reads ••• in every cell, the empty ones too
      if (o.missing) { td.textContent = '–'; td.title = m + ' does not add up and the engine gave no total for it'; return td; }
      const text = B.fmt(v, V.numFmt(spec, ctx, m), false);
      if (bars.has(m) && typeof v === 'number' && !o.bold) {
        const w = h('span', { class: 'bi-bar' });
        w.style.width = Math.round(100 * Math.abs(v) / maxBar[m]) + '%';
        td.appendChild(h('span', { class: 'bi-barcell' }, w, h('span', { class: 'bi-bartext', text })));
      } else { td.textContent = text; }
      if (o.bold && !V.additive(ctx, m)) { td.setAttribute('data-true-total', ''); td.title = 'Worked out over all the rows of the group, not added up from the figures shown'; }
      if (styler && typeof v === 'number') { V.paintCell(td, styler.cell(m, v, o.row || null)); }
      return td;
    };
    /** The value cells of one line: for a measure (values on rows) or for every measure (values on columns), each column, then the row's total. */
    const valueCells = (node, mOnly, bold) => {
      const out = [];
      const ms = mOnly ? [mOnly] : measures;
      cols.forEach((c) => ms.forEach((m) => {
        if (bold) { const t = total(m, node, ci >= 0 ? c : null); out.push(cell(m, t, { bold: true, missing: t === undefined })); } else { out.push(cell(m, node.cells.get(c + SEP + m), { row: node.rows.get(c) })); }
      }));
      if (colField && colSubs) { ms.forEach((m) => { const t = total(m, node, null); out.push(cell(m, t, { bold: true, missing: t === undefined })); }); }
      return out;
    };

    // ---- the header -------------------------------------------------------------------------------------------------------
    const headRows = [];
    const lead = () => (stepped || !deep && rowFields.length <= 1 ? [h('th', { scope: 'col', rowspan: onRows || !(colField && measures.length > 1) ? '1' : '2', text: rowFields.map(B.label).join(' / ') })]
      : rowFields.map((x) => h('th', { scope: 'col', rowspan: onRows || !(colField && measures.length > 1) ? '1' : '2', text: B.label(x) })));
    if (onRows) {
      headRows.push(h('tr', {}, lead(), h('th', { scope: 'col', text: 'Value' }), colField ? cols.map((c) => h('th', { scope: 'col', class: 'num', text: c })) : h('th', { scope: 'col', class: 'num', text: '' }),
        colField && colSubs ? h('th', { scope: 'col', class: 'num', text: 'Total' }) : null));
    } else if (colField && measures.length > 1) {
      headRows.push(h('tr', {}, lead(), cols.map((c) => h('th', { scope: 'colgroup', colspan: String(measures.length), class: 'num', text: c })),
        colSubs ? h('th', { scope: 'colgroup', colspan: String(measures.length), class: 'num', text: 'Total' }) : null));
      headRows.push(h('tr', {}, cols.map(() => measures.map((m) => h('th', { scope: 'col', class: 'num', text: m }))), colSubs ? measures.map((m) => h('th', { scope: 'col', class: 'num', text: m })) : null));
    } else {
      headRows.push(h('tr', {}, lead(), colField ? cols.map((c) => h('th', { scope: 'col', class: 'num', text: c })) : measures.map((m) => h('th', { scope: 'col', class: 'num', text: m })),
        colField && colSubs ? h('th', { scope: 'col', class: 'num', text: 'Total' }) : null));
    }

    // ---- the body ---------------------------------------------------------------------------------------------------------
    const body = h('tbody', {});
    let band = 0;
    const rowHead = (node, label, extra) => {
      const depth = node.path.length;
      const th = h('th', { scope: 'row', class: deep && stepped ? 'bi-mx-step' : '' }, extra || null, ' ', h('span', { class: 'bi-mx-label', text: label }));
      if (deep && stepped) { th.style.paddingLeft = (0.4 + 1.2 * (depth - 1)) + 'rem'; }
      if (ctx.interactive && ctx.onMark && depth) {
        th.classList.add('bi-mx-pick');
        th.tabIndex = 0;
        const act = (e) => { const rc = th.getBoundingClientRect(); ctx.onMark({ visual: spec.id, field: rowFields[depth - 1], value: node.raw, label: node.path[depth - 1], row: true }, { x: e.clientX || rc.left + 20, y: e.clientY || rc.bottom, el: th }); };
        th.addEventListener('click', (e) => { if (!e.target.closest('button')) { act(e); } });
        th.addEventListener('keydown', (e) => { if (e.key === 'Enter' && e.target === th) { e.preventDefault(); act(e); } });
      }
      return th;
    };
    /** The row-field columns of a tabular line: each level's value, and on a total line the group's name and "total" at its level. */
    const tabularHeads = (node, subtotalRow) => rowFields.map((x, i) => h('th', { scope: 'row', text: i >= node.path.length ? '' : subtotalRow && i === node.path.length - 1 ? node.path[i] + ' total' : node.path[i] }));
    const emit = (cells, attrs, headCells) => {
      if (onRows) {
        measures.forEach((m, i) => body.appendChild(h('tr', Object.assign({ 'data-value-row': m }, i === 0 ? attrs : { 'data-measure-row': '' }), i === 0 ? headCells : h('th', { scope: 'row', class: 'bi-mx-cont', 'aria-hidden': 'true' }),
          h('th', { scope: 'row', class: 'bi-mx-measure', text: m }), cells(m))));
      } else {
        body.appendChild(h('tr', attrs, headCells, cells(null)));
      }
    };
    const twist = (node) => {
      const key = node.path.join(' / ');
      const open = isOpen(node);
      const btn = h('button', { type: 'button', class: 'bi-twist', 'aria-expanded': open ? 'true' : 'false', 'aria-label': (open ? 'Collapse ' : 'Expand ') + key, 'data-group': key, 'data-level': String(node.path.length) },
        h('i', { class: 'bi bi-' + (open ? 'dash-square' : 'plus-square'), 'aria-hidden': 'true' }));
      btn.addEventListener('click', () => { state.toggled.set(keyOf(node.path), !open); V.matrix(host, spec, ctx, res); });
      return btn;
    };
    /** Empty cells for a group line without subtotals: as many as a line of values has. */
    const blanks = (m) => new Array((m ? 1 : measures.length) * (cols.length + (colField && colSubs ? 1 : 0))).fill(0).map(() => h('td', {}));
    const leaves = (n) => (n.children.size ? [...n.children.values()].reduce((a, c) => a.concat(leaves(c)), []) : [n]);
    const visit = (node) => {
      if (!deep) {                                                            // one row field, or several without levels (the 2.3 look): a line per leaf, its path as its label
        leaves(node).forEach((leaf) => {
          if (leaf === root) { return; }
          const banded = f.rowBanding && band++ % 2 === 1;
          emit((m) => valueCells(leaf, m, false), { 'data-row': leaf.path.join(' / '), class: banded ? 'bi-band' : '' }, rowHead(leaf, leaf.path.join(' / ')));
        });
        return;
      }
      node.children.forEach((child) => {
        const leaf = !child.children.size;
        const banded = f.rowBanding && band++ % 2 === 1;
        if (leaf) {
          const label = deep ? child.path[child.path.length - 1] : child.path.join(' / ');
          const attrs = { 'data-row': child.path.join(' / '), class: (deep && stepped ? 'bi-mx-leaf' : '') + (banded ? ' bi-band' : '') };
          emit((m) => valueCells(child, m, false), attrs, stepped || !deep ? rowHead(child, label) : tabularHeads(child, false));
          return;
        }
        const open = isOpen(child);
        const count = (function n(x) { return x.children.size ? [...x.children.values()].reduce((a, y) => a + n(y), 0) : 1; })(child);
        const attrs = rowSubs ? { class: 'bi-subtotal', 'data-subtotal': child.path.join(' / '), 'data-level': String(child.path.length) } : { class: 'bi-mx-group', 'data-group-row': child.path.join(' / '), 'data-level': String(child.path.length) };
        const label = child.path[child.path.length - 1];
        const head = stepped ? rowHead(child, label, twist(child)) : [h('th', { scope: 'row', colspan: String(rowFields.length) }, twist(child), ' ', label, h('small', { class: 'text-muted-d', text: ' (' + count + ')' }))];
        if (stepped) { head.appendChild(h('small', { class: 'text-muted-d', text: ' (' + count + ')' })); }
        if (stepped || !rowSubs) {
          emit((m) => (rowSubs ? valueCells(child, m, true) : blanks(m)), attrs, head);
          if (open) { visit(child); }
        } else {                                                              // tabular: the group's own line first, its rows, then its total
          emit((m) => blanks(m), { class: 'bi-mx-group', 'data-group-row': child.path.join(' / ') }, head);
          if (open) { visit(child); }
          emit((m) => valueCells(child, m, true), attrs, tabularHeads(child, true));
        }
      });
    };
    visit(root);
    if (subtotals && root.children.size) {
      const head = stepped || !deep ? [h('th', { scope: 'row', text: 'Total' })] : [h('th', { scope: 'row', colspan: String(rowFields.length), text: 'Total' })];
      emit((m) => valueCells(null, m, true), { class: 'bi-grandtotal', 'data-grandtotal': '' }, head);
    }

    // ---- the frame ----------------------------------------------------------------------------------------------------------
    const tools = deep ? h('div', { class: 'bi-mx-tools', role: 'toolbar', 'aria-label': 'Matrix levels' }) : null;
    if (tools) {
      const mk = (label, icon, all, attr) => {
        const b = h('button', { type: 'button', class: 'bi-chipbtn bi-mx-btn', 'aria-label': label, title: label, [attr]: '' }, B.icon(icon), h('span', { class: 'bi-lbl', text: ' ' + label }));
        b.addEventListener('click', () => { state.toggled.clear(); state.all = all; V.matrix(host, spec, ctx, res); });
        return b;
      };
      tools.appendChild(mk('Expand all', 'arrows-expand', true, 'data-expand-all'));
      tools.appendChild(mk('Collapse all', 'arrows-collapse', false, 'data-collapse-all'));
    }
    const table = h('table', { class: 'tbl bi-tbl bi-vgrid bi-vis-table bi-matrix' + (stepped ? ' bi-mx-stepped' : ' bi-mx-tabular') + (onRows ? ' bi-mx-onrows' : ''), 'data-plain': true, 'data-depth': String(rowFields.length) },
      h('caption', { class: 'visually-hidden', text: spec.title || 'Matrix' }), h('thead', {}, headRows), body);
    B.clear(host);
    if (tools) { host.appendChild(tools); }
    host.appendChild(h('div', { class: 'tbl-wrap bi-vis-scroll' }, table));
    if (!root.children.size) { host.appendChild(h('p', { class: 'bi-vis-empty', role: 'status', text: 'No rows for this selection.' })); }
    return NONE;
  };
})();
