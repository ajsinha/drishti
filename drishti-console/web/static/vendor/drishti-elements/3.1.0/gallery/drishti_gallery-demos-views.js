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
/* The gallery's demo of drishti-views: a ViewModel (contract 1.x, made-up figures) painted by paint(): the title line, the strip, the
   freshness line, the function keys and panels in each of their states. The kinds' own drawings come with the next steps of the
   library; until then a kind is drawn as a titled panel that says so. */
(function (root) {
  'use strict';
  const NS = (root.Drishti = root.Drishti || {});
  const GAL = (NS.gallery = NS.gallery || {});
  const demos = (GAL.demos = GAL.demos || []);
  const prov = { layout: 'made-up', fingerprint: null, source: 'orders-service', generation: 7, fetchedAt: '2026-11-19T09:30:00Z', live: true,
    businessDate: '2026-11-19', updatedAt: '2026-11-19T09:29:58Z', staleAfter: 'PT5M', stale: false, sutra: null, masked: 0, maskedPanels: [] };
  const view = (stale) => ({
    contract: '1.0', ref: { kind: 'region', id: 'EMEA' }, title: { pill: 'Region · Orders', id: 'EMEA', with: { label: 'Owner', text: 'Northwind Sales' } },
    strip: [{ label: 'Booked', text: '18,420,350', emphasis: true }, { label: 'vs yesterday', text: '+4.2%', tone: 'pos' }, { label: 'Open orders', text: '1,287' }],
    panels: [
      { id: 'summary', kind: 'kv', title: 'Summary', area: 'main', inferred: false, empty: false, data: { fields: [{ label: 'Region', text: 'EMEA' }] } },
      { id: 'capacity', kind: 'gauge', title: 'Capacity', area: 'right', inferred: false, empty: true, error: 'E-2041 the capacity service did not answer' },
      { id: 'related', kind: 'links', title: 'Related', area: 'right', inferred: false, empty: true, denied: 'no access to team' },
      { id: 'events', kind: 'timeline', title: 'Events', area: 'right', inferred: true, explanation: 'a dated list was found', empty: true, data: { events: [], more: 0 } }],
    keys: [{ key: 'F9', label: 'Raw JSON', action: 'raw' }], provenance: Object.assign({}, prov, { stale: !!stale }), timings: {} });

  demos.push({
    id: 'view', library: 'drishti-views', kind: 'function', title: 'A view from a ViewModel', about: 'drishti_views-paint.js', types: ['PaintOptions'],
    events: ['ready', 'error', 'issues', 'link', 'key', 'patch'], keys: [], ag: false, sutra: () => null,
    summary: 'paint(el, viewModel) draws a whole screen a server describes as JSON: the title line, the strip of figures, how fresh it is, the function keys and every panel in its place and state. Frames of the live stream patch it in one animation frame.',
    extra: [{ name: 'density', control: { kind: 'select', values: ['comfortable', 'compact', 'terminal'] }, desc: 'The density of the view.' },
      { name: 'stale', control: { kind: 'switch' }, desc: 'The source is older than its threshold.' }],
    values: { density: 'compact', stale: false },
    examples: [{ title: 'Compact', values: {} }, { title: 'Stale', values: { stale: true } }, { title: 'Terminal', values: { density: 'terminal' } }],
    mount: function (host, values, ctx) {
      const doc = host.ownerDocument, box = ctx.h(doc, 'div', { cls: 'gx-fn' });
      host.appendChild(box);
      const handle = ctx.E.views.paint(box, view(values.stale), { density: values.density, shadow: false });
      ['link', 'key', 'patch'].forEach((n) => handle.on(n, (e) => ctx.log(n, JSON.stringify(e.detail))));
      return { target: box, element: box, ready: handle.ready, destroy: () => { handle.dispose(); box.remove(); } };
    },
    code: () => ({ js: "const view = Drishti.elements.views.paint(document.getElementById('orders'), '/views/orders.json', { density: 'compact' });\nview.ready.then(() => view.patch({ seq: 8, patches: [{ op: 'strip', index: 0, cell: { label: 'Booked', text: '18,432,850' } }] }));", element: '<div id="orders"></div>' })
  });
}(typeof window !== 'undefined' ? window : null));
