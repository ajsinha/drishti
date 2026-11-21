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
/* The events inspector: drishti-events' part loaded when opened (drishti_events-inspector.js and its sheet; events.inspect()). It is
   assembled from the libraries: the lists are drishti-grids (loaded on demand), the answer of "why" is drishti-core's Explain drawing.
   Five tabs: Messages (time, direction, scope, topic, kind, seq, latency and what happened; a filter by topic pattern or words; pause),
   Subscriptions, Connection (the state timeline and the figures), Queue (what waits, since when) and Why. Keys: / filters, Space pauses,
   Enter opens the message under focus, Escape closes. Text only: nothing received is put into the page as HTML. */
import { h, rovingTabindex, renderExplain, load, loaded, openDrawer, isPattern } from '../../drishti-core/src/drishti_index.js';
import { matches } from './drishti_index.js';

/** The inspector's tabs. */
export const INSPECTOR_TABS = Object.freeze(['messages', 'subscriptions', 'connection', 'queue', 'why']);

const TAB_TITLES = { messages: 'Messages', subscriptions: 'Subscriptions', connection: 'Connection', queue: 'Queue', why: 'Why' };

/** @private hh:mm:ss.mmm */
function clock(ms) {
  if (typeof ms !== 'number') { return ''; }
  const d = new Date(ms), p = (n, w) => String(n).padStart(w || 2, '0');
  return p(d.getHours()) + ':' + p(d.getMinutes()) + ':' + p(d.getSeconds()) + '.' + p(d.getMilliseconds(), 3);
}

/**
 * What became of a recorded message, in words.
 * @param {Object} e a recorder entry
 * @returns {string}
 */
export function outcomeOf(e) {
  if (e.dir === 'out') { return 'published to ' + (e.scope || 'the page'); }
  if (e.delivered) { return 'delivered to ' + e.listeners + (e.listeners === 1 ? ' listener' : ' listeners'); }
  if (e.dropped) { return 'dropped: ' + e.dropped.reason; }
  return 'no listener';
}

/**
 * The Messages tab's rows: the recorder's entries, newest first, filtered by a topic pattern ('prices.>') or words in the topic, kind or
 * outcome; and by direction and outcome.
 * @param {Object} events
 * @param {{text: string, dir: string, outcome: string, max: number}} [f] outcome: 'delivered', 'dropped' or 'none'
 * @returns {Object[]}
 */
export function messageRows(events, f) {
  const q = Object.assign({ text: '', max: 2000 }, f), text = q.text.trim();
  const pattern = text && isPattern(text) && /[*>]/.test(text) ? text : null;
  const word = !pattern && text ? text.toLowerCase() : null;
  const out = [];
  const list = events.recorder.list;
  for (let i = list.length - 1; i >= 0 && out.length < q.max; i--) {
    const e = list[i];
    if (q.dir && e.dir !== q.dir) { continue; }
    const outcome = outcomeOf(e);
    if (q.outcome && !(q.outcome === 'delivered' ? e.delivered > 0 : q.outcome === 'dropped' ? !!e.dropped : !e.delivered && !e.dropped && e.dir === 'in')) { continue; }
    if (pattern && !safe(pattern, e.topic)) { continue; }
    if (word && (e.topic + ' ' + (e.kind || '') + ' ' + outcome).toLowerCase().indexOf(word) < 0) { continue; }
    out.push({ n: i, time: clock(e.at), dir: e.dir, scope: e.scope || '', topic: e.topic, kind: e.kind || '', seq: e.seq === undefined ? '' : e.seq, key: e.key || '',
      latency: e.dir === 'in' && e.deliveredAt && typeof e.time === 'number' ? Math.max(0, e.deliveredAt - e.time) : '', outcome: outcome, id: e.id || '' });
  }
  return out;
}

/** @private */
function safe(p, t) { try { return matches(p, t); } catch (e) { return false; } }

/**
 * The Subscriptions tab's rows.
 * @param {Object} events
 * @returns {Object[]}
 */
