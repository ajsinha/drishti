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
/* The graph's chrome in its three modes (g5, the product owner's pick: A the default, B and C modes of the same component): the shell
   with the toolbar (find, the path's two ends, the layout, export, the adjacency table), the canvas with its zoom buttons, minimap,
   badge and tooltip, and the status line; inspector (A) adds the rail (types, group, size, colour, the legend) and the inspector at the
   right; table (B) floats the controls on the canvas and puts a card at the node, with the Nodes, Edges and Path tables under it;
   terminal (C) puts the GRAPH> line above and the answers and function keys below. Built with drishti-core's h (no markup strings, a
   strict CSP holds); the drawing of the parts that change (rail, legend, inspector, status, answers) is here too. */
import { h, formatNumber } from '../../drishti-core/src/drishti_index.js';
import { FUNCTION_KEYS } from './drishti_graph-terminal.js';
import { selectElement } from '../../drishti-inputs/src/drishti_index.js';

/** The modes of the graph: inspector (g5 A, the default), table (B) and terminal (C). */
export const GRAPH_MODES = Object.freeze(['inspector', 'table', 'terminal']);

const LAYOUT_NAMES = { force: 'Force', layered: 'Layered', radial: 'Radial', circular: 'Circular', grouped: 'Grouped', preset: 'As given' };

function seg(doc, G, cls) {
  return h(doc, 'span', { cls: 'dn-seg ' + (cls || ''), attrs: { role: 'group', 'aria-label': 'Layout' } }, G.layouts.map((l) =>
    h(doc, 'button', { cls: 'dn-seg-b', text: LAYOUT_NAMES[l] || l, attrs: { type: 'button', 'data-layout': l, 'aria-pressed': String(G.state().layout === l) }, on: { click: () => G.setLayout(l) } })));
}

function picker(doc, G, what, label, values, current, set) {
  const now = String(current === null || current === undefined ? '' : current);         // drishti-inputs' Select
  const sel = selectElement(doc, { options: values, value: values.some(([v]) => v === now) ? now : undefined, label: label, search: 'auto' }, { class: 'dn-select', 'data-ctl': what });
  sel.component.on('change', (d) => set(d.value === '' || d.value === null ? null : d.value));
  return h(doc, 'span', { cls: 'dn-pick' }, [h(doc, 'span', { cls: 'dn-lbl', text: label }), sel]);
}

/** The group, size and colour pickers of the current fields. */
export function pickers(doc, G) {
  const f = G.fields(), st = G.state(), ml = (m) => G.measureLabel(m);
  return [
    picker(doc, G, 'group', 'Group', [['', 'none']].concat(f.attributes.map((a) => [a, a])), st.groupBy, (v) => G.setGroupBy(v)),
    picker(doc, G, 'size', 'Size', [['', 'one size']].concat(f.measures.map((m) => [m, ml(m)])), st.sizeBy, (v) => G.setSizeBy(v)),
    picker(doc, G, 'colour', 'Colour', f.attributes.map((a) => [a, a]).concat(f.measures.map((m) => [m, ml(m)])), st.colourBy || 'type', (v) => G.setColourBy(v))
  ];
}

/**
 * Builds the shell of a mode into the host; returns its parts by name.
 * @param {Object} G the Graph
 * @returns {Object<string, HTMLElement>}
 */
