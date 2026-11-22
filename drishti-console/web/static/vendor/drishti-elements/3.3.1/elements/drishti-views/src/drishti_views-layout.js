/*
 * Drishti Elements
 *
 * Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>.
 * All rights reserved.
 *
 * PROPRIETARY.
 *
 * This file is the proprietary property of Ashutosh Sinha.
 * Copying, use, modification or distribution of this file, via any medium,
 * is not permitted except with the express prior written permission of the
 * copyright holder.
 *
 * See the LICENSE file in the root of this repository.
 */
/* Where the panels go. Without a layout the panels flow in two columns, main and right (one when no panel is on the right), each a
   12-column grid in which a panel's span is its width. With a layout: named areas drawn as a picture (rows of names, the CSS grid
   notation held as data), column widths, a divider the reader may drag between two areas, areas whose panels are tabs, a sticky area,
   panels that fold; or, with no picture, the flow with folding sections. The size of the CONTAINER decides the arrangement, not the
   window's: below 640 px one column in the phone order, up to 1000 px the tablet picture (or the areas stacked), above it the picture.
   Sizes come from a ResizeObserver; nothing here reads layout while a patch is applied. */
import { h, isObject } from '../../drishti-core/src/drishti_index.js';
import { say } from './drishti_views-cells.js';

/** The container widths (px) at which the arrangement changes: at most NARROW is a phone, at most MEDIUM a tablet. */
export const WIDTHS = Object.freeze({ narrow: 640, medium: 1000 });

const cellsOf = (row) => String(row || '').trim().split(/\s+/).filter(Boolean);
const NAME = /^[A-Za-z][A-Za-z0-9_-]{0,63}$/;
const fr = (cols) => cols.map((c) => 'minmax(0, ' + (+c) + 'fr)').join(' ');
const quoted = (rows) => rows.map((r) => '"' + cellsOf(r).join(' ') + '"').join(' ');


/**
 * The arrangement a view's layout asks for, as data: the mode (flow, columns, areas, sections), each area's panels in order, the
 * tabbed and sticky areas, the split, the folds, and each panel's rank in the phone order. Pure: no DOM.
 * @param {Object} vm the ViewModel
 * @returns {Object}
 */
export function arrange(vm) {
  const panels = (vm.panels || []).filter(isObject);
  const layout = isObject(vm.layout) ? vm.layout : null;
  const place = layout && isObject(layout.place) ? layout.place : {};
  const picture = layout && Array.isArray(layout.areas) ? layout.areas.filter((r) => typeof r === 'string' && cellsOf(r).length) : [];
  const names = [];
  picture.forEach((r) => cellsOf(r).forEach((c) => { if (c !== '.' && NAME.test(c) && names.indexOf(c) < 0) { names.push(c); } }));
  const width = picture.length ? cellsOf(picture[0]).length : 0;
  const ok = picture.every((r) => cellsOf(r).length === width) && names.length > 0;
  const areaOf = (p) => {
    const at = isObject(place[p.id]) ? place[p.id] : {};
    if (typeof at.area === 'string' && names.indexOf(at.area) >= 0) { return at.area; }
    if (p.area === 'right' && names.length) { return names[names.length - 1]; }
    return names.length ? names[0] : (p.area === 'right' ? 'right' : 'main');
  };
  const rows = panels.map((p, i) => {
    const at = isObject(place[p.id]) ? place[p.id] : {};
    const order = Number.isInteger(at.order) && at.order >= 0 ? at.order : i;
    return { id: String(p.id), area: areaOf(p), section: typeof at.section === 'string' ? at.section : null, collapsible: at.collapsible === true, order: order, index: i };
  }).sort((a, b) => a.order - b.order || a.index - b.index);
  const out = { mode: 'flow', panels: rows, areas: [], picture: [], columns: [], tablet: [], tabletColumns: [], tabs: [], sticky: [], split: null, sections: [], phone: {} };
  if (layout && ok) {
    out.mode = 'areas';
    out.picture = picture.map((r) => cellsOf(r).join(' '));
    out.columns = Array.isArray(layout.columns) && layout.columns.length === width && layout.columns.every((c) => typeof c === 'number' && c > 0) ? layout.columns.slice() : Array(width).fill(1);
    out.tablet = Array.isArray(layout.tablet) ? layout.tablet.filter((r) => typeof r === 'string' && cellsOf(r).length) : [];
    out.tabletColumns = Array.isArray(layout.tabletColumns) ? layout.tabletColumns.slice() : [];
    out.tabs = [].concat(layout.tabs || []).filter((t) => typeof t === 'string');
    out.sticky = [].concat(layout.sticky || []).filter((t) => typeof t === 'string');
    out.areas = names.map((n) => ({ name: n, panels: rows.filter((r) => r.area === n).map((r) => r.id) }));
    const s = isObject(layout.split) && Array.isArray(layout.split.between) && layout.split.between.length === 2 ? layout.split : null;
    if (s) {
      const k = boundary(out.picture, s.between[0], s.between[1]);
      if (k > 0) { out.split = { left: s.between[0], right: s.between[1], at: k, resizable: s.resizable !== false }; }
    }
  } else if (layout && Array.isArray(layout.sections) && layout.sections.some((s) => isObject(s) && s.id)) {
    out.mode = 'sections';
    const sections = layout.sections.filter((s) => isObject(s) && typeof s.id === 'string');
    const known = sections.map((s) => s.id);
    out.top = rows.filter((r) => known.indexOf(r.section) < 0).map((r) => r.id);
    out.sections = sections.map((s) => ({ id: s.id, title: typeof s.title === 'string' ? s.title : s.id, collapsed: s.collapsed === true,
      panels: rows.filter((r) => r.section === s.id).map((r) => r.id) }));
  } else {
    const right = rows.filter((r) => r.area === 'right').map((r) => r.id);
    out.mode = right.length ? 'columns' : 'flow';
    out.areas = right.length ? [{ name: 'main', panels: rows.filter((r) => r.area !== 'right').map((r) => r.id) }, { name: 'right', panels: right }]
      : [{ name: 'main', panels: rows.map((r) => r.id) }];
  }
  out.folds = rows.filter((r) => r.collapsible).map((r) => r.id);
  out.phone = phoneOrder(vm, out, rows, names);
  return out;
}

