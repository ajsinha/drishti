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
/* One page of a report inside <drishti-report> (docs/architecture/ELEMENTS.md section 18): the tabs and the bar, the 12-column grid of visual frames, the
   state the reader builds (slicers, the marks selected on other visuals, the business date) and each visual's query, one at a time and at most three at once.
   It draws with the console's own report code (static/js/bi-*.js, loaded as classic scripts into window.drishtiBi), so a visual looks and behaves as in the
   viewer; only the transport (the embed API with the host's token) and the page chrome are the element's. Everything is built with DOM calls and text:
   no markup from the server is parsed here. Not in the element: drill down and through, alerts, the rows behind a visual, Perspective (a note says so). */
const B = () => window.drishtiBi;

export class ReportPage {
  /** @param o { root, host, rep, cat, api(method, path, body), emit(name, detail), options: {page, visual, chrome} } */
  constructor(o) {
    Object.assign(this, o);
    this.state = { slicers: {}, selections: [], dates: null, carry: [], mode: 'filter', asTable: {} };
    this.frames = new Map();
    this.pageId = null;
    this.rolling = {};
    this.seen = 0;
    this.masked = new Set();
    this.group = {};
    Object.keys(o.cat.visuals).forEach((t) => { this.group[t] = o.cat.visuals[t].group; });
    this.idx = B().index(o.rep.dataset);
    this.mods = false;
  }

  get report() { return this.rep.report; }
  page(id) { return this.report.pages.find((p) => p.id === id); }
  get current() { return this.page(this.pageId); }
  drawn(spec) { return ['static', 'entity', 'slicer', 'perspective'].indexOf(this.group[spec.type]) < 0; }

  /** What the host may read: the filters in force. */
  filters() {
    const s = this.state, sl = {};
    Object.keys(s.slicers).forEach((k) => { if (s.slicers[k] != null) { sl[k] = s.slicers[k]; } });
    return { page: this.pageId, slicers: sl, dates: s.dates, selections: s.selections.map((x) => ({ visual: x.visual, field: x.field, values: x.values.slice() })), carry: s.carry, mode: s.mode };
  }
  /** The initial or a later setting from the host: `{slicers, dates, selections, carry, mode}`; anything missing is cleared. */
  apply(f) {
    f = f || {};
    const s = this.state;
    s.slicers = Object.assign({}, f.slicers || {});
    s.dates = typeof f.dates === 'string' ? { from: f.dates, to: f.dates } : (f.dates && (f.dates.from || f.dates.to) ? f.dates : null);
    s.selections = (f.selections || []).map((x) => ({ visual: x.visual, field: x.field, values: [].concat(x.values), label: x.label || String(x.values) }));
    s.carry = f.carry || [];
    s.mode = f.mode === 'highlight' ? 'highlight' : 'filter';
  }

  // -- the request for one visual ------------------------------------------------------------------------------------------
  stateFor(spec) {
    const s = this.state, sl = {};
    Object.keys(s.slicers).forEach((k) => { if (s.slicers[k] != null) { sl[k] = s.slicers[k]; } });
    return { dates: s.dates || undefined, carry: s.carry, slicers: sl, drill: [],
      selections: s.selections.map((x) => ({ visual: x.visual, field: x.field, values: x.values, highlight: s.mode === 'highlight' })) };
  }
  ctxFor(spec) {
    const sel = this.state.selections.find((s) => s.visual === spec.id);
    return { index: this.idx, palette: this.palette, t: this.tokens, interactive: true, selected: sel || null, rolling: this.rolling,
      onMark: (mark) => this.selectMark(spec, mark), navigate: (a) => this.navigate(a),
      slicer: spec.type === 'slicer' ? { value: this.state.slicers[spec.id] == null ? null : this.state.slicers[spec.id], onChange: (v) => { this.state.slicers[spec.id] = v; this.changed(spec.id); } } : null };
  }
  restyle() {                                                       // the theme changed: colours are read again from the element
    this.tokens = B().tokens(this.host);
    this.palette = B().palette((this.report.theme || {}).palette, this.tokens);
  }

