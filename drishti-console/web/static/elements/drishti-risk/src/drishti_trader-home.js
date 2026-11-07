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
/* The trader home (docs/architecture/SHARED_SCREENS.md section 4; the product owner's option A, with C's feed as a column and B one click
   from a book): five headline figures, my books in a drishti-grid at terminal density, the markets I watch, what changed since the morning
   or my last visit, and a live feed. The trading domain's: it lives in drishti-risk, licensed with the banking module, never in a generic
   library. Every figure is the server's (the answer of GET /api/home/trading/home); this draws them, flashes what moved and marks every
   number for the one Explain (data-explain, opened by the page's Explain). The host gives it how to read the answer (load), how to hear the
   server's ticks (live) and where a book opens (openBook). */
import { h, formatCompact, formatNumber, formatPercent, announce } from '../../drishti-core/src/drishti_index.js';
import { Grid } from '../../drishti-grid/src/drishti_index.js';

/** The parts of the home, in the page's order. */
export const TRADER_HOME_PARTS = Object.freeze(['headline', 'books', 'markets', 'changes', 'feed']);

/** How long the home waits after a tick before it asks again (ticks within it are one ask). */
export const TICK_WAIT_MS = 300;

/**
 * An amount as a trader reads it: 1,840,000 → 1.84m, −142,000 → −142k (thousands without decimals), the sign always when asked.
 * @param {*} v
 * @param {boolean} [signed]
 * @returns {string}
 */
export function money(v, signed) {
  return formatCompact(v === null || v === undefined || v === '' ? null : Number(v), { decimals: (a, u) => (u === 'k' || !u ? 0 : 2), sign: signed ? 'always' : 'auto' });
}

/**
 * A limit use (a share: 0.83) as a percentage without decimals.
 * @param {*} v
 * @returns {string}
 */
export function useText(v) { return v === null || v === undefined ? '—' : formatPercent(Number(v), { decimals: 0 }); }

/**
 * A market tile's value in its unit, and its change since the day before.
 * @param {{value: *, unit: string, change: ?{delta: number}}} t
 * @returns {{last: string, change: string, dir: ?string}}
 */
export function marketText(t) {
  const unit = t.unit === 'bp' ? ' bp' : t.unit === '%' ? '%' : (t.unit ? ' ' + t.unit : '');
  const last = t.value === null || t.value === undefined ? '—' : formatNumber(Number(t.value), { decimals: t.unit === 'bp' ? 1 : 3 }) + unit;
  const d = t.change && typeof t.change.delta === 'number' ? t.change.delta : null;
  const cu = t.unit === '%' ? ' bp' : unit;                  // a rate's change is in basis points (the market landing's own scale)
  return { last: last, change: d === null ? '—' : formatNumber(d, { decimals: 1, sign: 'always' }) + cu, dir: d === null || d === 0 ? null : d > 0 ? 'up' : 'down' };
}

/**
 * The state of a limit in words a screen reader says and a colour repeats.
 * @param {string} state breach | near | ok | none
 * @returns {string}
 */
export function limitWords(state) { return state === 'breach' ? 'over its limit' : state === 'near' ? 'near its limit' : state === 'ok' ? 'within its limit' : 'no limit'; }

/** The Explain request of a VaR figure (the one Explain: GET /api/explain, the person's own). */
function varRequest(scope) { return scope ? JSON.stringify({ kind: 'var', measure: 'var', scope: scope }) : null; }

/**
 * The trader home, drawn into an element.
 */
export class TraderHome {
  /**
   * @param {HTMLElement} host the element the home fills
   * @param {{load: function(string, {fresh: boolean}): Promise<Object>, live: ?function({tick: function(Object): void, state: function(string): void}): function(): void,
   *   openBook: ?function(string): void, since: ?string, density: ?string, onPainted: ?function(Object): void}} o load(since) answers the
   *   home (GET /api/home/trading/home?since=, fresh after a tick); live subscribes to the server's ticks and gives back how to stop; openBook opens a book
   */
  constructor(host, o) {
    if (!host || !host.ownerDocument) { throw new Error('the trader home needs an element to fill'); }
    this.host = host;
    this.doc = host.ownerDocument;
    this.o = Object.assign({ since: 'morning', density: 'terminal' }, o);
    this.since = this.o.since === 'visit' ? 'visit' : 'morning';
    this.answer = null;
    this.asking = null;
    this.again = false;
    this.timer = 0;
    this.seen = new Set();
    this.ticks = 0;
    this.fresh = false;
    this.off = null;
    this.build();
  }

  el(tag, p, kids) { return h(this.doc, tag, p, kids); }