export function subscriptionRows(events) {
  return Array.from(events._subs).filter((s) => s.pattern[0] !== '$').map((s, i) => {
    const last = s.latest();
    return { n: i, pattern: s.pattern, scopes: s.scopes.join(', ') || 'none', state: s.state + (s.reason ? ': ' + s.reason : ''), received: s.stats.received,
      delivered: s.stats.delivered, dropped: s.stats.dropped, last: s.stats.lastAt ? clock(s.stats.lastAt) : '', latest: last ? textOf(last.data) : '' };
  });
}

/**
 * The Queue tab's rows: what waits for a server, oldest first.
 * @param {Object} events
 * @returns {Object[]}
 */
export function queueRows(events) {
  const c = events._adapter('server'), box = c && c.outbox;
  if (!box || !box.entries) { return []; }
  const now = events.clock.now();
  return Array.from(box.entries.values()).map((e, i) => ({ n: i, topic: e.frame.topic, since: clock(e.at), waitingMs: now - e.at, sent: e.sent ? 'sent, not yet acknowledged' : 'waiting to be sent',
    persisted: e.persist ? 'on disk' : 'memory', tries: e.tries }));
}

/**
 * The Connection tab: the states, newest last, and the figures in words.
 * @param {Object} events
 * @returns {{states: Array<{time: string, state: string, reason: string}>, figures: Array<{label: string, value: string}>}}
 */
export function connectionView(events) {
  const m = events.metrics(), c = m.connection, rtt = c.rtt || {};
  const fig = (label, v) => ({ label: label, value: v === null || v === undefined || v === '' ? 'none' : String(v) });
  return {
    states: events.recorder.states.map((s) => ({ time: clock(s.at), state: s.state, reason: s.reason || (s.waitMs != null ? 'next try in ' + Math.round(s.waitMs / 100) / 10 + ' s' : '') })),
    figures: [fig('State', c.state), fig('Transport', (c.transport || 'none') + (c.shared ? ' (shared by the tabs)' : '')), fig('Connects', c.connects), fig('Reconnects', c.reconnects),
      fig('Round trip', rtt.last == null ? null : rtt.last + ' ms (p50 ' + rtt.p50 + ', p99 ' + rtt.p99 + ')'), fig('Clock offset', c.offset == null ? null : Math.round(c.offset) + ' ms'),
      fig('Messages in', m.in.total + ' (' + m.in.perSecond1s + ' a second)'), fig('Delivery latency p99', m.in.latency.p99 == null ? null : m.in.latency.p99 + ' ms' + (m.in.latencyApproximate ? ' (approximate)' : '')),
      fig('Slowest listener', m.in.listenerMs.slowest ? m.in.listenerMs.slowest + ' (p99 ' + m.in.listenerMs.p99 + ' ms)' : null),
      fig('Publishes', m.out.publishes + ', acknowledged ' + m.out.acked + ', failed ' + m.out.failed), fig('Drops', Object.keys(m.drops).map((k) => k + ' ' + m.drops[k]).join(', '))]
  };
}

/** @private a value as one line of text */
function textOf(v) { let t; try { t = JSON.stringify(v); } catch (e) { t = String(v); } return t && t.length > 120 ? t.slice(0, 117) + '...' : String(t); }

const COLUMNS = {
  messages: [['time', 'Time', 110], ['dir', 'Dir', 50], ['scope', 'Scope', 70], ['topic', 'Topic', 220], ['kind', 'Kind', 80], ['seq', 'Seq', 70, 'number'],
    ['latency', 'Latency ms', 90, 'number'], ['outcome', 'What happened', 200]],
  subscriptions: [['pattern', 'Pattern', 200], ['scopes', 'Scopes', 110], ['state', 'State', 140], ['received', 'Received', 80, 'number'],
    ['delivered', 'Delivered', 80, 'number'], ['dropped', 'Dropped', 80, 'number'], ['last', 'Last', 110], ['latest', 'Latest value', 220]],
  queue: [['topic', 'Topic', 220], ['since', 'Since', 110], ['waitingMs', 'Waiting ms', 90, 'number'], ['sent', 'State', 180], ['persisted', 'Kept', 80], ['tries', 'Tries', 60, 'number']]
};

