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
/* drishti-genomics (at the one Drishti Elements version): the genomics module's domain library on Drishti Elements (KERNEL_AND_MODULES.md section 11.4; the custom-visual
   SDK, DEVELOPER_GUIDE "A module's own visual"). It ships inside the module (elements/drishti-genomics/), is served by the console at
   /m/genomics/elements/drishti-genomics/<module version>/ while the module is on, and goes with it.

   Domain glue only: each kind maps the genomics module's answers and words onto drishti-chart's generic kinds and options (the owner's
   rule: every drawing capability lives in the generic libraries; Drishti Elements' docs/DESIGN.md), so the card, theme, tooltips, Explain, the
   data table, export, the keyboard and linked selection come with them, and nothing is drawn here.

     genomics.manhattan   the association plot: drishti-chart's segmented scatter (the chromosomes as segments, the genome-wide and
                          suggestive thresholds, the tallest peaks named by their gene)
     genomics.oncoprint   the cohort matrix: drishti-chart's matrix (the alteration of each gene in each sample, in the server's order,
                          with clinical tracks over the samples)
     genomics.lollipop    a protein's variants: drishti-chart's lollipop, the head's colour the impact or, given the scan (EFFECT), what
                          that exact change does in the laboratory; the protein's domains as bands

     protein.dms          the deep mutational scan: a diverging heatmap of every position by every amino acid with the fold, burial and
                          active-site tracks over the positions (heatmap tracks)
     protein.backbone     the structure: drishti-chart's trace3d (one point per residue in chain order, coloured by the mean effect; flat
                          where WebGL is off)
     protein.landscape    the design campaign: designs over the fitness surface, round by round (frames, Play), the natural protein marked

   No colour is named: tones come from the theme. */