  /** The page's frame: the status line, the headline tiles, books and markets side by side, then what changed and the feed. */
  build() {
    const e = this.el.bind(this);
    this.status = e('span', { cls: 'th-live', attrs: { 'data-th-live': 'connecting', role: 'status' }, text: 'Connecting…' });
    this.dateLine = e('span', { cls: 'th-date', attrs: { 'data-th-date': '' } });
    this.tiles = e('section', { cls: 'th-tiles', attrs: { 'aria-label': 'Headline figures', 'data-th-part': 'headline' } });
    this.booksBox = e('div', { cls: 'th-grid', attrs: { 'data-th-books-grid': '' } });
    this.marketsBox = e('div', { cls: 'th-grid', attrs: { 'data-th-markets-grid': '' } });
    this.changesBox = e('div', { cls: 'th-changes', attrs: { 'data-th-changes': '', 'aria-live': 'polite' } });
    this.feedList = e('ol', { cls: 'th-feed', attrs: { 'data-th-feed': '', 'aria-live': 'polite', 'aria-label': 'Live feed, newest first' } });
    this.problemsLine = e('p', { cls: 'th-problems', attrs: { 'data-th-problems': '', hidden: true } });
    const sinceBtn = (v, label) => e('button', { cls: 'th-since', text: label, attrs: { type: 'button', 'data-th-since': v, 'aria-pressed': String(this.since === v) },
      on: { click: () => this.setSince(v) } });
    this.sinceBtns = [sinceBtn('morning', 'Since this morning'), sinceBtn('visit', 'Since my last visit')];
    const card = (part, title, body, extra) => e('section', { cls: 'th-card', attrs: { 'data-th-part': part, 'aria-label': title } },
      [e('h2', {}, [title, extra || null]), body]);
    const liveTag = () => e('span', { cls: 'th-tick', text: '● live', attrs: { 'aria-hidden': 'true' } });
    this.host.textContent = '';
    this.host.appendChild(e('div', { cls: 'th-head' }, [this.dateLine, this.status,
      e('button', { cls: 'th-refresh', text: 'Refresh', attrs: { type: 'button', 'data-th-refresh': '' }, on: { click: () => this.refresh() } })]));
    this.host.appendChild(this.tiles);
    this.host.appendChild(this.problemsLine);
    this.host.appendChild(e('div', { cls: 'th-cols' }, [
      card('books', 'My books', this.booksBox, liveTag()),
      card('markets', 'Markets I watch', this.marketsBox, liveTag())]));
    this.host.appendChild(e('div', { cls: 'th-cols th-cols-low' }, [
      card('changes', 'What changed', e('div', {}, [e('div', { cls: 'th-sinces', attrs: { role: 'group', 'aria-label': 'Since when' } }, this.sinceBtns), this.changesBox])),
      card('feed', 'Live feed', this.feedList, liveTag())]));
    this.books = new Grid(this.booksBox, {
      columns: this.bookColumns(), rows: [], getRowId: (r) => r.book, layout: 'terminal', density: this.o.density, chrome: false, checkboxes: false,
      rowSelection: 'none', clientAggregation: false, label: 'My books', decorateCell: (c) => this.decorateBook(c), live: { flashMs: 1500 } });
    this.markets = new Grid(this.marketsBox, {
      columns: [{ field: 'label', header: 'Market', width: 170, pinned: 'left' }, { field: 'last', header: 'Last', width: 110 },
        { field: 'chg', header: 'Chg', width: 100 }],
      rows: [], getRowId: (r) => r.id, layout: 'terminal', density: this.o.density, chrome: false, checkboxes: false, rowSelection: 'none',
      clientAggregation: false, label: 'Markets I watch', decorateCell: (c) => this.decorateMarket(c), live: { flashMs: 1500 } });
    this.booksBox.addEventListener('drishti:cell-clicked', (ev) => {
      const d = ev.detail || {};
      if (d.columnId === 'book' && d.data && this.o.openBook) { this.o.openBook(d.data.book); }
    });
  }

  bookColumns() {
    return [
      { field: 'book', header: 'Book', width: 150, pinned: 'left' },
      { field: 'pnl', header: 'P&L today', type: 'number', width: 110, format: (v) => money(v, true) },
      { field: 'var', header: 'VaR', type: 'number', width: 100, additive: false, format: (v) => money(v) },
      { field: 'limit', header: 'Limit', type: 'number', width: 100, additive: false, format: (v) => money(v) },
      { field: 'limitUse', header: 'Limit use', type: 'number', width: 140, additive: false, format: (v) => useText(v) },
      { field: 'trades', header: 'Trades', type: 'number', width: 80, format: (v) => (v === null || v === undefined ? '—' : formatNumber(v, { decimals: 0 })) }];
  }