export function buildShell(G) {
  const doc = G.doc, mode = G.state().mode, P = {};
  const tool = (id, text, title, fn) => h(doc, 'button', { cls: 'dn-tool', text, attrs: { type: 'button', 'data-tool': id, title, 'aria-label': title }, on: { click: fn } });
  P.find = h(doc, 'input', { cls: 'dn-input dn-find', attrs: { type: 'search', placeholder: 'Find a node…', 'aria-label': 'Find a node', 'data-ctl': 'find' },
    on: { keydown: (e) => { if (e.key === 'Enter') { e.preventDefault(); G.findAndShow(P.find.value); } } } });
  P.from = h(doc, 'input', { cls: 'dn-input dn-end', attrs: { placeholder: 'Path from', 'aria-label': 'Path from', 'data-ctl': 'from' }, on: { keydown: (e) => { if (e.key === 'Enter') { G.pathFromInputs(); } } } });
  P.to = h(doc, 'input', { cls: 'dn-input dn-end', attrs: { placeholder: 'to', 'aria-label': 'Path to', 'data-ctl': 'to' }, on: { keydown: (e) => { if (e.key === 'Enter') { G.pathFromInputs(); } } } });
  P.title = h(doc, 'span', { cls: 'dn-title', text: G.o.title || '' });
  const exports = h(doc, 'span', { cls: 'dn-tools', attrs: { role: 'toolbar', 'aria-label': 'Graph tools' } }, [
    tool('png', 'PNG', 'Export as PNG', () => G.export('png')), tool('svg', 'SVG', 'Export as SVG', () => G.export('svg')), tool('data', 'Data', 'Export the data (CSV)', () => G.export('csv')),
    P.adjBtn = tool('table', '▦', 'The adjacency table (a text alternative)', () => G.toggleAdjacency()),
    tool('lasso', '◌', 'Lasso: draw round nodes to select them (or hold Alt and drag)', () => G.toggleLasso())]);
  P.lassoBtn = exports.querySelector('[data-tool="lasso"]');
  P.toolbar = h(doc, 'div', { cls: 'dn-tb', attrs: { role: 'toolbar', 'aria-label': 'Graph' } }, [P.title, P.find, h(doc, 'span', { cls: 'dn-div' }), P.from, P.to,
    h(doc, 'button', { cls: 'dn-btn', text: 'Path', attrs: { type: 'button', 'data-act': 'path' }, on: { click: () => G.pathFromInputs() } }),
    h(doc, 'button', { cls: 'dn-btn', text: '✕', attrs: { type: 'button', 'data-act': 'clearpath', title: 'Clear the path', 'aria-label': 'Clear the path' }, on: { click: () => G.clearPath() } }),
    h(doc, 'span', { cls: 'dn-div' }), mode === 'table' ? null : h(doc, 'span', { cls: 'dn-lbl', text: 'Layout' }), mode === 'table' ? null : seg(doc, G), h(doc, 'span', { cls: 'dn-sp' }), exports]);
  // the canvas
  P.canvas = h(doc, 'canvas', { cls: 'dn-canvas', attrs: { tabindex: '0', role: 'img', 'aria-roledescription': 'graph' } });
  P.mini = h(doc, 'canvas', { cls: 'dn-mini-c', attrs: { 'aria-hidden': 'true' } });
  P.badge = h(doc, 'div', { cls: 'dn-badge', attrs: { 'aria-hidden': 'true' } });
  P.tip = h(doc, 'div', { cls: 'dn-tip', attrs: { role: 'tooltip', hidden: true } });
  P.zoom = h(doc, 'div', { cls: 'dn-zoom' }, [tool('zoom-in', '+', 'Zoom in', () => G.zoomBy(1.4)), tool('zoom-out', '−', 'Zoom out', () => G.zoomBy(1 / 1.4)), tool('fit', '⤢', 'Fit the graph', () => G.fit())]);
  P.miniBox = h(doc, 'div', { cls: 'dn-mini' }, [P.mini]);
  P.cv = h(doc, 'div', { cls: 'dn-cv' }, [P.canvas, P.zoom, P.miniBox, P.badge, P.tip]);
  if (G.o.minimap === false) { P.miniBox.hidden = true; }
  if (mode === 'table') {
    P.float = h(doc, 'div', { cls: 'dn-float' }, [h(doc, 'span', { cls: 'dn-pill' }, [seg(doc, G, 'dn-seg-sm')]), h(doc, 'span', { cls: 'dn-pill', attrs: { 'data-pickers': '' } }, pickers(doc, G)), P.typePills = h(doc, 'span', { cls: 'dn-pill dn-types-pill' })]);
    P.card = h(doc, 'div', { cls: 'dn-card', attrs: { hidden: true, role: 'dialog', 'aria-label': 'The node' } });
    P.cv.appendChild(P.float); P.cv.appendChild(P.card);
  }
  P.adj = h(doc, 'div', { cls: 'dn-adj', attrs: { hidden: true, 'aria-label': 'Adjacency table' } });
  P.status = h(doc, 'div', { cls: 'dn-status', attrs: { role: 'status' } });
  P.tableHost = h(doc, 'div', { cls: 'dn-grid' });
  P.tabs = h(doc, 'div', { cls: 'dn-tabs', attrs: { role: 'tablist', 'aria-label': 'Tables' } });
  if (mode === 'inspector') {
    P.rail = h(doc, 'div', { cls: 'dn-rail', attrs: { 'aria-label': 'Filters and encodings' } });
    P.insp = h(doc, 'div', { cls: 'dn-insp', attrs: { 'aria-label': 'Inspector', 'aria-live': 'polite' } });
    P.body = h(doc, 'div', { cls: 'dn-body dn-body-a' }, [P.rail, h(doc, 'div', { cls: 'dn-mid' }, [P.cv, P.tabs, P.tableHost]), P.insp]);
    P.shell = h(doc, 'div', { cls: 'dn-shell' }, [P.toolbar, P.body, P.adj, P.status]);
  } else if (mode === 'table') {
    P.shell = h(doc, 'div', { cls: 'dn-shell' }, [P.toolbar, P.cv, P.tabs, P.tableHost, P.adj, P.status]);
  } else {
    P.input = h(doc, 'input', { cls: 'dn-cmd-in', attrs: { autocomplete: 'off', spellcheck: 'false', 'aria-label': 'Graph command', placeholder: 'help · path A B · expand X · group by type · top 10 <measure> · explain · layout radial', 'data-ctl': 'command' } });
    P.cmd = h(doc, 'div', { cls: 'dn-cmd' }, [h(doc, 'span', { cls: 'dn-prompt', text: 'GRAPH>' }), P.input, h(doc, 'span', { cls: 'dn-hint', text: '↑ history · Tab completes · / focuses' })]);
    P.out = h(doc, 'div', { cls: 'dn-out', attrs: { role: 'log', 'aria-label': 'Answers', 'aria-live': 'polite' } });
    P.fk = h(doc, 'div', { cls: 'dn-fk', attrs: { role: 'toolbar', 'aria-label': 'Function keys' } }, Object.keys(FUNCTION_KEYS).map((k) =>
      h(doc, 'button', { cls: 'dn-fk-b', attrs: { type: 'button', 'data-fk': k }, on: { click: () => G.functionKey(k) } }, [h(doc, 'b', { text: k === 'Escape' ? 'Esc' : k }), ' ' + FUNCTION_KEYS[k]])));
    P.shell = h(doc, 'div', { cls: 'dn-shell dn-shell-c' }, [P.cmd, P.cv, P.out, P.tableHost, P.fk, P.adj, P.status]);
  }
  return P;
}

