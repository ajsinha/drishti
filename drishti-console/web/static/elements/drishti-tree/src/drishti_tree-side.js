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
/* drishti-tree's side panel and its chrome's words (g6): the selected node (its label, kind and id, a further parent's mark ⧉, each
   measure with ◆ on a non-additive one, "Σ children" beside it where the measure asks, labelled as not a figure of the measure, the
   children's count), its actions (Explain, expand or collapse, expand everything below, zoom in on a partition, and in the edit mode
   "Move to" for the keyboard), the Explain sheet's place, the edit mode's proposal (its moves, Submit for review, Undo last, Discard),
   the legend of a measure's bands, and the status line. Built with drishti-core's element maker; nothing inline. */
import { h, toneColour } from '../../drishti-core/src/drishti_index.js';
import { selectElement } from '../../drishti-inputs/src/drishti_index.js';

/**
 * The side panel's content for a Tree.
 * @param {Object} t the Tree (model, selected, editing, mode, theme, text(), o)
 * @returns {Node[]}
 */
export function sideContent(t) {
  const doc = t.doc, m = t.model, out = [];
  if (t.editing) { out.push(proposalBox(t)); }
  const n = t.selected ? m.node(t.selected) : null;
  if (!n) {
    out.push(h(doc, 'p', { cls: 'dt-empty', text: t.o.emptyText || 'Select a node to see its figures, its place in the tree and Explain.' }));
    out.push(legend(t));
    return out.filter(Boolean);
  }
  out.push(h(doc, 'div', { cls: 'dt-ttl', text: n.label }));
  out.push(h(doc, 'div', { cls: 'dt-subt', text: [n.kind, n.id].filter(Boolean).join(' · ') + (n.also.length ? ' · ⧉ ' + (n.also.length + 1) + ' parents' : '') + (n.moved ? ' · proposed' : '') }));
  const kv = [];
  m.measures.forEach((ms) => {
    const v = n.values[ms.field];
    kv.push(h(doc, 'span', { cls: 'dt-k' }, [ms.label, ms.additive === false ? h(doc, 'span', { cls: 'dt-dia', text: ' ◆', attrs: { title: 'not additive' } }) : null]));
    kv.push(h(doc, 'span', { cls: 'dt-v', text: v === undefined || v === null ? (m.isPending(n.id, ms.field) ? '…' : '–') : t.text(ms.field, v) }));
    if (ms.childSum && n.children.length) {
      const s = m.childSum(n.id, ms.field);
      kv.push(h(doc, 'span', { cls: 'dt-k', text: 'Σ children (not a ' + ms.label + ')' }));
      kv.push(h(doc, 'span', { cls: 'dt-v dt-dim', text: s === null ? '…' : t.text(ms.field, s) }));
    }
  });
  kv.push(h(doc, 'span', { cls: 'dt-k', text: 'Children' }), h(doc, 'span', { cls: 'dt-v', text: String(n.children.length) }));
  out.push(h(doc, 'div', { cls: 'dt-kv' }, kv));
  const acts = [btn(doc, 'explain', 'Explain', true)];
  if (n.children.length) {
    acts.push(btn(doc, 'toggle', m.isOpen(n.id) ? 'Collapse' : 'Expand'), btn(doc, 'expand-below', 'Expand all below'));
    if (t.mode === 'sunburst' || t.mode === 'icicle') { acts.push(btn(doc, 'zoom-in', 'Zoom in')); }
  }
  if ((t.mode === 'sunburst' || t.mode === 'icicle') && t.focus && t.focus !== (m.roots[0] && m.roots[0].id)) { acts.push(btn(doc, 'zoom-out', 'Zoom out')); }
  out.push(h(doc, 'div', { cls: 'dt-acts' }, acts));
  out.push(t.explainBox);
  if (t.editing && n.parent) {
    const options = m.all().filter((x) => !m.moveProblem(n.id, x.id));
    const sel = selectElement(doc, { label: 'Move ' + n.label + ' to a new parent', placeholder: 'a new parent…',            // drishti-inputs' Select (its change reaches the tree's own)
      options: options.map((x) => ({ value: x.id, label: m.breadcrumbs(x.id).join(' › ') })) }, { class: 'dt-pick', 'data-ctl': 'move-to' });
    out.push(h(doc, 'h3', { cls: 'dt-sec', text: 'Move to (proposal)' }), h(doc, 'div', { cls: 'dt-acts' }, [sel]));
  }
  out.push(legend(t));
  return out.filter(Boolean);
}

