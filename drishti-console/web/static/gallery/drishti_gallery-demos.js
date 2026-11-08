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
/* The gallery's demos of drishti-core and drishti-grid (the chart's, the maps' and drishti-risk's are in drishti_gallery-demos-chart.js).
   A demo names its library, the typedefs its options panel is made from (the JSDoc, through the catalogue), the frozen event lists it
   listens to, the modules whose opening words give its description and its keys, and its examples. An element demo's build(values) is
   the object the gallery mounts AND prints in the code tabs (the snippet is the example's source, not a copy); a function demo draws
   its own small page with drishti-core. Drishti.gallery.demos is the list, in the order of the gallery's left pane. */
(function (root) {
  'use strict';
  const NS = (root.Drishti = root.Drishti || {});
  const GAL = (NS.gallery = NS.gallery || {});
  const demos = (GAL.demos = GAL.demos || []);

  /** The values a demo's typed options were given (not its example controls), each only when set. */
  function typed(values, names) {
    const o = {};
    names.forEach((n) => { if (values[n] !== undefined && values[n] !== '') { o[n] = values[n]; } });
    return o;
  }

  /** An element demo mounted: its tag with attributes, its options (and spec) set, as a page would. */
  function mountElement(host, b) {
    const el = host.ownerDocument.createElement(b.tag);
    Object.keys(b.attrs || {}).forEach((k) => { if (b.attrs[k] !== undefined && b.attrs[k] !== null) { el.setAttribute(k, String(b.attrs[k])); } });
    host.appendChild(el);
    el.options = b.options;
    if (b.spec) { el.spec = b.spec; }
    return {
      target: el, element: el,
      ready: el.chart && el.chart.ready ? el.chart.ready : Promise.resolve(),
      destroy: () => { if (el.grid) { el.grid.destroy(); } el.remove(); }
    };
  }

  /** A row of buttons above a demo (Tick, Stream, Export ...). */
  function toolbar(doc, h, label, buttons) {
    return h(doc, 'div', { cls: 'gx-demo-bar', attrs: { role: 'toolbar', 'aria-label': label } },
      buttons.map((b) => h(doc, 'button', { cls: 'gx-btn', text: b.text, attrs: { type: 'button', 'aria-pressed': b.pressed === undefined ? null : String(b.pressed), 'data-action': b.id }, on: { click: b.run } })));
  }

  const GRID_TYPES = ['GridViewOptions', 'GridOptions'];
  const GRID_SCALARS = ['layout', 'density', 'title', 'subtitle', 'label', 'pinTotals', 'checkboxes', 'phoneWidth', 'clientAggregation', 'rowSelection', 'blockSize'];
  const PIVOTS = {
    'Desk, book × region, currency': { rows: ['DESK', 'BOOK'], columns: ['REGION', 'CCY'], values: [{ colId: 'MTM', aggFunc: 'sum' }, { colId: 'VAR', aggFunc: 'sum' }], filters: [{ colId: 'TRADER', filter: null }] },
    'Desk × region': { rows: ['DESK'], columns: ['REGION'], values: [{ colId: 'MTM', aggFunc: 'sum' }, { colId: 'VAR', aggFunc: 'sum' }, { colId: 'ES', aggFunc: 'sum' }], filters: [] },
    'Trader × currency': { rows: ['TRADER'], columns: ['CCY'], values: [{ colId: 'MTM', aggFunc: 'sum' }], filters: [] }
  };

  /** The grid's options from the panel's values: the sample trades, the stand-in server's hooks, and every typed option set. */
  function gridBuild(values, ctx, extra) {
    const D = ctx.data, G = ctx.E.grid;
    const rows = ctx.memo('trades:' + values.rows, () => D.trades(+values.rows || 231));
    const explain = ctx.memo('explain:' + values.rows, () => D.gridExplain(G, rows));
    const o = Object.assign({ columns: D.COLUMNS }, extra && extra.datasource ? { datasource: extra.datasource } : { rows: rows },
      { getRowId: ctx.getRowId, explain: explain, download: ctx.download }, typed(values, GRID_SCALARS.filter((n) => n !== 'layout' && n !== 'density')), extra && extra.options);
    const names = new Map([[D.COLUMNS, 'columns'], [rows, 'rows'], [ctx.getRowId, 'getRowId'], [explain, 'explain'], [ctx.download, 'download']]);
    if (extra && extra.names) { extra.names.forEach((v, k) => names.set(k, v)); }
    return { tag: 'drishti-grid', ctor: 'Grid', library: 'drishti-grid', attrs: { layout: values.layout, density: values.density }, options: o,
      jsOptions: typed(values, ['layout', 'density']), names: names, given: Array.from(new Set(names.values())) };
  }

  function gridSutra(values, pivot) {
    if (pivot) {
      return 'panels:\n  - id: risk\n    kind: pivot\n    title: Desk P&L and risk\n    rows: $.TRADES\n    by: [' + pivot.rows.join(', ') + ']\n    across: ' + (pivot.columns[0] || '') +
        '\n    value: ' + pivot.values[0].colId + '\n    agg: ' + (pivot.values[0].aggFunc || 'sum') + '\n    fmt: compact\n    expand: 1\n# A Sutra pivot draws drishti-grid; its non-additive measures (VaR, ES) are the server\'s.';
    }
    return 'panels:\n  - id: trades\n    kind: table\n    title: ' + (values.title || 'Trades') + '\n    rows: $.TRADES\n    columns:\n' +
      '      - { label: Trade, bind: "@.ID", link: true }\n      - { label: Desk, bind: "@.DESK" }\n      - { label: Book, bind: "@.BOOK" }\n' +
      '      - { label: MTM, bind: "@.MTM", fmt: compact, total: true }\n      - { label: VaR 99% 1d, bind: "@.VAR", fmt: compact }   # not additive: never totalled';
  }

  function gridDemo(spec) {
    return Object.assign({
      library: 'drishti-grid', kind: 'element', about: 'drishti_grid-view.js', types: GRID_TYPES, events: ['GRID_EVENTS', 'VIEW_EVENTS'],
      keys: ['drishti_keyboard.js'], ag: true, functionKeys: true,
      mount: function (host, values, ctx) {
        const m = mountElement(host, this.build(values, ctx));
        if (this.after) { this.after(m.element, values, ctx); }
        return m;
      }
    }, spec);
  }

  demos.push(gridDemo({
    id: 'grid', title: 'Grid', summary: 'Rows, sorting, filtering, pinning, ranges with the status bar, Explain, export and the full keyboard; three layouts and three densities of one component.',
    extra: [{ name: 'rows', control: { kind: 'select', values: ['231', '2000', '20000'] }, desc: 'Sample trades in the page (synthetic).' }],
    values: { rows: '231', layout: 'wells', density: 'compact', title: 'Trades', subtitle: 'synthetic · as of 2026-11-06' },
    examples: [{ title: 'Trades, wells layout', values: {} }, { title: 'The terminal layout (VaR screens)', values: { layout: 'terminal', density: 'terminal' } },
      { title: 'The side panel (apps moving from AG Grid)', values: { layout: 'panel', density: 'comfortable' } }],
    build: function (values, ctx) { return gridBuild(values, ctx); },
    sutra: (values) => gridSutra(values)
  }));

  demos.push(gridDemo({
    id: 'pivot', title: 'Pivot', summary: 'The grid as a pivot: wells, member and value filters, top N, collapsible column groups, pinned totals; VaR and ES totals asked of the server (◆), never summed.',
    extra: [{ name: 'rows', control: { kind: 'select', values: ['231', '2000'] }, desc: 'Sample trades in the page (synthetic).' },
      { name: 'wells', control: { kind: 'select', values: Object.keys(PIVOTS) }, desc: 'The rows, columns and values the pivot starts with.' }],
    values: { rows: '231', wells: Object.keys(PIVOTS)[0], layout: 'wells', density: 'compact', title: 'Desk P&L and risk by region' },
    examples: [{ title: 'Wells (the default)', values: {} }, { title: 'Terminal, as VaR screens open', values: { layout: 'terminal', density: 'terminal' } },
      { title: 'Side panel, desk × region', values: { layout: 'panel', wells: 'Desk × region' } }],
    build: function (values, ctx) {
      const D = ctx.data, G = ctx.E.grid;
      const rows = ctx.memo('trades:' + values.rows, () => D.trades(+values.rows || 231));
      const serverPivot = ctx.memo('pivot:' + values.rows, () => D.serverPivot(G, rows, 40));
      const pivot = PIVOTS[values.wells] || PIVOTS[Object.keys(PIVOTS)[0]];
      const formats = [{ measure: 'VaR 99% 1d', type: 'colourScale' }, { measure: 'MTM', type: 'dataBar' }];
      return gridBuild(values, ctx, { options: { pivot: pivot, serverPivot: serverPivot, conditionalFormats: formats }, names: new Map([[serverPivot, 'serverPivot']]) });
    },
    after: (el) => { const m = el.grid.model; if (m.pivotMode) { m.pivotState.expandToLevel('columns', 2); m.pivotState.expandToLevel('rows', 2); m.runPivot(); } },
    sutra: (values) => gridSutra(values, PIVOTS[values.wells] || PIVOTS[Object.keys(PIVOTS)[0]])
  }));

  demos.push(gridDemo({
    id: 'ag-adapter', title: 'AG Grid adapter', about: 'drishti_grid-ag.js', types: [], events: ['GRID_EVENTS'],
    summary: 'A pivot made purely from AG Grid Enterprise-shaped options (columnDefs with rowGroup, pivot and aggFunc, pivotMode, the totals, sideBar) by createGridFromAgOptions: an application moves to drishti-grid by changing one call.',
    extra: [{ name: 'wells', control: { kind: 'select', values: Object.keys(PIVOTS) }, desc: 'The rowGroup, pivot and aggFunc columns the options name.' },
      { name: 'grandTotalRow', control: { kind: 'select', values: ['bottom', 'top', 'none'] }, desc: 'AG Grid\'s grandTotalRow.' },
      { name: 'groupTotalRow', control: { kind: 'select', values: ['none', 'bottom'] }, desc: 'AG Grid\'s groupTotalRow (a total row below each open group).' },
      { name: 'sideBar', control: { kind: 'select', values: ['true', 'false'] }, desc: 'AG Grid\'s sideBar: true gives the panel layout.' }],
    values: { wells: Object.keys(PIVOTS)[1], grandTotalRow: 'bottom', groupTotalRow: 'none', sideBar: 'true' },
    examples: [{ title: 'Desk × region, side bar', values: {} }, { title: 'Totals on top, footers below groups', values: { grandTotalRow: 'top', groupTotalRow: 'bottom' } },
      { title: 'Without a side bar (the wells)', values: { sideBar: 'false', wells: Object.keys(PIVOTS)[0] } }],
    agExtra: (values) => {
      const x = { grandTotalRow: values.grandTotalRow === 'none' ? undefined : values.grandTotalRow, groupTotalRow: values.groupTotalRow === 'bottom' ? 'bottom' : undefined };
      if (values.sideBar === 'false') { x.sideBar = undefined; }
      return x;
    },
    build: function (values, ctx) {
      const D = ctx.data, G = ctx.E.grid;
      const rows = ctx.memo('trades:231', () => D.trades(231));
      const serverPivot = ctx.memo('pivot:231', () => D.serverPivot(G, rows, 40));
      return gridBuild({ rows: '231', layout: values.sideBar === 'false' ? 'wells' : 'panel', density: 'compact', title: 'Desk P&L and risk (from AG-shaped options)' }, ctx,
        { options: { pivot: PIVOTS[values.wells] || PIVOTS['Desk × region'], serverPivot: serverPivot, rowSelection: 'multiple' }, names: new Map([[serverPivot, 'serverPivot']]) });
    },
    mount: function (host, values, ctx) {
      const b = this.build(values, ctx), s = ctx.code.agShape(Object.assign({}, b.options, b.jsOptions), b.names, this.agExtra(values));
      const el = ctx.h(host.ownerDocument, 'div', { cls: 'gx-ag-grid', attrs: { 'data-adapter': 'ag' } });
      host.appendChild(el);
      const api = ctx.E.grid.createGridFromAgOptions(el, s.options);
      api.addEventListener('cellClicked', (e) => ctx.log('onCellClicked', { value: e.value, column: e.column && e.column.colId, rowIndex: e.rowIndex }));
      api.addEventListener('columnPivotChanged', () => ctx.log('onColumnPivotChanged', { pivot: api.getPivotColumns().map((c) => c.colId) }));
      el.agApi = api;
      return { target: el, element: el, api: api, ready: api.ready, destroy: () => { api.destroy(); el.remove(); } };
    },
    code: function (values, ctx) { const b = this.build(values, ctx); return { js: ctx.code.agOptions(Object.assign({}, b.options, b.jsOptions), b.names, this.agExtra(values)) }; },
    sutra: () => null
  }));

  demos.push(gridDemo({
    id: 'layouts', title: 'Layouts × densities', summary: 'The three layouts (wells, panel, terminal) in the three densities (comfortable, compact, terminal): nine grids of one component.',
    types: [], extra: [{ name: 'mode', control: { kind: 'select', values: ['pivot', 'flat'] }, desc: 'Each grid as a pivot or flat rows.' }],
    values: { mode: 'pivot' }, ag: false,
    examples: [{ title: 'Nine pivots', values: {} }, { title: 'Nine flat grids', values: { mode: 'flat' } }],
    build: function (values, ctx) {
      const b = gridBuild({ rows: '231', layout: 'wells', density: 'compact' }, ctx, values.mode === 'flat' ? null : { options: { pivot: PIVOTS['Desk × region'] } });
      b.loop = true;
      return b;
    },
    mount: function (host, values, ctx) {
      const doc = host.ownerDocument, b = this.build(values, ctx), wrap = ctx.h(doc, 'div', { cls: 'gx-matrix' }), els = [];
      ['wells', 'panel', 'terminal'].forEach((l) => ['comfortable', 'compact', 'terminal'].forEach((d) => {
        const cell = ctx.h(doc, 'section', { cls: 'gx-matrix-cell', attrs: { 'aria-label': l + ' · ' + d } }, [ctx.h(doc, 'h3', { text: l + ' · ' + d })]);
        wrap.appendChild(cell);
        const m = mountElement(cell, Object.assign({}, b, { attrs: { layout: l, density: d } }));
        els.push(m);
      }));
      host.appendChild(wrap);
      return { target: wrap, element: wrap, ready: Promise.resolve(), destroy: () => { els.forEach((m) => m.destroy()); wrap.remove(); } };
    },
    code: function (values, ctx) {
      const b = this.build(values, ctx);
      const c = ctx.code.elementCode(b);
      c.js = c.js.replace(/^const grid = new Grid\(document\.getElementById\('grid'\), /m, "for (const layout of ['wells', 'panel', 'terminal']) {\n  for (const density of ['comfortable', 'compact', 'terminal']) {\n    new Grid(cell(layout, density), Object.assign({ layout, density }, ").replace(/\);$/, '));\n  }\n}');
      return c;
    },
    sutra: () => null
  }));

  demos.push(gridDemo({
    id: 'server-rows', title: 'Server-side rows', summary: 'Rows asked a block at a time with sort, filter and groups (a datasource), virtual scrolling, loading placeholders, refresh and purge.',
    extra: [{ name: 'rows', control: { kind: 'select', values: ['20000', '100000'] }, desc: 'Rows the stand-in datasource holds.' },
      { name: 'delay', control: { kind: 'select', values: ['40', '250', '800'] }, desc: 'The stand-in server\'s answer time, in milliseconds.' }],
    values: { rows: '100000', delay: '40', layout: 'wells', density: 'compact', title: 'Every trade (server-side)', blockSize: 100 },
    examples: [{ title: '100,000 rows, a block at a time', values: {} }, { title: 'A slow server: the placeholders', values: { delay: '800', rows: '20000' } }],
    build: function (values, ctx) {
      const D = ctx.data, G = ctx.E.grid;
      const rows = ctx.memo('trades:' + values.rows, () => D.trades(+values.rows));
      const ds = ctx.memo('ds:' + values.rows + ':' + values.delay, () => G.memoryDatasource(rows, { delay: +values.delay }));
      return gridBuild(Object.assign({}, values, { rows: values.rows }), ctx, { datasource: ds, names: new Map([[ds, 'datasource']]) });
    },
    agNote: 'rowModelType serverSide',
    sutra: () => null
  }));

  demos.push(gridDemo({
    id: 'server-tree', title: 'Server-side tree data', summary: 'A tree the server answers a level at a time: a node\'s children asked when it opens (groupKeys its path), reopened from the cache, every open level asked again on a sort.',
    extra: [{ name: 'delay', control: { kind: 'select', values: ['40', '250', '800'] }, desc: 'The stand-in server\'s answer time, in milliseconds.' }],
    values: { rows: '2000', delay: '250', layout: 'wells', density: 'compact', title: 'Bank › desk › book › trade (server-side tree)', blockSize: 100 },
    examples: [{ title: 'Bank, desks, books and trades', values: {} }, { title: 'A slow server: each level\'s placeholders', values: { delay: '800' } }],
    build: function (values, ctx) {
      const D = ctx.data, G = ctx.E.grid;
      const rows = ctx.memo('trades:' + values.rows, () => D.trades(+values.rows));
      const ds = ctx.memo('tree-ds:' + values.rows + ':' + values.delay, () => G.memoryDatasource(rows, { delay: +values.delay, keyField: 'ID', getDataPath: (r) => ['Bank', r.DESK, r.BOOK, r.ID] }));
      const isGroup = (r) => r.treeGroup, key = (r) => r.treeKey;
      return gridBuild(values, ctx, { datasource: ds, options: { treeData: true, groupDefaultExpanded: 1, isServerSideGroup: isGroup, getServerSideGroupKey: key, getRowId: (r, i, keys) => keys.concat([r.treeKey]).join('/') },
        names: new Map([[ds, 'datasource'], [isGroup, 'isServerSideGroup'], [key, 'getServerSideGroupKey']]) });
    },
    agNote: 'rowModelType serverSide with treeData, isServerSideGroup and getServerSideGroupKey',
    sutra: () => null
  }));

  demos.push(gridDemo({
    id: 'live', title: 'Live updates', summary: 'Transactions by row id from a feed (here a manual feed: SSE and WebSocket feeds take the same messages), batched, with the up and down flash.',
    extra: [{ name: 'perTick', control: { kind: 'select', values: ['3', '20', '200'] }, desc: 'Rows changed by each tick.' }],
    values: { perTick: '3', layout: 'wells', density: 'compact', title: 'Live MTM' },
    examples: [{ title: 'A manual feed', values: {} }],
    build: function (values, ctx) { return gridBuild(Object.assign({ rows: '231' }, values), ctx, { options: { live: { flashMs: 1200, serverDebounceMs: 60, asyncWaitMs: 50 } } }); },
    mount: function (host, values, ctx) {
      const doc = host.ownerDocument, G = ctx.E.grid, feed = G.manualFeed();
      const rows = ctx.memo('trades:231', () => ctx.data.trades(231)), rnd = ctx.data.random(5).rnd;
      let timer = 0;
      const tick = () => {
        const up = [];
        for (let i = 0; i < +values.perTick; i++) { const t = rows[Math.floor(rnd() * rows.length)]; t.MTM = Math.round(t.MTM + (rnd() - 0.45) * 4e5); up.push(Object.assign({}, t)); }
        feed.push({ type: 'transaction', update: up });
        ctx.log('feed.push', { type: 'transaction', update: up.length + ' rows' });
      };
      const bar = toolbar(doc, ctx.h, 'Feed', [{ id: 'tick', text: 'Tick', run: tick },
        { id: 'stream', text: 'Stream', pressed: false, run: (e) => { const on = e.currentTarget.getAttribute('aria-pressed') !== 'true'; e.currentTarget.setAttribute('aria-pressed', String(on)); clearInterval(timer); if (on) { timer = setInterval(tick, 400); } } }]);
      host.appendChild(bar);
      const m = mountElement(host, this.build(values, ctx));
      const off = m.element.grid.connectLive(feed);
      const destroy = m.destroy;
      m.destroy = () => { clearInterval(timer); if (typeof off === 'function') { off(); } destroy(); bar.remove(); };
      m.tick = tick;
      return m;
    },
    code: function (values, ctx) {
      const c = ctx.code.elementCode(this.build(values, ctx));
      const feed = "\n\n// a feed: Drishti.elements.grid.eventSourceFeed(url) or webSocketFeed(url) in a real page; a manual one here\nconst feed = manualFeed();\ngrid.connectLive(feed);\nfeed.push({ type: 'transaction', update: [{ ID: 'T7', MTM: 1250000 }] });";
      c.js = c.js.replace("import { Grid }", 'import { Grid, manualFeed }') + feed;
      return c;
    },
    sutra: () => null
  }));

  demos.push(gridDemo({
    id: 'editing', title: 'Editing', summary: 'Editable columns: a value set by row id becomes a transaction and a cellValueChanged event; server-side and computed columns are never editable.',
    extra: [{ name: 'value', control: { kind: 'text', values: [] }, desc: 'The value "Set" writes into the focused editable cell (Trader, or MTM as a number).' }],
    values: { value: '1250000', layout: 'wells', density: 'compact', title: 'Editable trades' },
    examples: [{ title: 'Trader and MTM editable', values: {} }],
    build: function (values, ctx) {
      const cols = ctx.memo('editcols', () => ctx.data.COLUMNS.map((c) => (c.field === 'TRADER' || c.field === 'MTM' ? Object.assign({}, c, { editable: true }) : c)));
      const b = gridBuild(Object.assign({ rows: '231' }, values), ctx);
      b.options.columns = cols;
      b.names.set(cols, 'columns');
      return b;
    },
    mount: function (host, values, ctx) {
      const doc = host.ownerDocument;
      let m = null;
      const set = () => {
        const g = m.element.grid, f = g.model.navigator.focus;
        if (!f) { ctx.log('note', 'Click an editable cell (Trader or MTM) first.'); return; }
        const col = g.model.shownColumns()[f.col];
        const v = col && col.type === 'number' ? +values.value : values.value;
        ctx.log('setCellValue', { row: f.row, column: col ? col.id : f.col, value: v, applied: g.model.setCellValue(f.row, f.col, v) });
      };
      const bar = toolbar(doc, ctx.h, 'Edit', [{ id: 'set', text: 'Set the focused cell to "' + values.value + '"', run: set }]);
      host.appendChild(bar);
      m = mountElement(host, this.build(values, ctx));
      const destroy = m.destroy;
      m.destroy = () => { destroy(); bar.remove(); };
      return m;
    },
    sutra: () => null
  }));

  demos.push(gridDemo({
    id: 'export', title: 'Export (CSV, Excel)', summary: 'CSV and a real .xlsx (values, outline levels, number formats) written in the browser by drishti-grid\'s own writer: no dependency.',
    values: { layout: 'wells', density: 'compact', title: 'Export' }, ag: false,
    examples: [{ title: 'Export a pivot', values: {} }],
    build: function (values, ctx) { return gridBuild(Object.assign({ rows: '231' }, values), ctx, { options: { pivot: PIVOTS['Desk × region'] } }); },
    mount: function (host, values, ctx) {
      const doc = host.ownerDocument;
      let m = null;
      const out = ctx.h(doc, 'span', { cls: 'gx-note', attrs: { 'aria-live': 'polite' } });
      const run = (id) => () => { Promise.resolve(m.element.grid.runAction(id)).then(() => { out.textContent = ctx.downloads.length ? 'Last file: ' + ctx.downloads[ctx.downloads.length - 1].name + ', ' + ctx.downloads[ctx.downloads.length - 1].size + ' bytes' : ''; }); };
      const bar = toolbar(doc, ctx.h, 'Export', [{ id: 'export-csv', text: 'CSV', run: run('export-csv') }, { id: 'export-xlsx', text: 'Excel (.xlsx)', run: run('export-xlsx') }]);
      bar.appendChild(out);
      host.appendChild(bar);
      m = mountElement(host, this.build(values, ctx));
      const destroy = m.destroy;
      m.destroy = () => { destroy(); bar.remove(); };
      return m;
    },
    code: function (values, ctx) {
      const c = ctx.code.elementCode(this.build(values, ctx));
      c.js += "\n\ngrid.runAction('export-xlsx');   // or 'export-csv'; the download option says where the file goes";
      return c;
    },
    sutra: () => null
  }));

  /* ---------------- drishti-core: functions, each with a small page of its own ---------------- */
  function fnDemo(spec) {
    return Object.assign({ library: 'drishti-core', kind: 'function', events: [], keys: [], ag: false, sutra: () => null }, spec);
  }
  function table(doc, h, head, rows) {
    return h(doc, 'table', { cls: 'gx-table' }, [h(doc, 'thead', {}, [h(doc, 'tr', {}, head.map((x) => h(doc, 'th', { text: x, attrs: { scope: 'col' } })))]),
      h(doc, 'tbody', {}, rows.map((r) => h(doc, 'tr', {}, r.map((x) => (x && x.nodeType ? h(doc, 'td', {}, [x]) : h(doc, 'td', { text: String(x) }))))))]);
  }
  const done = (el) => ({ target: el, element: el, ready: Promise.resolve(), destroy: () => el.remove() });

  const FORMAT_FNS = ['formatNumber', 'formatCompact', 'formatPercent', 'formatBasisPoints', 'formatCurrency'];
  const SAMPLES = [-30205543.218, 1234.5, 0.04256, -0.5, 987654321, 0];
  demos.push(fnDemo({
    id: 'formats', title: 'Formats', about: 'drishti_format.js', types: ['NumberOptions'],
    summary: 'Numbers, money, percentages and basis points by the server\'s rules: half up on the decimal value, the true minus, the named formats of formats.yaml.',
    extra: [{ name: 'fn', control: { kind: 'select', values: FORMAT_FNS }, desc: 'The formatter.' }, { name: 'currency', control: { kind: 'select', values: ['USD', 'EUR', 'GBP', 'JPY', 'CAD', 'INR'] }, desc: 'formatCurrency\'s currency.' }],
    values: { fn: 'formatNumber', currency: 'USD', decimals: 2 },
    examples: [{ title: 'Two decimals', values: {} }, { title: 'Compact (1.2m)', values: { fn: 'formatCompact' } }, { title: 'Money', values: { fn: 'formatCurrency', currency: 'JPY' } }],
    opts: (values) => typed(values, ['decimals', 'maxDecimals', 'grouping', 'sign', 'minus', 'scale', 'prefix', 'suffix', 'empty', 'locale']),
    mount: function (host, values, ctx) {
      const doc = host.ownerDocument, C = ctx.E.core, o = this.opts(values);
      const f = (v) => (values.fn === 'formatCurrency' ? C.formatCurrency(v, values.currency, o) : C[values.fn](v, o));
      const el = ctx.h(doc, 'div', { cls: 'gx-fn' }, [table(doc, ctx.h, ['Value', values.fn + '(value' + (values.fn === 'formatCurrency' ? ", '" + values.currency + "'" : '') + ', options)'],
        SAMPLES.map((v) => [String(v), f(v)])), ctx.h(doc, 'p', { cls: 'gx-note', text: 'Named formats: ' + C.formatNames().join(', ') })]);
      host.appendChild(el);
      return done(el);
    },
    code: function (values, ctx) {
      const lit = ctx.code.literal(this.opts(values));
      const call = values.fn === 'formatCurrency' ? "formatCurrency(-30205543.218, '" + values.currency + "', " + lit + ')' : values.fn + '(-30205543.218, ' + lit + ')';
      return { js: "import { " + values.fn + " } from '/static/elements/drishti-core/src/drishti_index.js';\n\n" + call + ';', element: '<script src="/static/elements/drishti-core/drishti_core.js"></script>\n<script>\n  Drishti.elements.core.' + call + ';\n</script>' };
    }
  }));

  demos.push(fnDemo({
    id: 'theme', title: 'Theme tokens', about: 'drishti_theme.js', types: [],
    summary: 'The theme as CSS custom properties generated from drishti_tokens.css: light and dark themes, the switch, and WCAG contrast arithmetic on every text token.',
    extra: [{ name: 'theme', control: { kind: 'select', values: (ctx) => ctx.E.core.themes().map((t) => t.name) }, desc: 'The theme in force (the whole gallery follows it).' }],
    values: { theme: '' },
    examples: [{ title: 'The current theme\'s tokens', values: {} }],
    mount: function (host, values, ctx) {
      const doc = host.ownerDocument, C = ctx.E.core;
      if (values.theme) { C.setTheme(values.theme, { persist: false }); ctx.log('setTheme', values.theme); }
      const name = C.currentTheme(doc), tk = C.tokens(name), bg = tk['--d-bg'];
      const rows = Object.keys(tk).filter((k) => /^--d-/.test(k) && /^(#|rgb)/.test(String(tk[k]).trim())).map((k) => {
        const sw = ctx.h(doc, 'span', { cls: 'gx-swatch', attrs: { 'aria-hidden': 'true' }, style: { background: tk[k] } });
        const ratio = C.TEXT_TOKENS.indexOf(k) >= 0 && bg ? C.contrastRatio(tk[k], bg).toFixed(2) + ' : 1' : '';
        return [ctx.h(doc, 'code', { text: k }), tk[k], sw, ratio];
      });
      const el = ctx.h(doc, 'div', { cls: 'gx-fn' }, [ctx.h(doc, 'p', { text: 'Theme in force: ' + name + ' (' + C.scheme(name) + ')' }), table(doc, ctx.h, ['Token', 'Value', 'Swatch', 'Contrast on --d-bg'], rows)]);
      host.appendChild(el);
      return done(el);
    },
    code: (values) => ({ js: "import { setTheme, tokens, contrastRatio } from '/static/elements/drishti-core/src/drishti_index.js';\n\nsetTheme('" + (values.theme || 'light') + "', { persist: false });\nconst t = tokens();\ncontrastRatio(t['--d-ink'], t['--d-bg']);   // WCAG ratio", element: '<html data-theme="' + (values.theme || 'light') + '">   <!-- the tokens are CSS custom properties: css/drishti_tokens.css -->' })
  }));

  demos.push(fnDemo({
    id: 'events', title: 'Event bus', about: 'drishti_events.js', types: [],
    summary: 'A typed event bus: on, once, off, emit, counts; the grid, the chart and linked selection talk through it.',
    values: {}, examples: [{ title: 'Emit and listen', values: {} }],
    mount: function (host, values, ctx) {
      const doc = host.ownerDocument, bus = ctx.E.core.createBus();
      const out = ctx.h(doc, 'ol', { cls: 'gx-list', attrs: { 'aria-live': 'polite', 'aria-label': 'Heard' } });
      let n = 0;
      const offs = [bus.on('trade.booked', (p) => { out.appendChild(ctx.h(doc, 'li', { text: 'on trade.booked → ' + JSON.stringify(p) })); ctx.log('trade.booked', p); })];
      bus.once('limit.breached', (p) => { out.appendChild(ctx.h(doc, 'li', { text: 'once limit.breached → ' + JSON.stringify(p) })); ctx.log('limit.breached', p); });
      const bar = toolbar(doc, ctx.h, 'Emit', [{ id: 'emit', text: 'emit trade.booked', run: () => bus.emit('trade.booked', { id: 'T' + (++n) }) },
        { id: 'once', text: 'emit limit.breached (heard once)', run: () => bus.emit('limit.breached', { desk: 'Rates' }) }]);
      const el = ctx.h(doc, 'div', { cls: 'gx-fn' }, [bar, out]);
      host.appendChild(el);
      const m = done(el);
      m.destroy = () => { offs.forEach((f) => f && f()); bus.clear(); el.remove(); };
      return m;
    },
    code: () => ({ js: "import { createBus } from '/static/elements/drishti-core/src/drishti_index.js';\n\nconst bus = createBus();\nconst off = bus.on('trade.booked', (p) => console.log(p.id));\nbus.once('limit.breached', (p) => alert(p.desk));\nbus.emit('trade.booked', { id: 'T1' });\noff();" })
  }));

  demos.push(fnDemo({
    id: 'overlays', title: 'Dialogs, drawers and toasts', about: 'drishti_overlay.js', types: ['ToastOptions'],
    summary: 'A modal dialog that gives focus back, a drawer that keeps Tab inside and closes on Escape, and toasts in one shared tray.',
    values: { text: 'Saved: the view is in your layouts', timeout: 4000 },
    examples: [{ title: 'Toast, dialog, drawer', values: {} }],
    mount: function (host, values, ctx) {
      const doc = host.ownerDocument, C = ctx.E.core, h = ctx.h;
      const dlg = h(doc, 'dialog', { cls: 'gx-dialog', attrs: { 'aria-label': 'A dialog' } }, [h(doc, 'p', { text: 'A modal dialog: focus moved in, and goes back when it closes.' }),
        h(doc, 'label', {}, ['Name ', h(doc, 'input', { attrs: { type: 'text', value: 'Rates VaR' } })]), h(doc, 'button', { cls: 'gx-btn', text: 'Close', attrs: { type: 'button' }, on: { click: () => C.closeDialog(dlg, 'ok') } })]);
      const drawer = h(doc, 'aside', { cls: 'gx-drawer', attrs: { hidden: true, 'aria-label': 'A drawer' } }, [h(doc, 'p', { text: 'A drawer: Tab stays inside; Escape closes it.' }),
        h(doc, 'button', { cls: 'gx-btn', text: 'Close the drawer', attrs: { type: 'button' }, on: { click: () => close && close() } })]);
      let close = null;
      const bar = toolbar(doc, h, 'Overlays', [{ id: 'toast', text: 'Toast', run: () => { C.toast(this.opts(values)); ctx.log('toast', this.opts(values)); } },
        { id: 'dialog', text: 'Dialog', run: () => { C.openDialog(dlg); ctx.log('openDialog', ''); } },
        { id: 'drawer', text: 'Drawer', run: () => { close = C.openDrawer(drawer, { onClose: () => ctx.log('drawer closed', '') }); ctx.log('openDrawer', ''); } }]);
      const el = h(doc, 'div', { cls: 'gx-fn' }, [bar, dlg, drawer]);
      host.appendChild(el);
      const m = done(el);
      m.destroy = () => { if (close) { close(); } C.closeDialog(dlg); el.remove(); };
      return m;
    },
    opts: (values) => typed(values, ['text', 'href', 'className', 'timeout']),
    code: function (values, ctx) { return { js: "import { toast, openDialog, openDrawer } from '/static/elements/drishti-core/src/drishti_index.js';\n\ntoast(" + ctx.code.literal(this.opts(values)) + ");\nopenDialog(document.querySelector('dialog'));\nconst close = openDrawer(document.querySelector('aside'), { onClose: () => {} });" }; }
  }));

  demos.push(fnDemo({
    id: 'explain', title: 'Explain', about: 'drishti_explain.js', types: ['ExplainResult'],
    summary: 'How a figure was made, drawn the same for a grid cell, a chart mark and a map place: the scope, the steps, the parts, the worst scenarios and the trend.',
    extra: [{ name: 'mode', control: { kind: 'select', values: ['drawer', 'card', 'split'] }, desc: 'Where the component draws it.' }],
    values: { mode: 'card', title: 'VaR 99% 1d', value: '9.42m', scope: 'Rates · as of 2026-11-06 · 54 trades' },
    examples: [{ title: 'A card', values: {} }, { title: 'A split pane (the terminal layout)', values: { mode: 'split' } }],
    result: (values) => Object.assign({ partsTitle: 'Stand-alone by book', parts: [{ label: 'Govt Bonds', value: 5.1e6, text: '5.10m' }, { label: 'Swaps', value: 3.9e6, text: '3.90m' }, { label: 'Inflation', value: 1.8e6, text: '1.80m' }],
      steps: [{ label: 'Scenario P&L vectors added scenario by scenario', value: '' }, { label: 'VaR of the summed vector', value: '9.42m' }, { label: 'Sum of the parts (not used)', value: '10.80m' }, { label: 'Diversification', value: '−1.38m' }],
      scenarios: [{ label: '2024-03-03', value: '−15.1m' }, { label: '2024-06-05', value: '−14.4m' }], trend: [8.1, 8.4, 8.9, 9.6, 9.1, 9.42], limit: 12 }, typed(values, ['title', 'value', 'scope', 'note'])),
    mount: function (host, values, ctx) {
      const doc = host.ownerDocument, el = ctx.h(doc, 'div', { cls: 'gx-fn gx-explain' });
      host.appendChild(el);
      ctx.E.core.renderExplain(el, { result: this.result(values) }, values.mode, { prefix: 'dg', onClose: () => ctx.log('close', '') });
      return done(el);
    },
    code: function (values, ctx) { return { js: "import { renderExplain } from '/static/elements/drishti-core/src/drishti_index.js';\n\nrenderExplain(el, { result: " + ctx.code.literal(this.result(values)) + " }, '" + values.mode + "');" }; }
  }));

  demos.push(fnDemo({
    id: 'api-client', title: 'API client', about: 'drishti_api.js', types: ['ClientOptions'],
    summary: 'fetch with the person\'s token: problems in words with their DRS code and fix, GET retries, a time limit, abort, the as-of header. Here a stand-in server answers.',
    values: { retries: 2, retryDelay: 100, timeout: 0 },
    examples: [{ title: 'Answers, problems and retries', values: {} }],
    mount: function (host, values, ctx) {
      const doc = host.ownerDocument, h = ctx.h;
      let flaky = 0;
      const fakeFetch = (url) => new Promise((resolve) => setTimeout(() => {
        const json = (status, body) => resolve(new Response(JSON.stringify(body), { status: status, headers: { 'content-type': 'application/json' } }));
        if (/ok/.test(url)) { json(200, { rows: [{ desk: 'Rates', var99: 9.42e6 }] }); } else if (/problem/.test(url)) { json(404, { code: 'DRS-4040', title: 'No such desk', detail: 'There is no desk RATESX.', fix: 'Pick a desk from DESK <GO>.' }); } else { json(++flaky % 3 ? 503 : 200, flaky % 3 ? { title: 'Busy' } : { rows: [] }); }
      }, 60));
      const client = ctx.E.core.createClient(Object.assign({ fetch: fakeFetch }, typed(values, ['base', 'credentials', 'retries', 'retryDelay', 'timeout', 'unreachable'])));
      const out = h(doc, 'pre', { cls: 'gx-out', attrs: { 'aria-live': 'polite' } });
      const ask = (path) => () => { const t0 = Date.now(); client.get(path).then((r) => { out.textContent = 'GET ' + path + ' → ' + JSON.stringify(r) + ' (' + (Date.now() - t0) + ' ms)'; ctx.log('answer', path); },
        (e) => { out.textContent = 'GET ' + path + ' → ' + e.message + (e.code ? ' [' + e.code + ']' : '') + ' (' + (Date.now() - t0) + ' ms)'; ctx.log('problem', e.message); }); };
      const el = h(doc, 'div', { cls: 'gx-fn' }, [toolbar(doc, h, 'Requests', [{ id: 'ok', text: 'GET /desks/ok', run: ask('/desks/ok') }, { id: 'problem', text: 'GET /desks/problem', run: ask('/desks/problem') },
        { id: 'flaky', text: 'GET /desks/flaky (retried)', run: ask('/desks/flaky') }]), out]);
      host.appendChild(el);
      return done(el);
    },
    code: function (values, ctx) { return { js: "import { createClient } from '/static/elements/drishti-core/src/drishti_index.js';\n\nconst api = createClient(" + ctx.code.literal(typed(values, ['base', 'credentials', 'retries', 'retryDelay', 'timeout', 'unreachable'])) + ");\nconst desks = await api.get('/api/v1/desks');   // an ApiError with words, code and fix when it fails" }; }
  }));

  demos.push(fnDemo({
    id: 'a11y', title: 'Accessibility helpers', about: 'drishti_a11y.js', types: [],
    summary: 'A live region (announce), a roving tab index (one Tab stop, arrows inside), a focus trap: what every component uses for its keyboard.',
    values: {}, examples: [{ title: 'A toolbar with a roving tab index', values: {} }],
    mount: function (host, values, ctx) {
      const doc = host.ownerDocument, h = ctx.h, C = ctx.E.core;
      const items = ['Day', 'Week', 'Month', 'Year'].map((t) => h(doc, 'button', { cls: 'gx-btn', text: t, attrs: { type: 'button', role: 'tab', 'aria-selected': 'false' } }));
      const list = h(doc, 'div', { cls: 'gx-demo-bar', attrs: { role: 'tablist', 'aria-label': 'Period' } }, items);
      const roving = C.rovingTabindex(list, { selector: '[role="tab"]', onMove: (el) => { items.forEach((x) => x.setAttribute('aria-selected', String(x === el))); C.announce(el.textContent + ' selected'); ctx.log('move', el.textContent); } });
      const el = h(doc, 'div', { cls: 'gx-fn' }, [h(doc, 'p', { text: 'Tab into the list, then the arrows, Home and End: one Tab stop, the choice announced.' }), list]);
      host.appendChild(el);
      const m = done(el);
      m.destroy = () => { roving.destroy(); el.remove(); };
      return m;
    },
    code: () => ({ js: "import { rovingTabindex, announce } from '/static/elements/drishti-core/src/drishti_index.js';\n\nrovingTabindex(document.querySelector('[role=tablist]'), {\n  selector: '[role=tab]',\n  onMove: (el) => announce(el.textContent + ' selected')\n});" })
  }));
}(typeof window !== 'undefined' ? window : globalThis));