/** The rail of the inspector mode: the types with their counts (a click draws or leaves out), the pickers and the legend. */
export function renderRail(G, P) {
  if (!P.rail) { return; }
  const doc = G.doc, leg = G.legend();
  P.rail.replaceChildren(
    h(doc, 'h3', { cls: 'dn-sec' }, ['Types', h(doc, 'span', { cls: 'dn-r', text: G.visibleNodes().length + ' drawn' })]),
    typeList(G, leg), h(doc, 'h3', { cls: 'dn-sec', text: 'Encodings' }), h(doc, 'div', { cls: 'dn-rows' }, pickers(doc, G)),
    h(doc, 'h3', { cls: 'dn-sec', text: 'Legend' }), legendEl(G, leg));
}

/** The types as chips (the rail, and the table mode's pill). */
export function typeList(G, leg) {
  const doc = G.doc;
  return h(doc, 'div', { cls: 'dn-types', attrs: { role: 'group', 'aria-label': 'Types drawn' } }, leg.types.map((t) =>
    h(doc, 'button', { cls: 'dn-chip' + (t.off ? ' dn-off' : ''), attrs: { type: 'button', 'data-type': t.type, 'aria-pressed': String(!t.off), title: (t.off ? 'Draw ' : 'Leave out ') + t.label }, on: { click: () => G.toggleType(t.type) } },
      [swatch(doc, t.colour, t.shape), h(doc, 'span', { text: t.label || '(none)' }), h(doc, 'span', { cls: 'dn-c', text: t.shown + ' / ' + t.count })])));
}

function swatch(doc, colour, shape) { const s = h(doc, 'i', { cls: 'dn-sw dn-sw-' + (shape || 'circle'), attrs: { 'aria-hidden': 'true' } }); s.style.setProperty('background', colour); return s; }