/**
 * The inspector drawn into an element.
 */
export class Inspector {
  /**
   * @param {HTMLElement} el where it is drawn
   * @param {Object} events the Events inspected
   * @param {{refreshMs: number, onClose: function(): void, gridLib: Object}} [o] gridLib: drishti-grid's API (default: loaded)
   */
  constructor(el, events, o) {
    this.el = el;
    this.events = events;
    this.o = Object.assign({ refreshMs: 1000 }, o);
    this.doc = el.ownerDocument;
    this.grid = this.o.gridLib || loaded('drishti-grid');
    this.tab = 'messages';
    this.paused = false;
    this.filter = { text: '' };
    this.grids = {};
    this.build();
    this.timer = this.doc.defaultView.setInterval(() => { if (!this.paused) { this.refresh(); } }, this.o.refreshMs);
    this.refresh();
  }

  /** @private */
  build() {
    const d = this.doc;
    this.el.classList.add('drishti-events-inspector');
    this.el.setAttribute('role', 'region');
    this.el.setAttribute('aria-label', 'Events inspector');
    const bar = h(d, 'div', { cls: 'dei-bar' });
    this.tabs = h(d, 'div', { cls: 'dei-tabs', attrs: { role: 'tablist', 'aria-label': 'Inspector views' } });
    INSPECTOR_TABS.forEach((t) => {
      this.tabs.appendChild(h(d, 'button', { cls: 'dei-tab', text: TAB_TITLES[t], attrs: { type: 'button', role: 'tab', id: 'dei-tab-' + t, 'aria-controls': 'dei-pane-' + t,
        'aria-selected': t === this.tab ? 'true' : 'false', 'data-tab': t }, on: { click: () => this.show(t) } }));
    });
    this.roving = rovingTabindex(this.tabs, { selector: '[role="tab"]' });
    this.find = h(d, 'input', { cls: 'dei-filter', attrs: { type: 'search', placeholder: 'Filter: a topic pattern (prices.>) or words', 'aria-label': 'Filter the messages' },
      on: { input: () => { this.filter.text = this.find.value; this.refresh(); } } });
    this.pause = h(d, 'button', { cls: 'dei-pause', text: 'Pause', attrs: { type: 'button', 'aria-pressed': 'false' }, on: { click: () => this.togglePause() } });
    const close = h(d, 'button', { cls: 'dei-close', text: 'Close', attrs: { type: 'button', 'aria-label': 'Close the events inspector' }, on: { click: () => this.close() } });
    this.status = h(d, 'span', { cls: 'dei-status', attrs: { 'aria-live': 'polite' } });
    bar.append(this.tabs, this.find, this.pause, this.status, close);
    this.panes = {};
    const body = h(d, 'div', { cls: 'dei-body' });
    INSPECTOR_TABS.forEach((t) => {
      const p = h(d, 'div', { cls: 'dei-pane dei-pane-' + t, attrs: { role: 'tabpanel', id: 'dei-pane-' + t, 'aria-labelledby': 'dei-tab-' + t } });
      p.hidden = t !== this.tab;
      this.panes[t] = p;
      body.appendChild(p);
    });
    this.detail = h(d, 'pre', { cls: 'dei-detail', attrs: { 'aria-label': 'The message' } });
    this.detail.hidden = true;
    this.panes.messages.appendChild(this.detail);
    this.buildWhy();
    this.el.append(bar, body);
    this.onKey = (e) => this.key(e);
    this.el.addEventListener('keydown', this.onKey);
  }