  /** A book's cell: the book opens it; VaR, limit and limit use carry their Explain; the limit use draws its bar; P&L its tone. */
  decorateBook(c) {
    const x = c.kind === 'leaf' && this.books ? this.books.model.rowAt(c.row) : null;
    const r = (x && x.row) || {};
    const id = c.columnId;
    if (id === 'book') {
      return { content: this.el('a', { cls: 'th-book', text: c.text, attrs: { href: '#', 'data-th-book': c.text, role: 'link', title: 'Open the book position by position' },
        on: { click: (ev) => { ev.preventDefault(); if (this.o.openBook) { this.o.openBook(c.text); } } } }) };
    }
    if (id === 'pnl') {
      const n = c.value === null || c.value === undefined ? null : Number(c.value);
      return { cls: n === null || n === 0 ? '' : n > 0 ? 'th-up' : 'th-down', title: 'P&L today: the server\'s pivot engine over the trade valuations (Explain for P&L is under construction: explain-trading-pnl)' };
    }
    if (id === 'var' || id === 'limit' || id === 'limitUse') {
      const req = varRequest(r.scope || (r.book ? 'BOOK:' + r.book : null));
      const span = this.el('span', { cls: 'th-fig', text: c.text, attrs: { 'data-explain': req, tabindex: '0', 'data-th-fig': id } });
      if (id !== 'limitUse') { return { content: span }; }
      const use = c.value === null || c.value === undefined ? null : Number(c.value);
      const bar = this.el('span', { cls: 'th-bar th-' + (r.state || 'none'), attrs: { 'aria-hidden': 'true' } },
        [this.el('i', { style: { width: Math.max(0, Math.min(100, use === null ? 0 : use * 100)) + '%' } })]);
      return { content: this.el('span', { cls: 'th-use' }, [bar, span]), title: r.book ? r.book + ' ' + limitWords(r.state) : '' };
    }
    return null;
  }

  decorateMarket(c) {
    if (c.columnId !== 'chg') { return c.columnId === 'last' ? { title: 'A stored figure: the market-data pack\'s, as loaded' } : null; }
    const x = c.kind === 'leaf' && this.markets ? this.markets.model.rowAt(c.row) : null;
    const m = x && x.row;
    return m && m.dir ? { cls: m.dir === 'up' ? 'th-up' : 'th-down' } : null;
  }

  /** Starts: the first answer, then the server's ticks. */
  start() {
    const p = this.refresh();
    if (this.o.live && !this.off) {
      this.off = this.o.live({ tick: () => this.onTick(), state: (s) => this.liveState(s) });
    }
    return p;
  }

  /** A tick from the server: ask again soon (ticks close together are one ask, never two asks at once). */
  onTick() {
    this.ticks += 1;
    this.fresh = true;                                         // the server's data moved: what was kept for this person is read again
    this.liveState('live');
    clearTimeout(this.timer);
    this.timer = this.doc.defaultView.setTimeout(() => this.refresh(), TICK_WAIT_MS);
  }

  liveState(s) {
    const words = { live: '● live', connecting: 'Connecting…', reconnecting: 'Reconnecting…', off: 'Not live: refresh to update' };
    this.status.setAttribute('data-th-live', s);
    this.status.textContent = words[s] || s;
  }

  setSince(v) {
    if (v === this.since) { return Promise.resolve(this.answer); }
    this.since = v;
    this.sinceBtns.forEach((b) => b.setAttribute('aria-pressed', String(b.getAttribute('data-th-since') === v)));
    return this.refresh();
  }

  /** Asks the server for the home and draws it; an ask while one runs is made once it ends. */
  refresh() {
    if (this.asking) { this.again = true; return this.asking; }
    const fresh = this.fresh;
    this.fresh = false;
    this.asking = Promise.resolve(this.o.load(this.since, { fresh: fresh })).then((a) => { this.paint(a); return a; }, (err) => {
      this.problemsLine.hidden = false;
      this.problemsLine.textContent = 'Not available: ' + (err && err.message ? err.message : err);
      return null;
    }).then((a) => {
      this.asking = null;
      if (this.again) { this.again = false; return this.refresh(); }
      return a;
    });
    return this.asking;
  }