/** The legend: colour (keys or a ramp) and size samples. */
export function legendEl(G, leg) {
  const doc = G.doc, parts = [];
  const c = leg.colour;
  if (c && c.kind === 'ramp') {
    const bar = h(doc, 'div', { cls: 'dn-ramp', attrs: { 'aria-hidden': 'true' } });
    bar.style.setProperty('background', 'linear-gradient(90deg,' + c.colours.join(',') + ')');
    parts.push(h(doc, 'div', { cls: 'dn-lg-t', text: 'Colour: ' + c.label }), bar, h(doc, 'div', { cls: 'dn-ends' }, [h(doc, 'span', { text: c.loText }), h(doc, 'span', { text: c.hiText })]));
  } else if (c) {
    parts.push(h(doc, 'div', { cls: 'dn-lg-t', text: 'Colour: ' + c.label }), h(doc, 'ul', { cls: 'dn-keys' }, c.items.map((it) => h(doc, 'li', {}, [swatch(doc, it.colour, it.shape), it.label]))));
  }
  if (leg.size) {
    parts.push(h(doc, 'div', { cls: 'dn-lg-t', text: 'Size: ' + leg.size.label }), h(doc, 'div', { cls: 'dn-sizes' }, leg.size.samples.map((s) => {
      const dot = h(doc, 'i', { attrs: { 'aria-hidden': 'true' } });
      dot.style.setProperty('width', (s.r * 2) + 'px'); dot.style.setProperty('height', (s.r * 2) + 'px');
      return h(doc, 'span', {}, [dot, s.text]);
    })));
  }
  return h(doc, 'div', { cls: 'dn-legend', attrs: { 'aria-label': 'Legend' } }, parts);
}

/**
 * The inspector's content: one node (its measures, attributes, degree, actions and neighbours), a selection of several (Σ of the
 * measures that add up), or the hint.
 * @param {Object} G
 * @returns {HTMLElement}
 */
export function inspectorContent(G) {
  const doc = G.doc, sel = G.selection().map((id) => G.nodeOrGroup(id)).filter(Boolean);
  if (!sel.length) { return h(doc, 'div', { cls: 'dn-empty', text: 'Click a node to see its details, its neighbours and Explain. Shift+click adds to the selection; drag round nodes with Alt held (or the lasso) to select many.' }); }
  const act = (id, text, fn, pri) => h(doc, 'button', { cls: 'dn-btn' + (pri ? ' dn-pri' : ''), text, attrs: { type: 'button', 'data-act': id }, on: { click: fn } });
  if (sel.length > 1) {
    const rows = [];
    G.measureList().forEach((m) => {
      const vs = sel.map((n) => n[m.field]).filter((v) => typeof v === 'number');
      if (!vs.length) { return; }
      rows.push(h(doc, 'span', { cls: 'dn-k', text: (m.additive === false ? '◆ ' : 'Σ ') + (m.label || m.field) }),
        h(doc, 'span', { cls: 'dn-v', text: m.additive === false ? 'not summed' : G.formatMeasure(m.field, vs.reduce((s, v) => s + v, 0)), attrs: m.additive === false ? { title: 'A measure that does not add up is never summed: ask the server for the selection\'s figure' } : null }));
    });
    return h(doc, 'div', {}, [h(doc, 'div', { cls: 'dn-ttl', text: sel.length + ' selected' }), h(doc, 'div', { cls: 'dn-kv' }, rows),
      h(doc, 'div', { cls: 'dn-acts' }, [act('pathsel', 'Path between the first two', () => G.findPath(sel[0].id, sel[1].id)), act('pin', 'Pin', () => G.pin(sel.map((n) => n.id))), act('clear', 'Clear', () => G.clearSelection())])]);
  }
  const n = sel[0], kv = [], nb = G.neighbourList(n.id);
  G.measureList().forEach((m) => { if (typeof n[m.field] === 'number') { kv.push(h(doc, 'span', { cls: 'dn-k', text: (m.additive === false ? '◆ ' : '') + (m.label || m.field) }), h(doc, 'span', { cls: 'dn-v', text: G.formatMeasure(m.field, n[m.field]) })); } });
  kv.push(h(doc, 'span', { cls: 'dn-k', text: 'Degree' }), h(doc, 'span', { cls: 'dn-v', text: String(n.members ? '–' : G.model.degree(n.id)) }));
  G.fields().attributes.filter((a) => a !== 'type' && n[a] !== undefined && n[a] !== null && n[a] !== '').forEach((a) => kv.push(h(doc, 'span', { cls: 'dn-k', text: a }), h(doc, 'span', { cls: 'dn-v', text: String(n[a]) })));
  const look = G.lookOf(n.id) || {};
  return h(doc, 'div', {}, [
    h(doc, 'div', { cls: 'dn-ttl' }, [swatch(doc, look.colour, look.shape), h(doc, 'span', { text: n.label }), h(doc, 'span', { cls: 'dn-ty', text: G.typeLabel(n.type) })]),
    h(doc, 'div', { cls: 'dn-sub', text: n.id + (n.pinned ? ' · pinned' : '') + (n.members ? ' · a group of ' + n.members.length : '') }),
    h(doc, 'div', { cls: 'dn-kv' }, kv),
    h(doc, 'div', { cls: 'dn-acts' }, n.members ? [act('open', 'Open the group', () => G.openGroup(n.id), true)] : [
      act('explain', 'Explain', () => G.explain(n.id), true), act('expand', 'Expand', () => G.expand(n.id)), act('collapse', 'Collapse', () => G.collapse(n.id)),
      act('pin', n.pinned ? 'Unpin' : 'Pin', () => (n.pinned ? G.unpin([n.id]) : G.pin([n.id]))), act('hide', 'Hide', () => G.hide([n.id])),
      act('pathfrom', 'Path from here', () => G.setPathEnd('from', n.id)), n.link ? act('open', 'Open', () => G.openLink(n)) : null]),
    G.explainBox = h(doc, 'div', { cls: 'dn-explain', attrs: { hidden: true } }),
    h(doc, 'h3', { cls: 'dn-sec' }, ['Neighbours', h(doc, 'span', { cls: 'dn-r', text: String(nb.length) })]),
    h(doc, 'div', { cls: 'dn-nb' }, nb.slice(0, 14).map((x) => h(doc, 'button', { cls: 'dn-nb-i', attrs: { type: 'button', 'data-node': x.node.id }, on: { click: () => { G.select([x.node.id]); G.centerOn(x.node.id); } } },
      [swatch(doc, (G.lookOf(x.node.id) || {}).colour, (G.lookOf(x.node.id) || {}).shape), h(doc, 'span', { text: x.node.label }), h(doc, 'span', { cls: 'dn-m', text: x.dir + (x.edge.label ? ' ' + x.edge.label : '') })]))
      .concat(nb.length > 14 ? [h(doc, 'span', { cls: 'dn-m', text: (nb.length - 14) + ' more' })] : []))
  ]);
}

