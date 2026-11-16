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
/* drishti-genomics, the protein kinds: domain glue over drishti-chart's generic kinds and options, like drishti_genomics.js (the
   card, Explain, the table, export, the keyboard and linked selection come with them; nothing is drawn here).

     protein.dms        the deep mutational scan: a diverging heatmap of every position by every amino acid (orange hurts, blue helps,
                        around the natural protein's 0) with the fold, burial and active-site tracks over the positions (heatmap tracks)
     protein.backbone   the structure: drishti-chart's trace3d (one point per residue in chain order, coloured by the mean effect, the
                        active site and the selection drawn larger; flat where WebGL is off)
     protein.landscape  the design campaign: designs on the precomputed map over the fitness surface (drishti-chart's surface), stepped
                        round by round (frames, Play), the natural protein marked */
(function (root) {
  'use strict';
  const NS = (root.Drishti = root.Drishti || {});
  const E = (NS.elements = NS.elements || {});
  const VERSION = '0.1.0';
  const KINDS = ['protein.dms', 'protein.backbone', 'protein.landscape'];
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
    const s = Object.assign({}, spec, { kind: 'trace3d', id: 'POS', x: 'X', y: 'Y', z: 'Z', order: 'POS', turn: spec.turn !== false, selected: marked,
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
    return { kind: 'protein.landscape', title: 'Design rounds climbing the landscape (synthetic)', data: { rows: rows }, field: { rows: field, cell: [0.5, 0.5] }, wildType: { EMB_X: -2, EMB_Y: -2 } };
  }

  /** The campaign. spec: rows {DESIGN_ID, ROUND, EMB_X, EMB_Y, ACTIVITY}, field {rows: [{x, y, value}], cell}, wildType {EMB_X, EMB_Y}, round (shown first), interval. */
  function landscape(C, spec, rows, env) {
    const wt = spec.wildType;
    const s = Object.assign({}, spec, { kind: 'scatter', id: spec.id || 'DESIGN_ID', x: { field: 'EMB_X', title: 'Design map (precomputed)' }, y: { field: 'EMB_Y', title: '' },
      size: { field: 'ACTIVITY', title: 'Activity (× natural)' }, tone: 'link', opacity: 0.8,
      frames: { field: 'ROUND', label: 'Round', mode: spec.trail === false ? 'only' : 'upto', interval: spec.interval || 900, at: spec.round, play: true, playText: 'Play the rounds' },
      surface: spec.field && spec.field.rows ? { rows: spec.field.rows, cell: spec.field.cell || [1, 1], opacity: 0.45 } : undefined,
      annotations: (spec.annotations || []).concat(wt ? [{ type: 'point', x: C.num(wt.EMB_X), y: C.num(wt.EMB_Y), label: 'natural', tone: 'warn' }] : []) });
    const b = C.buildScatter(s, rows, env);
    b.summary = 'Design campaign' + (spec.title ? ' of ' + spec.title : '') + ': ' + rows.length + ' designs on the fitness landscape';
    b.legend = [];
    return b;
  }

  function install(C) {
    const defs = {
      'protein.dms': { build: (s, r, e) => dms(C, s, r, e), label: 'mutational scan', sample: sampleDms, cartesian: false },
      'protein.backbone': { build: (s, r, e) => backbone(C, s, r, e), label: 'backbone trace', sample: sampleBackbone, cartesian: false, prepare: C.ensure3d },
      'protein.landscape': { build: (s, r, e) => landscape(C, s, r, e), label: 'design landscape', sample: sampleLandscape, cartesian: true }
    };
    return KINDS.filter((k) => {
      if (C.kindOf(k)) { return false; }
      C.registerKind(k, Object.assign({ family: 'genomics' }, defs[k]));
      return true;
    });
  }

  const ready = E.load ? E.load('drishti-chart').then((C) => { install(C); return C; }) : Promise.reject(new Error('drishti-core is not loaded'));
  E.visuals = E.visuals || {};
  const was = E.visuals.genomics;
  E.visuals.genomics = { module: 'genomics', library: 'drishti-genomics', version: VERSION, kinds: (was ? was.kinds : []).filter((k) => KINDS.indexOf(k) < 0).concat(KINDS),
    sample: was ? was.sample : sampleDms, ready: was ? Promise.all([was.ready, ready]).then(() => ready) : ready };
  E.libs = E.libs || {};
  E.libs['drishti-genomics'] = Object.freeze(Object.assign({}, E.libs['drishti-genomics'], { library: 'drishti-genomics', version: VERSION, PROTEIN_KINDS: KINDS, installProtein: install,
    sampleDms: sampleDms, sampleBackbone: sampleBackbone, sampleLandscape: sampleLandscape }));
}(typeof window !== 'undefined' ? window : globalThis));