  /** @private the Why tab: a topic, an optional key, Explain */
  buildWhy() {
    const d = this.doc, p = this.panes.why;
    const topic = h(d, 'input', { cls: 'dei-why-topic', attrs: { type: 'text', placeholder: 'prices.fx.USDCAD', 'aria-label': 'Topic' } });
    const key = h(d, 'input', { cls: 'dei-why-key', attrs: { type: 'text', placeholder: 'key (optional)', 'aria-label': 'Key' } });
    const out = h(d, 'div', { cls: 'dei-why-answer' });
    const ask = () => {
      if (!topic.value.trim()) { out.textContent = 'Name a topic first.'; return; }
      const r = this.events.explain({ topic: topic.value.trim(), key: key.value.trim() || undefined });
      renderExplain(out, { row: 0, col: 0, loading: false, result: r, error: null }, 'card', { prefix: 'dg' });
      out.setAttribute('data-failed', r.failed ? r.failed.label : '');
    };
    const go = h(d, 'button', { cls: 'dei-why-go', text: 'Explain', attrs: { type: 'button' }, on: { click: ask } });
    topic.addEventListener('keydown', (e) => { if (e.key === 'Enter') { ask(); } });
    p.append(h(d, 'div', { cls: 'dei-why-form' }, [topic, key, go]), out);
    this.why = { topic: topic, key: key, out: out, ask: ask };
  }

  /**
   * Shows a tab.
   * @param {string} t one of INSPECTOR_TABS
   * @returns {void}
   */
  show(t) {
    if (INSPECTOR_TABS.indexOf(t) < 0) { return; }
    this.tab = t;
    Array.prototype.forEach.call(this.tabs.children, (b) => b.setAttribute('aria-selected', b.getAttribute('data-tab') === t ? 'true' : 'false'));
    INSPECTOR_TABS.forEach((x) => { this.panes[x].hidden = x !== t; });
    this.find.hidden = t !== 'messages';
    this.refresh();
  }

  /** @private a grid in a pane, made once */
  gridIn(t, rows) {
    if (!this.grid) { return null; }
    let g = this.grids[t];
    if (!g) {
      const host = h(this.doc, 'div', { cls: 'dei-grid' });
      this.panes[t].insertBefore(host, this.panes[t].firstChild);
      g = this.grids[t] = new this.grid.Grid(host, { columns: COLUMNS[t].map((c) => ({ field: c[0], header: c[1], width: c[2], type: c[3] })), rows: rows,
        getRowId: (r) => String(r.n), chrome: false, rowSelection: 'single', density: 'compact', layout: 'panel', customize: false, ribbon: false, pivotToggle: false });
      if (t === 'messages') { g.on('cellDoubleClicked', (c) => this.open(c && c.row)); }
      return g;
    }
    g.setRows(rows);
    return g;
  }

  /**
   * Draws the shown tab from the events' state now.
   * @returns {void}
   */
  refresh() {
    const ev = this.events, t = this.tab;
    if (t === 'messages') {
      this.rows = messageRows(ev, this.filter);
      this.gridIn('messages', this.rows);
      this.status.textContent = (this.paused ? 'Paused: ' : '') + this.rows.length + ' messages';
    } else if (t === 'subscriptions') { this.gridIn('subscriptions', subscriptionRows(ev)); this.status.textContent = ''; }
    else if (t === 'queue') {
      const rows = queueRows(ev);
      this.gridIn('queue', rows);
      this.status.textContent = rows.length ? rows.length + ' waiting' : 'Nothing waits';
    } else if (t === 'connection') { this.drawConnection(); this.status.textContent = ''; }
  }

  /** @private the Connection tab: figures and the state timeline, as text */
  drawConnection() {
    const d = this.doc, p = this.panes.connection, v = connectionView(this.events);
    while (p.firstChild) { p.removeChild(p.firstChild); }
    const dl = h(d, 'dl', { cls: 'dei-figures' });
    v.figures.forEach((f) => { dl.append(h(d, 'dt', { text: f.label }), h(d, 'dd', { text: f.value })); });
    const ol = h(d, 'ol', { cls: 'dei-timeline', attrs: { 'aria-label': 'The connection\'s states, newest last' } });
    v.states.slice(-50).forEach((s) => ol.appendChild(h(d, 'li', { text: s.time + '  ' + s.state + (s.reason ? ': ' + s.reason : '') })));
    p.append(dl, ol);
  }