  /** Draws an answer: the first one whole, the next ones as updates (changed figures flash, new feed items are marked and said). */
  paint(a) {
    if (!a || a.available === false) {
      this.problemsLine.hidden = false;
      this.problemsLine.textContent = (a && a.reason) || 'Nothing to show';
      return;
    }
    const first = !this.answer;
    this.answer = a;
    this.dateLine.textContent = 'Business date ' + (a.businessDate || '—') + (a.dataDate && a.dataDate !== a.businessDate ? ' (valuations of ' + a.dataDate + ')' : '') +
      (a.scope && a.scope.id ? ' · ' + (a.scope.kind === 'desk' ? 'desk ' : '') + a.scope.id : '');
    this.paintTiles(a.headline || {}, first);
    this.paintBooks(a.books || [], first);
    this.paintMarkets(a.markets, first);
    this.paintChanges(a.changes || {});
    this.paintFeed(a.feed || [], first);
    const probs = a.problems || [];
    this.problemsLine.hidden = !probs.length;
    this.problemsLine.textContent = probs.length ? 'Not shown: ' + probs.join(' · ') : '';
    this.host.setAttribute('data-th-painted', String((Number(this.host.getAttribute('data-th-painted')) || 0) + 1));
    if (this.o.onPainted) { this.o.onPainted(a); }
  }

  tile(id, label, value, sub, o) {
    o = o || {};
    let t = this.tiles.querySelector('[data-th-tile="' + id + '"]');
    if (!t) {
      t = this.el('div', { cls: 'th-tile', attrs: { 'data-th-tile': id } }, [this.el('span', { cls: 'k', text: label }),
        this.el(o.href ? 'a' : 'span', { cls: 'v', attrs: { 'data-th-v': '', href: o.href || null } }), this.el('span', { cls: 's', attrs: { 'data-th-s': '' } })]);
      this.tiles.appendChild(t);
    }
    const v = t.querySelector('[data-th-v]');
    const before = v.textContent;
    v.textContent = value;
    v.className = 'v' + (o.tone ? ' th-' + o.tone : '');
    if (o.explain) { v.setAttribute('data-explain', o.explain); v.setAttribute('tabindex', '0'); if (o.explainTab) { v.setAttribute('data-explain-tab', o.explainTab); } } else { v.removeAttribute('data-explain'); }
    if (o.title) { v.setAttribute('title', o.title); }
    if (o.href) { v.setAttribute('href', o.href); }
    t.querySelector('[data-th-s]').textContent = sub || '';
    if (before && before !== value && !o.first) {
      t.classList.remove('th-flash');
      void t.offsetWidth;                                      // restart the animation for a second change
      t.classList.add('th-flash');
    }
  }

  paintTiles(x, first) {
    const n = (v) => (v === null || v === undefined ? null : Number(v));
    const pnl = n(x.pnl);
    this.tile('pnl', 'P&L today', money(pnl, true), x.previousDate ? 'since ' + x.previousDate : 'no valuations the day before',
      { tone: pnl === null || pnl === 0 ? null : pnl > 0 ? 'up' : 'down', first: first, title: 'The server\'s pivot engine over the trade valuations (Explain for P&L is under construction: explain-trading-pnl)' });
    this.tile('var', 'VaR' + (x.confidence ? ' ' + x.confidence : ''), money(x.var), x.limit !== null && x.limit !== undefined ? 'limit ' + money(x.limit) + ' · ' + useText(x.limitUse) : 'no limit',
      { tone: x.state === 'breach' ? 'bad' : x.state === 'near' ? 'warn' : null, explain: varRequest(x.scope), first: first });
    const b = x.biggest;
    this.tile('biggest', 'Biggest risk', b ? String(b.label || '—') : '—', b ? 'VaR contribution ' + money(b.componentVar) : 'no contributions',
      { explain: b && b.scope ? varRequest(b.scope) : null, first: first });
    const breaches = (x.breaches || []).length, near = (x.near || []).length;
    this.tile('breaches', 'Breaches', formatNumber(breaches, { decimals: 0 }), near ? near + ' near (' + (x.near || []).join(', ') + ')' : 'none near',
      { tone: breaches ? 'bad' : near ? 'warn' : 'ok', first: first, title: (x.breaches || []).join(', ') });
    this.tile('approvals', 'Approvals for me', x.approvals === null || x.approvals === undefined ? '—' : formatNumber(x.approvals, { decimals: 0 }),
      x.approvalsDueSoon ? x.approvalsDueSoon + ' due soon' : 'in the Approvals queue', { href: '/approvals', tone: x.approvals ? 'warn' : null, first: first });
  }

