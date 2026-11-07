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
/* The inputs harness (drishti_harness.html): every drishti-inputs component with synthetic data and a stand-in server in the page, an
   editable grid following the filter bar (write-back, four eyes with ?fourEyes=1), and drishti-data's live client fed by stand-in
   EventSource and WebSocket classes the test drives, plus the Arrow client. The figures are invented. window.__harness is what the
   browser test drives. */
(function () {
  'use strict';
  const E = window.Drishti.elements, core = E.core, G = E.grid, I = E.inputs, D = E.data;
  const q = new URLSearchParams(location.search);
  const H = window.__harness = { events: [], asked: 0, treeAsked: 0, writes: [], proposals: [], submitted: [], live: {} };
  const log = document.getElementById('hx-log');
  const note = (name, detail) => { H.events.push({ name: name, detail: detail }); log.textContent = H.events.length + ' events · last ' + name; };
  if (q.get('theme') === 'light') { document.documentElement.setAttribute('data-theme', 'light'); }
  if (q.get('density')) { document.querySelector('.hx-main').setAttribute('data-density', q.get('density')); }
  document.addEventListener('drishti:change', (e) => note('change:' + (e.target.id || e.target.tagName.toLowerCase()), e.detail));
  let seed = 7;
  const rnd = () => { seed = (seed * 16807) % 2147483647; return seed / 2147483647; };
  const later = (v, ms) => new Promise((resolve) => setTimeout(() => resolve(v), ms === undefined ? 30 : ms));

  /* ---- pickers ---------------------------------------------------------------------------------------------------------------------- */
  H.range = new I.DateRangePicker(document.getElementById('hx-range'), { calendar: 'US', asOf: '2026-11-06', label: 'Period', value: { preset: 'mtd' } });
  const DESKS = { Rates: ['Govt Bonds', 'Swaps', 'Inflation'], Credit: ['IG Corp', 'HY Corp'], FX: ['G10 Spot', 'EM Forwards'], Equities: ['Cash EQ', 'Index Options'] };
  const TRADERS = ['A. Rao', 'M. Chen', 'J. Smith', 'L. Duval'];
  const tree = Object.keys(DESKS).map((d) => ({ id: d, label: d, children: DESKS[d].map((b) => ({ id: d + '/' + b, label: b, hasChildren: true })) }));
  H.tree = new I.TreePicker(document.getElementById('hx-tree'), { nodes: tree, label: 'Books', placeholder: 'Every book',
    loadChildren: (n) => { H.treeAsked++; if (n.id === 'FX/EM Forwards' && !H.treeFailedOnce) { H.treeFailedOnce = true; return later(null, 20).then(() => { throw new Error('the server is busy'); }); }
      return later(TRADERS.map((t) => ({ id: n.id + '/' + t, label: t, detail: Math.round(20 + rnd() * 80) + ' trades' })), 30); } });
  const CCYS = ['USD', 'EUR', 'GBP', 'JPY', 'CHF', 'CAD', 'AUD', 'NZD', 'SEK', 'NOK', 'DKK', 'PLN', 'CZK', 'HUF', 'TRY', 'ZAR', 'MXN', 'BRL', 'CLP', 'COP', 'PEN',
    'CNY', 'HKD', 'SGD', 'KRW', 'TWD', 'INR', 'IDR', 'MYR', 'THB', 'PHP', 'ILS', 'SAR', 'AED', 'QAR', 'KWD', 'EGP', 'NGN', 'KES', 'RUB'];
  H.ms = new I.MultiSelect(document.getElementById('hx-ms'), { options: CCYS.map((c, i) => ({ value: c, label: c, group: i < 9 ? 'G10' : 'Emerging' })), value: ['USD', 'EUR'], label: 'Currencies', max: 12 });
  const NAMES = ['Acme', 'Borealis', 'Cobalt', 'Delta', 'Everest', 'Fjord', 'Granite', 'Harbor', 'Ion', 'Juniper'];
  const KINDS = ['Bank', 'Capital', 'Partners', 'Holdings', 'Securities'];
  const CPTYS = [];
  NAMES.forEach((n) => KINDS.forEach((k) => [1, 2, 3, 4, 5, 6, 7, 8, 9, 10].forEach((i) => CPTYS.push({ value: 'C' + (CPTYS.length + 1), label: n + ' ' + k + (i > 1 ? ' ' + i : ''), detail: ['US', 'UK', 'DE', 'JP'][CPTYS.length % 4] }))));
  H.combo = new I.Combobox(document.getElementById('hx-combo'), { label: 'Counterparty', placeholder: 'Type a name', debounceMs: 120,
    source: (text, signal) => { H.asked++; H.lastQuery = text; return later(null, 40).then(() => { if (signal && signal.aborted) { const e = new Error('aborted'); e.name = 'AbortError'; throw e; } if (/^zz/i.test(text)) { throw new Error('the directory is down'); } return I.searchOptions(I.normalizeOptions(CPTYS), text).slice(0, 20); }); } });

  /* ---- the filter bar and an editable grid that follows it ------------------------------------------------------------------------- */
  const bus = H.bus = core.createBus();
  const books = [];
  Object.keys(DESKS).forEach((d) => DESKS[d].forEach((b) => books.push({ d: d, b: b })));
  const rows = [];
  for (let i = 0; i < 120; i++) {
    const bk = books[i % books.length];
    rows.push({ ID: 'T' + (1000 + i), DESK: bk.d, BOOK: bk.b, CCY: CCYS[i % 6], NOTIONAL: Math.round((1 + rnd() * 99)) * 100000, TRADE_DATE: '2026-10-' + String(1 + (i % 28)).padStart(2, '0'),
      ACTIVE: i % 5 !== 0, COMMENT: '', MTM: Math.round((rnd() - 0.45) * 2e6) });
  }
  H.rows = rows;
  const COLS = [
    { field: 'ID', header: 'Trade', width: 80, pinned: 'left' },
    { field: 'DESK', header: 'Desk', editable: true, options: Object.keys(DESKS), width: 100 },
    { field: 'BOOK', header: 'Book', editable: true, required: true, width: 120 },
    { field: 'CCY', header: 'Currency', editable: true, options: CCYS.slice(0, 6), width: 90 },
    { field: 'NOTIONAL', header: 'Notional', type: 'number', format: 'amount0', editable: true, required: true, min: 0, validate: (v) => (v > 5e9 ? 'Notional above 5bn needs a desk head: ask the desk' : null) },
    { field: 'TRADE_DATE', header: 'Trade date', type: 'date', editable: true, calendar: 'US', businessOnly: false, width: 130 },
    { field: 'ACTIVE', header: 'Active', type: 'boolean', editable: true, width: 70 },
    { field: 'COMMENT', header: 'Comment', editable: (r) => r.ACTIVE, maxLength: 40, width: 160 },
    { field: 'MTM', header: 'MTM', type: 'number', format: 'compact' }];
  const fourEyes = q.get('fourEyes') === '1';
  const gridEl = document.createElement('drishti-grid');
  gridEl.setAttribute('layout', 'wells');
  gridEl.setAttribute('density', 'compact');
  document.getElementById('hx-grid').appendChild(gridEl);
  gridEl.options = { columns: COLS, rows: rows.map((r) => Object.assign({}, r)), getRowId: (r) => r.ID, title: 'Trades (editable)', subtitle: 'synthetic', checkboxes: false,
    editing: { fourEyes: fourEyes, writeBack: (tx) => { H.writes.push(tx); return later(null, 15).then(() => {
      if (tx.edits.some((e) => /REFUSE/.test(String(e.value)))) { return { status: 'refused', message: 'The server refused: REFUSE is not a comment' }; }
      return fourEyes ? { status: 'proposed', proposalId: 'P-' + (100 + H.writes.length) } : { status: 'applied' };
    }); } } };
  H.grid = gridEl.grid;
  ['cellEditStarted', 'cellEditStopped', 'cellValueChanged', 'editTransaction', 'editUndone', 'editRedone', 'proposalResolved', 'filterChanged'].forEach((n) => {
    gridEl.addEventListener('drishti:' + n.replace(/[A-Z]/g, (c) => '-' + c.toLowerCase()), (e) => note(n, e.detail));
  });
  H.grid.followFilters(bus);
  H.fbar = new I.FilterBar(document.getElementById('hx-fbar'), { bus: bus, asOf: '2026-11-06', calendar: 'US', label: 'Filters',
    fields: [{ id: 'DESK', label: 'Desk', type: 'set', options: Object.keys(DESKS) }, { id: 'CCY', label: 'Currency', type: 'set', options: CCYS.slice(0, 6) },
      { id: 'NOTIONAL', label: 'Notional', type: 'number' }, { id: 'BOOK', label: 'Book', type: 'text' }, { id: 'TRADE_DATE', label: 'Trade date', type: 'date' }] });
  bus.on(core.FILTER_CHANNEL, (p) => note('bus:filters', p));

  /* ---- the form ------------------------------------------------------------------------------------------------------------------- */
  const schema = { type: 'object', title: 'Amend a trade', description: 'Fields marked * are required. Changes go to a second person.', required: ['book', 'side', 'notional', 'tradeDate', 'maturity', 'product'],
    order: ['book', 'side', 'product', 'notional', 'currency', 'tradeDate', 'maturity', 'strike', 'optionType', 'legs', 'desks', 'counterparty', 'comment', 'rule'],
    properties: {
      book: { type: 'string', title: 'Book code', pattern: '[A-Z]{2}-\\d{3}', patternMessage: 'a book code such as RT-123' },
      side: { type: 'string', enum: ['BUY', 'SELL'], enumLabels: ['Buy', 'Sell'] },
      product: { type: 'string', enum: ['SWAP', 'OPTION', 'BOND', 'FX_FORWARD'], enumLabels: ['Swap', 'Option', 'Bond', 'FX forward'] },
      notional: { type: 'number', minimum: 1000, maximum: 5e9, description: 'Write 25m, 1,250,000 or 300k' },
      currency: { type: 'string', enum: CCYS.slice(0, 6), default: 'USD' },
      tradeDate: { type: 'date', title: 'Trade date', options: { calendar: 'US', businessOnly: true, asOf: '2026-11-06' } },
      maturity: { type: 'date', after: 'tradeDate', options: { calendar: 'US', asOf: '2026-11-06' } },
      strike: { type: 'number', minimum: 0, when: { field: 'product', equals: 'OPTION' } },
      optionType: { type: 'string', title: 'Call or put', enum: ['CALL', 'PUT'], enumLabels: ['Call', 'Put'], when: { field: 'product', equals: 'OPTION' } },
      legs: { type: 'array', title: 'Legs', minItems: 1, maxItems: 3, when: { field: 'product', equals: 'SWAP' },
        items: { type: 'object', title: 'Leg', required: ['pay'], properties: { pay: { type: 'string', enum: ['FIXED', 'FLOAT'], enumLabels: ['Fixed', 'Float'] }, rate: { type: 'number', title: 'Rate (%)', minimum: -5, maximum: 50, when: { field: 'pay', equals: 'FIXED' } } } } },
      desks: { type: 'array', title: 'Desks told', items: { type: 'string', enum: Object.keys(DESKS) }, maxItems: 2 },
      counterparty: { type: 'string', widget: 'combobox', options: { source: CPTYS.slice(0, 60) } },
      comment: { type: 'string', format: 'textarea', maxLength: 200 },
      rule: { type: 'string', title: 'Limit rule', format: 'code', options: { mode: 'rachana', height: 70 } }
    } };
  H.form = new I.Form(document.getElementById('hx-form'), { schema: schema, value: { book: 'RT-101', side: 'BUY', product: 'SWAP', notional: 25000000, currency: 'USD', tradeDate: '2026-11-02', maturity: '2031-11-03', legs: [{ pay: 'FIXED', rate: 3.25 }] },
    fourEyes: { approvers: ['M. Chen', 'J. Smith', 'L. Duval'].map((n) => ({ value: n, label: n })), submit: (p) => { H.proposals.push(p); return later({ id: 'P-' + (500 + H.proposals.length), status: 'proposed' }, 20); } },
    check: (v) => later(v.book === 'RT-999' ? { book: 'Book RT-999 is closed (the server says)' } : {}, 10) });
  ['proposed', 'invalid', 'submitted'].forEach((n) => H.form.on(n, (d) => note('form:' + n, d)));

  /* ---- code ------------------------------------------------------------------------------------------------------------------------- */
  const CATALOGUE = [{ name: 'sum', signature: 'sum(measure)', description: 'adds the members' }, { name: 'avg', signature: 'avg(measure)' }, { name: 'var', signature: 'var(confidence, horizon)', description: 'value at risk, not additive' },
    { name: 'es', signature: 'es(confidence, horizon)', description: 'expected shortfall' }, { name: 'round', signature: 'round(x, digits)' }, { name: 'abs', signature: 'abs(x)' },
    { name: 'coalesce', signature: 'coalesce(a, b, ...)' }, { name: 'if', signature: 'if(test, then, else)' }, { name: 'stressed_var', signature: 'stressed_var(confidence, horizon)' },
    { name: 'NOTIONAL', kind: 'field' }, { name: 'MTM', kind: 'field' }, { name: 'DESK', kind: 'field' }];
  const lint = (text) => {
    const out = [];
    text.split('\n').forEach((line, i) => {
      let depth = 0;
      for (const ch of line) { if (ch === '(') { depth++; } if (ch === ')') { depth--; } }
      if (depth !== 0) { out.push({ line: i + 1, severity: 'error', message: depth > 0 ? 'a "(" is not closed' : 'a ")" has no "("' }); }
      const re = /([A-Za-z_]\w*)\s*\(/g;
      let m;
      while ((m = re.exec(line))) { if (!CATALOGUE.some((c) => c.name === m[1])) { out.push({ line: i + 1, from: m.index, to: m.index + m[1].length, severity: 'error', message: 'no function ' + m[1] }); } }
      if (/\bMTM\b/.test(line) && /\bvar\(/.test(line)) { out.push({ line: i + 1, severity: 'warning', message: 'MTM and VaR in one rule: check the units' }); }
    });
    return later(out, 5);
  };
  H.code = new I.CodeEditor(document.getElementById('hx-code'), { mode: 'rachana', label: 'Limit rule', height: 120, catalogue: CATALOGUE, lint: lint, lintDelay: 150,
    value: 'if(var(99, 1d) > 1.5m, "breach", "ok")\nround(sum(NOTIONAL) / 1e6, 2)' });
  H.yaml = new I.CodeEditor(document.getElementById('hx-yaml'), { mode: 'yaml', readOnly: true, height: 110, label: 'Sutra (read-only)',
    value: 'kind: view\ntitle: Desk risk\npanels:\n  - grid: $positions\n    filter: "@desk == \'Rates\'"\n    keys: [F2, F7]\n' });
  H.py = new I.CodeEditor(document.getElementById('hx-py'), { mode: 'python', height: 90, label: 'Python', value: 'def var(pnl, q=0.99):\n    """historical VaR"""\n    return -sorted(pnl)[int(len(pnl) * (1 - q))]\n' });
  H.diff = new I.CodeDiff(document.getElementById('hx-diff'), { mode: 'yaml', labels: ['Published', 'Draft'],
    original: 'kind: view\ntitle: Desk risk\npanels:\n  - grid: $positions\n  - chart: $pnl\n',
    value: 'kind: view\ntitle: Desk risk and limits\npanels:\n  - grid: $positions\n  - chart: $pnl\n  - grid: $limits\n' });

  /* ---- live: stand-in EventSource and WebSocket the test drives ------------------------------------------------------------------- */
  class FakeES extends EventTarget {
    constructor(url) { super(); this.url = url; this.readyState = 0; H.live.es = this; H.live.urls = (H.live.urls || []).concat([url]); setTimeout(() => { if (this.readyState !== 2) { this.readyState = 1; this.dispatchEvent(new Event('open')); } }, 5); }
    close() { this.readyState = 2; }
    push(data, id) { const e = new MessageEvent('message', { data: JSON.stringify(data), lastEventId: id === undefined ? '' : String(id) }); this.dispatchEvent(e); }
    drop() { this.readyState = 2; this.dispatchEvent(new Event('error')); }
  }
  class FakeWS extends EventTarget {
    constructor(url) { super(); this.url = url; this.sent = []; H.live.ws = this; setTimeout(() => this.dispatchEvent(new Event('open')), 5); }
    send(t) { this.sent.push(t); }
    close() { this.closed = true; }
    push(data) { this.dispatchEvent(new MessageEvent('message', { data: JSON.stringify(data) })); }
    drop(code) { this.dispatchEvent(Object.assign(new Event('close'), { code: code || 1006 })); }
  }
  H.FakeWS = FakeWS;
  const liveRows = rows.slice(0, 30).map((r) => ({ ID: r.ID, DESK: r.DESK, CCY: r.CCY, MTM: r.MTM }));
  const liveEl = document.createElement('drishti-grid');
  liveEl.setAttribute('density', 'terminal');
  document.getElementById('hx-live-grid').appendChild(liveEl);
  liveEl.options = { columns: [{ field: 'ID', header: 'Trade', width: 80 }, { field: 'DESK', header: 'Desk' }, { field: 'CCY', header: 'Currency' }, { field: 'MTM', header: 'MTM', type: 'number', format: 'amount0' }],
    rows: liveRows, getRowId: (r) => r.ID, title: 'Live (stand-in feed)', checkboxes: false, live: { asyncWaitMs: 20, flashMs: 600 } };
  H.liveGrid = liveEl.grid;
  H.client = new D.LiveClient({ url: '/hx/live/trades', EventSource: FakeES, heartbeatMs: Number(q.get('heartbeat') || 400), backoff: { baseMs: 40, maxMs: 200, jitter: false } });
  const state = document.getElementById('hx-live-state'), stats = document.getElementById('hx-live-stats');
  H.client.on('state', (s) => { state.textContent = s.state + (s.waitMs ? ' (again in ' + s.waitMs + ' ms)' : ''); H.live.states = (H.live.states || []).concat([s.state]); });
  H.pipe = D.pipeToGrid(H.client, H.liveGrid, { getRowId: (r) => r.ID, onStats: (s) => { H.live.stats = s; stats.textContent = s.received + ' received · ' + s.applied + ' applied · ' + s.coalesced + ' folded · ' + s.batches + ' batches'; } });

  /* ---- Arrow: the fixture the test serves (Flight over HTTP when the stand-in capabilities say so) -------------------------------- */
  H.readArrow = (flight) => {
    const client = new D.ArrowClient({ capabilitiesUrl: '/hx-fixture/capabilities', flight: flight ? undefined : false });
    const out = document.getElementById('hx-arrow-state');
    return client.read({ url: '/hx-fixture/trades.arrow', ticket: { kind: 'trades', paths: ['ID', 'DESK', 'MTM'] }, onBatch: (n, b) => { H.batches = b; } }).then((fr) => {
      const g = fr.toGrid({ id: 'ID', formats: { MTM: 'amount0' } });
      const el = document.createElement('drishti-grid');
      el.setAttribute('density', 'terminal');
      const box = document.getElementById('hx-arrow-grid');
      box.textContent = '';
      box.appendChild(el);
      el.options = { columns: g.columns, rows: g.rows, getRowId: g.getRowId, title: 'Arrow (' + fr.via + ')', checkboxes: false };
      H.arrowGrid = el.grid;
      out.textContent = 'Arrow: ' + fr.length + ' rows via ' + fr.via + ', ' + fr.names.length + ' columns';
      return { via: fr.via, rows: fr.length, names: fr.names, types: fr.fields().map((f) => f.type), first: fr.rows()[0].toObject(), zeroCopy: fr.column('MTM') instanceof Float64Array };
    }, (e) => { out.textContent = 'Arrow: ' + e.message; throw e; });
  };

  document.getElementById('hx-theme').addEventListener('click', () => {
    core.setTheme(document.documentElement.getAttribute('data-theme') === 'light' ? 'terminal' : 'light', { persist: false });
  });
  H.ready = true;
}());
