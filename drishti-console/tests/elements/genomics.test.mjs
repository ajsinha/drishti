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
/* The genomics module's domain library (modules/genomics/elements/drishti-genomics/) under Node: every kind is glue over drishti-chart's
   generic kinds (the owner's rule: the drawing capability is in the generic library), built from its own sample and from the shapes the
   module's server answers (GenomicsShowcase*: the association plot, the cohort matrix, the lollipop, the scan, the structure, the
   campaign). The drawn screens are tested in a browser (test_genomics_showcase_browser.py). */
import test from 'node:test';
import assert from 'node:assert/strict';
import { createRequire } from 'node:module';
import { join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';
import * as C from '../../web/static/elements/drishti-chart/src/drishti_index.js';

const require = createRequire(import.meta.url);
const LIB = join(dirname(fileURLToPath(import.meta.url)), '..', '..', '..', 'modules', 'genomics', 'elements', 'drishti-genomics');
globalThis.Drishti = { elements: { load: () => Promise.resolve(C) } };
require(join(LIB, 'drishti_genomics.js'));
const th = C.chartTheme({ theme: 'terminal' });
const env = { theme: th, fmt: C.formatterFor('number'), xfmt: C.formatterFor() };
const build = (spec) => { const s = C.normalizeSpec(spec); return C.kindOf(s.kind).build(s, C.rowsOf(s.data), env); };
const ALL = ['genomics.manhattan', 'genomics.oncoprint', 'genomics.lollipop', 'protein.dms', 'protein.backbone', 'protein.landscape'];

test('the library registers its six kinds, each with a sample the gallery draws', async () => {
  await globalThis.Drishti.elements.visuals.genomics.ready;
  assert.deepEqual(globalThis.Drishti.elements.visuals.genomics.kinds.slice().sort(), ALL.slice().sort());
  ALL.forEach((k) => {
    const def = C.kindOf(k);
    assert.ok(def && def.family === 'genomics' && typeof def.sample === 'function', k);
    const b = build(def.sample());
    assert.ok(b.option && b.table && b.summary, k);
  });
});

test('the association plot: chromosomes as segments, the line, the peak named, sites past the line in the bad tone', () => {
  const b = build(C.kindOf('genomics.manhattan').sample());
  const pts = b.option.series.find((s) => s.type === 'scatter' && !s.helper);
  assert.equal(pts.markArea.data.length, 3);
  assert.ok(pts.markLine.data.some((l) => l.yAxis === 7.3));
  assert.deepEqual(b.option.series.find((s) => s.name === '__labels').data[0].name, 'HLXK7');
  assert.ok(pts.data.filter((d) => d.value[1] >= 7.3).every((d) => d.itemStyle.color === C.toneColour(th, 'bad')));
  assert.match(b.summary, /3 chromosomes/);
});

test('the cohort matrix: category cells in the server\'s order and the clinical tracks over the samples', () => {
  const spec = C.kindOf('genomics.oncoprint').sample();
  const b = build(spec);
  assert.deepEqual(b.option.yAxis[0].data, spec.genes);
  assert.deepEqual(b.option.xAxis[0].data, spec.samples);
  assert.equal(b.option.xAxis[0].axisLabel.show, false);
  assert.equal(b.option.series.filter((s) => s.track).length, 2);
  assert.ok(b.legend.some((k) => k.name === 'missense') && b.legend.some((k) => k.name === 'responder'));
});

test('the lollipop: by impact, or by the scan\'s measured effect; domains as bands', () => {
  const spec = C.kindOf('genomics.lollipop').sample();
  const scan = build(spec), impact = build(Object.assign({}, spec, { colourBy: 'impact' }));
  assert.deepEqual(scan.legend.map((k) => k.name), ['damaging', 'intermediate', 'tolerated', 'not measured']);
  assert.deepEqual(impact.legend.map((k) => k.name), ['HIGH', 'MODERATE', 'LOW']);
  assert.equal(scan.option.series[1].markArea.data.length, 2);
});

test('the protein kinds: the scan with its three tracks, the trace flat without WebGL, the campaign in frames over the surface', () => {
  const d = build(C.kindOf('protein.dms').sample());
  assert.deepEqual(d.option.series.filter((s) => s.track).map((s) => s.name), ['Fold', 'Burial', 'Active site']);
  assert.equal(d.option.visualMap.inRange.color, th.diverging);
  const t = build(C.kindOf('protein.backbone').sample());
  assert.ok(t.flat && /drawn flat/.test(t.summary) && /2 marked/.test(t.summary));
  const l = C.normalizeSpec(C.kindOf('protein.landscape').sample());
  const built = C.kindOf('protein.landscape').build(l, C.rowsOf(l.data), env);
  assert.equal(built.option.series[0].name, '__surface');
  assert.ok(l.kind === 'protein.landscape');
  const s = C.kindOf('protein.landscape').build(l, C.rowsOf(l.data), env);
  assert.ok(s.option.series[1].markPoint.data.some((p) => p.label.formatter === 'natural'));
});