(function (root) {
  'use strict';
  const NS = (root.Drishti = root.Drishti || {});
  const E = (NS.elements = NS.elements || {});
  const VERSION = '0.1.0';
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
    const t = C.num(spec.threshold) || 7.3;
    const s = Object.assign({}, spec, { kind: 'segmented', id: spec.id || 'VARIANT_ID', x: Object.assign({ field: 'X', title: 'Chromosome' }, spec.x), y: Object.assign({ field: 'NEG_LOG10_P', title: '−log₁₀ p', min: 0 }, spec.y),
      segments: { values: (spec.chromosomes || []).map((c) => ({ name: String(c.CHROM), from: C.num(c.FROM), to: C.num(c.TO) })), tones: ['link', 'muted'] },
      thresholds: [{ value: t, label: 'genome-wide', tone: 'bad' }].concat(spec.suggestive ? [{ value: C.num(spec.suggestive), label: 'suggestive', tone: 'muted' }] : []),
      labelTop: { field: 'GENE', n: spec.peaks || 4 } });
    const b = C.buildSegmented(s, rows, env);
    b.summary = 'Association plot' + (spec.title ? ' of ' + spec.title : '') + ': ' + rows.length + ' sites drawn on ' + (spec.chromosomes || []).length + ' chromosomes, ' + rows.filter((r) => C.num(r.NEG_LOG10_P) >= t).length + ' past the genome-wide line';
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
    const s = Object.assign({}, spec, { kind: 'matrix', id: spec.id || 'CELL_ID',
      x: { field: 'SAMPLE_ID', domain: spec.samples, labels: false, title: 'Samples' }, y: { field: 'GENE', domain: spec.genes },
      value: { field: 'ALTERATION', order: ALTERATION_ORDER, tones: ALTERATION_TONES, title: 'Alteration' },
      tracks: (spec.tracks || []).map((t) => Object.assign({ tones: { responder: 'good', 'non-responder': 'muted' } }, t)) });
    const b = C.buildMatrix(s, rows.map((r) => Object.assign({ CELL_ID: r.SAMPLE_ID + ':' + r.GENE }, r)), env);
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
  const PROTEIN_KINDS = ['protein.dms', 'protein.backbone', 'protein.landscape'];
  const AAS = 'AVILMFWYCGPSTNQDEKRH*'.split('');
  const FOLD_TONES = { helix: 'accent', strand: 'link', loop: 'muted', H: 'accent', E: 'link', C: 'muted' };

  const seeded = (seed) => { let s = seed; return () => { s = (s * 16807) % 2147483647; return (s - 1) / 2147483646; }; };

  /** A small scan for the gallery: 40 positions, 21 changes, a helix and a strand, an active site. */
  function sampleDms() {
    const rnd = seeded(5), rows = [], residues = [];
    for (let p = 1; p <= 40; p++) {
      const fold = p < 14 ? 'helix' : p < 22 ? 'loop' : p < 32 ? 'strand' : 'loop', burial = Math.round(Math.abs(Math.sin(p / 3)) * 100) / 100, site = p === 18 || p === 27;
      residues.push({ POS: p, SECONDARY: fold, BURIAL: burial, ACTIVE_SITE: site });
      AAS.forEach((aa) => rows.push({ ID: p + ':' + aa, POS: p, AA: aa, SCORE: aa === '*' ? -2 : Math.round(((rnd() - 0.6) * 2 * (site ? 2 : 1) * (0.4 + burial)) * 100) / 100 }));
    }
    return { kind: 'protein.dms', title: 'Every change at every position (synthetic)', data: { rows: rows }, residues: residues };
  }

  /** The scan. spec: rows {ID, POS, AA, SCORE}, residues [{POS, SECONDARY, BURIAL, ACTIVE_SITE}], aas (the order of the changes), assay (its word), window [first, last] (the overview strip's columns; past 150 positions the strip is on). */
  function dms(C, spec, rows, env) {
    const res = spec.residues || [], val = (f) => { const o = {}; res.forEach((r) => { if (r[f] !== null && r[f] !== undefined) { o[String(r.POS)] = r[f]; } }); return o; };
    const site = {};
    res.forEach((r) => { if (r.ACTIVE_SITE === true || r.ACTIVE_SITE === 'true' || r.ACTIVE_SITE === 1) { site[String(r.POS)] = 'active site'; } });
    const positions = res.length ? res.map((r) => String(r.POS)) : undefined;
    const s = Object.assign({}, spec, { kind: 'heatmap', id: spec.id || 'ID', gap: 0,
      x: { field: 'POS', domain: positions, title: 'Position' }, y: { field: 'AA', domain: spec.aas || AAS, title: 'Change to' },
      value: { field: 'SCORE', title: spec.assay ? 'Effect on ' + spec.assay : 'Effect', format: 'number' }, scale: { type: 'diverging', mid: 0 },
      tracks: [{ title: 'Fold', values: val('SECONDARY'), type: 'category', tones: FOLD_TONES }, { title: 'Burial', values: val('BURIAL') }, { title: 'Active site', values: site, type: 'category', tones: { 'active site': 'bad' } }],
      trackSize: 8, zoom: spec.window ? { window: spec.window } : positions && positions.length > 150 ? true : undefined });
    const b = C.buildHeatmap(s, rows, env);
    b.summary = 'Deep mutational scan' + (spec.title ? ' of ' + spec.title : '') + ': ' + (positions ? positions.length : '') + ' positions by ' + (spec.aas || AAS).length + ' changes, ' + rows.length + ' measurements';
    return b;
  }

  /** A small fold for the gallery: a helix, a turn and a strand. */
  function sampleBackbone() {
    const rows = [];
    for (let p = 1; p <= 60; p++) {
      const a = p * 1.75, h = p <= 30;
      rows.push({ POS: p, X: h ? Math.cos(a) * 2.3 : 8 + (p - 30) * 0.2, Y: h ? Math.sin(a) * 2.3 : Math.sin(p) * 0.6, Z: h ? p * 1.5 : 45 - (p - 30) * 1.4, MEAN_ACTIVITY: Math.round(Math.sin(p / 6) * 100) / 100, ACTIVE_SITE: p === 12 || p === 44 });
    }
    return { kind: 'protein.backbone', title: 'The backbone, coloured by mean effect (synthetic)', data: { rows: rows } };
  }

  /** The structure. spec: rows {POS, X, Y, Z, MEAN_ACTIVITY, ACTIVE_SITE}, selected (positions marked besides the active site), turn. */
  function backbone(C, spec, rows, env) {
    const marked = rows.filter((r) => r.ACTIVE_SITE === true || r.ACTIVE_SITE === 'true' || r.ACTIVE_SITE === 1).map((r) => String(r.POS)).concat((spec.selected || []).map(String));
    const s = Object.assign({}, spec, { kind: 'trace3d', id: 'POS', x: 'X', y: 'Y', z: 'Z', order: 'POS', turn: spec.turn !== false, selected: marked, fallback: spec.fallback || 'flat',
      colour: { field: spec.colourField || 'MEAN_ACTIVITY', scale: 'diverging', mid: 0, title: 'Mean effect', legend: [{ name: 'hurts', value: -1 }, { name: 'neutral', value: 0 }, { name: 'helps', value: 1 }] } });
    const b = C.buildTrace3d(s, rows, env);
    b.summary = (b.flat ? 'Structure, drawn flat' : 'Structure, a 3D backbone trace') + (spec.title ? ' of ' + spec.title : '') + ': ' + rows.length + ' residues, ' + marked.length + ' marked';
    return b;
  }

  /** A small campaign for the gallery: four rounds climbing a surface. */
  function sampleLandscape() {
    const rnd = seeded(9), rows = [], field = [];
    for (let r = 1; r <= 4; r++) { for (let i = 0; i < 40; i++) { const t = r / 4; rows.push({ DESIGN_ID: 'D' + r + '-' + i, ROUND: r, EMB_X: Math.round(((rnd() - 0.5) * 5 * (1 - t) + 2 * t) * 100) / 100, EMB_Y: Math.round(((rnd() - 0.5) * 5 * (1 - t) + 1.5 * t) * 100) / 100, ACTIVITY: Math.round((1 + 12 * t * rnd()) * 100) / 100 }); } }
    for (let x = -3; x <= 3; x += 0.5) { for (let y = -3; y <= 3; y += 0.5) { field.push({ x: x, y: y, value: Math.round(15 * Math.exp(-((x - 2) ** 2 + (y - 1.5) ** 2) / 3) * 100) / 100 }); } }
    return { kind: 'protein.landscape', title: 'Design rounds climbing the landscape (synthetic)', data: { rows: rows }, field: { rows: field, cell: [0.5, 0.5] }, wildType: { EMB_X: -2, EMB_Y: -2 }, frames: landscapeFrames({}) };
  }

  /** The campaign's frames, one a round: drishti-chart's frame bar (Play, the slider) reads them from the chart's spec, so a page gives
   *  the spec these (spec.round shown first, spec.interval, spec.trail false for one round at a time; spec.frames overrides). */
  function landscapeFrames(spec) {
    const o = spec || {};
    return Object.assign({ field: 'ROUND', label: 'Round', mode: o.trail === false ? 'only' : 'upto', interval: o.interval || 900, at: o.round, play: true, playText: 'Play the rounds' }, o.frames);
  }

  /** The campaign. spec: rows {DESIGN_ID, ROUND, EMB_X, EMB_Y, ACTIVITY}, field {rows: [{x, y, value}], cell}, wildType {EMB_X, EMB_Y}, round (shown first), interval. */
  function landscape(C, spec, rows, env) {
    const wt = spec.wildType;
    const s = Object.assign({}, spec, { kind: 'scatter', id: spec.id || 'DESIGN_ID', x: { field: 'EMB_X', title: 'Design map (precomputed)' }, y: { field: 'EMB_Y', title: '' },
      size: { field: 'ACTIVITY', title: 'Activity (× natural)' }, tone: 'link', opacity: 0.8,
      frames: landscapeFrames(spec),
      surface: spec.field && spec.field.rows ? { rows: spec.field.rows, cell: spec.field.cell || [1, 1], opacity: 0.45 } : undefined,
      annotations: (spec.annotations || []).concat(wt ? [{ type: 'point', x: C.num(wt.EMB_X), y: C.num(wt.EMB_Y), label: 'natural', tone: 'warn' }] : []) });
    const b = C.buildScatter(s, rows, env);
    b.summary = 'Design campaign' + (spec.title ? ' of ' + spec.title : '') + ': ' + rows.length + ' designs on the fitness landscape';
    b.legend = [];
    return b;
  }

  function installProtein(C) {
    const defs = {
      'protein.dms': { build: (s, r, e) => dms(C, s, r, e), label: 'mutational scan', sample: sampleDms, cartesian: false },
      'protein.backbone': { build: (s, r, e) => backbone(C, s, r, e), label: 'backbone trace', sample: sampleBackbone, cartesian: false, prepare: C.ensure3d },
      'protein.landscape': { build: (s, r, e) => landscape(C, s, r, e), label: 'design landscape', sample: sampleLandscape, cartesian: true }
    };
    return PROTEIN_KINDS.filter((k) => {
      if (C.kindOf(k)) { return false; }
      C.registerKind(k, Object.assign({ family: 'genomics' }, defs[k]));
      return true;
    });
  }

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

  const ready = E.load ? E.load('drishti-chart').then((C) => { install(C); installProtein(C); return C; }) : Promise.reject(new Error('drishti-core is not loaded'));
  E.visuals = E.visuals || {};
  const was = E.visuals.genomics;
  E.visuals.genomics = { module: 'genomics', library: 'drishti-genomics', version: VERSION, kinds: (was ? was.kinds : []).filter((k) => KINDS.indexOf(k) < 0 && PROTEIN_KINDS.indexOf(k) < 0).concat(KINDS, PROTEIN_KINDS),
    sample: sampleLollipop, ready: was ? Promise.all([was.ready, ready]).then(() => ready) : ready };
  E.libs = E.libs || {};
  E.libs['drishti-genomics'] = Object.freeze(Object.assign({}, E.libs['drishti-genomics'], { library: 'drishti-genomics', version: VERSION, KINDS: KINDS, install: install, PROTEIN_KINDS: PROTEIN_KINDS, installProtein: installProtein,
    sample: sampleLollipop, sampleManhattan: sampleManhattan, sampleOncoprint: sampleOncoprint, sampleDms: sampleDms, sampleBackbone: sampleBackbone, sampleLandscape: sampleLandscape, landscapeFrames: landscapeFrames, tones: { IMPACT_TONES, ALTERATION_TONES, EFFECT_TONES } }));
}(typeof window !== 'undefined' ? window : globalThis));