function boundary(picture, left, right) {
  for (const r of picture) {
    const cs = cellsOf(r);
    for (let i = 0; i + 1 < cs.length; i++) { if (cs[i] === left && cs[i + 1] === right) { return i + 1; } }
  }
  return -1;
}

function phoneOrder(vm, out, rows, names) {
  const list = (vm.layout && Array.isArray(vm.layout.phone) ? vm.layout.phone : (isObject(vm.phone) && Array.isArray(vm.phone.order) ? vm.phone.order : [])).map(String);
  const rank = {};
  let k = 0;
  list.forEach((x) => rows.forEach((r) => { if (!(r.id in rank) && (r.id === x || (names.indexOf(x) >= 0 && r.area === x))) { rank[r.id] = k++; } }));
  const areaRank = (r) => (names.indexOf(r.area) >= 0 ? names.indexOf(r.area) : (r.area === 'right' ? 1 : 0));
  rows.slice().sort((a, b) => areaRank(a) - areaRank(b)).forEach((r) => { if (!(r.id in rank)) { rank[r.id] = k++; } });
  return rank;
}

/** The drawn arrangement: the grid of areas or columns, tabs, folds, the divider, and the container's width classes. */
export class Layout {
  /**
   * @param {Object} ctx the paint's context
   * @param {Object} plan what arrange() answered
   * @param {Map<string, {element: HTMLElement, head: HTMLElement, title: HTMLElement}>} frames the panel frames by id
   */
  constructor(ctx, plan, frames) {
    this.ctx = ctx;
    this.plan = plan;
    this.frames = frames;
    this.undo = [];
    this.mode = '';
    this.cols = plan.columns.slice();
    const doc = ctx.doc;
    this.element = h(doc, 'div', { cls: 'dv-grid dv-' + plan.mode, attrs: { 'data-layout': plan.mode } });
    if (plan.mode === 'sections') {
      this.element.appendChild(this.cells(plan.top));
      plan.sections.forEach((s) => this.element.appendChild(this.section(s)));
    } else {
      plan.areas.forEach((a) => this.element.appendChild(this.area(a)));
    }
    if (plan.mode === 'areas') { this.areasEl = this.element; this.picture('wide'); }
    if (plan.mode === 'columns') { this.element.style.setProperty('grid-template-columns', 'minmax(0, 8fr) minmax(0, 4fr)'); }
    Object.keys(plan.phone).forEach((id) => { const f = frames.get(id); if (f) { f.element.style.setProperty('--dv-po', String(plan.phone[id])); } });
    plan.folds.forEach((id) => this.fold(id));
    if (plan.split) { this.divider(); }
  }

  cells(ids) {
    const doc = this.ctx.doc;
    return h(doc, 'div', { cls: 'dv-cells' }, ids.map((id) => (this.frames.get(id) || {}).element).filter(Boolean));
  }

