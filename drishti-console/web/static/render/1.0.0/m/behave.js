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
/* What a rendered view does (CLIENT_RENDERER.md section 7.4): bar and gauge widths, tabs (click, arrows, Home, End), tree rows that open
   and close, drill-down levels with their breadcrumb (Esc goes up), the Table | Pivot switch, and, unless the host turns them off
   (tables: false), sorting, a filter box and paging on every table; clicks on rows and bars that carry an event become drishti:row-click
   and drishti:select events. Everything happens inside the container: no address change, no new window. */

const MULT = { k: 1e3, m: 1e6, mm: 1e6, b: 1e9, bn: 1e9, tn: 1e12 };
/** A cell as a sortable value (the console's colfilter.js rules): amounts with k/m/bn, signs, separators, %, bp, dates, else text. */
export function sortValue(text) {
  const t = String(text).replace(/[−–]/g, '-').replace(/,/g, '').replace(/\s/g, '');
  const m = t.match(/^([+-]?)(\d+(?:\.\d+)?)(k|m|mm|bn|b|tn)?(%|bp)?$/i);
  if (m) { const n = parseFloat(m[2]) * (m[3] ? MULT[m[3].toLowerCase()] : 1); return { n: m[1] === '-' ? -n : n }; }
  if (/^\d{4}-\d{2}-\d{2}/.test(t) && !isNaN(Date.parse(t.slice(0, 10)))) { return { n: Date.parse(t.slice(0, 10)) }; }
  return { s: String(text).toLowerCase() };
}
export function compareValues(a, b) {
  if (a.n != null && b.n != null) { return a.n - b.n; }
  if (a.n != null) { return -1; }
  if (b.n != null) { return 1; }
  return a.s < b.s ? -1 : a.s > b.s ? 1 : 0;
}
const cellText = (r, i) => (r.cells[i] ? r.cells[i].textContent.replace(/[▸▾]/g, '').replace(/\s+/g, ' ').trim() : '');
const el = (tag, cls, text) => { const e = document.createElement(tag); if (cls) { e.className = cls; } if (text != null) { e.textContent = text; } return e; };
const PAGE = 25;

/** Widths from data-w (the CSP forbids style attributes; CSSOM is allowed). */
export function widths(scope) {
  scope.querySelectorAll('[data-w]').forEach((x) => { const w = parseFloat(x.getAttribute('data-w')) || 0; x.style.width = Math.max(0, Math.min(100, w)) + '%'; });
}

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
        if (j != null) { e.preventDefault(); pick((j + heads.length) % heads.length, true); }
      });
    });
  });
}

function pivotSwitch(scope, R) {
  scope.querySelectorAll('.pv-switch').forEach((sw) => {
    if (sw.__r) { return; } sw.__r = true;
    const box = sw.parentNode, btns = [...sw.querySelectorAll('[data-pv-tab]')];
    btns.forEach((b) => b.addEventListener('click', () => {
      btns.forEach((x) => { const on = x === b; x.classList.toggle('on', on); x.setAttribute('aria-selected', on ? 'true' : 'false'); x.tabIndex = on ? 0 : -1;
        const pane = box.querySelector('[id="' + x.getAttribute('aria-controls') + '"]'); if (pane) { pane.hidden = !on; } });
      const host = box.querySelector('.pv-host');
      if (b.getAttribute('data-pv-tab') === 'pivot' && host && !host.firstChild) { R.pivotTab(host, host.getAttribute('data-pivot-panel')); }
    }));
  });
}

