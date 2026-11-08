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
/* Row selection on a console table (consoleTables): a table with data-select="multiple" gets a checkbox in each row's first cell and
   Select all in the first heading, drawn here, so a page writes no checkbox of its own. A row with data-select-id can be picked (the id is
   what the page reads); a row with data-select-off cannot (its box is disabled and says why, the attribute's words). Select all picks
   every row that can be picked and passes the table's filters. A change fires drishti:rows-selected ({ids}) on the table; the page reads
   and sets the selection through table.__tblSelection ({ids, rows, set, clear}). data-select-label names what a pick means ("Maintain"). */

/**
 * The selection of a console table, made once per table.
 * @param {HTMLTableElement} t a table with data-select
 * @param {{rows: function(): HTMLTableRowElement[]}} o rows: the table's body rows (a row filtered out has __out)
 * @returns {?{ids: function(): string[], rows: function(): HTMLTableRowElement[], set: function(Iterable<string>): void, clear: function(): void}}
 */
export function rowSelection(t, o) {
  if (!t.hasAttribute('data-select') || t.getAttribute('data-select') === 'none') { return null; }
  if (t.__tblSelection) { return t.__tblSelection; }
  const doc = t.ownerDocument, win = doc.defaultView, verb = t.getAttribute('data-select-label') || 'Select';
  const picked = new Set();
  const idOf = (r) => r.getAttribute('data-select-id');
  const pickable = (r) => !!idOf(r) && !r.hasAttribute('data-select-off');
  function box(label, title, disabled) {
    const lab = doc.createElement('label'), inp = doc.createElement('input');
    lab.className = 'tbl-check';
    inp.type = 'checkbox'; inp.className = 'tbl-check-in'; inp.setAttribute('aria-label', label);
    if (title) { lab.title = title; }
    if (disabled) { inp.disabled = true; lab.classList.add('tbl-check-off'); }
    const stop = (e) => { e.stopPropagation(); };              // a pick never sorts the column or moves the table's cursor
    lab.addEventListener('click', stop);
    inp.addEventListener('keydown', stop);
    lab.appendChild(inp);
    return lab;
  }
  function name(r) { const c = r.cells[0]; return c ? c.textContent.replace(/\s+/g, ' ').trim() : ''; }
  const headRow = t.tHead && t.tHead.rows[0];
  const all = headRow && headRow.cells[0] ? box('Select all rows', '', false) : null;
  if (all) { headRow.cells[0].insertBefore(all, headRow.cells[0].firstChild); }
  o.rows().forEach((r) => {
    if (!r.cells[0] || r.cells.length < 2) { return; }                 // a "nothing here" row spanning the table
    const off = !pickable(r), b = box(off ? name(r) + ': ' + (r.getAttribute('data-select-off') || 'cannot be picked') : verb + ' ' + name(r),
      off ? r.getAttribute('data-select-off') || '' : '', off);
    if (!off) { b.firstChild.setAttribute('data-select-box', idOf(r)); }
    r.cells[0].insertBefore(b, r.cells[0].firstChild);
  });
  function sync(fire) {
    const can = o.rows().filter(pickable);
    can.forEach((r) => { const on = picked.has(idOf(r)); r.classList.toggle('tbl-picked', on); const i = r.querySelector('[data-select-box]'); if (i) { i.checked = on; } });
    if (all) {
      const n = can.filter((r) => picked.has(idOf(r))).length;
      all.firstChild.checked = n > 0 && n === can.length;
      all.firstChild.indeterminate = n > 0 && n < can.length;
      all.firstChild.disabled = !can.length;
    }
    if (fire) { t.dispatchEvent(new win.CustomEvent('drishti:rows-selected', { bubbles: true, detail: { ids: api.ids() } })); }
  }
  const api = {
    /** the picked ids, in the table's order */
    ids: () => api.rows().map(idOf),
    /** the picked rows, in the table's order */
    rows: () => o.rows().filter((r) => pickable(r) && picked.has(idOf(r))),
    /** picks exactly these ids (those that can be picked) */
    set: (ids) => { picked.clear(); const want = new Set(ids || []); o.rows().forEach((r) => { if (pickable(r) && want.has(idOf(r))) { picked.add(idOf(r)); } }); sync(true); },
    clear: () => { picked.clear(); sync(true); }
  };
  t.addEventListener('change', (e) => {
    const i = e.target;
    if (!i.classList || !i.classList.contains('tbl-check-in')) { return; }
    if (i === (all && all.firstChild)) {
      picked.clear();
      if (i.checked) { o.rows().forEach((r) => { if (pickable(r) && !r.__out) { picked.add(idOf(r)); } }); }
    } else {
      const id = i.getAttribute('data-select-box');
      if (i.checked) { picked.add(id); } else { picked.delete(id); }
    }
    sync(true);
  });
  t.__tblSelection = api;
  sync(false);
  return api;
}