function btn(doc, act, text, pri) { return h(doc, 'button', { cls: 'dt-btn' + (pri ? ' dt-pri' : ''), text: text, attrs: { type: 'button' }, data: { act: act } }); }

/** @private the edit mode's proposal: one draft of every move, for another person to approve */
function proposalBox(t) {
  const doc = t.doc, p = t.model.proposal();
  if (!p.moves.length) {
    return h(doc, 'div', { cls: 'dt-prop' }, [h(doc, 'h4', { text: 'Edit mode: propose a change' }),
      h(doc, 'p', { text: (t.mode === 'grid' || t.mode === 'org' ? 'Drag a node onto its new parent, or select it and pick its new parent below.' : 'Select a node and pick its new parent below.') +
        ' Moves collect here as one proposal; another person approves it (four eyes) before anything changes.' })]);
  }
  return h(doc, 'div', { cls: 'dt-prop' }, [
    h(doc, 'h4', { text: 'Proposal · ' + p.moves.length + ' move' + (p.moves.length > 1 ? 's' : '') + ' · draft' }),
    h(doc, 'ol', {}, p.moves.map((mv) => h(doc, 'li', {}, [mv.label + ': ' + mv.fromPath[mv.fromPath.length - 1] + ' → ', h(doc, 'b', { text: mv.toPath[mv.toPath.length - 1] })]))),
    t.o.proposalNote ? h(doc, 'p', { cls: 'dt-who', text: t.o.proposalNote }) : null,
    h(doc, 'div', { cls: 'dt-acts' }, [btn(doc, 'submit', 'Submit for review', true), btn(doc, 'undo', 'Undo last'), btn(doc, 'discard', 'Discard')])
  ]);
}

/** @private the bands of the measure that colours the nodes, and what ◆ means */
function legend(t) {
  const doc = t.doc, m = t.model, out = [];
  const cm = t.colourBy && m.measure(t.colourBy);
  if (cm && cm.bar && cm.bar.bands) {
    let lo = null;
    const items = cm.bar.bands.map((b, i) => {
      const words = i === cm.bar.bands.length - 1 && lo !== null ? '≥ ' + t.text(cm.field, lo) : (lo === null ? '< ' : t.text(cm.field, lo) + ' – ') + t.text(cm.field, b.to);
      lo = b.to;
      return h(doc, 'span', { cls: 'dt-lg' }, [h(doc, 'span', { cls: 'dt-sw', style: { 'background-color': toneColour(t.theme, b.tone) } }), words]);
    });
    out.push(h(doc, 'h3', { cls: 'dt-sec', text: cm.label }), h(doc, 'div', { cls: 'dt-legend' }, items));
  }
  if (m.measures.some((x) => x.additive === false)) {
    out.push(h(doc, 'p', { cls: 'dt-note' }, [h(doc, 'span', { cls: 'dt-dia', text: '◆ ' }), 'not additive: a parent\'s figure comes from the server, never a sum of its children.']));
  }
  return out.length ? h(doc, 'div', { cls: 'dt-legend-box' }, out) : null;
}

/**
 * The status line's parts for a Tree.
 * @param {Object} t the Tree
 * @returns {Node[]}
 */
export function statusParts(t) {
  const doc = t.doc, m = t.model, parts = [];
  parts.push(h(doc, 'span', { text: (t.o.title ? t.o.title + ' · ' : '') + m.size() + ' nodes' + (t.mode === 'grid' || t.mode === 'org' ? ' · ' + m.visible().length + ' shown' : '') }));
  if (t.hits.size || t.query) { parts.push(h(doc, 'span', { text: t.hits.size + ' found' })); }
  const shared = m.all().filter((n) => n.also.length).length;
  if (shared) { parts.push(h(doc, 'span', { text: shared + ' with two or more parents ⧉' })); }
  const p = m.moves.length;
  if (p) { parts.push(h(doc, 'span', { cls: 'dt-warn', text: 'proposal: ' + p + ' move' + (p > 1 ? 's' : '') + ', not live' })); }
  if (t.waiting) { parts.push(h(doc, 'span', { text: 'asking the server…' })); }
  if (t.failed) { parts.push(h(doc, 'span', { cls: 'dt-warn', text: t.failed })); }
  (t.o.status || []).forEach((s) => parts.push(h(doc, 'span', { text: String(s) })));
  if (m.measures.some((x) => x.additive === false)) { parts.push(h(doc, 'span', { cls: 'dt-right', text: '◆ not additive · figures from the server' })); }
  return parts;
}