/** Tree rows (children:): ▸/▾ open and close; with tables on, a filter box keeps matches and their ancestors. */
function tree(t, R) {
  const body = t.tBodies[0];
  if (!body || t.__r) { return; } t.__r = true;
  const rows = [...body.rows];
  rows.forEach((r) => { r.__depth = +r.getAttribute('data-depth'); const b = r.querySelector('.tr-tog'); r.__open = !!b && b.getAttribute('aria-expanded') === 'true'; r.__text = r.textContent.replace(/[▸▾]/g, '').replace(/\s+/g, ' ').toLowerCase(); });
  let q = '';
  function paint() {
    const real = rows.map((r) => !q || r.__text.includes(q)), hit = real.slice();
    if (q) { rows.forEach((r, i) => { if (!real[i]) { return; } for (let j = i - 1, need = r.__depth - 1; j >= 0 && need >= 0; j--) { if (rows[j].__depth === need) { hit[j] = true; need--; } } }); }
    const parentOpen = [true];
    rows.forEach((r, i) => {
      const d = r.__depth, visible = q ? hit[i] : (d === 0 || parentOpen[d - 1] === true);
      r.hidden = !visible;
      let open = r.__open;
      if (q) { open = false; for (let j = i + 1; j < rows.length && rows[j].__depth > d; j++) { if (hit[j]) { open = true; break; } } }
      parentOpen[d] = visible && open;
      const b = r.querySelector('.tr-tog');
      if (b) { b.setAttribute('aria-expanded', open ? 'true' : 'false'); b.setAttribute('aria-label', (open ? 'Collapse ' : 'Expand ') + b.getAttribute('aria-label').replace(/^(Collapse|Expand) /, '')); b.textContent = open ? '▾' : '▸'; }
    });
  }
  body.addEventListener('click', (e) => {
    const b = e.target.closest && e.target.closest('.tr-tog');
    if (!b || q) { return; }
    const r = b.closest('tr'); r.__open = !r.__open; paint();
    const again = r.querySelector('.tr-tog'); if (again) { again.focus(); }
  });
  if (R.tables !== false && !t.hasAttribute('data-no-search')) {
    const wrap = t.closest('.tbl-wrap') || t.parentNode, bar = el('div', 'tr-bar'), box = el('input', 'tbl-q tr-q');
    box.type = 'search'; box.placeholder = 'Filter rows'; box.setAttribute('aria-label', 'Filter rows');
    box.addEventListener('input', () => { q = box.value.trim().toLowerCase(); paint(); });
    bar.appendChild(box); wrap.parentNode.insertBefore(bar, wrap);
  }
  paint();
}

/** A table that sorts (a heading's button: ascending, descending, the server's order), filters and pages, all in place. */
function sortable(t) {
  if (t.__r || t.hasAttribute('data-plain') || !t.tBodies.length) { return; } t.__r = true;
  const body = t.tBodies[0], original = [...body.rows], heads = t.tHead && t.tHead.rows.length ? [...t.tHead.rows[0].cells] : [];
  const st = { col: -1, dir: 0, q: '', page: 0 };
  function apply() {
    let rows = original.slice();
    if (st.col >= 0 && st.dir) { rows.sort((a, b) => compareValues(sortValue(cellText(a, st.col)), sortValue(cellText(b, st.col))) * st.dir); }
    rows.forEach((r) => body.appendChild(r));
    const shown = rows.filter((r) => !st.q || r.textContent.toLowerCase().includes(st.q));
    const pages = Math.max(1, Math.ceil(shown.length / PAGE));
    st.page = Math.min(st.page, pages - 1);
    rows.forEach((r) => { r.hidden = true; });
    shown.slice(st.page * PAGE, st.page * PAGE + PAGE).forEach((r) => { r.hidden = false; });
    heads.forEach((th, i) => { th.setAttribute('aria-sort', i === st.col && st.dir ? (st.dir > 0 ? 'ascending' : 'descending') : 'none'); });
    if (pager) {
      pager.hidden = shown.length <= PAGE && !st.q;
      info.textContent = shown.length ? (st.page * PAGE + 1) + '–' + Math.min(shown.length, st.page * PAGE + PAGE) + ' of ' + shown.length : 'No rows match';
      prev.disabled = st.page === 0; next.disabled = st.page >= pages - 1;
    }
  }
  heads.forEach((th, i) => {
    const b = el('button', 'tbl-sort'); b.type = 'button';
    while (th.firstChild) { b.appendChild(th.firstChild); }
    b.title = 'Sort by ' + b.textContent.trim();
    th.appendChild(b);
    b.addEventListener('click', () => { if (st.col !== i) { st.col = i; st.dir = 1; } else { st.dir = st.dir === 1 ? -1 : st.dir === -1 ? 0 : 1; if (!st.dir) { st.col = -1; } } apply(); });
  });
  let pager = null, info = null, prev = null, next = null;
  if (original.length > PAGE || !t.hasAttribute('data-no-search')) {
    pager = el('div', 'tbl-bar');
    if (!t.hasAttribute('data-no-search')) {
      const q = el('input', 'tbl-q'); q.type = 'search'; q.placeholder = 'Filter rows'; q.setAttribute('aria-label', 'Filter rows');
      q.addEventListener('input', () => { st.q = q.value.trim().toLowerCase(); st.page = 0; apply(); });
      pager.appendChild(q);
    }
    prev = el('button', 'fk tbl-pg-b', '‹'); prev.type = 'button'; prev.setAttribute('aria-label', 'Previous page');
    next = el('button', 'fk tbl-pg-b', '›'); next.type = 'button'; next.setAttribute('aria-label', 'Next page');
    info = el('span', 'tbl-pg-i mono'); info.setAttribute('aria-live', 'polite');
    prev.addEventListener('click', () => { st.page--; apply(); }); next.addEventListener('click', () => { st.page++; apply(); });
    pager.appendChild(prev); pager.appendChild(info); pager.appendChild(next);
    const wrap = t.closest('.tbl-wrap') || t; wrap.parentNode.insertBefore(pager, wrap);
  }
  apply();
}

