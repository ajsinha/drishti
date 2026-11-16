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
/* The gallery's demos of the state kit, the command palette, the bullet bar and the grid's cell drawings (sparkline, bar, bullet). */
(function (root) {
  'use strict';
  const NS = (root.Drishti = root.Drishti || {});
  const GAL = (NS.gallery = NS.gallery || {});
  const demos = (GAL.demos = GAL.demos || []);
  const fn = (spec) => Object.assign({ library: 'drishti-core', kind: 'function', events: [], keys: [], ag: false, sutra: () => null }, spec);
  const done = (el) => ({ target: el, element: el, ready: Promise.resolve(), destroy: () => el.remove() });

  demos.push(fn({
    id: 'states', title: 'States: empty, loading, error, stale', about: 'drishti_states.js', types: ['StateOptions'],
    summary: 'The four states every component shows in place of its content, drawn one way: empty (and why), a loading skeleton, an error with a fix, stale figures with their age. Reduced motion stills the skeleton.',
    extra: [{ name: 'state', control: { kind: 'select', values: ['empty', 'loading', 'error', 'stale'] }, desc: 'The state shown.' },
      { name: 'compact', control: { kind: 'checkbox' }, desc: 'A row in a list or a cell, not a panel.' }],
    values: { state: 'error', compact: false, text: 'The VaR could not be computed.' },
    examples: [{ title: 'Error with a fix', values: {} }, { title: 'Loading skeleton', values: { state: 'loading' } }, { title: 'Stale', values: { state: 'stale', text: 'These figures are old.' } },
      { title: 'Empty', values: { state: 'empty', text: 'No trades match this filter.' } }],
    mount: function (host, values, ctx) {
      const C = ctx.E.core, doc = host.ownerDocument;
      const el = C.stateBlock(doc, values.state, { text: values.state === 'loading' ? undefined : values.text, compact: !!values.compact, since: '12:04', detail: values.state === 'error' ? 'DRS-5003' : undefined,
        fix: values.state === 'error' || values.state === 'stale' ? { label: values.state === 'error' ? 'Retry' : 'Refresh', run: () => ctx.log('fix', values.state) } : undefined });
      host.appendChild(el);
      return done(el);
    },
    code: () => ({ js: "const block = Drishti.elements.core.stateBlock(document, 'error', {\n  text: 'The VaR could not be computed.',\n  fix: { label: 'Retry', run: () => reload() }\n});\nhost.appendChild(block);", element: '<!-- any host element -->' })
  }));

  demos.push(fn({
    id: 'command-palette', title: 'Command palette', about: 'drishti_command-palette.js', types: ['PaletteCommand'],
    summary: 'Ctrl+K (Cmd+K) opens a fuzzy-searched list of the commands, pages and words a page registers. Keyboard first: Up, Down, Enter, Escape; an ARIA combobox over a listbox; a full-width sheet with 44 px rows on a phone.',
    values: {}, examples: [{ title: 'Open it, type "vp"', values: {} }],
    mount: function (host, values, ctx) {
      const C = ctx.E.core, doc = host.ownerDocument;
      const pal = new C.CommandPalette(doc);
      const say = (c) => { ctx.log('run', c.id); };
      pal.register([{ id: 'go.var', title: 'Go to the VaR page', group: 'Pages', words: ['risk', 'value at risk'], keys: ['G', 'V'], run: say },
        { id: 'go.exposure', title: 'Go to the Exposure page', group: 'Pages', run: say },
        { id: 'cmd.export', title: 'Export the grid as CSV', group: 'Commands', detail: 'The rows now shown', keys: ['Ctrl+E'], run: say },
        { id: 'cmd.theme', title: 'Switch theme', group: 'Commands', words: ['dark', 'light'], run: say },
        { id: 'word.dv01', title: 'DV01', group: 'Words', detail: 'Change in value for one basis point', run: say }]);
      const off = C.bindPaletteKey(doc.defaultView, pal);
      const btn = ctx.h(doc, 'button', { cls: 'gx-btn', text: 'Open the palette (Ctrl+K)', attrs: { type: 'button' }, on: { click: () => pal.open() } });
      host.appendChild(btn);
      return { target: btn, element: btn, ready: Promise.resolve(), destroy: () => { off(); pal.destroy(); btn.remove(); } };
    },
    code: () => ({ js: "const E = Drishti.elements.core;\nconst palette = new E.CommandPalette(document);\npalette.register({ id: 'go.var', title: 'Go to the VaR page', group: 'Pages', href: '/var' });\nE.bindPaletteKey(window, palette, Drishti.keys);", element: '<!-- Ctrl+K opens it -->' })
  }));

  demos.push(fn({
    id: 'bullet', library: 'drishti-chart', title: 'Bullet (limit) bar', about: 'drishti_bullet.js', types: ['BulletOptions'],
    summary: 'A figure against its limit and the bands behind it: horizontal or vertical, over-limit drawn in the bad tone, with its words in the label for a screen reader. The grid\'s bullet and bar cells are this drawing.',
    extra: [{ name: 'orientation', control: { kind: 'select', values: ['horizontal', 'vertical'] }, desc: 'The direction of the bar.' }],
    values: { value: 82, limit: 100, orientation: 'horizontal' },
    examples: [{ title: 'Within the limit', values: {} }, { title: 'Over the limit', values: { value: 118 } }, { title: 'Vertical', values: { orientation: 'vertical' } }],
    mount: function (host, values, ctx) {
      const doc = host.ownerDocument, box = ctx.h(doc, 'div', { cls: 'gx-fn' });
      box.style.width = values.orientation === 'vertical' ? '2em' : '320px';
      box.style.height = values.orientation === 'vertical' ? '160px' : '1.6em';
      box.appendChild(ctx.E.chart.bullet(doc, { value: Number(values.value), limit: Number(values.limit), orientation: values.orientation, label: 'VaR 99% 1d',
        bands: [{ to: 70, tone: 'ok' }, { to: 90, tone: 'warn' }, { to: 130, tone: 'bad' }] }));
      host.appendChild(box);
      return done(box);
    },
    code: () => ({ js: "host.appendChild(Drishti.elements.chart.bullet(document, {\n  value: 82, limit: 100, label: 'VaR 99% 1d',\n  bands: [{ to: 70, tone: 'ok' }, { to: 90, tone: 'warn' }, { to: 130, tone: 'bad' }]\n}));", element: '<!-- any host element -->' })
  }));

  demos.push(fn({
    id: 'grid-cells', library: 'drishti-grid', title: 'Grid cells: sparkline, bar, bullet', about: 'drishti_cells.js', types: ['CellDrawOptions'],
    summary: 'A column\'s cellRenderer drawing a row\'s series as a sparkline, a figure as a bar, a figure against its limit as a bullet. The cell\'s text stays the figure: copy, export and sort use it.',
    values: {}, examples: [{ title: 'VaR by desk', values: {} }],
    mount: function (host, values, ctx) {
      const G = ctx.E.grid, doc = host.ownerDocument;
      let seed = 3;
      const rnd = () => { seed = (seed * 16807) % 2147483647; return seed / 2147483647; };
      const rows = ['Rates', 'Credit', 'FX', 'Equities', 'Commodities', 'Munis'].map((desk, i) => {
        const limit = 100 + i * 10, var99 = Math.round(limit * (0.5 + rnd() * 0.65)), trend = [];
        for (let k = 0, v = var99; k < 30; k++) { v = Math.max(5, v + (rnd() - 0.5) * 12); trend.push(Math.round(v)); }
        return { id: desk, desk, var99, limit, trend, last: trend[trend.length - 1], notional: Math.round(200 + rnd() * 800) };
      });
      const el = doc.createElement('drishti-grid');
      host.appendChild(el);
      el.options = { rows, getRowId: (r) => r.id, columns: [{ field: 'desk', header: 'Desk', width: 130 },
        { field: 'var99', header: 'VaR 99% 1d', type: 'number', width: 90 },
        { field: 'var99', id: 'bullet', header: 'Against the limit', width: 200, cellRenderer: G.bulletCell({ limit: 'limit', showValue: false, bands: [{ to: 70, tone: 'ok' }, { to: 90, tone: 'warn' }, { to: 160, tone: 'bad' }] }) },
        { field: 'notional', header: 'Notional', type: 'number', width: 180, cellRenderer: G.barCell({ max: 1000 }) },
        { field: 'last', header: '30 days', type: 'number', width: 160, cellRenderer: G.sparklineCell({ values: 'trend' }) }] };
      return { target: el, element: el, ready: Promise.resolve(), destroy: () => { if (el.grid) { el.grid.destroy(); } el.remove(); } };
    },
    code: () => ({ js: "const G = Drishti.elements.grid;\ngrid.options = { rows, columns: [\n  { field: 'var99', cellRenderer: G.bulletCell({ limit: 'limit', bands: [{ to: 70, tone: 'ok' }, { to: 90, tone: 'warn' }, { to: 160, tone: 'bad' }] }) },\n  { field: 'notional', cellRenderer: G.barCell({ max: 1000 }) },\n  { field: 'last', cellRenderer: G.sparklineCell({ values: 'trend' }) }\n] };", element: '<drishti-grid id="grid"></drishti-grid>' })
  }));

  demos.push(fn({
    id: 'colour-scale', library: 'drishti-chart', title: 'Colour scale', about: 'drishti_chart-scale.js', types: ['ScaleOptions'],
    summary: 'A sequential or diverging scale with a midpoint: the colour of a figure and a legend as an element (low, middle, high). The ramps are the theme\'s, so dark and light follow.',
    extra: [{ name: 'type', control: { kind: 'select', values: ['diverging', 'sequential'] }, desc: 'Sequential or diverging.' }],
    values: { type: 'diverging', midpoint: 0 },
    examples: [{ title: 'Diverging about 0', values: {} }, { title: 'Sequential', values: { type: 'sequential' } }],
    mount: function (host, values, ctx) {
      const doc = host.ownerDocument, th = ctx.E.chart.chartTheme({ element: host }), div = values.type === 'diverging';
      const sc = ctx.E.chart.colourScale({ type: values.type, domain: [-40, 100], midpoint: Number(values.midpoint) || 0, ramp: div ? th.diverging : th.sequential, format: (v) => String(Math.round(v)) });
      const box = ctx.h(doc, 'div', { cls: 'gx-fn' }, [sc.legend(doc), ctx.h(doc, 'p', { cls: 'gx-note', text: 'colour(70) = ' + sc.colour(70) + ', colour(-20) = ' + sc.colour(-20) })]);
      host.appendChild(box);
      return done(box);
    },
    code: () => ({ js: "const scale = Drishti.elements.chart.colourScale({ type: 'diverging', domain: [-40, 100], midpoint: 0, ramp: theme.diverging });\nscale.colour(70);\nhost.appendChild(scale.legend(document));", element: '<!-- any host element -->' })
  }));

  demos.push(fn({
    id: 'small-multiples', library: 'drishti-chart', title: 'Small multiples', about: 'drishti_chart-multiples.js', types: ['FacetOptions'],
    summary: 'One chart per member of a field on one shared y scale, with the crosshair linked: point at a day in one panel and every panel shows it.',
    values: {}, examples: [{ title: 'VaR by desk', values: {} }],
    mount: function (host, values, ctx) {
      const doc = host.ownerDocument, C = ctx.E.chart, rows = [];
      ['Rates', 'Credit', 'FX', 'Equities'].forEach((d, i) => { for (let k = 0; k < 30; k++) { rows.push({ desk: d, day: k + 1, var: Math.round(20 + i * 8 + 10 * Math.sin((k + i * 3) / 4)) }); } });
      const box = ctx.h(doc, 'div', { cls: 'gx-fn' });
      host.appendChild(box);
      const f = C.facets(box, { kind: 'line', x: { field: 'day', type: 'category' }, y: { field: 'var' }, series: [{ field: 'var' }], data: { rows } }, 'desk', { columns: 2, chart: { echarts: root.echarts } });
      return { target: box, element: box, ready: f.ready, destroy: () => { f.destroy(); box.remove(); } };
    },
    code: () => ({ js: "Drishti.elements.chart.facets(host, { kind: 'line', x: { field: 'day' }, y: { field: 'var' }, data: { rows } }, 'desk', { columns: 2 });", element: '<!-- any host element -->' })
  }));

  demos.push(fn({
    id: 'grid-rules', library: 'drishti-grid', title: 'Grid: rule builder, share link, heat pivot', about: 'drishti_grid-rules.js', types: [],
    summary: 'The conditional-format rule builder (a form from a schema: compare with a figure, top share, scale, bars), a link that carries a saved view in its fragment, and the heat-cell pivot preset with its marginal totals.',
    values: {}, examples: [{ title: 'Heat pivot of VaR', values: {} }],
    mount: function (host, values, ctx) {
      const G = ctx.E.grid, doc = host.ownerDocument;
      const rows = [];
      ['Rates', 'Credit', 'FX', 'Equities'].forEach((d, i) => ['EMEA', 'APAC', 'Americas'].forEach((r, k) => rows.push({ desk: d, region: r, var99: 20 + ((i * 13 + k * 7) % 41), mtm: ((i + 1) * (k + 2) * 37) % 300 - 120 })));
      const bar = ctx.h(doc, 'div', { cls: 'gx-fn' }, [ctx.h(doc, 'button', { cls: 'gx-btn', text: 'Add a rule…', attrs: { type: 'button' }, on: { click: () => G.openRuleBuilder(el.grid) } }),
        ctx.h(doc, 'button', { cls: 'gx-btn', text: 'Copy a link to this view', attrs: { type: 'button' }, on: { click: () => { const l = G.shareLink(doc.defaultView.location.href, el.grid.getState()); ctx.log('shareLink', l.length + ' characters'); } } })]);
      const el = doc.createElement('drishti-grid');
      host.appendChild(bar);
      host.appendChild(el);
      el.options = Object.assign({ rows, getRowId: (r) => r.desk + r.region, columns: [{ field: 'desk', header: 'Desk' }, { field: 'region', header: 'Region' },
        { field: 'var99', header: 'VaR 99% 1d', type: 'number' }, { field: 'mtm', header: 'MTM', type: 'number' }] }, G.heatPivotPreset(['var99', 'mtm']));
      return { target: el, element: el, ready: Promise.resolve(), destroy: () => { if (el.grid) { el.grid.destroy(); } bar.remove(); el.remove(); } };
    },
    code: () => ({ js: "const G = Drishti.elements.grid;\ngrid.options = Object.assign({ rows, columns }, G.heatPivotPreset(['var99', 'mtm']));\nG.openRuleBuilder(grid.grid);\nconst link = G.shareLink(location.href, grid.grid.getState());\nconst view = G.readSharedView(location.href);", element: '<drishti-grid id="grid"></drishti-grid>' })
  }));
}(typeof window !== 'undefined' ? window : null));
