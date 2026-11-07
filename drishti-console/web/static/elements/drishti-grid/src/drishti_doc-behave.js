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
/* What a document table does, in place (nothing in the page's address, no new window): tree rows open and close (▸ ▾), and a filter
   box keeps the rows that match with their ancestors; a plain table sorts by a heading's button (ascending, descending, the server's
   order; cells read by drishti-core's textSortKey, as the console's tables read them), filters with one box and pages 25 rows at a
   time; a drill shows one level at a time with its breadcrumb (Up, Esc or Backspace go up). Every element is enhanced once. The pure
   parts (what a tree shows, a pager's words) are exported for tests. */
import { textSortKey, compareTextKeys } from '../../drishti-core/src/drishti_index.js';

/**
 * What a tree table shows: per row, whether it is visible and whether it is drawn open. With a query (matches given) a row shows when
 * it or a row under it matches, and a row is open when a row under it matches; without one, a row shows when its parent is shown
 * and open.
 * @param {number[]} depths each row's depth, rows in document order (a row's children follow it)
 * @param {boolean[]} opens whether each row is open (by the person)
 * @param {?boolean[]} matches whether each row matches the query, or null when there is no query
 * @returns {{visible: boolean[], open: boolean[]}}
 */
export function treeVisibility(depths, opens, matches) {
  const n = depths.length, q = !!matches, hit = q ? matches.slice() : [];
  if (q) {
    for (let i = 0; i < n; i++) {
      if (!matches[i]) { continue; }
      for (let j = i - 1, need = depths[i] - 1; j >= 0 && need >= 0; j--) { if (depths[j] === need) { hit[j] = true; need--; } }
    }
  }
  const visible = [], open = [], parentOpen = [true];
  for (let i = 0; i < n; i++) {
    const d = depths[i], vis = q ? !!hit[i] : (d === 0 || parentOpen[d - 1] === true);
    let o = !!opens[i];
    if (q) { o = false; for (let j = i + 1; j < n && depths[j] > d; j++) { if (hit[j]) { o = true; break; } } }
    parentOpen[d] = vis && o;
    visible.push(vis);
    open.push(o);
  }
  return { visible, open };
}

/**
 * A pager's state and words: the page kept inside the pages, the rows it shows, "1–25 of 60" or "No rows match".
 * @param {number} total the rows that pass the filter
 * @param {number} page the page asked for (0 first)
 * @param {number} size rows a page
 * @returns {{page: number, pages: number, first: number, last: number, text: string}} first and last are row indexes (last exclusive)
 */
export function pageInfo(total, page, size) {
  const pages = Math.max(1, Math.ceil(total / size)), p = Math.max(0, Math.min(page, pages - 1));
  const first = p * size, last = Math.min(total, first + size);
  return { page: p, pages, first, last, text: total ? (first + 1) + '–' + last + ' of ' + total : 'No rows match' };
}

const mark = (x) => { if (x.__dgDoc) { return false; } x.__dgDoc = true; return true; };
const cellText = (r, i) => (r.cells[i] ? r.cells[i].textContent.replace(/[▸▾]/g, '').replace(/\s+/g, ' ').trim() : '');
function make(doc, tag, cls, text) { const e = doc.createElement(tag); if (cls) { e.className = cls; } if (text !== undefined && text !== null) { e.textContent = text; } return e; }
function searchBox(doc, cls) {
  const box = make(doc, 'input', cls);
  box.type = 'search'; box.placeholder = 'Filter rows'; box.setAttribute('aria-label', 'Filter rows');
  return box;
}