/** Drill-down: one level at a time, the breadcrumb above; Esc or the Up button go up. In place: the host's address never changes. */
function drillDown(root) {
  if (root.__r) { return; } root.__r = true;
  const crumbs = root.querySelector('[data-dr-crumbs]'), up = root.querySelector('[data-dr-up]');
  const levels = [...root.querySelectorAll(':scope > .dr-lv')];
  function show(path) {
    levels.forEach((lv) => { const on = lv.getAttribute('data-dr-path') === path; lv.hidden = !on; lv.classList.toggle('on', on); });
    while (crumbs.firstChild) { crumbs.removeChild(crumbs.firstChild); }
    const parts = path ? path.split('.') : [];
    const steps = [{ path: '', label: root.getAttribute('data-dr-root') }];
    parts.forEach((_, i) => { const p = parts.slice(0, i + 1).join('.'); const lv = levels.find((x) => x.getAttribute('data-dr-path') === p); steps.push({ path: p, label: lv ? lv.getAttribute('data-dr-crumb') : p }); });
    steps.forEach((s, i) => {
      const li = el('li');
      if (i === steps.length - 1) { li.setAttribute('aria-current', 'location'); li.textContent = s.label; }
      else { const b = el('button', 'dr-step', s.label); b.type = 'button'; b.addEventListener('click', () => show(s.path)); li.appendChild(b); }
      crumbs.appendChild(li);
    });
    up.hidden = !path;
    root.__path = path;
  }
  root.addEventListener('click', (e) => {
    const a = e.target.closest && e.target.closest('[data-dr-open]');
    if (!a || !root.contains(a)) { return; }
    e.preventDefault(); e.stopPropagation();
    show(a.getAttribute('data-dr-open'));
  });
  up.addEventListener('click', () => { const p = root.__path || ''; show(p.includes('.') ? p.slice(0, p.lastIndexOf('.')) : ''); });
  root.addEventListener('keydown', (e) => { if ((e.key === 'Escape' || e.key === 'Backspace') && root.__path && !/input|textarea/i.test(e.target.tagName)) { e.preventDefault(); up.click(); } });
}

/** Rows and bars that carry an event (ev) or a control value (pick): drishti:row-click and drishti:select, cancelable. */
function events(scope, R) {
  scope.querySelectorAll('tr[data-ev], tr[data-pick], li[data-ev]').forEach((x) => {
    if (x.__r) { return; } x.__r = true;
    const fire = () => {
      const pnl = x.closest('.pnl'), panel = pnl ? pnl.getAttribute('data-panel') : null;
      const cells = x.tagName === 'TR' ? [...x.cells].map((c) => c.textContent.trim()) : [x.textContent.trim()];
      const detail = { panel, event: x.getAttribute('data-ev'), row: x.tagName === 'TR' ? [...x.parentNode.children].indexOf(x) : [...x.parentNode.children].indexOf(x), pick: x.getAttribute('data-pick'), cells };
      if (R.emit('row-click', detail)) {
        x.parentNode.querySelectorAll('[aria-selected="true"],[aria-pressed="true"]').forEach((y) => { y.setAttribute(y.hasAttribute('aria-pressed') ? 'aria-pressed' : 'aria-selected', 'false'); });
        x.setAttribute(x.tagName === 'TR' ? 'aria-selected' : 'aria-pressed', 'true');
      }
      if (detail.pick != null) {
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
 *   'library' (the default): the library's own, light ones (sorting, one filter box, paging, tree rows, drills, tabs, events);
 *   'host': none but the widths: the host page's own scripts enhance the DOM (the console's pages: tables.js, colfilter.js, tree-rows.js,
 *           drill.js, pivot.js, view.js, sutra-wire.js and controls.js run on it as on the macros' DOM);
 *   'console': the console's behaviours, loaded and started on this root by console.js (funnels, Chart any table, the Pivot grid, the
 *           nested pivot grid, drills, zoom); here only the tabs, the widths and the row events.
 */
export function enhance(scope, R) {
  widths(scope);
  if (R.behaviours === 'host') { return; }
  tabs(scope);
  if (R.behaviours === 'console') { events(scope, R); return; }
  pivotSwitch(scope, R);
  scope.querySelectorAll('table.tbl-tree').forEach((t) => tree(t, R));
  scope.querySelectorAll('.drill').forEach(drillDown);
  if (R.tables !== false) { scope.querySelectorAll('table.tbl:not(.tbl-tree):not(.dr-tbl):not(.pivot)').forEach(sortable); }
  events(scope, R);
}
