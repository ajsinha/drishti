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
/* The screen grid (SUTRA_NEXT.md section 13; CLIENT_RENDERER.md section 8): without a layout the panels flow in two columns, main and
   right; with one, the named areas (a picture, a split, tabs, a sticky area) or the flow grid's sections. A port of core/screen_grid.py
   (arrange) and static/js/screen-grid.js (the picture, the tablet picture, the phone order, tabs, folds and the split), without the
   reader's own arrangement (Customize stays in the console). On a phone every grid is one column in the phone order; nothing scrolls
   sideways but a wide table inside itself. */
import { h, icon } from './dom.js';
import { capitalize } from './format.js';

const GRID = 12;
const TABLET = '(max-width: 1000px) and (min-width: 641px)', PHONE = '(max-width: 640px)';
const n = (v, lo, hi) => (Number.isInteger(v) && v >= lo && v <= hi ? v : null);
const cellsOf = (row) => String(row || '').trim().split(/\s+/).filter(Boolean);

/** arrange(layout, panels): the grid the template draws, or null for a view that flows (core/screen_grid.py, with no reader layout). */
export function arrange(layout, panels) {
  if (!layout || typeof layout !== 'object') { return null; }
  const place = (layout.place && typeof layout.place === 'object') ? layout.place : {};
  const pictured = (layout.areas || []).filter((r) => typeof r === 'string');
  const names = [...new Set(pictured.flatMap((r) => r.split(/\s+/).filter((c) => c && c !== '.')))];
  const rows = panels.map((p, i) => {
    const at = (place[p.id] && typeof place[p.id] === 'object') ? place[p.id] : {};
    const order = n(at.order, 0, 999);
    const q = Object.assign({}, p, { grid: { area: at.area || (names.length ? names[0] : null), section: at.section, collapsible: !!at.collapsible, collapsed: false } });
    return [order == null ? i : order, i, q];
  }).sort((a, b) => a[0] - b[0] || a[1] - b[1]);
  const ordered = rows.map((r) => r[2]);
  const out = { arrange: !!layout.arrange, sticky: layout.sticky, tabs: [...(layout.tabs || [])], columns: columns(layout, names, pictured), split: null, picture: [], tablet: [], tabletColumns: [] };
  const split = layout.split && typeof layout.split === 'object' ? layout.split : null;
  if (split && (split.between || []).length === 2) {
    out.split = { left: split.between[0], right: split.between[1], resizable: split.resizable !== false, remember: split.remember || 'user' };
  }
  if (pictured.length) {
    out.mode = 'areas'; out.picture = pictured; out.tablet = (layout.tablet || []).map(String); out.tabletColumns = [...(layout.tabletColumns || [])];
    out.areas = names.map((nm) => ({ name: nm, panels: ordered.filter((q) => q.grid.area === nm), tabs: out.tabs.includes(nm), sticky: nm === layout.sticky }));
  } else {
    out.mode = 'flow';
    const sections = (layout.sections || []).filter((s) => s && typeof s === 'object' && s.id), known = new Set(sections.map((s) => s.id));
    out.top = ordered.filter((q) => !known.has(q.grid.section));
    out.sections = sections.map((s) => ({ id: s.id, title: s.title || s.id, collapsed: !!s.collapsed, panels: ordered.filter((q) => q.grid.section === s.id) }));
  }
  out.phone = phone((layout.phone || []).map(String), ordered, names);
  out.fold = {};
  ordered.forEach((q) => { if (q.grid.collapsible) { out.fold[q.id] = false; } });
  return out;
}
function columns(layout, names, pictured) {
  const width = pictured.length ? cellsOf(pictured[0]).length : 0;
  if (!width) { return []; }
  const cols = layout.columns;
  if (Array.isArray(cols) && cols.length === width) { return cols; }
  return Array(width).fill(Math.round(GRID / width * 100) / 100);
}
function phone(list, ordered, names) {
  const rank = {};
  let k = 0;
  list.forEach((x) => ordered.forEach((q) => { if (!(q.id in rank) && (q.id === x || (names.includes(x) && q.grid.area === x))) { rank[q.id] = k++; } }));
  ordered.slice().sort((a, b) => (names.includes(a.grid.area) ? names.indexOf(a.grid.area) : 0) - (names.includes(b.grid.area) ? names.indexOf(b.grid.area) : 0))
    .forEach((q) => { if (!(q.id in rank)) { rank[q.id] = k++; } });
  return rank;
}

