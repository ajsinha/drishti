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
/* drishti-genomics 0.2.0: the genomics module's domain library on Drishti Elements (KERNEL_AND_MODULES.md section 11.4; the custom-visual
   SDK, DEVELOPER_GUIDE "A module's own visual"). It ships inside the module (elements/drishti-genomics/), is served by the console at
   /m/genomics/elements/drishti-genomics/<module version>/ while the module is on, and goes with it.

   Domain glue only: each kind maps the genomics module's answers and words onto drishti-chart's generic kinds and options (the owner's
   rule: every drawing capability lives in the generic libraries; ELEMENTS.md section 7.4), so the card, theme, tooltips, Explain, the
   data table, export, the keyboard and linked selection come with them, and nothing is drawn here.

     genomics.manhattan   the association plot: scatter with the chromosomes as segments (drishti-chart segments), the genome-wide and
                          suggestive lines (annotations), the tallest peaks named by their gene (labelTop), sites past the line in the
                          bad tone (colourWhen)
     genomics.oncoprint   the cohort matrix: a heatmap with category cells (the alteration of each gene in each sample, in the server's
                          order) and clinical tracks over the samples (heatmap tracks)
     genomics.lollipop    a protein's variants: drishti-chart's lollipop, the head's colour the impact or, given the scan (EFFECT), what
                          that exact change does in the laboratory; the protein's domains as bands

   The protein kinds (protein.dms, protein.backbone, protein.landscape) are in drishti_protein.js. No colour is named: tones come from the
   theme. */