  load(spec, quiet) {
    const entry = this.frames.get(spec.id);
    if (!entry) { return Promise.resolve(); }
    const token = ++entry.token;
    if (!this.drawn(spec)) { entry.frame.result(null, (h) => B().visuals.draw(h, spec, this.ctxFor(spec), null)); return Promise.resolve(); }
    if (!quiet) { entry.frame.busy(); }
    return B().enqueue(() => this.api('POST', '/reports/' + B().id2path(this.rep.id) + '/query', { page: this.pageId, visual: spec.id, state: this.stateFor(spec) })).then((res) => {
      if (token !== entry.token) { return; }
      entry.res = res;
      if (res.live && res.live.generation > this.seen) { this.seen = res.live.generation; }
      ((res.meta && res.meta.masked) || []).forEach((c) => this.masked.add(c));
      B().live.markStale(entry.frame, false);
      this.draw(spec.id, quiet);
    }, (e) => {
      if (token !== entry.token) { return; }
      if (quiet && entry.res) { B().live.markStale(entry.frame, true, e.code ? e.code + ' ' + e.detail : e.message); } else { entry.frame.error(e); this.emit('error', { code: e.code, status: e.status, detail: e.detail || e.message, visual: spec.id, fatal: false }); }
    });
  }
  draw(id, live) {
    const entry = this.frames.get(id);
    if (!entry || !entry.res) { return; }
    const spec = entry.spec, res = entry.res;
    entry.frame.result(res, (h) => {
      const c = this.ctxFor(spec);
      c.update = !!live;
      return this.state.asTable[id] ? B().visuals.dataTable(h, spec, c, res, true) : B().visuals.draw(h, spec, c, res);
    }, live ? { keep: true } : undefined);
    this.buttons(spec);
    const chart = entry.frame.body.querySelector('.bi-chart');
    if (chart) { chart.setAttribute('data-ready', '1'); }
  }
  /** Every visual of the page that draws data is asked again, quietly (a live tick). */
  refreshLive() { return Promise.all(this.current.visuals.filter((v) => this.drawn(v)).map((v) => this.load(v, true))); }
  refreshAll(exceptId) { this.current.visuals.forEach((v) => { if (v.id !== exceptId) { this.load(v); } }); this.chips(); }
  changed(exceptId) { this.refreshAll(exceptId); this.emit('filter-changed', Object.assign(this.filters(), { source: 'user' })); }
  settled() { return Promise.all([...this.frames.values()].map((e) => e.pending || Promise.resolve())); }

  // -- interactions ---------------------------------------------------------------------------------------------------------
  selectMark(spec, mark) {
    const multi = this.host.modifier;
    const sel = this.state.selections, cur = sel.find((s) => s.visual === spec.id);
    if (cur && cur.values.some((v) => String(v) === String(mark.value))) {
      cur.values = cur.values.filter((v) => String(v) !== String(mark.value));
      if (!cur.values.length) { this.state.selections = sel.filter((s) => s !== cur); }
    } else if (cur && multi) {
      cur.values.push(mark.value);
    } else {
      this.state.selections = sel.filter((s) => s.visual !== spec.id);
      this.state.selections.push({ visual: spec.id, field: mark.field, values: [mark.value], label: mark.label });
    }
    this.say((this.state.selections.find((s) => s.visual === spec.id) ? 'Selected ' : 'Cleared ') + mark.label + '.');
    this.current.visuals.forEach((v) => { if (v.id !== spec.id) { this.load(v); } else { this.buttons(v); this.draw(v.id); } });
    this.chips();
    this.emit('filter-changed', Object.assign(this.filters(), { source: 'user' }));
  }
  clearSelection(id) { this.state.selections = this.state.selections.filter((s) => s.visual !== id); this.changed(null); }
  navigate(a) {
    if (a.navigate && this.page(String(a.navigate))) { this.go(String(a.navigate), [], 'user'); } else if (a.bookmark) { this.bookmark(String(a.bookmark)); }
  }
  bookmark(name) {
    const b = (this.report.bookmarks || []).find((x) => x.name === name);
    if (!b) { return; }
    this.state.slicers = Object.assign({}, b.slicers || {});
    this.go(b.page, (b.filters || []).map((f) => ({ field: f.field, op: f.op, values: f.values || [] })), 'user');
  }
  reset() {
    Object.assign(this.state, { slicers: {}, selections: [], dates: null, asTable: {} });
    this.q('[data-date]') && (this.q('[data-date]').value = '');
    this.drawGrid();
    this.emit('filter-changed', Object.assign(this.filters(), { source: 'user' }));
  }
  say(text) { const el = this.q('[data-announce]'); if (el) { el.textContent = text; } }