/** The grid's DOM (terminal/_grid.html): panelFn(p) draws a panel; chrome (a console page) adds the arrange bar the reader's own layout uses. */
export function gridDom(g, panelFn, chrome, sutra) {
  const cells = (list, name, extra) => h('div', Object.assign({ class: 'sg-cells' + (extra || ''), 'data-sg-cells': name }), list.map(panelFn));
  const sg = { picture: g.picture || [], columns: g.columns, tablet: g.tablet || [], tabletColumns: g.tabletColumns || [], split: g.split, phone: g.phone, fold: g.fold,
    sticky: g.sticky, mine: g.mine, mode: g.mode };
  const root = h('div', { class: 'vgrid sg sg-' + g.mode, 'data-sgrid': true, 'data-sg-sutra': sutra || '', 'data-sg-arrange': chrome && g.arrange ? true : null,
    'data-sg-own': g.own ? true : null, 'data-sg': JSON.stringify(sg) });
  if (chrome && g.arrange) {
    root.appendChild(h('div', { class: 'sg-bar', role: 'region', 'aria-label': 'Arrange this screen' }, icon('grid-1x2'), ' ',
      h('span', null, 'You can arrange this screen: drag a panel by ', h('b', null, '⋮⋮'), ', drag its right edge to resize. Only you see it.'), ' ',
      h('button', { type: 'button', class: 'sg-reset', 'data-sg-reset': true, hidden: g.own ? null : true }, 'Reset layout')));
  }
  if (g.mode === 'areas') {
    root.appendChild(h('div', { class: 'sg-areas', 'data-sg-areas': true }, g.areas.map((a) => h('div', {
      class: 'sg-area' + (a.tabs ? ' sg-tabbed' : '') + (a.sticky ? ' sg-sticky' : ''), 'data-sg-area': a.name, 'aria-label': capitalize(a.name.replace(/-/g, ' ')), role: 'group' },
    a.tabs && a.panels.length ? h('div', { class: 'sg-tablist', role: 'tablist', 'aria-label': capitalize(a.name.replace(/-/g, ' ')) }, a.panels.map((p, i) => h('button', {
      type: 'button', role: 'tab', class: 'sg-tab', 'data-sg-tab': p.id, 'aria-controls': 'p-' + p.id, 'aria-selected': i === 0 ? 'true' : 'false', tabindex: i ? '-1' : null }, p.title || p.id))) : null,
    cells(a.panels, a.name)))));
  } else {
    root.appendChild(cells(g.top, '', ' sg-top'));
    g.sections.forEach((s) => root.appendChild(h('section', { class: 'sg-sec' + (s.collapsed ? ' sg-closed' : ''), 'data-sg-section': s.id, 'aria-labelledby': 'sg-h-' + s.id },
      h('h2', { class: 'sg-sec-h', id: 'sg-h-' + s.id }, h('button', { type: 'button', 'data-sg-fold': s.id, 'aria-expanded': s.collapsed ? 'false' : 'true', 'aria-controls': 'sg-b-' + s.id },
        icon('chevron-down'), ' ' + s.title + ' ', h('span', { class: 'sg-count' }, String(s.panels.length)))),
      h('div', { class: 'sg-cells', id: 'sg-b-' + s.id, 'data-sg-cells': '', 'data-sg-in': s.id, hidden: s.collapsed ? true : null }, s.panels.map(panelFn)))));
  }
  root.__grid = g;
  return root;
}

const quoted = (rows) => rows.map((r) => '"' + cellsOf(r).join(' ') + '"').join(' ');
const fr = (cols) => cols.map((c) => 'minmax(0, ' + (+c) + 'fr)').join(' ');

