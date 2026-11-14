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
/* The grid's live mode as one component: LiveSetGrid draws a live result set (a blotter, a FORM or SQL result) in a drishti-grid. It takes a
   snapshot of the rows, then the changes the server streams (merged by key and version, applied as one batched transaction so the changed
   cells tick), tells the server which page the viewer shows (the rows in view and a margin when the set is larger than the grid holds, or
   "all" when it fits), and starts again from a fresh snapshot when the server says resync, when the connection comes back, and when a
   person resumes after Pause. Beside the grid: the record panel (drishti_live-panel.js), grouping with live subtotals (the declaration's
   subtotal columns are summed by difference as rows tick), the tray of the person's changes still waiting (four eyes, on their way to the
   source) and Pivot ›, which hands the same kind, filters, grouping and numbers to the Pivot, refreshing live when the kind is live.
   The page supplies two things and nothing else: api (httpLiveApi, or any object with rows, row, edit, window, history and mine) and channel
   (any object with subscribe(handlers) that answers an unsubscribe function; the console's Drishti.Channel keyed live:<id> is one). */
import { h, announce } from '../../drishti-core/src/drishti_index.js';
import { selectElement } from '../../drishti-inputs/src/drishti_index.js';
import { Grid } from './drishti_grid-view.js';
import { ROW_KEY, ROW_VERSION, LiveRows, gridColumns, windowIds, pivotLayout, pivotHref, staticSpec, staticApi } from './drishti_live-model.js';
import { RecordPanel, isPending, itemsText, stateWords } from './drishti_live-panel.js';

/** The states the status chip shows. */
export const LIVE_STATES = Object.freeze(['connecting', 'live', 'reconnecting', 'paused', 'ended', 'failed']);

const SNAPSHOT_ROWS = 5000;
const WINDOW_DELAY_MS = 200;

/**
 * @typedef {Object} LiveSetOptions
 * @property {Object} spec the declaration as GET /live describes it (id, title, kind, columns, groupBy, subtotals, sort, links, editable,
 *   readOnly, fields, approval, reason, tray)
 * @property {Object} api httpLiveApi's shape
 * @property {{subscribe: function(Object): function(): void}} channel handlers hello, rows, resync, gone, error
 * @property {('wells'|'panel'|'terminal')} [layout='wells']
 * @property {function(string): void} [navigate] where Pivot › goes (default: the page's address is set)
 * @property {function(string): Promise<void>} [copy] the clipboard (default: navigator.clipboard)
 * @property {number} [snapshotRows=5000] the most rows held; a larger set is windowed
 * @property {function({title: string, columns: Object[], rows: Object[]}): void} [onChart] when given, a Chart button hands the rows held (as the columns draw them) to it
 * @property {string} [keyField] STATIC MODE: the field that names a row (default: its place)
 * @property {Array<{label: string, run: function(Object): void}>} [rowActions] buttons beside the open record's links (the page decides what they do; given the grid's row)
 * @property {number} [offset=0] the first row read (a page of a larger set: the page's rows tick, the rest of the set is not held)
 * @property {Object[]} [rows] STATIC MODE: plain rows (no api, no channel needed): a result without a record key (an SQL answer, a preview).
 *   The rows are not merged and not edited; the panel shows the row's JSON. The spec may be omitted (the columns come from the rows) or
 *   given as {title, columns}.
 */

/**
 * A live result set in a grid.
 */