  paintBooks(rows, first) {
    this.byBook = rows.slice();
    const data = rows.map((r) => Object.assign({}, r));
    if (first) { this.books.setRows(data); return; }
    const had = new Set((this.books.model.rowModel.rows || []).map((r) => r.book));
    const now = new Set(data.map((r) => r.book));
    this.books.applyTransaction({ update: data.filter((r) => had.has(r.book)), add: data.filter((r) => !had.has(r.book)),
      remove: Array.from(had).filter((b) => !now.has(b)).map((b) => ({ book: b })) });
  }

  paintMarkets(m, first) {
    const tiles = (m && m.tiles) || [];
    const rows = tiles.map((t) => { const x = marketText(t); return { id: t.id, label: t.label, last: x.last, chg: x.change, dir: x.dir, cmd: t.cmd }; });
    this.byMarket = rows;
    if (first || !this.marketRows) { this.markets.setRows(rows); } else { this.markets.applyTransaction({ update: rows }); }
    this.marketRows = rows;
    this.marketsBox.setAttribute('data-th-markets', String(rows.length));
    if (!rows.length) { this.marketsBox.setAttribute('data-th-empty', m ? 'no market data' : 'markets are not part of this lens'); }
  }

  paintChanges(c) {
    const e = this.el.bind(this), box = this.changesBox, k = c.counts || {};
    box.textContent = '';
    const head = this.answer.headline || {};
    if (c.since === 'visit' && !c.from) { box.appendChild(e('p', { cls: 'th-muted', text: 'This is your first visit: everything since this morning.' })); }
    box.appendChild(e('p', { attrs: { 'data-th-counts': '' } }, [
      e('b', { text: formatNumber(k.new || 0, { decimals: 0 }) }), ' new · ', e('b', { text: formatNumber(k.amended || 0, { decimals: 0 }) }), ' amended · ',
      e('b', { text: formatNumber(k.cancelled || 0, { decimals: 0 }) }), ' cancelled',
      c.from ? ' since ' + (c.since === 'visit' ? 'your visit at ' + String(c.from).replace('T', ' ').slice(0, 16) : c.from) : '']));
    if (c.untimed) { box.appendChild(e('p', { cls: 'th-muted', text: 'The trades gave no time of day: the day\'s events are shown.' })); }
    box.appendChild(e('p', {}, ['P&L today ', e('b', { text: money(c.pnl, true), cls: c.pnl > 0 ? 'th-up' : c.pnl < 0 ? 'th-down' : '' }),
      c.previousDate ? ' against ' + c.previousDate : '', ' · ',
      e('a', { text: 'Why it moved ›', attrs: { href: '#', 'data-explain': varRequest(head.scope), 'data-explain-tab': 'why', 'data-th-why': '' },
        on: { click: (ev) => ev.preventDefault() } })]));
    if ((c.movers || []).length) {
      box.appendChild(e('ul', { cls: 'th-movers', attrs: { 'aria-label': 'Biggest movers' } }, c.movers.map((m) => e('li', {}, [
        e('a', { text: m.trade, attrs: { href: '/go?q=' + encodeURIComponent(m.trade + ' <GO>') } }), ' ' + (m.book || '') + ' ',
        e('b', { text: money(m.change, true), cls: m.change > 0 ? 'th-up' : 'th-down' })]))));
    }
    if (c.available === false && c.reason) { box.appendChild(e('p', { cls: 'th-muted', text: 'Trades: ' + c.reason })); }
  }

  paintFeed(items, first) {
    const e = this.el.bind(this);
    const fresh = [];
    this.feedList.textContent = '';
    items.forEach((it) => {
      const isNew = !first && !this.seen.has(it.id);
      if (isNew) { fresh.push(it); }
      this.seen.add(it.id);
      const label = e(it.href ? 'a' : 'span', { text: it.text, attrs: { href: it.href || null } });
      this.feedList.appendChild(e('li', { cls: 'th-item th-' + it.kind + (it.state ? ' th-' + it.state : '') + (isNew ? ' th-new' : ''),
        attrs: { 'data-th-item': it.kind, 'data-th-id': it.id, 'data-th-new': isNew ? '' : null } },
      [e('time', { text: it.time || '' }), e('span', { cls: 'th-kind', text: it.kind }), label]));
    });
    if (!items.length) { this.feedList.appendChild(e('li', { cls: 'th-muted', text: 'Nothing yet today' })); }
    if (fresh.length) { announce(fresh.length === 1 ? 'New: ' + fresh[0].text : fresh.length + ' new items in the feed'); }
  }

  /** Stops listening and lets the grids go. */
  destroy() {
    clearTimeout(this.timer);
    if (this.off) { this.off(); this.off = null; }
    this.books.destroy();
    this.markets.destroy();
  }
}
