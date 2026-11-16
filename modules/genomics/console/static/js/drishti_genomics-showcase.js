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
/* The showcase's page script (showcase.html): it only displays. Every figure, every selection's members, every sentence of Explain is
   the module's server's (GenomicsBoard, ProteinBench); a selection made on a card (a peak, a cluster, a rectangle, a cell, a round, a
   design) is sent as the next call's parameters and the answer is drawn. The cards are drishti-chart charts of the module's kinds
   (drishti-genomics: glue over the generic kinds) and drishti-grid grids; the channels of the design's section 4 are told on
   drishti-core's bus so another page part may follow them. No table, canvas or svg of its own, no number formatted by hand, no colour. */
(function () {
  'use strict';
  const root = document.querySelector('[data-showcase]');
  if (!root) { return; }
  const E = window.Drishti.elements;
  const mode = root.dataset.showcase, api = root.dataset.genomicsApi, base = root.dataset.genomicsBase, enc = encodeURIComponent;
  const $ = (sel) => root.querySelector(sel);
  const qs = (o) => Object.keys(o).filter((k) => o[k] !== undefined && o[k] !== null && o[k] !== '').map((k) => k + '=' + enc(o[k])).join('&');

  function get(path, params) {
    const q = params ? qs(params) : '';
    return fetch(drishti_url(api + path + (q ? (path.indexOf('?') < 0 ? '?' : '&') + q : '')), { headers: { Accept: 'application/json' }, credentials: 'same-origin' })
      .then((r) => r.json().then((b) => { if (!r.ok) { throw new Error(b.detail || ('HTTP ' + r.status)); } return b; }));
  }
  function said(e) { E.core.toast({ text: e.message, timeout: 9000 }); root.dataset.state = 'failed'; }
  function tell(channel, ids) { E.core.bus.emit(E.core.SELECTION_EVENT, { channel: channel, ids: ids, source: root }); }
  const pct = (v) => (v === null || v === undefined ? '—' : E.core.formatPercent(v, { decimals: 0 }));
  const count = (v) => (v === null || v === undefined ? '—' : E.core.formatNumber(v, { decimals: 0 }));

  /* the records grid: the rows of the last Explain (its columns as the server named them) */
  const grids = {};
  function grid(name, rows, columns, key, onRow) {
    const host = root.querySelector('[data-grid="' + name + '"]');
    if (!host) { return null; }
    if (grids[name]) { grids[name].destroy(); }
    host.textContent = '';
    const g = new E.grid.Grid(host, { rows: rows, columns: columns, getRowId: (r) => String(key ? r[key] : rows.indexOf(r)), label: host.getAttribute('aria-label'), layout: 'wells', density: 'compact' });
    if (onRow) { g.on('cellClicked', (c) => { if (c && c.data) { onRow(c.data); } }); }
    grids[name] = g;
    return g;
  }
  function records(ex) {
    const rec = ex && ex.records;
    if (!rec || !rec.columns) { return; }
    grid('records', rec.rows, rec.columns.map((c) => ({ field: c, headerName: c.replace(/_/g, ' ') })), null);
    root.dataset.records = String(rec.count);
  }
  /** the server's links are the module's own routes: they open under this page's base */
  const local = (ex) => (ex && Array.isArray(ex.links) ? Object.assign({}, ex, { links: ex.links.map((l) => Object.assign({}, l, { href: /^\//.test(l.href) ? drishti_url(base + l.href) : l.href })) }) : ex);
  /** an Explain hook: the server's block for the mark (drawn by the chart), its records into the grid */
  const explainer = (path) => (ctx) => {
    const p = path(ctx);
    return p ? get(p[0], p[1]).then((ans) => { const ex = local(ans && ans.explain && !ans.steps ? ans.explain : ans); records(ex); return ex; }) : Promise.resolve(null);
  };

  let C = null;
  const charts = {};
  function chart(name, spec, o) {
    const host = root.querySelector('[data-chart="' + name + '"]');
    if (!host) { return null; }
    if (charts[name]) { charts[name].setSpec(spec); return charts[name]; }
    charts[name] = new C.Chart(host, spec, Object.assign({ height: 300, density: 'compact' }, o));
    return charts[name];
  }

  // ---------------------------------------------------------------- the board (genomics A) and its guided mode (C)
  function board() {
    const cohort = root.dataset.cohort, params = new URLSearchParams(location.search);
    const st = { trait: root.dataset.genomicsRoute === 'gwas' && root.dataset.genomicsId ? root.dataset.genomicsId : params.get('trait') || '', gene: params.get('gene') || '', cluster: params.get('cluster') || '', box: '' };
    const sel = () => ({ cluster: st.cluster, box: st.box });
    const selCohort = () => Object.assign({ cohort: cohort }, sel());

    function figures(s) {
      const set = (k, t) => { const d = root.querySelector('[data-figure="' + k + '"]'); if (d) { d.textContent = t; } };
      set('samples', count(s.samples) + (s.samples !== s.cohortSamples ? ' of ' + count(s.cohortSamples) : ''));
      set('responseRate', pct(s.responseRate));
      set('alteredRate', pct(s.alteredRate));
      set('responseIfAltered', pct(s.responseIfAltered));
      set('responseIfNot', pct(s.responseIfNot));
      const lab = root.querySelector('[data-figure-label="altered"]');
      if (lab) { lab.textContent = (s.gene || 'Gene') + ' altered'; }
      $('[data-showcase-selection]').textContent = s.selection + (s.gene ? ' · gene ' + s.gene : '');
      $('[data-showcase-clear]').hidden = !(st.cluster || st.box);
    }
    function refresh() {
      root.dataset.state = 'loading';
      const g = st.gene;
      const link = $('[data-showcase-protein]');
      if (link && g) { link.hidden = false; link.href = drishti_url(base + '/proteins/' + enc(g)); link.textContent = 'Open ' + g + ' in the protein workbench'; }
      return Promise.all([
        get('cohorts/' + enc(cohort) + '/summary', Object.assign({ gene: g }, sel())).then(figures),
        get('cohorts/' + enc(cohort) + '/oncoprint', Object.assign({ genes: 24, gene: g }, sel())).then((o) => chart('oncoprint',
          { kind: 'genomics.oncoprint', title: 'Who carries what', subtitle: o.selection + ' · one column a sample, sorted by the server', data: { rows: o.rows }, genes: o.genes, samples: o.samples, tracks: o.tracks },
          { height: Math.max(220, 60 + o.genes.length * 15 + o.tracks.length * 11), explain: explainer(() => ['cohorts/' + enc(cohort) + '/summary', Object.assign({ gene: g }, sel())]) })),
        g ? get('genes/' + enc(g) + '/lollipop', selCohort()).then((l) => chart('lollipop',
          { kind: 'genomics.lollipop', colourBy: 'scan', title: g + ': variants along the protein', subtitle: 'height = carriers in the selection · colour = the scan of the protein (' + (l.assay || 'none') + ')',
            data: { rows: l.rows }, id: 'VARIANT_ID', length: l.length, domains: l.domains },
          { height: 300, explain: explainer((ctx) => (ctx.ids && ctx.ids[0] ? ['explain/variant/' + enc(ctx.ids[0]), selCohort()] : null)) })) : null,
        get('cohorts/' + enc(cohort) + '/samples/map', sel()).then((m) => {
          const c = chart('map', { kind: 'scatter', title: 'Map of samples', subtitle: 'expression embedding, precomputed · drag a rectangle to pick patients', id: 'SAMPLE_ID', data: { rows: m.rows },
            x: { field: 'EMB_X', title: '' }, y: { field: 'EMB_Y', title: '' }, colour: 'CLUSTER', brush: 'rect' },
          { height: 320, link: { channel: 'genomics.samples' }, explain: explainer((ctx) => (ctx.rows && ctx.rows[0] ? ['explain/cluster/' + enc(ctx.rows[0].CLUSTER), { cohort: cohort }] : null)) });
          c.ready.then(() => c.setSelection(m.selected, { silent: true }));
          clusters(m.clusters);
          if (!c.wired) { c.wired = true; c.events.on('brushed', (b) => { st.box = [b.box.x[0], b.box.x[1], b.box.y[0], b.box.y[1]].map((v) => Math.round(v * 1000) / 1000).join(','); st.cluster = ''; tell('genomics.samples', b.ids); refresh(); }); }
        })
      ]).then(() => { root.dataset.state = 'ready'; }).catch(said);
    }
    function clusters(list) {
      const host = $('[data-showcase-clusters]');
      if (!host || host.childElementCount) { Array.from(host ? host.children : []).forEach((b) => b.setAttribute('aria-pressed', String(b.dataset.cluster === st.cluster))); return; }
      list.forEach((c) => {
        const b = document.createElement('button');
        b.type = 'button';
        b.className = 'btn-pill btn-ghost';
        b.dataset.cluster = c.CLUSTER;
        b.setAttribute('aria-pressed', String(c.CLUSTER === st.cluster));
        b.textContent = c.CLUSTER + ' · ' + count(c.SAMPLES) + ' · ' + pct(c.RESPONSE_RATE) + ' respond';
        b.addEventListener('click', () => { st.cluster = st.cluster === c.CLUSTER ? '' : c.CLUSTER; st.box = ''; if (charts.map) { charts.map.clearBrush(); } tell('genomics.samples', [st.cluster]); refresh(); });
        host.appendChild(b);
      });
    }
    function pickGene(g) {
      if (!g || g === st.gene) { return; }
      st.gene = g;
      tell('genomics.gene', [g]);
      refresh();
    }
    $('[data-showcase-clear]').addEventListener('click', () => { st.cluster = ''; st.box = ''; if (charts.map) { charts.map.clearBrush(); } refresh(); });
    get('associations', { trait: st.trait }).then((a) => {
      st.trait = a.trait;
      if (!st.gene && a.peaks.length) { st.gene = a.peaks[0].GENE; }
      const m = chart('manhattan', { kind: 'genomics.manhattan', title: 'Genome-wide association · ' + a.trait.toLowerCase(), subtitle: count(a.total) + ' sites, ' + count(a.drawn) + ' drawn · click a peak: every card follows',
        data: { rows: a.rows }, chromosomes: a.chromosomes, threshold: a.threshold, suggestive: a.suggestive, peaks: 4, onClick: 'select' }, { height: 300,
        explain: explainer((ctx) => (ctx.ids && ctx.ids[0] ? ['explain/site/' + enc(ctx.ids[0])] : null)) });
      m.events.on('markClicked', (ctx) => { const r = ctx.rows && ctx.rows[0]; if (r && r.GENE) { pickGene(r.GENE); } });
      return refresh();
    }).then(() => {
      if (charts.oncoprint) { charts.oncoprint.events.on('markClicked', (ctx) => { const r = ctx.rows && ctx.rows[0]; if (r && r.GENE) { pickGene(r.GENE); } }); }
    }).catch(said);
    if (mode === 'guided') { guided(); }
  }

  /* the guided mode (genomics C): drishti-core's Tour walks the board's own cards, four steps with the presenter's sentence */
  function guided() {
    new E.core.Tour(root, { steps: 4, onStep: () => Object.keys(charts).forEach((k) => { if (charts[k].engine) { charts[k].engine.resize(); } }) });
  }

  // ---------------------------------------------------------------- the workbench (protein A)
  function bench() {
    const id = root.dataset.genomicsId, params = new URLSearchParams(location.search);
    const st = { assay: params.get('assay') || '', residue: params.get('residue') ? Number(params.get('residue')) : null, aa: '', design: '', round: params.get('round') || '' };
    const note = (t) => { $('[data-showcase-note]').textContent = t || ''; };
    const words = () => { $('[data-showcase-selection]').textContent = id + (st.assay ? ' · ' + st.assay : '') + (st.residue ? ' · position ' + st.residue + (st.aa ? ' to ' + st.aa : '') : '') + (st.design ? ' · design ' + st.design : ''); };
    let structure = null;

    function scan() {
      return get('proteins/' + enc(id) + '/dms', { assay: st.assay }).then((d) => {
        st.assay = d.assay;
        assays(d.assays);
        const c = chart('dms', { kind: 'protein.dms', title: 'Deep mutational scan · ' + d.assay.toLowerCase(), subtitle: 'every position × every change · orange hurts, blue helps · 0 is the natural protein',
          data: { rows: d.rows }, residues: d.residues, aas: d.aas, assay: d.assay, onClick: 'select', window: window.innerWidth < 700 ? [0, 59] : undefined },
        { height: 380, explain: explainer((ctx) => (ctx.rows && ctx.rows[0] ? ['explain/cell/' + enc(ctx.rows[0].POS + ':' + ctx.rows[0].AA), { protein: id }] : null)) });
        if (!c.wired) { c.wired = true; c.events.on('markClicked', (ctx) => { const r = ctx.rows && ctx.rows[0]; if (r && r.POS) { pickResidue(r.POS, r.AA); } }); }
        words();
      });
    }
    function assays(list) {
      const host = $('[data-showcase-assays]');
      host.textContent = '';
      list.forEach((a) => {
        const b = document.createElement('button');
        b.type = 'button';
        b.className = 'btn-pill btn-ghost';
        b.textContent = a;
        b.setAttribute('aria-pressed', String(a === st.assay));
        b.addEventListener('click', () => { if (a !== st.assay) { st.assay = a; scan().catch(said); } });
        host.appendChild(b);
      });
    }
    function drawStructure(marked) {
      if (!structure) { return; }
      chart('backbone', { kind: 'protein.backbone', title: 'Structure · coloured by mean effect', subtitle: 'drag to turn · the active site and the selection drawn larger', data: { rows: structure.rows },
        selected: marked || [], turn: true }, { height: 320, explain: explainer((ctx) => (ctx.rows && ctx.rows[0] ? ['explain/residue/' + ctx.rows[0].POS, { protein: id }] : null)) });
    }
    function pickResidue(pos, aa) {
      st.residue = pos;
      st.aa = aa || '';
      tell('protein.residue', [String(pos)]);
      words();
      drawStructure([String(pos)]);
      get('proteins/' + enc(id) + '/residues/' + pos).then((r) => {
        chart('residue', { kind: 'bar', title: 'Position ' + pos + ' (' + r.AA + '): its changes · ' + st.assay.toLowerCase(), id: 'AA', data: { rows: r.options.filter((o) => o.ASSAY === st.assay) },
          x: { field: 'AA', title: 'Change to' }, y: { field: 'SCORE', title: 'Effect' }, legend: 'none', tone: 'link' },
        { height: 300, explain: () => Promise.resolve(r.explain).then((ex) => { records(ex); return ex; }) });
        note(r.explain.sentence);
      }).catch(said);
      return campaign();
    }
    function campaign() {
      return get('proteins/' + enc(id) + '/designs', { residue: st.residue, aa: st.aa }).then((c) => {
        const ch = chart('landscape', { kind: 'protein.landscape', title: 'Design campaign · ' + count(c.total) + ' designs, ' + c.rounds.length + ' rounds', subtitle: count(c.rows.length) + ' drawn · press Play the rounds',
          data: { rows: c.rows }, field: c.field, wildType: c.wildType, round: st.round || c.rounds[c.rounds.length - 1], interval: 1100 },
        { height: 340, explain: explainer((ctx) => (ctx.ids && ctx.ids[0] ? ['explain/design/' + enc(ctx.ids[0]), { protein: id }] : null)) });
        ch.ready.then(() => ch.setSelection(st.design ? [st.design] : (c.carrying || []), { silent: true }));
        if (!ch.wired) {
          ch.wired = true;
          ch.events.on('frameChanged', (f) => { st.round = String(f.value); tell('protein.round', [st.round]); leaderboard(); });
          ch.events.on('markClicked', (ctx) => { if (ctx.ids && ctx.ids[0]) { pickDesign(ctx.ids[0]); } });
        }
        if (st.residue) { note((c.carryingTotal || 0) + ' designs carry a change at position ' + st.residue + (st.aa ? ' to ' + st.aa : '') + '; they are ringed on the campaign.'); }
      });
    }
    function rounds() {
      return get('proteins/' + enc(id) + '/rounds').then((r) => chart('rounds', { kind: 'boxplot', title: 'Activity by round', subtitle: 'against the natural protein (1×)', id: 'ROUND', data: { rows: r.rows },
        category: 'ROUND', value: { field: 'MEDIAN', title: 'Activity (× natural)' }, boxes: { low: 'LOW', q1: 'Q1', median: 'MEDIAN', q3: 'Q3', high: 'HIGH', n: 'N' },
        annotations: [{ type: 'line', axis: 'y', value: 1, label: 'natural', tone: 'muted', showValue: false }] }, { height: 300, explain: () => Promise.resolve(r.explain) }));
    }
    function leaderboard() {
      return get('proteins/' + enc(id) + '/leaderboard', { round: st.round, n: 20 }).then((l) => grid('leaderboard', l.rows,
        [{ field: 'RANK', type: 'number' }, { field: 'DESIGN_ID', headerName: 'Design' }, { field: 'ROUND', type: 'number' }, { field: 'ACTIVITY', headerName: 'Activity (×)', type: 'number' },
          { field: 'DELTA_TM', headerName: 'ΔTm (°C)', type: 'number' }, { field: 'N_CHANGES', headerName: 'Changes', type: 'number' }, { field: 'CHANGES', headerName: 'Which' }],
        'DESIGN_ID', (row) => pickDesign(row.DESIGN_ID))).catch(said);
    }
    function pickDesign(did) {
      st.design = did;
      tell('protein.design', [did]);
      words();
      get('proteins/' + enc(id) + '/designs/' + enc(did)).then((d) => {
        note(d.explain.sentence);
        records(d.explain);
        if (charts.dms) { charts.dms.setSelection(d.cells, { silent: true }); }
        if (charts.landscape) { charts.landscape.setSelection([did], { silent: true }); }
        drawStructure(d.positions.map(String));
      }).catch(said);
    }
    Promise.all([scan(), get('proteins/' + enc(id) + '/structure').then((s) => { structure = s; drawStructure(st.residue ? [String(st.residue)] : []); }), rounds()])
      .then(() => (st.residue ? pickResidue(st.residue, '') : campaign()))
      .then(() => { if (!st.round && charts.landscape) { const f = charts.landscape.frame(); st.round = f ? String(f.value) : ''; } return leaderboard(); })
      .then(() => { root.dataset.state = 'ready'; if (root.dataset.genomicsRoute === 'designs' && charts.landscape) { charts.landscape.el.scrollIntoView({ block: 'center' }); } }).catch(said);
  }

  /* the data first: with a kind missing the page says which, in words, and asks nothing else (the packs are not loaded here) */
  function missing(st) {
    const need = (st.missing || []).filter((k) => (mode === 'bench' ? ['protein', 'residue', 'dms_score', 'design', 'design_mutation', 'design_round'] : ['gene', 'sample', 'variant', 'variant_call', 'association']).indexOf(k) >= 0);
    if (!need.length) { return false; }
    root.querySelectorAll('.drishti-showcase-grid, .drishti-showcase-figures, .drishti-showcase-guide, .drishti-showcase-bar').forEach((el) => { el.hidden = true; });
    const p = document.createElement('p');
    p.className = 'drishti-notice';
    p.dataset.tone = 'warn';
    p.setAttribute('role', 'status');
    p.dataset.showcaseMissing = need.join(',');
    p.textContent = 'This showcase needs the packs genomics and protein; this server has no rows of ' + need.join(', ') + '. Load them (drishti.py lake demo) and open the page again.';
    root.insertBefore(p, root.children[1] || null);
    root.dataset.state = 'needs-data';
    return true;
  }

  root.dataset.state = 'loading';
  Promise.all([E.visuals && E.visuals.genomics ? E.visuals.genomics.ready : E.load('drishti-chart'), E.load('drishti-grid'), get('showcase/status')]).then(([chartLib, , status]) => {
    C = chartLib;
    if (missing(status)) { return; }
    if (mode === 'bench') { bench(); } else { board(); }
  }).catch(said);
}());
