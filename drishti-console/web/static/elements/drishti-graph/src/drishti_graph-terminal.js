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
/* The terminal mode's graph query line (g5 C): GRAPH> path A B, expand X, group by country, top 10 <measure>, explain, layout radial,
   filter <type>…, export png. A command is read here into an action on the Graph (its public methods) and lines of answer; completion
   (Tab) offers the command words, then node ids, attributes, measures, layouts or types by what the command takes; the function keys map
   to commands. No page: the Graph is any object with the methods used, so the commands are tested under Node with a stand-in. */

/** The commands, in the order help lists them. */
export const COMMANDS = Object.freeze(['help', 'find', 'select', 'expand', 'collapse', 'path', 'layout', 'group', 'size', 'colour', 'filter', 'pin', 'unpin',
  'hide', 'top', 'explain', 'export', 'fit', 'clear']);

/** The function keys of the terminal mode and the command each runs. */
export const FUNCTION_KEYS = Object.freeze({ F1: 'help', F2: 'explain', F3: 'expand', F4: 'collapse', F5: 'layout', F6: 'group', F7: 'path', F8: 'export', F9: 'pin', Escape: 'clear' });

/**
 * One line of a command's answer.
 * @typedef {Object} TerminalLine
 * @property {string} text
 * @property {('ok'|'error'|'dim'|'echo'|'')} [tone]
 * @property {string} [node] a node the line names (the line is a link that selects it)
 */

const HELP = [
  ['find <words>', 'fly to and select the best match'], ['select <id> [<id>…]', 'select nodes (the table follows)'],
  ['expand [<id>]', 'show the neighbours (F3)'], ['collapse [<id>]', 'hide what is below (F4)'], ['path <a> <b>', 'the shortest path, highlighted (F7)'],
  ['layout <name>', 'force, layered, radial, circular, grouped (F5: the next)'], ['group by <attribute>|none', 'fold nodes into groups (F6: the next)'],
  ['size <measure>|none', 'node size by a measure'], ['colour <attribute>|<measure>', 'node colour'], ['filter <type>…|all', 'draw only these types'],
  ['pin | unpin [all]', 'keep the selection in place (F9)'], ['hide [<id>]', 'leave a node out'], ['top <n> [<measure>]', 'the largest drawn nodes'],
  ['explain [<id>]', 'how the figure was made (F2)'], ['export png|svg|csv|json', 'download (F8)'], ['fit · clear', 'fit the view · clear selection and path (Esc)']];

/**
 * Splits a command line into words (double quotes keep spaces: find "ACME Bank").
 * @param {string} line
 * @returns {string[]}
 */
export function words(line) {
  const out = [], re = /"([^"]*)"|(\S+)/g;
  let m;
  while ((m = re.exec(String(line || '')))) { out.push(m[1] !== undefined ? m[1] : m[2]); }
  return out;
}

/** A node named by id, or the best match of its words. */
function nodeOf(G, w) { if (!w) { return null; } const n = G.model.node(w); return n || G.model.find(w, 1)[0] || null; }

/** The node a command acts on: the one named, else the first selected. */
function target(G, w) { return w ? nodeOf(G, w) : (G.selection()[0] ? G.model.node(G.selection()[0]) : null); }

/**
 * Runs one command line on a Graph; resolves with the lines of its answer (an error is a line, never a throw).
 * @param {Object} G the Graph (model, selection, select, findPath, expand, collapse, setLayout, setGroupBy, setSizeBy, setColourBy, setTypes,
 *   pin, unpin, hide, explain, export, fit, clearSelection, clearPath, centerOn, fields, layouts, state)
 * @param {string} line
 * @returns {Promise<TerminalLine[]>}
 */
