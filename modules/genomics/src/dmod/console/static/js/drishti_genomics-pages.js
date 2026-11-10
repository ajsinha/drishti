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
/* The genomics module's page script: fills explorer.html from the module's API (/m/genomics/api/... through the console, the server's
   /api/v1/genomics/...). Lists on drishti-grid, the gene's variants on drishti-chart as the module's own kind genomics.lollipop (its
   library, drishti_genomics.js, registers it). No table, canvas or svg of its own and no colour (conformance C2, C3). */
(function () {
  'use strict';
  const root = document.querySelector('[data-genomics-route]');
  if (!root) { return; }
  const E = window.Drishti.elements;
  const route = root.dataset.genomicsRoute, id = root.dataset.genomicsId, api = root.dataset.genomicsApi, base = root.dataset.genomicsBase;
  const enc = encodeURIComponent;

  function get(path) {
    return fetch(api + path, { headers: { Accept: 'application/json' }, credentials: 'same-origin' }).then((r) => r.json().then((b) => {
      if (!r.ok) { throw new Error(b.detail || ('HTTP ' + r.status)); }
      return b;
    }));
  }
  function host(name) { return root.querySelector('[data-grid="' + name + '"]'); }
  function said(name, e) { const h = host(name); if (h) { E.core.toast({ text: e.message, timeout: 8000 }); h.dataset.state = 'failed'; } }
  function link(kind, field) {
    return { field: field, headerName: field.replace(/_/g, ' '), cellRenderer: (p) => {
      const a = document.createElement('a');
      a.href = base + '/' + kind + '/' + enc(p.value);
      a.textContent = p.value;
      return a;
    } };
  }
  function grid(name, rows, columns, key) {
    const h = host(name);
    if (!h) { return; }
    new E.grid.Grid(h, { rows: rows, columns: columns, getRowId: (r) => String(r[key]), label: h.getAttribute('aria-label'), layout: 'wells', density: 'compact' });
  }
  const VARIANT_COLUMNS = [link('variants', 'VARIANT_ID'), link('genes', 'GENE'), { field: 'CHROM' }, { field: 'POS', type: 'number' }, { field: 'REF' },
    { field: 'ALT' }, { field: 'CONSEQUENCE' }, { field: 'IMPACT' }, { field: 'PROTEIN_POS', type: 'number' }, { field: 'CARRIERS', type: 'number' },
    { field: 'AF', type: 'number' }, { field: 'FILTER' }];

  if (route === 'variants') {
    get('variants').then((b) => grid('variants', b.rows, VARIANT_COLUMNS, 'VARIANT_ID')).catch((e) => said('variants', e));
  } else if (route === 'genes') {
    get('genes').then((b) => grid('genes', b.rows, [link('genes', 'GENE'), { field: 'CHROM' }, { field: 'START', type: 'number' },
      { field: 'PROTEIN_LENGTH', type: 'number' }, { field: 'VARIANTS', type: 'number' }], 'GENE')).catch((e) => said('genes', e));
  } else if (route === 'cohorts') {
    get('cohorts').then((b) => grid('cohorts', b.rows, [link('cohorts', 'COHORT_ID'), { field: 'SAMPLES', type: 'number' },
      { field: 'VARIANTS', type: 'number' }, { field: 'GENES', valueFormatter: (p) => (p.value || []).join(', ') }], 'COHORT_ID')).catch((e) => said('cohorts', e));
  } else if (route === 'cohort') {
    get('cohorts/' + enc(id)).then((b) => grid('variants', b.variants, VARIANT_COLUMNS, 'VARIANT_ID')).catch((e) => said('variants', e));
  } else if (route === 'variant') {
    get('variants/' + enc(id)).then((v) => {
      grid('variant', Object.keys(v).filter((k) => k !== 'SAMPLES').map((k) => ({ FIELD: k, VALUE: v[k] === null ? '' : String(v[k]) })),
        [{ field: 'FIELD' }, { field: 'VALUE' }], 'FIELD');
      grid('carriers', (v.SAMPLES || []).map((s) => ({ SAMPLE_ID: s })), [{ field: 'SAMPLE_ID' }], 'SAMPLE_ID');
    }).catch((e) => said('variant', e));
  } else if (route === 'gene') {
    const chartHost = root.querySelector('[data-genomics-chart]');
    Promise.all([get('genes/' + enc(id)), E.visuals && E.visuals.genomics ? E.visuals.genomics.ready : E.load('drishti-chart')]).then(([g, C]) => {
      grid('variants', g.variants, VARIANT_COLUMNS, 'VARIANT_ID');
      new C.Chart(chartHost, { kind: 'genomics.lollipop', title: g.GENE + ' (' + g.PROTEIN_LENGTH + ' aa)', data: { rows: g.variants }, id: 'VARIANT_ID',
        length: g.PROTEIN_LENGTH, domains: g.DOMAINS || [] }, { height: 320 });
    }).catch((e) => said('variants', e));
  }
}());
