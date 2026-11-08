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
/* The lineage DAG, a preset of the layered layout: stages left to right (by default sources → tables → models → reports; a host names
   its own), each node in its stage's column whatever its longest path, a shape and a colour per stage, status badges (ok, warn or stale,
   bad or failed, running) on the nodes, arrows the way the data flows. lineageOptions gives the graph's options; a host adds its hooks
   (explain, onLink) and its mode. */

/** The stages of a lineage by default, in their order. */
export const LINEAGE_STAGES = Object.freeze(['source', 'table', 'model', 'report']);

const STAGE_LOOK = { source: { shape: 'triangle', colour: 'link' }, table: { shape: 'square', colour: 'accent' }, model: { shape: 'diamond', colour: 'good' }, report: { shape: 'circle', colour: 'warn' } };
const SHAPES = ['triangle', 'square', 'diamond', 'circle', 'hexagon'];
const TONES = ['link', 'accent', 'good', 'warn', 'muted'];

/**
 * A lineage: nodes with their stage and status, edges from what feeds to what is fed.
 * @typedef {Object} LineageSpec
 * @property {Array<{id: string, label: string, stage: string, status: string}>} nodes stage: one of stages (the column); status: a badge
 * @property {Array<{source: string, target: string, label: string}>} edges
 * @property {string[]} [stages] the columns, in order (default LINEAGE_STAGES)
 * @property {Object<string, string>} [stageLabels] each stage's words (default the stage, capitalised and plural)
 * @property {string} [title]
 */

/**
 * The graph's options for a lineage: the layered layout left to right with each stage a column, the stages as types (shape, colour,
 * words), directed edges, colour by stage.
 * @param {LineageSpec} spec
 * @param {Object} [more] options to add (mode, explain, onLink, height ...)
 * @returns {import('./drishti_graph-base.js').GraphOptions}
 */
export function lineageOptions(spec, more) {
  const stages = (spec.stages && spec.stages.length ? spec.stages : LINEAGE_STAGES).slice();
  (spec.nodes || []).forEach((n) => { if (n.stage && !stages.includes(n.stage)) { stages.push(n.stage); } });
  const word = (s) => (spec.stageLabels && spec.stageLabels[s]) || (s.charAt(0).toUpperCase() + s.slice(1) + (s.endsWith('s') ? '' : 's'));
  const types = {};
  stages.forEach((s, i) => {
    const look = STAGE_LOOK[s] || {};
    types[s] = { label: word(s), shape: look.shape || SHAPES[i % SHAPES.length], colour: look.colour || TONES[i % TONES.length] };
  });
  const nodes = (spec.nodes || []).map((n) => Object.assign({}, n, { type: n.stage || n.type || stages[0] }));
  return Object.assign({
    title: spec.title || 'Lineage',
    data: { directed: true, nodes, edges: (spec.edges || []).map((e) => Object.assign({}, e)) },
    layout: 'layered',
    layoutOptions: { direction: 'LR', rank: (n) => { const k = stages.indexOf(n.type); return k >= 0 ? k : null; } },
    types, colourBy: 'type'
  }, more || {});
}

/**
 * A lineage's health: how many nodes of each status, and the nodes downstream of a bad one (what its failure reaches).
 * @param {LineageSpec} spec
 * @returns {{counts: Object<string, number>, affected: string[]}}
 */
export function lineageHealth(spec) {
  const counts = {}, down = new Map(), bad = [];
  (spec.nodes || []).forEach((n) => { const s = n.status || 'ok'; counts[s] = (counts[s] || 0) + 1; down.set(n.id, []); if (s === 'bad' || s === 'failed' || s === 'error') { bad.push(n.id); } });
  (spec.edges || []).forEach((e) => { if (down.has(e.source)) { down.get(e.source).push(e.target); } });
  const seen = new Set(), queue = bad.slice();
  while (queue.length) { const id = queue.shift(); (down.get(id) || []).forEach((t) => { if (!seen.has(t)) { seen.add(t); queue.push(t); } }); }
  bad.forEach((id) => seen.delete(id));
  return { counts, affected: Array.from(seen) };
}