  /**
   * Opens one message: its header and, when the recorder keeps payloads, its data (as text).
   * @param {(Object|number)} row a Messages row, or its index
   * @returns {void}
   */
  open(row) {
    const r = typeof row === 'number' ? (this.rows || [])[row] : row;
    if (!r) { return; }
    const e = this.events.recorder.list[r.n];
    if (!e) { return; }
    const shown = Object.assign({}, e);
    delete shown.patterns;
    this.detail.textContent = JSON.stringify(shown, null, 2);
    this.detail.hidden = false;
  }

  /** @private pause and resume (the list stops moving; nothing is lost) */
  togglePause() {
    this.paused = !this.paused;
    this.pause.textContent = this.paused ? 'Resume' : 'Pause';
    this.pause.setAttribute('aria-pressed', this.paused ? 'true' : 'false');
    this.refresh();
  }

  /** @private */
  key(e) {
    const t = e.target, typing = t && (t.tagName === 'INPUT' || t.tagName === 'TEXTAREA');
    if (e.key === 'Escape') { if (!this.detail.hidden) { this.detail.hidden = true; } else { this.close(); } e.preventDefault(); return; }
    if (typing) { return; }
    if (e.key === '/') { e.preventDefault(); this.show('messages'); this.find.focus(); return; }
    if (e.key === ' ' && this.tab === 'messages' && !(t && t.tagName === 'BUTTON')) { e.preventDefault(); this.togglePause(); return; }
    if (e.key === 'Enter' && this.tab === 'messages' && this.grids.messages) {
      const f = this.grids.messages.model.navigator.focus;
      if (f) { e.preventDefault(); this.open(f.row); }
    }
  }

  /**
   * Closes the inspector (its element is emptied).
   * @returns {void}
   */
  close() {
    if (this.closed) { return; }
    this.closed = true;
    this.doc.defaultView.clearInterval(this.timer);
    this.el.removeEventListener('keydown', this.onKey);
    if (this.roving && this.roving.destroy) { this.roving.destroy(); }
    Object.values(this.grids).forEach((g) => { if (g.destroy) { g.destroy(); } });
    while (this.el.firstChild) { this.el.removeChild(this.el.firstChild); }
    this.el.classList.remove('drishti-events-inspector');
    if (this.o.onClose) { this.o.onClose(); }
  }
}

/**
 * Opens the inspector: into an element (a dock's panel, a page's own place), or else a drawer at the page's side. drishti-grid is loaded
 * first.
 * @param {Object} events
 * @param {HTMLElement} [el] where to draw it
 * @param {{refreshMs: number}} [o]
 * @returns {Promise<Inspector>}
 */
export function openInspector(events, el, o) {
  const opts = Object.assign({}, o);
  return load('drishti-grid').then(() => {
    if (el) { return new Inspector(el, events, opts); }
    const doc = (typeof document !== 'undefined' && document) || null;
    const drawer = h(doc, 'aside', { cls: 'dei-drawer', attrs: { 'aria-label': 'Events inspector' } });
    doc.body.appendChild(drawer);
    let closeDrawer = null;
    const ins = new Inspector(drawer, events, Object.assign({}, opts, { onClose: () => { if (closeDrawer) { closeDrawer(); } drawer.remove(); } }));
    closeDrawer = openDrawer(drawer, { onClose: () => ins.close(), initial: ins.tabs.firstChild });
    return ins;
  });
}

/**
 * A command for drishti-core's command palette: "Open the events inspector".
 * @param {Object} events
 * @returns {{id: string, title: string, run: function(): Promise<Inspector>}}
 */
export function inspectorCommand(events) { return { id: 'events.inspect', title: 'Open the events inspector', run: () => openInspector(events) }; }