/** Tree rows: ▸/▾ open and close; with tables on, a filter box keeps matches and their ancestors. */
function tree(t, o) {
  const body = t.tBodies[0];
  if (!body || !mark(t)) { return; }
  const doc = t.ownerDocument, rows = Array.prototype.slice.call(body.rows);
  rows.forEach((r) => {
    r.__depth = +r.getAttribute('data-depth');
    const b = r.querySelector('.tr-tog');
    r.__open = !!b && b.getAttribute('aria-expanded') === 'true';
    r.__text = r.textContent.replace(/[▸▾]/g, '').replace(/\s+/g, ' ').toLowerCase();
  });
  let q = '';
  function paint() {
    const st = treeVisibility(rows.map((r) => r.__depth), rows.map((r) => r.__open), q ? rows.map((r) => r.__text.includes(q)) : null);
    rows.forEach((r, i) => {
      r.hidden = !st.visible[i];
      const b = r.querySelector('.tr-tog'), open = st.open[i];
      if (b) {
        b.setAttribute('aria-expanded', open ? 'true' : 'false');
        b.setAttribute('aria-label', (open ? 'Collapse ' : 'Expand ') + b.getAttribute('aria-label').replace(/^(Collapse|Expand) /, ''));
        b.textContent = open ? '▾' : '▸';
      }
    });
  }
  body.addEventListener('click', (e) => {
    const b = e.target.closest && e.target.closest('.tr-tog');
    if (!b || q) { return; }
    const r = b.closest('tr');
    r.__open = !r.__open;
    paint();
    const again = r.querySelector('.tr-tog');
    if (again) { again.focus(); }
  });
  if (o.tables !== false && !t.hasAttribute('data-no-search')) {
    const wrap = t.closest('.tbl-wrap') || t.parentNode, bar = make(doc, 'div', 'tr-bar'), box = searchBox(doc, 'tbl-q tr-q');
    box.addEventListener('input', () => { q = box.value.trim().toLowerCase(); paint(); });
    bar.appendChild(box);
    wrap.parentNode.insertBefore(bar, wrap);
  }
  paint();
}

/** A table that sorts (a heading's button: ascending, descending, the server's order), filters and pages, all in place. */
function sortable(t, o) {
  if (t.hasAttribute('data-plain') || !t.tBodies.length || !mark(t)) { return; }
  const doc = t.ownerDocument, size = o.pageSize || 25;
  const body = t.tBodies[0], original = Array.prototype.slice.call(body.rows), heads = t.tHead && t.tHead.rows.length ? Array.prototype.slice.call(t.tHead.rows[0].cells) : [];
  const st = { col: -1, dir: 0, q: '', page: 0 };
  let pager = null, info = null, prev = null, next = null;
  function apply() {
    const rows = original.slice();
    if (st.col >= 0 && st.dir) { rows.sort((a, b) => compareTextKeys(textSortKey(cellText(a, st.col)), textSortKey(cellText(b, st.col))) * st.dir); }
    rows.forEach((r) => body.appendChild(r));
    const shown = rows.filter((r) => !st.q || r.textContent.toLowerCase().includes(st.q));
    const pi = pageInfo(shown.length, st.page, size);
    st.page = pi.page;
    rows.forEach((r) => { r.hidden = true; });
    shown.slice(pi.first, pi.first + size).forEach((r) => { r.hidden = false; });
    heads.forEach((th, i) => { th.setAttribute('aria-sort', i === st.col && st.dir ? (st.dir > 0 ? 'ascending' : 'descending') : 'none'); });
    if (pager) {
      pager.hidden = shown.length <= size && !st.q;
      info.textContent = pi.text;
      prev.disabled = pi.page === 0;
      next.disabled = pi.page >= pi.pages - 1;
    }
  }
  heads.forEach((th, i) => {
    const b = make(doc, 'button', 'tbl-sort');
    b.type = 'button';
    while (th.firstChild) { b.appendChild(th.firstChild); }
    b.title = 'Sort by ' + b.textContent.trim();
    th.appendChild(b);
    b.addEventListener('click', () => {
      if (st.col !== i) { st.col = i; st.dir = 1; } else { st.dir = st.dir === 1 ? -1 : st.dir === -1 ? 0 : 1; if (!st.dir) { st.col = -1; } }
      apply();
    });
  });
  if (original.length > size || !t.hasAttribute('data-no-search')) {
    pager = make(doc, 'div', 'tbl-bar');
    if (!t.hasAttribute('data-no-search')) {
      const q = searchBox(doc, 'tbl-q');
      q.addEventListener('input', () => { st.q = q.value.trim().toLowerCase(); st.page = 0; apply(); });
      pager.appendChild(q);
    }
    prev = make(doc, 'button', 'fk tbl-pg-b', '‹'); prev.type = 'button'; prev.setAttribute('aria-label', 'Previous page');
    next = make(doc, 'button', 'fk tbl-pg-b', '›'); next.type = 'button'; next.setAttribute('aria-label', 'Next page');
    info = make(doc, 'span', 'tbl-pg-i mono'); info.setAttribute('aria-live', 'polite');
    prev.addEventListener('click', () => { st.page--; apply(); });
    next.addEventListener('click', () => { st.page++; apply(); });
    pager.appendChild(prev); pager.appendChild(info); pager.appendChild(next);
    const wrap = t.closest('.tbl-wrap') || t;
    wrap.parentNode.insertBefore(pager, wrap);
  }
  apply();
}