  // -- the chrome ------------------------------------------------------------------------------------------------------------
  q(sel) { return this.shell && this.shell.querySelector(sel); }
  build() {
    const h = B().h, o = this.options;
    const bare = o.chrome === 'bare' || !!o.visual;
    this.shell = h('div', { class: 'bi-embed', part: 'report', role: 'region', 'aria-label': this.label || this.rep.title }, !bare ? h('header', { class: 'bi-head' }, h('h2', { class: 'bi-embed-title', 'data-title': '', text: this.rep.title })) : null,
      h('span', { class: 'visually-hidden', 'data-announce': '', role: 'status', 'aria-live': 'polite' }));
    this.bar = null;
    if (!bare) {
      const tools = h('div', { class: 'bi-report-tools' },
        h('label', { class: 'bi-tool' }, 'Business date ', h('input', { type: 'date', class: 'studio-in', 'data-date': '' })),
        h('label', { class: 'bi-tool', 'data-bookmarks-wrap': '', hidden: !(this.report.bookmarks || []).length }, 'Bookmarks ', h('select', { class: 'studio-select', 'data-bookmarks': '' }, h('option', { value: '', text: 'Choose one' }))),
        h('label', { class: 'bi-tool' }, 'Click a mark to ', h('select', { class: 'studio-select', 'data-mode': '' }, h('option', { value: 'filter', text: 'filter the page' }), h('option', { value: 'highlight', text: 'highlight' }))),
        h('button', { type: 'button', class: 'btn-pill btn-ghost', 'data-reset': '', hidden: true, text: 'Reset' }));
      this.shell.appendChild(h('div', { class: 'bi-live-bar', 'data-live-bar': '', hidden: true },
        h('span', { class: 'bi-live-state', 'data-live-state': 'connecting', role: 'status', 'aria-live': 'polite' }, h('span', { class: 'bi-live-dot', 'aria-hidden': 'true' }), h('span', { 'data-live-text': '' }, 'Connecting')),
        h('time', { class: 'bi-live-time', 'data-live-time': '' }),
        h('button', { type: 'button', class: 'btn-pill btn-ghost bi-live-pause', 'data-live-pause': '', 'aria-pressed': 'false' }, B().icon('pause-fill'), ' ', h('span', { text: 'Pause' }))));
      this.shell.appendChild(h('div', { class: 'bi-report-bar' }, h('nav', { class: 'bi-tabs', 'data-pages': '', role: 'tablist', part: 'tabs', 'aria-label': 'Pages of the report' }), tools));
      this.shell.appendChild(h('p', { class: 'bi-chips', 'data-chips': '', part: 'chips', 'aria-label': 'Filters in force' }));
      this.wire();
    }
    this.shell.appendChild(h('div', { class: 'bi-rgrid', 'data-grid': '', part: 'grid', role: 'group', 'aria-label': 'Report page' }));
    return this.shell;
  }
  wire() {
    const date = this.q('[data-date]'), mode = this.q('[data-mode]'), bm = this.q('[data-bookmarks]');
    date.addEventListener('change', () => { this.state.dates = date.value ? { from: date.value, to: date.value } : null; this.changed(null); });
    mode.addEventListener('change', () => { this.state.mode = mode.value; if (this.state.selections.length) { this.changed(null); } });
    this.q('[data-reset]').addEventListener('click', () => this.reset());
    (this.report.bookmarks || []).forEach((b) => bm.appendChild(B().h('option', { value: b.name, text: b.name })));
    bm.addEventListener('change', () => { if (bm.value) { this.bookmark(bm.value); bm.value = ''; } });
  }
  syncBar() {
    const date = this.q('[data-date]'), mode = this.q('[data-mode]');
    if (date) { date.value = this.state.dates && this.state.dates.from && this.state.dates.from === this.state.dates.to ? this.state.dates.from : ''; }
    if (mode) { mode.value = this.state.mode; }
  }
  tabs() {
    const nav = this.q('[data-pages]');
    if (!nav) { return; }
    B().clear(nav);
    this.report.pages.filter((p) => !p.drillthrough || p.id === this.pageId).forEach((p) => {
      const b = B().h('button', { type: 'button', class: 'bi-tab', role: 'tab', 'data-page': p.id, 'aria-selected': p.id === this.pageId ? 'true' : 'false', text: p.title });
      b.addEventListener('click', () => { if (p.id !== this.pageId) { this.state.selections = []; this.go(p.id, [], 'user'); } });
      nav.appendChild(b);
    });
  }
  chips() {
    const box = this.q('[data-chips]');
    if (!box) { return; }
    const h = B().h, s = this.state;
    B().clear(box);
    s.carry.forEach((c) => box.appendChild(h('span', { class: 'bi-chip', 'data-chip': 'carry' }, B().icon('funnel'), ' ' + c.field + ': ' + c.values.join(', '))));
    s.selections.forEach((x) => box.appendChild(h('span', { class: 'bi-chip', 'data-chip': 'selection' }, B().icon('cursor'), ' ' + x.field + ': ' + x.values.join(', '))));
    Object.keys(s.slicers).forEach((k) => {
      const v = s.slicers[k];
      if (v == null) { return; }
      const sp = this.report.pages.map((p) => p.visuals.find((y) => y.id === k)).find(Boolean);
      box.appendChild(h('span', { class: 'bi-chip', 'data-chip': 'slicer' }, B().icon('sliders'), ' ' + (sp ? sp.title || B().label(sp.wells.field[0]) : k) + ': ' + (Array.isArray(v) ? v.join(', ') : (v.from == null ? '' : v.from) + ' to ' + (v.to == null ? '' : v.to))));
    });
    this.q('[data-reset]').hidden = !box.children.length && !s.dates;
  }