(function (root) {
  'use strict';
  const NS = (root.Drishti = root.Drishti || {});
  const E = (NS.elements = NS.elements || {});
  const VERSION = '0.2.0';
  const KINDS = ['genomics.manhattan', 'genomics.oncoprint', 'genomics.lollipop'];
  /** The words of the module: a variant's impact, an alteration, a measured effect, each to a theme tone. */
  const IMPACT_TONES = { HIGH: 'bad', MODERATE: 'warn', LOW: 'muted' };
  const ALTERATION_TONES = { missense: 'link', truncating: 'bad', inframe: 'accent', splice: 'warn', amplification: 'pos', 'deep deletion': 'neg' };
  const ALTERATION_ORDER = ['missense', 'truncating', 'inframe', 'splice', 'amplification', 'deep deletion'];
  const EFFECT_TONES = { damaging: 'bad', intermediate: 'warn', tolerated: 'good', 'not measured': 'muted' };

  /** A small chromosome set and sites for the gallery: three chromosomes, a peak on the second. */
  function sampleManhattan() {
    const rows = [], chroms = [{ CHROM: '1', FROM: 0, TO: 1000 }, { CHROM: '2', FROM: 1000, TO: 1800 }, { CHROM: '3', FROM: 1800, TO: 2400 }];
    let s = 7;
    const rnd = () => { s = (s * 16807) % 2147483647; return (s - 1) / 2147483646; };
    for (let i = 0; i < 400; i++) {
      const x = Math.floor(rnd() * 2400), peak = Math.abs(x - 1300) < 40 ? 9 * (1 - Math.abs(x - 1300) / 40) : 0;
      rows.push({ VARIANT_ID: 'HX-' + i, X: x, NEG_LOG10_P: Math.round((-Math.log10(rnd()) * 0.8 + peak) * 100) / 100, GENE: peak > 4 ? 'HLXK7' : 'G' + (i % 40) });
    }
    return { kind: 'genomics.manhattan', title: 'Association with response (synthetic)', data: { rows: rows }, chromosomes: chroms, threshold: 7.3, suggestive: 5 };
  }

  /** The association plot. spec: rows {VARIANT_ID, X (the genome offset), NEG_LOG10_P, GENE}, chromosomes [{CHROM, FROM, TO}], threshold, suggestive, peaks (how many named). */
  function manhattan(C, spec, rows, env) {
    const t = C.num(spec.threshold) || 7.3, segs = (spec.chromosomes || []).map((c) => ({ name: String(c.CHROM), from: C.num(c.FROM), to: C.num(c.TO) }));
    const s = Object.assign({}, spec, { kind: 'scatter', id: spec.id || 'VARIANT_ID', x: Object.assign({ field: 'X', title: 'Chromosome' }, spec.x), y: Object.assign({ field: 'NEG_LOG10_P', title: '−log₁₀ p', min: 0 }, spec.y),
      segments: segs.length ? { values: segs, tones: ['link', 'muted'] } : undefined, labelTop: { field: 'GENE', n: spec.peaks || 4 },
      colourWhen: [{ when: 'NEG_LOG10_P >= ' + t, tone: 'bad' }],
      annotations: (spec.annotations || []).concat([{ type: 'line', axis: 'y', value: t, label: 'genome-wide', tone: 'bad', showValue: false }])
        .concat(spec.suggestive ? [{ type: 'line', axis: 'y', value: C.num(spec.suggestive), label: 'suggestive', tone: 'muted', showValue: false }] : []) });
    const b = C.buildScatter(s, rows, env);
    b.summary = 'Association plot' + (spec.title ? ' of ' + spec.title : '') + ': ' + rows.length + ' sites drawn on ' + segs.length + ' chromosomes, ' + rows.filter((r) => C.num(r.NEG_LOG10_P) >= t).length + ' past the genome-wide line';
    b.legend = [];
    return b;
  }

  /** A small cohort for the gallery: four genes over 40 samples with two tracks. */
  function sampleOncoprint() {
    const genes = ['HLXK7', 'TRP2', 'KRX1', 'MYB4'], samples = [], rows = [], resp = {}, cluster = {};
    for (let i = 0; i < 40; i++) { samples.push('S' + String(i + 1).padStart(2, '0')); }
    samples.forEach((sid, i) => {
      resp[sid] = i % 3 === 0 ? 'responder' : 'non-responder';
      cluster[sid] = 'C' + (1 + (i % 4));
      genes.forEach((g, j) => { if ((i * (j + 3)) % (5 + j) === 0) { rows.push({ SAMPLE_ID: sid, GENE: g, ALTERATION: ALTERATION_ORDER[(i + j) % 4] }); } });
    });
    return { kind: 'genomics.oncoprint', title: 'Who carries what (synthetic)', data: { rows: rows }, genes: genes, samples: samples,
      tracks: [{ title: 'Response', values: resp }, { title: 'Cluster', values: cluster }] };
  }

  /** The cohort matrix. spec: rows {SAMPLE_ID, GENE, ALTERATION} (the non-empty cells), genes and samples (the server's order), tracks [{title, values: {sample: value}}]. */
  function oncoprint(C, spec, rows, env) {
    const s = Object.assign({}, spec, { kind: 'heatmap', id: spec.id || 'CELL_ID', gap: 0,
      x: { field: 'SAMPLE_ID', domain: spec.samples, labels: false, title: 'Samples' }, y: { field: 'GENE', domain: spec.genes },
      value: { field: 'ALTERATION', type: 'category', order: ALTERATION_ORDER, tones: ALTERATION_TONES, title: 'Alteration' },
      tracks: (spec.tracks || []).map((t) => Object.assign({ axis: 'x', type: 'category', tones: { responder: 'good', 'non-responder': 'muted' } }, t)), trackSize: 9 });
    const keyed = rows.map((r) => Object.assign({ CELL_ID: r.SAMPLE_ID + ':' + r.GENE }, r));
    const b = C.buildHeatmap(s, keyed, env);
    b.summary = 'Cohort matrix' + (spec.title ? ' of ' + spec.title : '') + ': ' + (spec.genes || []).length + ' genes over ' + (spec.samples || []).length + ' samples, ' + rows.length + ' alterations';
    return b;
  }

  /** A sample with data, for the gallery and the studio: synthetic variants along a 400 amino acid protein with two domains. */
  function sampleLollipop() {
    const rows = [[24, 3, 'HIGH', 'damaging'], [61, 1, 'LOW', 'tolerated'], [118, 5, 'MODERATE', 'intermediate'], [175, 9, 'HIGH', 'damaging'], [176, 2, 'MODERATE', 'tolerated'],
      [245, 7, 'HIGH', 'damaging'], [248, 4, 'MODERATE', 'intermediate'], [273, 6, 'HIGH', 'damaging'], [306, 1, 'LOW', 'not measured'], [337, 2, 'MODERATE', 'tolerated']]
      .map((r, i) => ({ VARIANT_ID: 'HX-S' + (i + 1), PROTEIN_POS: r[0], CARRIERS: r[1], IMPACT: r[2], EFFECT: r[3] }));
    return { kind: 'genomics.lollipop', title: 'Variants along HLXK7 (synthetic)', data: { rows: rows }, id: 'VARIANT_ID', length: 393, colourBy: 'scan',
      domains: [{ NAME: 'Kinase', FROM: 102, TO: 292 }, { NAME: 'Tail', FROM: 325, TO: 356 }] };
  }

  /** The lollipop. spec: rows {PROTEIN_POS, CARRIERS, IMPACT, EFFECT}, length, domains [{NAME, FROM, TO}], colourBy 'impact' (default) or 'scan' (EFFECT). */
  function lollipop(C, spec, rows, env) {
    const scan = spec.colourBy === 'scan';
    const s = Object.assign({}, spec, { kind: 'lollipop', id: spec.id || 'VARIANT_ID',
      x: Object.assign({ field: 'PROTEIN_POS', title: 'Protein position (aa)' }, typeof spec.x === 'object' ? spec.x : {}),
      y: Object.assign({ field: 'CARRIERS', title: 'Carriers' }, typeof spec.y === 'object' ? spec.y : {}),
      colour: scan ? { field: 'EFFECT', type: 'category', tones: EFFECT_TONES, order: ['damaging', 'intermediate', 'tolerated', 'not measured'], title: 'Measured effect' }
        : { field: 'IMPACT', type: 'category', tones: IMPACT_TONES, order: ['HIGH', 'MODERATE', 'LOW'], title: 'Impact' },
      bands: (spec.domains || []).map((d) => ({ name: d.NAME, from: d.FROM, to: d.TO })) });
    const b = C.buildLollipop(s, rows, env);
    b.summary = 'Lollipop chart' + (spec.title ? ' of ' + spec.title : '') + ': ' + rows.length + ' variants along a protein' + (spec.length ? ' of ' + spec.length + ' amino acids' : '')
      + ', ' + (spec.domains || []).length + ' domains, coloured by ' + (scan ? 'the measured effect' : 'the impact');
    return b;
  }

  /** Registers the kinds in drishti-chart's registry (once; a later call is a no-op). Returns the kinds registered now. */
  function install(C) {
    const defs = {
      'genomics.manhattan': { build: (s, r, e) => manhattan(C, s, r, e), label: 'association plot', sample: sampleManhattan },
      'genomics.oncoprint': { build: (s, r, e) => oncoprint(C, s, r, e), label: 'cohort matrix', sample: sampleOncoprint },
      'genomics.lollipop': { build: (s, r, e) => lollipop(C, s, r, e), label: 'variant lollipop', sample: sampleLollipop }
    };
    return KINDS.filter((k) => {
      if (C.kindOf(k)) { return false; }
      C.registerKind(k, Object.assign({ family: 'genomics', cartesian: k !== 'genomics.oncoprint', studio: ['zoom'] }, defs[k]));
      return true;
    });
  }

  const ready = E.load ? E.load('drishti-chart').then((C) => { install(C); return C; }) : Promise.reject(new Error('drishti-core is not loaded'));
  E.visuals = E.visuals || {};
  const was = E.visuals.genomics;
  E.visuals.genomics = { module: 'genomics', library: 'drishti-genomics', version: VERSION, kinds: (was ? was.kinds : []).filter((k) => KINDS.indexOf(k) < 0).concat(KINDS),
    sample: sampleLollipop, ready: was ? Promise.all([was.ready, ready]).then(() => ready) : ready };
  E.libs = E.libs || {};
  E.libs['drishti-genomics'] = Object.freeze(Object.assign({}, E.libs['drishti-genomics'], { library: 'drishti-genomics', version: VERSION, KINDS: KINDS, install: install,
    sample: sampleLollipop, sampleManhattan: sampleManhattan, sampleOncoprint: sampleOncoprint, tones: { IMPACT_TONES, ALTERATION_TONES, EFFECT_TONES } }));
}(typeof window !== 'undefined' ? window : globalThis));