/** Drill-down: one level at a time, the breadcrumb above; Esc or the Up button go up. */
function drillDown(root) {
  if (!mark(root)) { return; }
  const doc = root.ownerDocument, crumbs = root.querySelector('[data-dr-crumbs]'), up = root.querySelector('[data-dr-up]');
  const levels = Array.prototype.filter.call(root.children, (x) => x.classList.contains('dr-lv'));
  function show(path) {
    levels.forEach((lv) => { const on = lv.getAttribute('data-dr-path') === path; lv.hidden = !on; lv.classList.toggle('on', on); });
    while (crumbs.firstChild) { crumbs.removeChild(crumbs.firstChild); }
    const parts = path ? path.split('.') : [];
    const steps = [{ path: '', label: root.getAttribute('data-dr-root') }];
    parts.forEach((_, i) => {
      const p = parts.slice(0, i + 1).join('.'), lv = levels.find((x) => x.getAttribute('data-dr-path') === p);
      steps.push({ path: p, label: lv ? lv.getAttribute('data-dr-crumb') : p });
    });
    steps.forEach((s, i) => {
      const li = make(doc, 'li');
      if (i === steps.length - 1) { li.setAttribute('aria-current', 'location'); li.textContent = s.label; }
      else { const b = make(doc, 'button', 'dr-step', s.label); b.type = 'button'; b.addEventListener('click', () => show(s.path)); li.appendChild(b); }
      crumbs.appendChild(li);
    });
    up.hidden = !path;
    root.__path = path;
  }
  root.addEventListener('click', (e) => {
    const a = e.target.closest && e.target.closest('[data-dr-open]');
    if (!a || !root.contains(a)) { return; }
    e.preventDefault();
    e.stopPropagation();
    show(a.getAttribute('data-dr-open'));
  });
  up.addEventListener('click', () => { const p = root.__path || ''; show(p.includes('.') ? p.slice(0, p.lastIndexOf('.')) : ''); });
  root.addEventListener('keydown', (e) => {
    if ((e.key === 'Escape' || e.key === 'Backspace') && root.__path && !/input|textarea/i.test(e.target.tagName)) { e.preventDefault(); up.click(); }
  });
}

/**
 * Enhances the document tables under a scope (a whole view, or one panel drawn again): tree tables, drills and, with tables on,
 * sorting, filtering and paging on every plain table (not a tree, a drill's table, a pivot or a Data table). Each element once.
 * @param {(Element|DocumentFragment|ShadowRoot)} scope
 * @param {{tables: boolean, sort: boolean, pageSize: number}} [o] tables default true (false: no sorting, filter boxes or pages at all);
 *   sort default true (false: plain tables are left as drawn, no heading buttons, filter box or pager, for a host whose own table tools
 *   take them; trees keep their filter box and drills work); pageSize default 25
 * @returns {void}
 */
export function enhanceDocTables(scope, o) {
  o = o || {};
  scope.querySelectorAll('table.tbl-tree').forEach((t) => tree(t, o));
  scope.querySelectorAll('.drill').forEach(drillDown);
  if (o.tables !== false && o.sort !== false) { scope.querySelectorAll('table.tbl:not(.tbl-tree):not(.dr-tbl):not(.pivot)').forEach((t) => sortable(t, o)); }
}