/** The status line: what is drawn, how, and how long it took. */
export function renderStatus(G, P) {
  const doc = G.doc, s = G.stats(), st = G.state(), parts = [
    h(doc, 'span', {}, [h(doc, 'b', { text: formatNumber(s.nodes, { decimals: 0 }) }), ' nodes · ', h(doc, 'b', { text: formatNumber(s.edges, { decimals: 0 }) }), ' edges drawn']),
    h(doc, 'span', { text: 'layout ' + st.layout + (s.layoutMs !== null ? ' in ' + s.layoutMs + ' ms' : '') }),
    h(doc, 'span', { text: 'frame ' + s.frameMs + ' ms' }),
    G.selection().length ? h(doc, 'span', { text: G.selection().length + ' selected' }) : null,
    s.path ? h(doc, 'span', { cls: 'dn-warn', text: 'path: ' + s.path + ' hops' }) : null,
    st.groupBy ? h(doc, 'span', { cls: 'dn-warn', text: 'grouped by ' + st.groupBy }) : null,
    s.hidden ? h(doc, 'span', { text: s.hidden + ' not drawn' }) : null];
  P.status.replaceChildren(...parts.filter(Boolean));
  P.badge.textContent = s.nodes + ' · ' + s.edges + ' · ' + Math.round(G.t.k * 100) + '%';
}

/** One answer line of the terminal mode. */
export function answerLine(G, line) {
  const doc = G.doc;
  if (line.node) { return h(doc, 'div', { cls: 'dn-ln dn-' + (line.tone || 'plain') }, [h(doc, 'button', { cls: 'dn-ln-a', text: line.text, attrs: { type: 'button', 'data-node': line.node }, on: { click: () => { G.select([line.node]); G.centerOn(line.node); } } })]); }
  return h(doc, 'div', { cls: 'dn-ln dn-' + (line.tone || 'plain'), text: line.text });
}