export async function runCommand(G, line) {
  const w = words(line), c = (w[0] || '').toLowerCase(), out = [], say = (text, tone, node) => out.push({ text, tone: tone || '', node });
  const f = G.fields();
  try {
    switch (c) {
      case '': break;
      case 'help': case '?': HELP.forEach(([a, b]) => say(a.padEnd(30) + b)); break;
      case 'find': { const n = nodeOf(G, w.slice(1).join(' ')); if (!n) { say('nothing matches ' + w.slice(1).join(' '), 'error'); break; } G.select([n.id]); G.centerOn(n.id); say(n.label + ' · ' + (n.type || '') + ' · ' + n.id, 'ok', n.id); break; }
      case 'select': { const ns = w.slice(1).map((x) => nodeOf(G, x)).filter(Boolean); G.select(ns.map((n) => n.id)); say(ns.length + ' selected', 'ok'); ns.forEach((n) => say('  ' + n.label, '', n.id)); break; }
      case 'expand': case 'collapse': { const n = target(G, w[1]); if (!n) { say(c + ' <id> (or select a node first)', 'error'); break; } const r = await G[c](n.id); say(c === 'expand' ? n.label + ': ' + ((r && r.added) || 0) + ' nodes shown' : n.label + ': what is below is hidden', 'ok', n.id); break; }
      case 'path': {
        const a = nodeOf(G, w[1]), b = nodeOf(G, w[2]);
        if (!a || !b) { say('path <a> <b>: ' + (!a ? 'no node ' + (w[1] || '') : 'no node ' + (w[2] || '')), 'error'); break; }
        const p = G.findPath(a.id, b.id);
        if (!p) { say('no path from ' + a.label + ' to ' + b.label, 'error'); break; }
        say('path: ' + (p.nodes.length - 1) + ' hops', 'ok');
        p.nodes.forEach((id, i) => say((i ? '  → ' : '    ') + G.model.node(id).label, '', id));
        break;
      }
      case 'layout': { const l = w[1] || G.layouts[(G.layouts.indexOf(G.state().layout) + 1) % G.layouts.length]; if (!G.layouts.includes(l)) { say('layout ' + G.layouts.join('|'), 'error'); break; } await G.setLayout(l); say('layout ' + l, 'ok'); break; }
      case 'group': {
        const choices = ['none'].concat(f.attributes);
        const at = w[1] === 'by' ? w[2] : w[1] || choices[(choices.indexOf(G.state().groupBy || 'none') + 1) % choices.length];
        if (!choices.includes(at)) { say('group by ' + choices.join('|'), 'error'); break; }
        G.setGroupBy(at === 'none' ? null : at); say(at === 'none' ? 'ungrouped' : 'grouped by ' + at + ': ' + G.groupCount() + ' groups', 'ok'); break;
      }
      case 'size': { const m = w[1]; if (m !== 'none' && !f.measures.includes(m)) { say('size ' + ['none'].concat(f.measures).join('|'), 'error'); break; } G.setSizeBy(m === 'none' ? null : m); say(m === 'none' ? 'one size' : 'size by ' + m, 'ok'); break; }
      case 'colour': case 'color': { const m = w[1]; if (!f.attributes.concat(f.measures).includes(m)) { say('colour ' + f.attributes.concat(f.measures).join('|'), 'error'); break; } G.setColourBy(m); say('colour by ' + m, 'ok'); break; }
      case 'filter': {
        const all = G.model.types().map((t) => t.type);
        if (!w[1] || w[1] === 'all') { G.setTypes(all); say('every type drawn', 'ok'); break; }
        const bad = w.slice(1).filter((t) => !all.includes(t));
        if (bad.length) { say('no type ' + bad.join(', ') + ' (' + all.join(', ') + ')', 'error'); break; }
        G.setTypes(w.slice(1)); say('drawn: ' + w.slice(1).join(', '), 'ok'); break;
      }
      case 'pin': case 'unpin': {
        const ids = w[1] === 'all' ? G.model.nodeList().filter((n) => n.pinned).map((n) => n.id) : w[1] ? [nodeOf(G, w[1])].filter(Boolean).map((n) => n.id) : G.selection();
        if (!ids.length) { say(c + ' <id> (or select nodes first)', 'error'); break; }
        G[c](ids); say(ids.length + (c === 'pin' ? ' pinned' : ' unpinned'), 'ok'); break;
      }
      case 'hide': { const n = target(G, w[1]); if (!n) { say('hide <id>', 'error'); break; } G.hide([n.id]); say(n.label + ' hidden', 'ok'); break; }
      case 'top': {
        const k = Math.max(1, parseInt(w[1], 10) || 10), m = w[2] || G.state().sizeBy || f.measures[0];
        if (!m) { say('the nodes carry no measure', 'error'); break; }
        const rows = G.visibleNodes().filter((n) => typeof n[m] === 'number').sort((a, b) => b[m] - a[m]).slice(0, k);
        say('top ' + rows.length + ' by ' + m, 'ok');
        rows.forEach((n, i) => say(String(i + 1).padStart(3) + '  ' + n.label.padEnd(28).slice(0, 28) + '  ' + G.formatMeasure(m, n[m]), '', n.id));
        G.select(rows.map((n) => n.id));
        break;
      }
      case 'explain': { const n = target(G, w[1]); if (!n) { say('explain <id> (or select a node first)', 'error'); break; } const r = await G.explain(n.id); say(r && r.title ? r.title + (r.value ? ': ' + r.value : '') : 'explained: ' + n.label, 'ok', n.id); ((r && r.steps) || []).forEach((s) => say('  ' + s.label + (s.value ? '  ' + s.value : ''))); break; }
      case 'export': { const k = w[1] || 'png'; if (!['png', 'svg', 'csv', 'json'].includes(k)) { say('export png|svg|csv|json', 'error'); break; } G.export(k); say('exported ' + k, 'ok'); break; }
      case 'fit': G.fit(); say('fitted', 'ok'); break;
      case 'clear': G.clearPath(); G.clearSelection(); say('cleared', 'dim'); break;
      default: say('unknown command: ' + c + ' (help)', 'error');
    }
  } catch (e) { say(String((e && e.message) || e), 'error'); }
  return out;
}

/**
 * Completes the last word of a line (Tab): the command words first, then what the command takes.
 * @param {Object} G the Graph
 * @param {string} line
 * @returns {string[]} the candidates for the last word, best first (at most 12)
 */
export function complete(G, line) {
  const w = words(line), ends = /\s$/.test(line), last = ends ? '' : (w[w.length - 1] || ''), at = ends ? w.length : w.length - 1;
  const pick = (list) => list.filter((x) => x.toLowerCase().startsWith(last.toLowerCase())).slice(0, 12);
  if (at <= 0) { return pick(COMMANDS.slice()); }
  const c = w[0].toLowerCase(), f = G.fields();
  if (c === 'layout') { return pick(G.layouts.slice()); }
  if (c === 'group') { return at === 1 ? pick(['by', 'none']) : pick(['none'].concat(f.attributes)); }
  if (c === 'size' || c === 'top' && at === 2) { return pick(['none'].concat(f.measures)); }
  if (c === 'colour' || c === 'color') { return pick(f.attributes.concat(f.measures)); }
  if (c === 'filter') { return pick(['all'].concat(G.model.types().map((t) => t.type))); }
  if (c === 'export') { return pick(['png', 'svg', 'csv', 'json']); }
  if (['find', 'select', 'expand', 'collapse', 'path', 'pin', 'unpin', 'hide', 'explain'].includes(c)) {
    return last ? G.model.find(last, 12).map((n) => n.id) : [];
  }
  return [];
}