export class LiveSetGrid {
  /**
   * @param {HTMLElement} host
   * @param {LiveSetOptions} o
   */
  constructor(host, o) {
    this.host = host;
    this.doc = host.ownerDocument;
    this.static = Array.isArray(o.rows) && !o.api;
    if (this.static) {                                    // static mode: the same grid and panel over rows with no key, nothing merged or edited
      const given = o.spec || {};
      o = Object.assign({}, o, { spec: staticSpec({ rows: o.rows, columns: given.columns, title: given.title, id: given.id, links: given.links }), api: staticApi(o.rows, o.keyField),
        channel: { subscribe: () => () => undefined } });
    }
    this.o = o;
    this.spec = o.spec;
    this.api = o.api;
    this.rows = new LiveRows();
    this.state = 'connecting';
    this.live = false;
    this.paged = false;
    this.stream = null;
    this.hellos = 0;
    this.paused = false;
    this.panel = null;
    this.pending = [];
    this.timer = 0;
    this.stats = { batches: 0, upserts: 0, stale: 0, resyncs: 0 };
    this.build();
  }

  // ---- the frame ----------------------------------------------------------------------------------------------------------------------------
  /** @private the frame: the bar, the grid and the panel, the tray */
  build() {
    const d = this.doc, spec = this.spec;
    this.host.classList.add('dg-liveset');
    this.host.setAttribute('data-live-set', spec.id);
    this.status = h(d, 'span', { cls: 'dg-live-chip', attrs: { role: 'status', 'aria-live': 'polite' }, data: { state: this.state } });
    this.pauseBtn = this.static ? null : h(d, 'button', { cls: 'dg-live-btn', text: 'Pause', data: { act: 'pause' }, attrs: { type: 'button' }, on: { click: () => this.pause(!this.paused) } });
    this.pivotBtn = this.static ? null : h(d, 'button', { cls: 'dg-live-btn', text: 'Pivot ›', data: { act: 'pivot' }, attrs: { type: 'button', title: 'The same rows, filters and grouping in the Pivot' }, on: { click: () => this.toPivot() } });
    this.chartBtn = this.o.onChart ? h(d, 'button', { cls: 'dg-live-btn', text: 'Chart', data: { act: 'chart' }, attrs: { type: 'button', title: 'Chart these rows' },
      on: { click: () => this.o.onChart({ title: spec.title || spec.id, columns: spec.columns || [], rows: Array.from(this.rows.byId.values()) }) } }) : null;
    this.trayBtn = spec.tray ? h(d, 'button', { cls: 'dg-live-btn', text: 'My changes (0)', data: { act: 'tray' }, attrs: { type: 'button', 'aria-expanded': 'false' }, on: { click: () => this.toggleTray() } }) : null;
    const groupable = (spec.columns || []).filter((c) => c.type !== 'number' && c.type !== 'amount' && c.type !== 'list' && c.type !== 'datetime');
    this.groupSel = selectElement(d, { options: [{ value: '', label: 'No grouping' }].concat(groupable.map((c) => ({ value: c.field, label: c.label || c.field }))), value: (spec.groupBy || [])[0] || '',
      label: 'Group by', search: false }, { class: 'dg-live-group' });
    this.groupSel.component.on('change', (e) => this.group(e.value));
    this.bar = h(d, 'div', { cls: 'dg-live-bar', attrs: { role: 'toolbar', 'aria-label': (spec.title || spec.id) + ' controls' } }, [
      h(d, 'strong', { cls: 'dg-live-title', text: spec.title || spec.id }), this.status, h(d, 'span', { cls: 'dg-sp' }),
      h(d, 'label', { cls: 'dg-live-lbl' }, ['Group by ', this.groupSel]), this.chartBtn, this.pauseBtn, this.pivotBtn, this.trayBtn].filter(Boolean));
    this.gridHost = h(d, 'div', { cls: 'dg-live-grid' });
    this.panelHost = h(d, 'aside', { cls: 'dg-live-panel', attrs: { hidden: true, 'aria-label': 'Record' } });
    this.trayHost = h(d, 'section', { cls: 'dg-live-tray', attrs: { hidden: true, 'aria-label': 'My pending changes' }, data: { tray: 'true' } });
    // a source system switched off: the page says what it leaves out ("excludes ENDUR (switched off)")
    this.banner = spec.banner ? h(d, 'div', { cls: 'dg-live-banner', attrs: { role: 'status' }, data: { excludes: (spec.excludes || []).join(',') }, text: spec.banner }) : null;
    this.host.append(...[this.bar, this.banner, h(d, 'div', { cls: 'dg-live-split' }, [this.gridHost, this.panelHost]), this.trayHost].filter(Boolean));
  }

