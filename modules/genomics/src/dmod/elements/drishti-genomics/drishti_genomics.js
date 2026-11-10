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
/* drishti-genomics 0.1.0: the genomics module's domain library on Drishti Elements (KERNEL_AND_MODULES.md section 11.4; the custom-visual
   SDK, DEVELOPER_GUIDE "A module's own visual"). It ships inside the module's .dmod (elements/drishti-genomics/), is served by the
   console at /m/genomics/elements/drishti-genomics/<module version>/ while the module is on, and goes with it.

   One visual, registered in the Elements' visual registry (drishti-chart's kinds: registerKind) and built from drishti-chart's own
   primitives, so it gets the card, theme, tooltips, Explain, the data table, export and the keyboard like every kind:

     genomics.lollipop   a protein's variants: one stem and head per variant at its amino acid position (PROTEIN_POS), the head's
                         height the carriers (CARRIERS), its colour the impact (HIGH, MODERATE, LOW), the protein's domains as bands

   Nothing is drawn by hand (no canvas, svg or table of its own) and no colour is named: tones come from the theme. */
(function (root) {
  'use strict';
  const NS = (root.Drishti = root.Drishti || {});
  const E = (NS.elements = NS.elements || {});
  const KIND = 'genomics.lollipop';
  const IMPACT_TONES = { HIGH: 'bad', MODERATE: 'warn', LOW: 'muted' };

  /** A sample with data, for the gallery and the studio: synthetic variants along a 400 amino acid protein with two domains. */
  function sample() {
    const rows = [[24, 3, 'HIGH'], [61, 1, 'LOW'], [118, 5, 'MODERATE'], [175, 9, 'HIGH'], [176, 2, 'MODERATE'], [245, 7, 'HIGH'],
      [248, 4, 'MODERATE'], [273, 6, 'HIGH'], [306, 1, 'LOW'], [337, 2, 'MODERATE']]
      .map((r, i) => ({ VARIANT_ID: 'HX-S' + (i + 1), PROTEIN_POS: r[0], CARRIERS: r[1], IMPACT: r[2] }));
    return { kind: KIND, title: 'Variants along TP53 (synthetic)', data: { rows: rows }, id: 'VARIANT_ID', length: 393,
      domains: [{ NAME: 'DNA binding', FROM: 102, TO: 292 }, { NAME: 'Tetramerisation', FROM: 325, TO: 356 }] };
  }

  /**
   * Builds the lollipop from drishti-chart's scatter (the heads, by impact), its annotation bands (the domains) and a thin bar series
   * (the stems): spec x (default PROTEIN_POS), y (CARRIERS), colour (IMPACT), length (the protein's), domains [{NAME, FROM, TO}].
   */
  function buildLollipop(C, spec, rows, env) {
    const xf = C.fieldOf(spec.x) || 'PROTEIN_POS', yf = C.fieldOf(spec.y) || 'CARRIERS', by = (spec.colour && spec.colour.field) || 'IMPACT';
    const order = ['HIGH', 'MODERATE', 'LOW'].filter((k) => rows.some((r) => String(r[by]) === k));
    const rest = C.members(rows, by).filter((k) => order.indexOf(k) < 0);
    const bands = (spec.domains || []).map((d) => ({ type: 'band', axis: 'x', from: C.num(d.FROM), to: C.num(d.TO), label: d.NAME, tone: 'accent' }));
    const s = Object.assign({}, spec, {
      kind: 'scatter',
      x: Object.assign({ field: xf, title: 'Protein position (aa)', min: 0, max: C.num(spec.length) || undefined }, typeof spec.x === 'object' ? spec.x : {}),
      y: Object.assign({ field: yf, title: 'Carriers', min: 0 }, typeof spec.y === 'object' ? spec.y : {}),
      colour: { field: by, domain: order.concat(rest) },
      colourWhen: order.map((k) => ({ when: by + " == '" + k + "'", tone: IMPACT_TONES[k] })),
      annotations: (spec.annotations || []).concat(bands)
    });
    const built = C.buildScatter(s, rows, env);
    built.option.series.forEach((x) => {                     // each impact's heads and legend entry in its tone (HIGH bad, MODERATE warn, LOW muted)
      if (IMPACT_TONES[x.name]) { x.itemStyle = Object.assign({}, x.itemStyle, { color: C.toneColour(env.theme, IMPACT_TONES[x.name]) }); }
    });
    built.legend = built.legend.map((l) => (IMPACT_TONES[l.name] ? Object.assign({}, l, { colour: C.toneColour(env.theme, IMPACT_TONES[l.name]) }) : l))
      .sort((a, b) => (order.indexOf(a.name) < 0 ? 99 : order.indexOf(a.name)) - (order.indexOf(b.name) < 0 ? 99 : order.indexOf(b.name)));
    const stems = { type: 'bar', name: 'stems', barWidth: 1, silent: true, z: 1, tooltip: { show: false },
      itemStyle: { color: C.toneColour(env.theme, 'muted') },
      data: rows.filter((r) => C.num(r[xf]) !== null && C.num(r[yf]) !== null).map((r) => [C.num(r[xf]), C.num(r[yf])]) };
    built.option.series = [stems].concat(built.option.series);
    built.summary = 'Lollipop chart' + (spec.title ? ' of ' + spec.title : '') + ': ' + rows.length + ' variants along a protein'
      + (spec.length ? ' of ' + spec.length + ' amino acids' : '') + ', ' + (spec.domains || []).length + ' domains';
    return built;
  }

  /** Registers the kind in drishti-chart's registry (once; a later call is a no-op). Returns the kinds registered now. */
  function install(C) {
    if (C.kindOf(KIND)) { return []; }
    C.registerKind(KIND, { build: (spec, rows, env) => buildLollipop(C, spec, rows, env), label: 'variant lollipop', family: 'genomics',
      cartesian: true, sample: sample, studio: ['zoom'] });
    return [KIND];
  }

  const ready = E.load ? E.load('drishti-chart').then((C) => { install(C); return C; }) : Promise.reject(new Error('drishti-core is not loaded'));
  E.visuals = E.visuals || {};
  E.visuals.genomics = { module: 'genomics', library: 'drishti-genomics', version: '0.1.0', kinds: [KIND], sample: sample, ready: ready };
  E.libs = E.libs || {};
  E.libs['drishti-genomics'] = Object.freeze({ library: 'drishti-genomics', version: '0.1.0', KIND: KIND, sample: sample, install: install, buildLollipop: buildLollipop });
}(typeof window !== 'undefined' ? window : globalThis));