  area(a) {
    const doc = this.ctx.doc, p = this.plan;
    const tabbed = p.tabs.indexOf(a.name) >= 0 && a.panels.length > 0;
    const el = h(doc, 'div', { cls: 'dv-area' + (tabbed ? ' dv-tabbed' : '') + (p.sticky.indexOf(a.name) >= 0 ? ' dv-sticky' : ''),
      attrs: { 'data-area': a.name, role: p.mode === 'areas' ? 'group' : null, 'aria-label': p.mode === 'areas' ? a.name.replace(/[-_]/g, ' ') : null } });
    if (p.mode === 'areas') { el.style.setProperty('grid-area', a.name); }
    if (tabbed) { el.appendChild(this.tablist(a)); }
    el.appendChild(this.cells(a.panels));
    if (tabbed) {
      const ranks = a.panels.map((id) => p.phone[id] || 0);
      el.style.setProperty('--dv-po', String(Math.min(...ranks)));
    }
    return el;
  }

  tablist(a) {
    const doc = this.ctx.doc;
    const tabs = a.panels.map((id, i) => {
      const f = this.frames.get(id);
      return h(doc, 'button', { cls: 'dv-tab', text: f ? f.title.textContent : id,
        attrs: { type: 'button', role: 'tab', 'data-tab': id, 'aria-selected': i === 0 ? 'true' : 'false', tabindex: i ? '-1' : '0', 'aria-controls': f ? f.element.id : null } });
    });
    const pick = (t, focus) => {
      tabs.forEach((x) => {
        const on = x === t, f = this.frames.get(x.getAttribute('data-tab'));
        x.setAttribute('aria-selected', on ? 'true' : 'false');
        x.tabIndex = on ? 0 : -1;
        if (f) { f.element.hidden = !on; f.element.setAttribute('role', 'tabpanel'); }
      });
      if (focus) { t.focus(); }
    };
    tabs.forEach((t, i) => {
      t.addEventListener('click', () => pick(t));
      t.addEventListener('keydown', (e) => {
        const j = e.key === 'ArrowRight' ? i + 1 : e.key === 'ArrowLeft' ? i - 1 : e.key === 'Home' ? 0 : e.key === 'End' ? tabs.length - 1 : null;
        if (j !== null) { e.preventDefault(); pick(tabs[(j + tabs.length) % tabs.length], true); }
      });
    });
    const list = h(doc, 'div', { cls: 'dv-tablist', attrs: { role: 'tablist', 'aria-label': a.name.replace(/[-_]/g, ' ') } }, tabs);
    this.afterMount = (this.afterMount || []).concat([() => pick(tabs[0])]);
    return list;
  }

  section(s) {
    const doc = this.ctx.doc, hid = this.ctx.uid('s-' + s.id), bid = this.ctx.uid('sb-' + s.id);
    const body = this.cells(s.panels);
    body.id = bid;
    body.hidden = s.collapsed;
    const btn = h(doc, 'button', { cls: 'dv-sec-fold', attrs: { type: 'button', 'aria-expanded': s.collapsed ? 'false' : 'true', 'aria-controls': bid } },
      [h(doc, 'span', { cls: 'dv-chev', attrs: { 'aria-hidden': 'true' } }), s.title + ' ', h(doc, 'span', { cls: 'dv-count', text: String(s.panels.length) })]);
    const sec = h(doc, 'section', { cls: 'dv-sec' + (s.collapsed ? ' dv-closed' : ''), attrs: { 'data-section': s.id, 'aria-labelledby': hid } },
      [h(doc, 'h2', { cls: 'dv-sec-h', attrs: { id: hid } }, [btn]), body]);
    btn.addEventListener('click', () => {
      const open = btn.getAttribute('aria-expanded') !== 'true';
      btn.setAttribute('aria-expanded', open ? 'true' : 'false');
      sec.classList.toggle('dv-closed', !open);
      body.hidden = !open;
    });
    return sec;
  }

  fold(id) {
    const f = this.frames.get(id);
    if (!f) { return; }
    const doc = this.ctx.doc, m = this.ctx.messages;
    const b = h(doc, 'button', { cls: 'dv-fold', attrs: { type: 'button', 'aria-controls': f.body.id || null, 'data-fold': id } }, [h(doc, 'span', { cls: 'dv-chev', attrs: { 'aria-hidden': 'true' } })]);
    const set = (closed) => {
      f.element.classList.toggle('dv-folded', closed);
      f.body.hidden = closed;
      b.setAttribute('aria-expanded', closed ? 'false' : 'true');
      b.setAttribute('aria-label', say(m, closed ? 'unfold' : 'fold', { title: f.title.textContent }));
    };
    set(false);
    b.addEventListener('click', () => set(!f.element.classList.contains('dv-folded')));
    f.head.insertBefore(b, f.head.firstChild);
  }