  /** @private the status chip */
  setState(s, note) {
    this.state = s;
    this.status.setAttribute('data-state', s);
    const n = this.rows.size, total = this.rows.total;
    const count = (this.paged ? n.toLocaleString('en-US') + ' of ' + total.toLocaleString('en-US') : total.toLocaleString('en-US')) + ' rows';
    this.status.textContent = ({ connecting: 'CONNECTING', live: this.live ? 'LIVE' : 'STATIC', reconnecting: 'RECONNECTING', paused: 'PAUSED', ended: 'ENDED', failed: 'FAILED' })[s] + ' · ' + count
      + (note ? ' · ' + note : '');
    this.status.setAttribute('data-rows', total);
  }

  // ---- start and the snapshot -----------------------------------------------------------------------------------------------------------------
  /**
   * Loads the snapshot, draws the grid and follows the stream.
   * @returns {Promise<LiveSetGrid>}
   */
  start() {
    return this.snapshot().then((rows) => {
      const spec = this.spec;
      this.grid = new Grid(this.gridHost, { columns: gridColumns(spec), rows: rows, getRowId: (r) => r[ROW_KEY], layout: this.o.layout || 'wells', density: 'compact', title: '',
        label: spec.title || spec.id, rowSelection: 'single', clientAggregation: true, live: { flashMs: 1200 },
        getRowData: (r) => ({ key: r[ROW_KEY] }) });
      if ((spec.groupBy || []).length) { this.grid.model.setRowGroups(spec.groupBy.slice(0, 1)); }
      this.grid.on('cellClicked', (c) => { if (c && c.kind === 'leaf' && c.data) { this.open(c.data); } });
      this.gridHost.addEventListener('scroll', () => this.windowSoon(), true);
      this.off = this.o.channel.subscribe({ hello: (m) => this.onHello(m || {}), rows: (m) => this.onRows(m || {}), resync: () => this.resync('the server asked'),
        gone: (m) => this.setState('ended', (m && m.detail) || 'the stream ended'), error: () => { if (this.state !== 'paused') { this.setState('reconnecting'); } } });
      this.setState(this.state === 'connecting' ? 'connecting' : this.state);
      if (this.static) { this.setState('live'); }
      this.loadTray();
      return this;
    });
  }

  /** @private reads the set's rows */
  snapshot() {
    const s = this.spec.sort || {};
    return this.api.rows({ limit: this.o.snapshotRows || SNAPSHOT_ROWS, offset: this.o.offset || undefined, sort: s.field, descending: s.descending ? 'true' : '' }).then((r) => {
      this.live = !!r.live;
      const rows = this.rows.load(r.rows || [], r.total, r.seq);
      this.paged = this.rows.total > rows.length;
      return rows;
    });
  }

  /**
   * Starts again from a fresh snapshot (the server could not keep up, the connection came back, or a person resumed).
   * @returns {Promise<void>}
   */
  resync() {
    this.stats.resyncs++;
    this.pending = [];
    return this.snapshot().then((rows) => {
      this.grid.setRows(rows);
      this.refreshPanel();
      this.setState(this.paused ? 'paused' : 'live');
      this.sendWindow();
    }, (e) => this.setState('failed', e.message));
  }

  // ---- the stream -----------------------------------------------------------------------------------------------------------------------------
  /** @private the stream's hello */
  onHello(m) {
    this.stream = m.stream;
    this.hellos++;
    if (m.live !== undefined) { this.live = !!m.live; }
    if (this.hellos > 1) { this.resync(); return; }       // a connection that came back: what was missed is not replayed
    this.setState(this.paused ? 'paused' : 'live');
    this.sendWindow();
  }