/** The picture, the phone order, tabs, folds and the split on a drawn grid; returns a dispose function. */
export function gridBehave(root, onShown) {
  const cfg = root.__grid, areasEl = root.querySelector('[data-sg-areas]'), byId = (id) => root.querySelector('[id="p-' + id + '"]');
  let cols = (cfg.columns || []).slice(), bar = null;
  const undo = [];
  function picture() {
    if (!areasEl) { return; }
    let rows = cfg.picture || [], c = cols;
    if (window.matchMedia(TABLET).matches) {
      if ((cfg.tablet || []).length) { rows = cfg.tablet; c = cfg.tabletColumns && cfg.tabletColumns.length ? cfg.tabletColumns : null; }
      else { const seen = []; rows.forEach((r) => cellsOf(r).forEach((x) => { if (x !== '.' && !seen.includes(x)) { seen.push(x); } })); rows = seen; c = [12]; }
    }
    const width = cellsOf(rows[0]).length || 1;
    areasEl.style.gridTemplateAreas = quoted(rows);
    areasEl.style.gridTemplateColumns = fr(c && c.length === width ? c : Array(width).fill(1));
    areasEl.querySelectorAll('[data-sg-area]').forEach((a) => { a.style.gridArea = a.getAttribute('data-sg-area'); });
    placeSplit();
  }
  root.querySelectorAll('.pnl[data-panel]').forEach((p) => { const k = cfg.phone[p.getAttribute('data-panel')]; if (k !== undefined) { p.style.setProperty('--sg-po', k); } });
  root.querySelectorAll('.sg-tabbed').forEach((a) => {
    const ns = [...a.querySelectorAll('.pnl[data-panel]')].map((p) => cfg.phone[p.getAttribute('data-panel')] || 0);
    a.style.setProperty('--sg-po', ns.length ? Math.min(...ns) : 0);
  });
  const split = cfg.split;
  const boundary = () => { for (const r of cfg.picture || []) { const cs = cellsOf(r); for (let i = 0; i + 1 < cs.length; i++) { if (cs[i] === split.left && cs[i + 1] === split.right) { return i + 1; } } } return -1; };
  function placeSplit() {
    if (!bar) { return; }
    const l = areasEl.querySelector('[data-sg-area="' + split.left + '"]'), r = areasEl.querySelector('[data-sg-area="' + split.right + '"]');
    if (!l || !r || window.matchMedia(TABLET).matches || window.matchMedia(PHONE).matches) { bar.hidden = true; return; }
    bar.hidden = false;
    const box = areasEl.getBoundingClientRect(), lb = l.getBoundingClientRect(), rb = r.getBoundingClientRect();
    let top = Math.max(lb.top, rb.top) - box.top, bottom = Math.min(lb.bottom, rb.bottom) - box.top;
    if (bottom - top < 40) { top = Math.min(lb.top, rb.top) - box.top; bottom = Math.max(lb.bottom, rb.bottom) - box.top; }
    bar.style.left = ((lb.right + rb.left) / 2 - box.left) + 'px'; bar.style.top = top + 'px'; bar.style.height = Math.max(40, bottom - top) + 'px';
    bar.setAttribute('aria-valuenow', String(Math.round(cols.slice(0, boundary()).reduce((a, b) => a + b, 0) / 12 * 100)));
  }
  if (split && areasEl && boundary() > 0) {
    const k = boundary(), w = cellsOf(cfg.picture[0]).length;
    if (cols.length !== w) { cols = Array(w).fill(12 / w); }
    bar = h('div', { class: 'sg-split', role: 'separator', 'aria-orientation': 'vertical', 'aria-label': 'Divider between ' + split.left + ' and ' + split.right + (split.resizable ? ': drag, or use the arrow keys' : ''),
      'aria-valuemin': '0', 'aria-valuemax': '100', 'data-sg-split': true });
    areasEl.appendChild(bar);
    if (split.resizable) {
      bar.tabIndex = 0;
      const nudge = (d) => { const a = cols[k - 1] + d, b = cols[k] - d; if (a < 1.5 || b < 1.5) { return; } cols[k - 1] = Math.round(a * 4) / 4; cols[k] = Math.round(b * 4) / 4; areasEl.style.gridTemplateColumns = fr(cols); placeSplit(); };
      bar.addEventListener('keydown', (e) => { const d = e.key === 'ArrowLeft' ? -0.5 : e.key === 'ArrowRight' ? 0.5 : 0; if (d) { e.preventDefault(); nudge(d); } });
      bar.addEventListener('pointerdown', (e) => {
        e.preventDefault(); bar.setPointerCapture(e.pointerId);
        const box = areasEl.getBoundingClientRect();
        const move = (ev) => { const want = (ev.clientX - box.left) / box.width * 12, have = cols.slice(0, k).reduce((a, b) => a + b, 0); nudge(want - have); };
        const up = () => { bar.removeEventListener('pointermove', move); bar.removeEventListener('pointerup', up); };
        bar.addEventListener('pointermove', move); bar.addEventListener('pointerup', up);
      });
    }
    if (window.ResizeObserver) { const ro = new ResizeObserver(placeSplit); ro.observe(areasEl); undo.push(() => ro.disconnect()); }
  }
  picture();
  const mqs = [window.matchMedia(TABLET), window.matchMedia(PHONE)];
  mqs.forEach((m) => m.addEventListener('change', picture));
  undo.push(() => mqs.forEach((m) => m.removeEventListener('change', picture)));
  root.querySelectorAll('.sg-tabbed').forEach((a) => {
    const tabs = [...a.querySelectorAll('[data-sg-tab]')];
    const pick = (t, focus) => { tabs.forEach((x) => { const on = x === t, p = byId(x.getAttribute('data-sg-tab')); x.setAttribute('aria-selected', on ? 'true' : 'false'); x.tabIndex = on ? 0 : -1; if (p) { p.hidden = !on; p.setAttribute('role', 'tabpanel'); } }); if (focus) { t.focus(); } if (!t.__shown) { t.__shown = true; onShown && onShown(byId(t.getAttribute('data-sg-tab'))); } placeSplit(); };
    tabs.forEach((t, i) => {
      t.addEventListener('click', () => pick(t));
      t.addEventListener('keydown', (e) => { const j = e.key === 'ArrowRight' ? i + 1 : e.key === 'ArrowLeft' ? i - 1 : e.key === 'Home' ? 0 : e.key === 'End' ? tabs.length - 1 : null; if (j != null) { e.preventDefault(); pick(tabs[(j + tabs.length) % tabs.length], true); } });
    });
    if (tabs.length) { tabs[0].__shown = true; pick(tabs[0]); }
  });
  root.querySelectorAll('[data-sg-fold]').forEach((b) => b.addEventListener('click', () => {
    const sec = b.closest('[data-sg-section]'), body = sec.querySelector('[data-sg-in]'), closed = b.getAttribute('aria-expanded') === 'true';
    b.setAttribute('aria-expanded', closed ? 'false' : 'true'); sec.classList.toggle('sg-closed', closed); body.hidden = closed;
    if (!closed && onShown) { onShown(body); }
    placeSplit();
  }));
  Object.keys(cfg.fold || {}).forEach((pid) => {
    const p = byId(pid), hd = p && p.querySelector('.pnl-h');
    if (!hd || hd.querySelector('.sg-fold')) { return; }
    const b = h('button', { type: 'button', class: 'sg-fold', 'data-sg-fold-panel': pid, 'aria-controls': 'p-' + pid }, icon('chevron-up'));
    const set = (closed) => { p.classList.toggle('sg-folded', closed); b.setAttribute('aria-expanded', closed ? 'false' : 'true'); b.setAttribute('aria-label', (closed ? 'Open ' : 'Fold ') + ((hd.querySelector('h3') || hd).textContent || pid).trim().split('\n')[0]); b.firstChild.className = 'bi ' + (closed ? 'bi-chevron-down' : 'bi-chevron-up'); };
    set(!!cfg.fold[pid]);
    b.addEventListener('click', () => { const c = !p.classList.contains('sg-folded'); set(c); placeSplit(); });
    hd.insertBefore(b, hd.firstChild);
  });
  return () => undo.splice(0).forEach((f) => f());
}