  divider() {
    const doc = this.ctx.doc, s = this.plan.split, m = this.ctx.messages;
    const left = this.element.querySelector('[data-area="' + s.left + '"]');
    if (!left) { return; }
    const bar = h(doc, 'div', { cls: 'dv-split', attrs: { role: 'separator', 'aria-orientation': 'vertical', 'aria-label': say(m, 'divider', { a: s.left, b: s.right }),
      'aria-valuemin': '0', 'aria-valuemax': '100', 'data-split': s.left + '|' + s.right } });
    this.bar = bar;
    left.appendChild(bar);
    this.valueNow();
    if (!s.resizable) { return; }
    bar.tabIndex = 0;
    bar.addEventListener('keydown', (e) => {
      const d = e.key === 'ArrowLeft' ? -0.5 : e.key === 'ArrowRight' ? 0.5 : 0;
      if (d) { e.preventDefault(); this.nudge(d); }
    });
    bar.addEventListener('pointerdown', (e) => {
      e.preventDefault();
      if (bar.setPointerCapture) { bar.setPointerCapture(e.pointerId); }
      const box = this.element.getBoundingClientRect(), total = this.cols.reduce((a, b) => a + b, 0);
      const move = (ev) => { const want = (ev.clientX - box.left) / box.width * total, have = this.cols.slice(0, s.at).reduce((a, b) => a + b, 0); this.nudge(want - have); };
      const up = () => { bar.removeEventListener('pointermove', move); bar.removeEventListener('pointerup', up); };
      bar.addEventListener('pointermove', move);
      bar.addEventListener('pointerup', up);
    });
  }

  nudge(d) {
    const k = this.plan.split.at, total = this.cols.reduce((a, b) => a + b, 0), least = total / 8;
    const a = this.cols[k - 1] + d, b = this.cols[k] - d;
    if (a < least || b < least) { return; }
    this.cols[k - 1] = Math.round(a * 4) / 4;
    this.cols[k] = Math.round(b * 4) / 4;
    if (this.mode === 'wide') { this.element.style.setProperty('grid-template-columns', fr(this.cols)); }
    this.valueNow();
  }

  valueNow() {
    if (!this.bar) { return; }
    const total = this.cols.reduce((a, b) => a + b, 0), left = this.cols.slice(0, this.plan.split.at).reduce((a, b) => a + b, 0);
    this.bar.setAttribute('aria-valuenow', String(Math.round(left / total * 100)));
  }

  picture(mode) {
    const p = this.plan;
    let rows = p.picture, cols = this.cols;
    if (mode === 'medium') {
      if (p.tablet.length) { rows = p.tablet; cols = p.tabletColumns.length === cellsOf(p.tablet[0]).length ? p.tabletColumns : Array(cellsOf(p.tablet[0]).length).fill(1); } else { rows = p.areas.map((a) => a.name); cols = [1]; }
    }
    this.element.style.setProperty('grid-template-areas', quoted(rows));
    this.element.style.setProperty('grid-template-columns', fr(cols));
  }

  /**
   * Arranges for a container width: narrow (one column, the phone order), medium or wide.
   * @param {number} width the container's width in px
   * @returns {string} the arrangement now: narrow, medium or wide
   */
  fit(width) {
    const mode = width <= WIDTHS.narrow ? 'narrow' : (width <= WIDTHS.medium ? 'medium' : 'wide');
    if (mode === this.mode) { return mode; }
    this.mode = mode;
    this.element.classList.toggle('dv-narrow', mode === 'narrow');
    this.element.classList.toggle('dv-medium', mode === 'medium');
    if (this.plan.mode === 'areas' && mode !== 'narrow') { this.picture(mode); }
    if (this.plan.mode === 'columns') { this.element.style.setProperty('grid-template-columns', mode === 'wide' ? 'minmax(0, 8fr) minmax(0, 4fr)' : 'minmax(0, 1fr)'); }
    if (this.bar) { this.bar.hidden = mode !== 'wide'; }
    return mode;
  }

  /**
   * Called once the arrangement is in the page: the first tab of each tabbed area is chosen.
   * @returns {void}
   */
  mounted() { (this.afterMount || []).forEach((f) => f()); this.afterMount = []; }
}