  /** @private a rows event: merged by key and version, applied as one batch */
  onRows(ev) {
    if (this.paused) { return; }
    const tx = this.rows.merge(ev, this.paged);
    this.stats.batches++;
    this.stats.upserts += tx.add.length + tx.update.length;
    this.stats.stale += tx.stale;
    if (tx.add.length || tx.update.length || tx.remove.length) { this.grid.applyTransactionAsync(tx); }
    tx.update.concat(tx.add).forEach((r) => { if (this.panel && this.panel.key === r[ROW_KEY]) { this.panel.update(r); } });
    tx.remove.forEach((id) => { if (this.panel && this.panel.key === id) { this.panel.update(Object.assign({}, this.panel.row, { [ROW_VERSION]: -2 })); } });
    this.setState('live');
  }

  /**
   * Changes fields of rows the page holds (a static set that a page feeds itself: a monitor's ticks): the grid's rows are updated in place and the open
   * record follows.
   * @param {Array<{id: string, fields: Object}>} changes the row's key and the fields that changed
   * @returns {number} how many rows changed
   */
  patch(changes) {
    const update = [];
    (changes || []).forEach((c) => {
      const held = this.rows.byId.get(String(c.id));
      if (!held) { return; }
      Object.assign(held, c.fields);
      update.push(held);
    });
    if (update.length) { this.grid.applyTransactionAsync({ add: [], update: update, remove: [] }); }
    update.forEach((r) => { if (this.panel && this.panel.key === r[ROW_KEY]) { this.panel.update(r); } });
    return update.length;
  }

  /**
   * Pauses the grid (rows that arrive are not applied) or resumes it from a fresh snapshot.
   * @param {boolean} on
   * @returns {void}
   */
  pause(on) {
    this.paused = !!on;
    this.pauseBtn.textContent = on ? 'Resume' : 'Pause';
    this.pauseBtn.setAttribute('aria-pressed', on ? 'true' : 'false');
    if (on) { this.setState('paused'); announce('Live updates paused'); } else { this.resync(); announce('Live updates resumed'); }
  }

  // ---- the page window ------------------------------------------------------------------------------------------------------------------------
  /** @private the window after the scroll settles */
  windowSoon() {
    if (!this.paged) { return; }
    clearTimeout(this.timer);
    this.timer = setTimeout(() => this.sendWindow(), WINDOW_DELAY_MS);
  }

  /**
   * Tells the server the page this viewer shows: the ids in view and a margin, or all when the viewer holds the whole set.
   * @returns {Promise<void>}
   */
  sendWindow() {
    if (!this.stream || !this.grid) { return Promise.resolve(); }
    const m = this.grid.model;
    let ids = null;
    if (this.paged) {
      const shown = [];
      for (let i = 0; i < m.rowCount(); i++) { const id = m.idAt(i); if (id) { shown.push(id); } }
      const v = m.viewport.rows();
      ids = windowIds(shown, v.firstVisible, v.lastVisible, true);
    }
    this.lastWindow = ids === null ? { all: true } : { ids: ids };
    return this.api.window(this.stream, this.lastWindow).then(() => undefined, () => undefined);
  }

  // ---- grouping, the panel, the tray, the Pivot -----------------------------------------------------------------------------------------------
  /**
   * Groups the rows by a field (none: ungrouped); the subtotal columns follow the ticks.
   * @param {string} field
   * @returns {void}
   */
  group(field) { this.grid.model.setRowGroups(field ? [field] : []); this.grid.refresh(); }

  /**
   * Opens a row's record panel.
   * @param {Object} row the grid's row
   * @returns {void}
   */
  open(row) {
    if (this.panel && this.panel.key === row[ROW_KEY]) { return; }
    if (this.panel) { this.panel.destroy(); }
    this.panelHost.hidden = false;
    this.host.setAttribute('data-panel', 'open');
    this.panel = new RecordPanel(this.panelHost, { spec: this.spec, api: this.api, row: row, copy: this.o.copy || ((t) => navigator.clipboard.writeText(t)),
      actions: this.o.rowActions, onSaved: () => this.loadTray(), onClose: () => this.close() });
  }

