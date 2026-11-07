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
/* The gallery's demo of drishti-renderer 2.0.0: a screen drawn from a ViewModel (the strip, a key-value panel, a table and a note),
   with no server. The ViewModel is written here (the shape of VIEWMODEL.md); the drawing is the renderer's, with drishti-grid's
   document table and drishti-core's element maker. It appears with the library (the gallery shows a demo only when its library is in
   the manifest). */
(function (root) {
  'use strict';
  const NS = (root.Drishti = root.Drishti || {});
  const GAL = (NS.gallery = NS.gallery || {});
  const demos = (GAL.demos = GAL.demos || []);

  /** The sample screen: a bond with its terms and its lifecycle; rows as many as asked. */
  function viewModel(values) {
    const events = ['Booked', 'Amended', 'Confirmed', 'Settled', 'Coupon paid', 'Revalued'];
    const n = Math.max(1, Math.min(events.length, +values.rows || 4));
    return {
      contract: 1, mnemonic: 'TRD', ref: { kind: 'trade', id: 'DEMO-BOND-1' }, title: { id: 'DEMO-BOND-1', pill: values.title || 'US Treasury 4.25% 2038' },
      strip: [{ label: 'Notional', text: 'USD 25,000,000' }, { label: 'MTM (USD)', text: '+1,209,000', tone: 'pos', emphasis: true }, { label: '1-day P&L', text: '−41,000', tone: 'neg' }],
      keys: [],
      panels: [
        { id: 'terms', kind: 'kv', title: 'Terms', area: 'main', span: 6, empty: false, inferred: false,
          data: { fields: [{ label: 'Coupon', text: '4.2500%' }, { label: 'Frequency', text: 'Semi-annual' }, { label: 'Clean price', text: '97.84' }, { label: 'Yield', text: '4.4900%' }] } },
        { id: 'events', kind: 'table', title: 'Lifecycle', area: 'main', span: 6, empty: false, inferred: false,
          data: { columns: ['#', 'Event', 'By'], numeric: [true, false, false], search: false,
            rows: events.slice(0, n).map((e, i) => ({ cells: [{ label: '#', text: String(i + 1) }, { label: 'Event', text: e }, { label: 'By', text: i % 2 ? 'ops2' : 'trader1' }], highlight: false })) } },
        { id: 'note', kind: 'markdown', title: 'Reading this screen', area: 'right', empty: false, inferred: false,
          data: { text: 'Drawn by drishti-renderer from a ViewModel written in the page: no server, no Sutra.' } }
      ]
    };
  }

  demos.push({
    id: 'render', title: 'Render a ViewModel', library: 'drishti-renderer', kind: 'function', about: 'drishti_render.js', keys: [], events: [], types: [], ag: false,
    summary: 'A Drishti screen from a static ViewModel: the strip, a key-value panel, a table (drishti-grid\'s document table) and a note, drawn with the shared libraries.',
    extra: [{ name: 'rows', control: { kind: 'select', values: ['2', '4', '6'] }, desc: 'How many lifecycle rows the table has.' },
      { name: 'title', control: { kind: 'text', values: [] }, desc: 'The screen\'s title pill.' }],
    values: { rows: '4', title: 'US Treasury 4.25% 2038' },
    examples: [{ title: 'A bond with its lifecycle', values: {} }, { title: 'Two rows', values: { rows: '2' } }],
    sutra: () => null,
    mount: function (host, values, ctx) {
      const doc = host.ownerDocument, R = ctx.E.renderer;
      const el = ctx.h(doc, 'div', { cls: 'gx-fn gx-render', attrs: { 'data-render-demo': '' } });
      host.appendChild(el);
      const handle = R.render(el, viewModel(values), { live: false });
      return { target: el, element: el, ready: Promise.resolve(handle && handle.ready).catch(() => {}), destroy: () => { if (handle && handle.dispose) { handle.dispose(); } el.remove(); } };
    },
    code: function (values, ctx) {
      const lit = ctx.code.literal(viewModel(values));
      return { js: "import { render } from '/static/elements/drishti-renderer/src/drishti_index.js';\n\nrender(document.getElementById('screen'), " + lit + ', { live: false });',
        element: '<script src="/static/elements/drishti-core/drishti_core.js"></script>\n<script>\n  Drishti.elements.core.load(\'drishti-renderer\').then((R) => R.render(document.getElementById(\'screen\'), ' + lit + ', { live: false }));\n</script>' };
    }
  });
}(typeof window !== 'undefined' ? window : globalThis));