  // -- the grid -----------------------------------------------------------------------------------------------------------------
  buttons(spec) {
    const entry = this.frames.get(spec.id);
    if (!entry || !this.drawn(spec)) { return; }
    const h = B().h, tools = B().clear(entry.frame.tools), st = this.state;
    const mk = (cls, icon, label, fn, extra) => { const b = h('button', Object.assign({ type: 'button', class: 'bi-vis-btn ' + cls, 'aria-label': label, title: label }, extra || {}), B().icon(icon)); b.addEventListener('click', fn); tools.appendChild(b); };
    mk('bi-astable', 'table', st.asTable[spec.id] ? 'Show as a chart' : 'Show as a table', () => { st.asTable[spec.id] = !st.asTable[spec.id]; this.draw(spec.id); }, { 'data-act': 'as-table', 'aria-pressed': st.asTable[spec.id] ? 'true' : 'false' });
    if (st.selections.find((s) => s.visual === spec.id)) { mk('bi-clear', 'x-circle', 'Clear the selection', () => this.clearSelection(spec.id), { 'data-act': 'clear' }); }
  }
  /** The device class of the element's width (a phone, 640 px and narrower; a tablet, 1024 px and narrower) and the report's own layout for the page on it: {device, lay}, or null. */
  deviceLayout() { const device = B().phone.device(this.host.width), lay = device ? B().phone.layoutOf(this.report, this.pageId, device) : null; return lay ? { device, lay } : null; }
  drawGrid() {
    this.frames.forEach((e) => e.frame.dispose());
    this.frames.clear();
    const grid = B().clear(this.q('[data-grid]')), page = this.current, o = this.options;
    const dl = this.deviceLayout(), lay = dl && dl.lay;
    grid.setAttribute('data-phone', dl && dl.device === 'phone' ? 'layout' : 'auto');
    grid.setAttribute('data-device', dl ? dl.device : 'desktop');
    let list = B().phone.arrange(page, lay);
    if (o.visual) { list = list.filter((x) => x.spec.id === o.visual); }
    list.forEach(({ spec, rows }) => {
      const frame = B().frame(spec, { buttons: this.drawn(spec) ? [B().h('span', { class: 'bi-vis-slot' })] : [], onRetry: () => this.load(spec) });      // the slot makes the header; buttons() fills it
      if (o.visual) { frame.el.style.gridColumn = '1 / -1'; frame.el.style.height = Math.max(180, spec.at.h * 56) + 'px'; } else if (dl && dl.device === 'tablet') {
        frame.el.style.gridColumn = B().phone.tabletColumn(spec, lay); frame.el.style.gridRow = B().phone.tabletRow(spec, rows);
      } else if (lay) {
        frame.el.style.height = rows ? B().phone.height(rows) + 'px' : '';
      } else { frame.el.style.gridColumn = (spec.at.x + 1) + ' / span ' + spec.at.w; frame.el.style.gridRow = (spec.at.y + 1) + ' / span ' + spec.at.h; }
      frame.el.tabIndex = 0;
      this.frames.set(spec.id, { frame, spec, token: 0, res: null, pending: null });
      grid.appendChild(frame.el);
    });
    list.forEach(({ spec }) => { const e = this.frames.get(spec.id); e.pending = this.load(spec); this.buttons(spec); });
    this.tabs(); this.chips(); this.syncBar();
    this.emit('page-changed', { page: this.pageId, title: page.title, visuals: list.map((x) => x.spec.id), phone: !!dl && dl.device === 'phone', device: dl ? dl.device : 'desktop' });
  }
  go(pageId, carry, source) {
    this.pageId = pageId;
    this.state.carry = carry || [];
    if (source) { this.state.selections = []; }
    this.drawGrid();
    if (source) { this.emit('filter-changed', Object.assign(this.filters(), { source })); }
  }
  /** The element's width changed: swap between the report's phone layout and the grid when the page has a phone layout. */
  resized() {
    const was = this.frames.size ? this.q('[data-grid]').getAttribute('data-device') : null;
    const dl = this.deviceLayout(), now = dl ? dl.device : 'desktop';
    if (was && was !== now) { this.drawGrid(); } else { this.frames.forEach((e) => e.frame.resize()); }
  }
  start() {
    const o = this.options, first = this.report.pages.find((p) => !p.drillthrough) || this.report.pages[0];
    const owner = o.visual ? this.report.pages.find((p) => p.visuals.some((v) => v.id === o.visual)) : null;
    const wanted = o.page && this.page(o.page) ? o.page : owner ? owner.id : first.id;
    this.restyle();
    this.pageId = wanted;
    this.drawGrid();
    return this.settled();
  }
  dispose() { this.frames.forEach((e) => e.frame.dispose()); this.frames.clear(); }
}