  /**
   * Closes the record panel.
   * @returns {void}
   */
  close() {
    if (this.panel) { this.panel.destroy(); this.panel = null; }
    this.panelHost.hidden = true;
    this.host.removeAttribute('data-panel');
  }

  /** @private the open panel's row after a snapshot */
  refreshPanel() {
    if (!this.panel) { return; }
    const fresh = this.rows.byId.get(this.panel.key);
    if (fresh) { this.panel.update(fresh); }
  }

  /**
   * The person's changes still open (waiting for an approver, on their way to the source), in the tray.
   * @returns {Promise<Object[]>}
   */
  loadTray() {
    if (!this.spec.tray || !this.api.mine) { return Promise.resolve([]); }
    return this.api.mine(this.spec.kind).then((r) => {
      this.pending = (r.changes || []).filter(isPending);
      this.drawTray();
      return this.pending;
    }, () => []);
  }

  /** @private the tray's rows */
  drawTray() {
    const d = this.doc;
    this.trayBtn.textContent = 'My changes (' + this.pending.length + ')';
    this.trayHost.textContent = '';
    if (!this.pending.length) { this.trayHost.append(h(d, 'p', { cls: 'dg-live-note', text: 'Nothing of yours is waiting.' })); return; }
    this.pending.forEach((c) => this.trayHost.append(h(d, 'p', { cls: 'dg-live-hrow', data: { change: c.id, state: c.state } }, [
      h(d, 'button', { cls: 'dg-live-link', text: c.entity, attrs: { type: 'button' }, on: { click: () => { const r = this.rows.byId.get(String(c.entity)); if (r) { this.open(r); } } } }),
      ' · ', itemsText(c), h(d, 'span', { cls: 'dg-live-state', text: ' (' + stateWords(c.state, c.needed) + ')' })])));
  }

  /** @private shows or hides the tray */
  toggleTray() {
    this.trayHost.hidden = !this.trayHost.hidden;
    this.trayBtn.setAttribute('aria-expanded', this.trayHost.hidden ? 'false' : 'true');
    if (!this.trayHost.hidden) { this.loadTray(); }
  }

  /**
   * The filters on the grid as members of the fields they filter (a set filter), for the Pivot.
   * @returns {Object<string, string[]>}
   */
  filterMembers() {
    const out = {}, fm = this.grid.model.filterModel || {};
    Object.keys(fm).forEach((k) => { const f = fm[k]; if (f && f.type === 'set' && Array.isArray(f.values) && f.values.length) { out[k] = f.values.slice(); } });
    return out;
  }

  /**
   * The address of the Pivot for the same kind, filters, grouping and numbers; live when the kind is.
   * @returns {string}
   */
  pivotAddress() {
    const groups = this.grid.model.rowModel.groupBy || [];
    return pivotHref(pivotLayout(this.spec, { groupBy: groups.slice(), filters: this.filterMembers() }), this.live);
  }

  /**
   * Hands over to the Pivot (the navigate option, else the page's address is set).
   * @returns {void}
   */
  toPivot() {
    const href = this.pivotAddress();
    if (this.o.navigate) { this.o.navigate(href); } else { this.doc.defaultView.location.assign(href); }
  }

  /**
   * Stops following and takes the grid and the panel away.
   * @returns {void}
   */
  destroy() {
    clearTimeout(this.timer);
    if (this.off) { this.off(); this.off = null; }
    if (this.panel) { this.panel.destroy(); }
    if (this.grid) { this.grid.destroy(); }
    this.host.textContent = '';
    this.host.classList.remove('dg-liveset');
  }
}
