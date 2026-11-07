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
/* The gallery's demos of drishti-inputs (every input: the date and range pickers, the tree picker, the multi-select, the combobox, the
   form from a schema with four eyes, the filter bar with a grid following it, the code editor and the diff) and of drishti-data (a
   memory source paged into a grid, the live client fed by hand through a stand-in EventSource, a JSON frame as grid columns). The gallery
   shows a demo only when its library is in the manifest, so these appear with the libraries (drishti-inputs and drishti-data 0.1.0). */
(function (root) {
  'use strict';
  const NS = (root.Drishti = root.Drishti || {});
  const GAL = (NS.gallery = NS.gallery || {});
  const demos = (GAL.demos = GAL.demos || []);
  const INPUT_EVENTS = ['change', 'open', 'close', 'search', 'invalid', 'proposed', 'submitted', 'lint', 'loadFailed', 'reset', 'completed'];
  const later = (v, ms) => new Promise((resolve) => setTimeout(() => resolve(v), ms === undefined ? 30 : ms));

  function typed(values, names) {
    const o = {};
    names.forEach((n) => { if (values[n] !== undefined && values[n] !== '') { o[n] = values[n]; } });
    return o;
  }

  /** An input element mounted: its options set as a page sets them, its value and events. */
  function mountInput(host, b) {
    const el = host.ownerDocument.createElement(b.tag);
    host.appendChild(el);
    el.options = b.options;
    const c = el.component;
    return { target: el, element: el, component: c, ready: c && c.ready ? Promise.resolve(c.ready).catch(() => {}) : Promise.resolve(), destroy: () => { if (el.component && el.component.destroy) { el.component.destroy(); } el.remove(); } };
  }

  const DESKS = { Rates: ['Govt Bonds', 'Swaps', 'Inflation'], Credit: ['IG Corp', 'HY Corp'], FX: ['G10 Spot', 'EM Forwards'], Equities: ['Cash EQ', 'Index Options'] };
  const TRADERS = ['A. Rao', 'M. Chen', 'J. Smith', 'L. Duval'];
  const CCYS = ['USD', 'EUR', 'GBP', 'JPY', 'CHF', 'CAD', 'AUD', 'NZD', 'SEK', 'NOK', 'DKK', 'PLN', 'CZK', 'HUF', 'TRY', 'ZAR', 'MXN', 'BRL', 'CNY', 'HKD', 'SGD', 'KRW', 'INR', 'IDR'];
  const CPTYS = [];
  ['Acme', 'Borealis', 'Cobalt', 'Delta', 'Everest', 'Fjord', 'Granite', 'Harbor'].forEach((n) => ['Bank', 'Capital', 'Partners', 'Holdings'].forEach((k) => CPTYS.push({ value: 'C' + (CPTYS.length + 1), label: n + ' ' + k, detail: ['US', 'UK', 'DE', 'JP'][CPTYS.length % 4] })));

  function inputDemo(spec) {
    return Object.assign({
      library: 'drishti-inputs', kind: 'element', ag: false, keys: [], domEvents: INPUT_EVENTS, sutra: () => null,
      mount: function (host, values, ctx) { return mountInput(host, this.build(values, ctx)); }
    }, spec);
  }
  function build(tag, ctor, options, names) {
    return { tag: tag, ctor: ctor, library: 'drishti-inputs', attrs: {}, options: options, names: names || new Map(), given: Array.from((names || new Map()).values()) };
  }

  demos.push(inputDemo({
    id: 'date-picker', title: 'Date picker', about: 'drishti_date-picker.js', keys: ['drishti_date-picker.js'], types: ['DatePickerOptions'],
    summary: 'A business date by a holiday calendar (US, UK, TARGET, CA, combined): T, T-1 and the previous month-end as shortcuts, typed dates read, weekends and holidays refused.',
    values: { calendar: 'US', asOf: '2026-11-06', value: '2026-11-05', label: 'Business date' },
    examples: [{ title: 'US calendar', values: {} }, { title: 'TARGET, any day', values: { calendar: 'TARGET', businessOnly: false } }],
    build: (values) => build('drishti-date', 'DatePicker', typed(values, ['value', 'calendar', 'asOf', 'businessOnly', 'min', 'max', 'format', 'weekStart', 'shortcuts', 'required', 'label', 'placeholder']))
  }));

  demos.push(inputDemo({
    id: 'date-range', title: 'Date range', about: 'drishti_date-range.js', types: ['DateRangeOptions'],
    summary: 'A range with presets (month to date, last quarter, year to date …) counted from the business date, ends on business days when asked, a longest range.',
    values: { calendar: 'US', asOf: '2026-11-06', label: 'Period' },
    examples: [{ title: 'Month to date', values: {} }],
    build: (values) => build('drishti-date-range', 'DateRangePicker', Object.assign({ value: { preset: 'mtd' } }, typed(values, ['calendar', 'asOf', 'businessOnly', 'min', 'max', 'maxDays', 'weekStart', 'label'])))
  }));

  demos.push(inputDemo({
    id: 'tree-picker', title: 'Tree picker', about: 'drishti_tree-picker.js', types: ['TreePickerOptions'],
    summary: 'A hierarchy (desks, books, traders): search, partial selection, a node standing for everything under it, children asked of a server the first time a node opens.',
    values: { label: 'Books', inline: true },
    examples: [{ title: 'Desks, books and traders (traders from a stand-in server)', values: {} }],
    build: function (values, ctx) {
      const nodes = ctx.memo('tree', () => Object.keys(DESKS).map((d) => ({ id: d, label: d, children: DESKS[d].map((b) => ({ id: d + '/' + b, label: b, hasChildren: true })) })));
      const loadChildren = ctx.memo('tree-load', () => (n) => later(TRADERS.map((t) => ({ id: n.id + '/' + t, label: t })), 120));
      return build('drishti-tree-picker', 'TreePicker', Object.assign({ nodes: nodes, loadChildren: loadChildren }, typed(values, ['multiple', 'inline', 'label', 'placeholder'])), new Map([[nodes, 'nodes'], [loadChildren, 'loadChildren']]));
    }
  }));

  demos.push(inputDemo({
    id: 'multiselect', title: 'Multi-select', about: 'drishti_multiselect.js', types: ['MultiSelectOptions'],
    summary: 'Many values with search, groups, chips with "+N", Select all and Clear, a most-picked limit; keyboard first.',
    values: { label: 'Currencies', maxChips: 3 },
    examples: [{ title: 'Currencies, G10 and emerging', values: {} }, { title: 'At most three', values: { max: 3 } }],
    build: function (values, ctx) {
      const options = ctx.memo('ccys', () => CCYS.map((c, i) => ({ value: c, label: c, group: i < 9 ? 'G10' : 'Emerging' })));
      return build('drishti-multiselect', 'MultiSelect', Object.assign({ options: options, value: ['USD', 'EUR'] }, typed(values, ['maxChips', 'max', 'selectAll', 'label', 'placeholder'])), new Map([[options, 'options']]));
    }
  }));

  demos.push(inputDemo({
    id: 'combobox', title: 'Combobox', about: 'drishti_combobox.js', types: ['ComboboxOptions'],
    summary: 'Type-ahead over a server search (debounced, cancelled when the text changes), or a list; free text when allowed.',
    values: { label: 'Counterparty', placeholder: 'Type a name', debounceMs: 120 },
    examples: [{ title: 'Counterparties from a stand-in search', values: {} }],
    build: function (values, ctx) {
      const I = ctx.E.inputs;
      const source = ctx.memo('combo', () => (text, signal) => later(null, 60).then(() => { if (signal && signal.aborted) { const e = new Error('aborted'); e.name = 'AbortError'; throw e; } return I.searchOptions(I.normalizeOptions(CPTYS), text).slice(0, 20); }));
      return build('drishti-combobox', 'Combobox', Object.assign({ source: source }, typed(values, ['debounceMs', 'minChars', 'allowFree', 'label', 'placeholder'])), new Map([[source, 'search']]));
    }
  }));

  demos.push(inputDemo({
    id: 'select', title: 'Select', about: 'drishti_select.js', types: ['SelectOptions'],
    summary: 'One value from a list (the ARIA select-only combobox): arrows, Home and End, Page keys, type-ahead, groups, disabled options; a search on top of a long list.',
    values: { label: 'Reporting currency', search: 'auto' },
    examples: [{ title: 'Currencies, G10 and emerging (searchable)', values: {} }, { title: 'Horizon, three choices', values: { label: 'Horizon', short: true } },
      { title: 'Nothing picked yet', values: { placeholder: 'Pick a currency' } }],
    build: function (values, ctx) {
      const short = values.short;
      const options = ctx.memo(short ? 'horizons' : 'ccys-one', () => (short ? [{ value: '1d', label: '1-day' }, { value: '10d:sqrt10', label: '10-day (√10)' }, { value: '10d:sum', label: '10-day (sum)' }]
        : CCYS.map((c, i) => ({ value: c, label: c, group: i < 9 ? 'G10' : 'Emerging', disabled: c === 'RUB', detail: c === 'RUB' ? 'no FX on the date' : '' }))));
      const v = values.placeholder ? {} : { value: short ? '1d' : 'USD' };
      return build('drishti-select', 'Select', Object.assign({ options: options }, v, typed(values, ['label', 'placeholder', 'search', 'searchMin', 'disabled'])), new Map([[options, 'options']]));
    }
  }));

  demos.push(inputDemo({
    id: 'form', title: 'Form', about: 'drishti_form.js', types: ['FormOptions'],
    summary: 'A form from a schema: validation in words, conditional fields, arrays, dates by a calendar, the server\'s checks, a four-eyes submit to a second person.',
    values: { submitLabel: 'Propose', layout: 'columns' },
    examples: [{ title: 'Amend a trade (four eyes)', values: {} }, { title: 'Read-only', values: { readOnly: true } }],
    build: function (values, ctx) {
      const schema = ctx.memo('schema', () => ({ type: 'object', title: 'Amend a trade', required: ['book', 'side', 'notional', 'tradeDate'], order: ['book', 'side', 'product', 'notional', 'currency', 'tradeDate', 'strike', 'comment'],
        properties: { book: { type: 'string', title: 'Book code', pattern: '[A-Z]{2}-\\d{3}', patternMessage: 'a book code such as RT-123' },
          side: { type: 'string', enum: ['BUY', 'SELL'], enumLabels: ['Buy', 'Sell'] }, product: { type: 'string', enum: ['SWAP', 'OPTION', 'BOND'], enumLabels: ['Swap', 'Option', 'Bond'] },
          notional: { type: 'number', minimum: 1000, maximum: 5e9, description: 'Write 25m, 1,250,000 or 300k' }, currency: { type: 'string', enum: CCYS.slice(0, 6), default: 'USD' },
          tradeDate: { type: 'date', title: 'Trade date', options: { calendar: 'US', businessOnly: true, asOf: '2026-11-06' } },
          strike: { type: 'number', minimum: 0, when: { field: 'product', equals: 'OPTION' } }, comment: { type: 'string', format: 'textarea', maxLength: 200 } } }));
      const fourEyes = ctx.memo('foureyes', () => ({ approvers: ['M. Chen', 'J. Smith'].map((n) => ({ value: n, label: n })), submit: (p) => { ctx.log('proposal', p); return later({ id: 'P-501', status: 'proposed' }, 40); } }));
      const value = ctx.memo('form-value', () => ({ book: 'RT-101', side: 'BUY', product: 'SWAP', notional: 25000000, currency: 'USD', tradeDate: '2026-11-02' }));
      return build('drishti-form', 'Form', Object.assign({ schema: schema, value: value, fourEyes: fourEyes }, typed(values, ['submitLabel', 'readOnly', 'layout', 'locale'])), new Map([[schema, 'schema'], [fourEyes, 'fourEyes'], [value, 'value']]));
    }
  }));

  demos.push(inputDemo({
    id: 'filter-bar', title: 'Filter bar', about: 'drishti_filter-bar.js', types: ['FilterBarOptions'],
    summary: 'Filters as chips (sets, numbers, text, dates with presets) and a quick search; its filter model goes out on a bus, and a grid follows it.',
    values: { label: 'Filters', asOf: '2026-11-06', calendar: 'US' },
    examples: [{ title: 'A grid following the bar', values: {} }],
    build: function (values, ctx) {
      const fields = ctx.memo('fbar-fields', () => [{ id: 'DESK', label: 'Desk', type: 'set', options: Object.keys(DESKS) }, { id: 'CCY', label: 'Currency', type: 'set', options: CCYS.slice(0, 6) },
        { id: 'MTM', label: 'MTM', type: 'number' }, { id: 'BOOK', label: 'Book', type: 'text' }]);
      const bus = ctx.memo('fbar-bus', () => ctx.E.core.createBus());
      return build('drishti-filter-bar', 'FilterBar', Object.assign({ fields: fields, bus: bus }, typed(values, ['quick', 'asOf', 'calendar', 'label', 'channel'])), new Map([[fields, 'fields'], [bus, 'bus']]));
    },
    mount: function (host, values, ctx) {
      const b = this.build(values, ctx), m = mountInput(host, b);
      const g = host.ownerDocument.createElement('drishti-grid');
      g.setAttribute('density', 'compact');
      host.appendChild(g);
      g.options = { columns: ctx.data.COLUMNS, rows: ctx.memo('trades:231', () => ctx.data.trades(231)), getRowId: ctx.getRowId, title: 'Trades, following the filters', checkboxes: false };
      const off = g.grid.followFilters ? g.grid.followFilters(b.options.bus) : null;
      const destroy = m.destroy;
      m.destroy = () => { if (typeof off === 'function') { off(); } g.grid.destroy(); g.remove(); destroy(); };
      return m;
    },
    code: function (values, ctx) { const c = ctx.code.elementCode(this.build(values, ctx)); c.js += '\n\ngrid.followFilters(bus);   // a drishti-grid on the same bus follows the bar'; return c; }
  }));

  demos.push(inputDemo({
    id: 'code-editor', title: 'Code editor', about: 'drishti_code-editor.js', types: ['CodeEditorOptions'],
    summary: 'The vendored CodeMirror with Rachana, YAML and Python modes, completion from the function catalogue (Ctrl+Space), lint in words, read-only.',
    values: { mode: 'rachana', height: 140, label: 'Limit rule' },
    examples: [{ title: 'A Rachana rule with lint', values: {} }, { title: 'YAML, read-only', values: { mode: 'yaml', readOnly: true } }],
    build: function (values, ctx) {
      const catalogue = ctx.memo('code-cat', () => [{ name: 'sum', signature: 'sum(measure)', description: 'adds the members' }, { name: 'var', signature: 'var(confidence, horizon)', description: 'value at risk, not additive' },
        { name: 'es', signature: 'es(confidence, horizon)' }, { name: 'round', signature: 'round(x, digits)' }, { name: 'if', signature: 'if(test, then, else)' }]);
      const lint = ctx.memo('code-lint', () => (text) => later(text.split('\n').map((line, i) => { let depth = 0; for (const ch of line) { if (ch === '(') { depth++; } if (ch === ')') { depth--; } } return depth ? { line: i + 1, severity: 'error', message: depth > 0 ? 'a "(" is not closed' : 'a ")" has no "("' } : null; }).filter(Boolean), 5));
      const value = values.mode === 'yaml' ? 'kind: view\ntitle: Desk risk\npanels:\n  - grid: $positions\n' : values.mode === 'python' ? 'def var(pnl, q=0.99):\n    return -sorted(pnl)[int(len(pnl) * (1 - q))]\n' : 'if(var(99, 1d) > 1.5m, "breach", "ok")\nround(sum(NOTIONAL) / 1e6, 2)';
      return build('drishti-code', 'CodeEditor', Object.assign({ value: value, catalogue: catalogue, lint: lint }, typed(values, ['mode', 'readOnly', 'lineNumbers', 'height', 'lintDelay', 'label'])), new Map([[catalogue, 'catalogue'], [lint, 'lint']]));
    }
  }));

  demos.push(inputDemo({
    id: 'code-diff', title: 'Code diff', about: 'drishti_code-editor.js', types: [],
    summary: 'Two versions side by side, lines added and removed marked, the counts in words (a Sutra\'s published and draft versions).',
    values: {}, examples: [{ title: 'Published against draft', values: {} }],
    build: () => build('drishti-code-diff', 'CodeDiff', { mode: 'yaml', labels: ['Published', 'Draft'], original: 'kind: view\ntitle: Desk risk\npanels:\n  - grid: $positions\n  - chart: $pnl\n',
      value: 'kind: view\ntitle: Desk risk and limits\npanels:\n  - grid: $positions\n  - chart: $pnl\n  - grid: $limits\n' })
  }));

  /* ---------------- drishti-insight ---------------- */
  demos.push({
    id: 'workbook-export', title: 'Workbook export', about: 'drishti_insight-workbook.js', library: 'drishti-insight', kind: 'function', ag: false,
    keys: [], events: [], sutra: () => null, types: [],
    summary: 'A number\'s live-formula workbook, found through the one Explain\'s Workbook part and downloaded; the server writes it. Here the answer is a stand-in (the gallery has no server): Export logs the link it would open.',
    values: { scope: 'BANK' }, examples: [{ title: 'VaR of the bank', values: {} }, { title: 'A desk', values: { scope: 'DESK:RATES' } }],
    extra: [{ name: 'scope', control: { kind: 'select', values: ['BANK', 'DESK:RATES', 'DESK:FX'] }, desc: 'The number\'s scope.' }],
    mount: function (host, values, ctx) {
      const doc = host.ownerDocument, I = ctx.E.insight, h = ctx.h;
      const answer = { workbook: { links: [{ label: 'VaR workbook (' + values.scope + ')', href: '/api/var/workbook?scope=' + encodeURIComponent(values.scope), note: 'live formulas, a Provenance sheet' }] } };
      const win = { location: { assign: (u) => ctx.log('opened', { href: u }) }, Drishti: { Explain: { ask: () => Promise.resolve(answer) } } };
      const rows = I.workbookLinks(answer).map((l) => h(doc, 'li', { text: l.label + ' · ' + l.href + (l.note ? ' · ' + l.note : '') }));
      const go = () => I.exportWorkbook({ kind: 'var', measure: 'var', scope: values.scope }, { win: win }).then((l) => ctx.log('exported', { label: l.label }));
      const el = h(doc, 'div', { cls: 'gx-fn' }, [h(doc, 'ul', {}, rows),
        h(doc, 'div', { cls: 'gx-demo-bar', attrs: { role: 'toolbar', 'aria-label': 'Workbook' } }, [h(doc, 'button', { cls: 'gx-btn', text: 'Export', attrs: { type: 'button', 'data-action': 'export' }, on: { click: go } })])]);
      host.appendChild(el);
      return { target: el, element: el, ready: Promise.resolve(), destroy: () => el.remove() };
    },
    code: (values) => ({ js: "import { exportWorkbook } from '/static/elements/drishti-insight/src/drishti_index.js';\n\n// on a console page (the one Explain installed): the number's workbook, downloaded\nexportWorkbook({ kind: 'var', measure: 'var', scope: '" + values.scope + "' });",
      element: '<script src="/static/elements/drishti-core/drishti_core.js"></script>\n<script src="/static/elements/drishti-insight/drishti_insight.js"></script>\n<script>\n  Drishti.elements.insight.exportWorkbook({ kind: \'var\', measure: \'var\', scope: \'' + values.scope + '\' });\n</script>' })
  });

  /* ---------------- drishti-data ---------------- */
  function dataDemo(spec) { return Object.assign({ library: 'drishti-data', kind: 'function', ag: false, keys: [], events: [], sutra: () => null }, spec); }

  demos.push(dataDemo({
    id: 'data-source', title: 'Data sources', about: 'drishti_sources.js', types: [],
    summary: 'One data-source contract the grid and the chart share: rows in memory, REST pages, server-side rows, Arrow and live; here a memory source paged into a grid as a server would.',
    extra: [{ name: 'delay', control: { kind: 'select', values: ['30', '300'] }, desc: 'The source\'s answer time (ms), as a server\'s.' }],
    values: { delay: '30' }, examples: [{ title: '20,000 rows from a memory source', values: {} }],
    mount: function (host, values, ctx) {
      const D = ctx.E.data, rows = ctx.memo('trades:20000', () => ctx.data.trades(20000));
      const src = D.memorySource(rows, { delay: +values.delay });
      const g = host.ownerDocument.createElement('drishti-grid');
      host.appendChild(g);
      g.options = { columns: ctx.data.COLUMNS, datasource: src, getRowId: ctx.getRowId, title: 'Trades (memorySource)', blockSize: 100 };
      return { target: g, element: g, ready: Promise.resolve(), destroy: () => { g.grid.destroy(); g.remove(); } };
    },
    code: (values) => ({ js: "import { memorySource } from '/static/elements/drishti-data/src/drishti_index.js';\nimport { Grid } from '/static/elements/drishti-grid/src/drishti_index.js';\n\nconst datasource = memorySource(rows, { delay: " + values.delay + " });   // or restSource(url), serverSideSource(url), arrowSource(url)\nnew Grid(document.getElementById('grid'), { columns, datasource, getRowId, blockSize: 100 });" })
  }));

  demos.push(dataDemo({
    id: 'live-client', title: 'Live client', about: 'drishti_live-client.js', types: ['LiveClientOptions'],
    summary: 'One live connection over Server-Sent Events or a WebSocket: reconnect with back-off, a heartbeat, resume from the last event id, backpressure into the grid. Here a manual feed: Push sends a message, Drop cuts the line.',
    values: { heartbeatMs: 0 }, examples: [{ title: 'A manual feed into a grid', values: {} }],
    mount: function (host, values, ctx) {
      const doc = host.ownerDocument, D = ctx.E.data, h = ctx.h;
      let es = null, n = 0;
      class ManualEventSource extends root.EventTarget {
        constructor(url) { super(); this.url = url; this.readyState = 0; es = this; setTimeout(() => { if (this.readyState !== 2) { this.readyState = 1; this.dispatchEvent(new root.Event('open')); } }, 5); }
        close() { this.readyState = 2; }
      }
      const rows = ctx.memo('live-rows', () => ctx.data.trades(30).map((r) => ({ ID: r.ID, DESK: r.DESK, CCY: r.CCY, MTM: r.MTM })));
      const state = h(doc, 'span', { cls: 'gx-note', attrs: { 'aria-live': 'polite' } });
      const push = () => { if (!es || es.readyState !== 1) { ctx.log('note', 'the line is down: the client is reconnecting'); return; } const r = rows[(n++) % rows.length]; const up = { type: 'transaction', update: [Object.assign({}, r, { MTM: Math.round(r.MTM + (Math.random() - 0.5) * 4e5) })] };
        es.dispatchEvent(new root.MessageEvent('message', { data: JSON.stringify(up), lastEventId: String(n) })); ctx.log('push', { id: n, row: r.ID }); };
      const bar = h(doc, 'div', { cls: 'gx-demo-bar', attrs: { role: 'toolbar', 'aria-label': 'Feed' } }, [h(doc, 'button', { cls: 'gx-btn', text: 'Push', attrs: { type: 'button', 'data-action': 'push' }, on: { click: push } }),
        h(doc, 'button', { cls: 'gx-btn', text: 'Drop the line', attrs: { type: 'button', 'data-action': 'drop' }, on: { click: () => { if (es) { es.readyState = 2; es.dispatchEvent(new root.Event('error')); } } } }), state]);
      host.appendChild(bar);
      const g = doc.createElement('drishti-grid');
      g.setAttribute('density', 'terminal');
      host.appendChild(g);
      g.options = { columns: [{ field: 'ID', header: 'Trade', width: 80 }, { field: 'DESK', header: 'Desk' }, { field: 'CCY', header: 'Currency' }, { field: 'MTM', header: 'MTM', type: 'number', format: 'amount0' }],
        rows: rows.map((r) => Object.assign({}, r)), getRowId: (r) => r.ID, title: 'Live (a manual feed)', checkboxes: false, live: { asyncWaitMs: 20, flashMs: 600 } };
      const client = new D.LiveClient(Object.assign({ url: '/gallery/live/trades', EventSource: ManualEventSource, backoff: { baseMs: 200, maxMs: 1000, jitter: false } }, typed(values, ['heartbeatMs', 'resumeParam'])));
      client.on('state', (s) => { state.textContent = 'state: ' + s.state + (s.waitMs ? ' (again in ' + s.waitMs + ' ms)' : ''); ctx.log('state', s.state); });
      const pipe = D.pipeToGrid(client, g.grid, { getRowId: (r) => r.ID });
      if (client.start) { client.start(); }
      return { target: host, element: g, push: push, ready: Promise.resolve(), destroy: () => { pipe.stop(); if (client.stop) { client.stop(); } g.grid.destroy(); g.remove(); bar.remove(); } };
    },
    code: () => ({ js: "import { LiveClient, pipeToGrid } from '/static/elements/drishti-data/src/drishti_index.js';\n\nconst client = new LiveClient({ url: '/api/v1/live/trades' });   // SSE; ws:// or wss:// for a WebSocket\nclient.on('state', (s) => console.log(s.state));\npipeToGrid(client, grid, { getRowId: (r) => r.ID });" })
  }));

  demos.push(dataDemo({
    id: 'frames', title: 'Frames (Arrow and JSON)', about: 'drishti_arrow.js', types: [],
    summary: 'A frame: typed columns (numbers as Float64Array), rows as views over them, from Arrow (Flight over HTTP where offered) or JSON; toGrid() gives a grid its columns and rows.',
    values: {}, examples: [{ title: 'A JSON answer as a frame and a grid', values: {} }],
    mount: function (host, values, ctx) {
      const D = ctx.E.data, doc = host.ownerDocument;
      const json = { columns: ['ID', 'DESK', 'MTM', 'TRADE_DATE'], rows: ctx.data.trades(40).map((r, i) => [r.ID, r.DESK, r.MTM, '2026-10-' + String(1 + (i % 28)).padStart(2, '0')]) };
      const fr = D.fromJson(json), gg = fr.toGrid({ id: 'ID', formats: { MTM: 'amount0' } });
      const note = ctx.h(doc, 'p', { cls: 'gx-note', text: fr.length + ' rows, ' + fr.names.length + ' columns: ' + fr.fields().map((f) => f.name + ' ' + f.type).join(', ') });
      host.appendChild(note);
      const g = doc.createElement('drishti-grid');
      host.appendChild(g);
      g.options = { columns: gg.columns, rows: gg.rows, getRowId: gg.getRowId, title: 'From a frame', checkboxes: false };
      return { target: g, element: g, ready: Promise.resolve(), destroy: () => { g.grid.destroy(); g.remove(); note.remove(); } };
    },
    code: () => ({ js: "import { ArrowClient, fromJson } from '/static/elements/drishti-data/src/drishti_index.js';\n\nconst frame = await new ArrowClient({}).read({ url: '/api/v1/rows.arrow' });   // or fromJson(answer)\nconst { columns, rows, getRowId } = frame.toGrid({ id: 'ID' });" })
  }));
}(typeof window !== 'undefined' ? window : globalThis));
