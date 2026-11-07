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
/* The visual layer's decisions, without a page: the heading rows of a pivot (member groups spanning their columns, the collapsible
   ones), conditional formats (colour scales, data bars), which columns a phone shows, the column menu's and the context menu's items
   (plain descriptions: an id, words, a key, checked or not, and why an item cannot be used), the terminal's command line (written from
   the wells and read back), the status bar's figures and the flash of a changed cell. The layouts (drishti_grid-view.js) draw these;
   Node tests them. */

/** The view's own events (the engine's are GRID_EVENTS): dispatched on the element as drishti:<kebab-name> and on the grid's bus. */
export const VIEW_EVENTS = Object.freeze(['layoutChanged', 'densityChanged', 'menuOpened', 'menuAction', 'chartRequested', 'drillThrough',
  'viewSaved', 'measureSwitched', 'phoneChanged', 'explainShown', 'copied', 'exported']);

/** Below this width (px) the grid is drawn for a phone (ELEMENTS.md O2). */
export const PHONE_WIDTH = 640;

/** The terminal layout's function keys, each an action of the view. */
export const FUNCTION_KEYS = Object.freeze([
  { key: 'F2', action: 'explain', label: 'Explain' }, { key: 'F3', action: 'expand-all', label: 'Expand' },
  { key: 'F4', action: 'collapse-all', label: 'Collapse' }, { key: 'F5', action: 'toggle-pivot', label: 'Pivot ⇄ flat' },
  { key: 'F7', action: 'chart', label: 'Chart range' }, { key: 'F8', action: 'export-xlsx', label: 'Export' },
  { key: 'F9', action: 'drill', label: 'Rows' }, { key: 'F10', action: 'save-view', label: 'Save view' }]);

/** The conditional formats a column may take. */
export const CF_TYPES = Object.freeze(['colourScale', 'dataBar']);

/**
 * @typedef {Object} HeaderSpan
 * @property {number} start the first column (an index into the list given)
 * @property {number} end the last column (inclusive)
 * @property {string} label
 * @property {?Object} group the pivot column entry it opens and closes (null: not collapsible)
 */

/**
 * The heading rows of columns given their labels by level: consecutive columns with the same labels down to a level share one span at
 * that level (a member over its children, the value's name at the foot).
 * @param {Array<string[]>} labels per column, its label at each level (all the same length)
 * @returns {Array<HeaderSpan[]>} per level, the spans in order
 */
export function headerSpans(labels) {
  const levels = labels.reduce((m, l) => Math.max(m, l.length), 0), out = [];
  for (let lv = 0; lv < levels; lv++) {
    const row = [];
    labels.forEach((l, i) => {
      const key = l.slice(0, lv + 1).join('\u001f'), last = row[row.length - 1];
      if (last && last.key === key && lv < levels - 1) { last.end = i; } else { row.push({ start: i, end: i, label: l[lv] || '', key: key, group: null }); }
    });
    out.push(row.map((s) => ({ start: s.start, end: s.end, label: s.label, group: null, key: s.key })));
  }
  return out;
}

/**
 * A pivot result column's labels by level: its members from the top (a collapsed group "Total" below its name, an open group's total
 * "Total" under the group), "Grand total" at the top for the grand total, the value's name at the foot.
 * @param {{kind: string, colKey: string, measure: string}} col a shown pivot column
 * @param {?Object} entry its column entry of the answer (path, kind, depth)
 * @param {number} levels how many column dimensions the pivot has
 * @returns {string[]} levels + 1 labels
 */
export function pivotColumnLabels(col, entry, levels) {
  const out = [];
  const members = entry && entry.path && entry.path.length ? entry.path.map((p) => (p[1] === null || p[1] === undefined ? entry.label : String(p[1]))) : entry && entry.kind !== 'grand' && entry.label !== undefined ? [String(entry.label)] : [];
  for (let i = 0; i < levels; i++) {
    if (col.kind === 'grand' || !entry) { out.push(i === 0 ? 'Grand total' : ''); }
    else if (i < members.length) { out.push(members[i]); }
    else { out.push(i === members.length && (col.kind === 'total' || (entry.expandable && !entry.expanded)) ? 'Total' : ''); }
  }
  out.push(col.measure);
  return out;
}

/**
 * Marks the spans that open and close a pivot column group: the span at a level whose members name a group that has children.
 * @param {Array<HeaderSpan[]>} spans headerSpans of the pivot columns
 * @param {Array<?Object>} entries per column, its column entry
 * @returns {Array<HeaderSpan[]>} the same spans, with group set where collapsible
 */
export function markGroups(spans, entries) {
  spans.forEach((row, lv) => row.forEach((s) => {
    for (let i = s.start; i <= s.end; i++) {
      const e = entries[i];
      if (e && e.path && e.path.length === lv + 1 && (e.kind === 'total' || e.expandable)) { s.group = { path: e.path, open: e.kind === 'total' || !!e.expanded }; return; }
    }
  }));
  return spans;
}

/**
 * Whether a figure is a total: a pivot row that is a grand total, a subtotal or a group's own line, a total column, a row group.
 * @param {string} rowKind the row's kind (leaf, group, pivot) or the pivot entry's
 * @param {?string} colKind the pivot column's kind (node, total, grand, others) or null
 * @param {boolean} [expandable] a pivot row with children
 * @returns {boolean}
 */
export function isTotalCell(rowKind, colKind, expandable) {
  return rowKind === 'group' || rowKind === 'grand' || rowKind === 'total' || !!expandable || colKind === 'total' || colKind === 'grand';
}

/**
 * The hover note of a non-additive total: the figure is the server's own for the whole group, never the sum of its parts.
 * @param {string} header the measure's name
 * @param {string} text the figure as shown
 * @returns {string}
 */
export function nonAdditiveNote(header, text) {
  return '◆ ' + header + ' ' + text + ' is the server\'s figure for this whole group, not the sum of its parts (' + header +
    ' is not additive: diversification). Press E for Explain.';
}

/**
 * @typedef {Object} ConditionalFormat
 * @property {string} [column] the column's id (or a pivot value's source column)
 * @property {string} [measure] a pivot value's label
 * @property {('colourScale'|'dataBar')} type
 * @property {string} [colour] a theme token for the scale or the bar (default --d-accent for scales, --d-pos and --d-neg for bars)
 */

/**
 * How strong a colour-scale cell is: 0.08 at the least figure, 0.58 at the most (a line of one figure: the middle).
 * @param {number} v
 * @param {number} min
 * @param {number} max
 * @returns {number} the share of the colour, 0 to 1
 */
export function colourScaleAlpha(v, min, max) {
  if (typeof v !== 'number' || !isFinite(v)) { return 0; }
  const t = max > min ? (v - min) / (max - min) : 0.5;
  return Math.round((0.08 + 0.5 * Math.max(0, Math.min(1, t))) * 100) / 100;
}

/**
 * A data bar's length: the figure's share of the largest magnitude, as a percentage of the cell (at most 92, so the figure stays readable).
 * @param {number} v
 * @param {number} maxAbs
 * @returns {number} 0 to 92
 */
export function dataBarWidth(v, maxAbs) {
  if (typeof v !== 'number' || !isFinite(v) || !maxAbs) { return 0; }
  return Math.round(92 * Math.min(1, Math.abs(v) / maxAbs));
}

/**
 * The background of a conditionally formatted cell (a CSS background-image; the colours are the theme's tokens), or null.
 * @param {ConditionalFormat} rule
 * @param {*} v the figure
 * @param {{min: number, max: number, maxAbs: number}} range the column's figures
 * @returns {?string}
 */
export function cfBackground(rule, v, range) {
  if (typeof v !== 'number' || !isFinite(v) || !range) { return null; }
  if (rule.type === 'colourScale') {
    const pct = Math.round(colourScaleAlpha(v, range.min, range.max) * 100);
    const c = 'color-mix(in srgb, var(' + (rule.colour || '--d-accent') + ') ' + pct + '%, transparent)';
    return 'linear-gradient(' + c + ', ' + c + ')';
  }
  if (rule.type === 'dataBar') {
    const w = dataBarWidth(v, range.maxAbs), tok = v < 0 ? '--d-neg' : (rule.colour || '--d-pos');
    return 'linear-gradient(to left, color-mix(in srgb, var(' + tok + ') 26%, transparent) ' + w + '%, transparent ' + w + '%)';
  }
  return null;
}

/**
 * The least, most and largest magnitude of figures (non-numbers left out); null when there are none.
 * @param {Array<*>} values
 * @returns {?{min: number, max: number, maxAbs: number}}
 */
export function figureRange(values) {
  let min = null, max = null, maxAbs = 0;
  values.forEach((v) => {
    if (typeof v !== 'number' || !isFinite(v)) { return; }
    min = min === null || v < min ? v : min;
    max = max === null || v > max ? v : max;
    maxAbs = Math.max(maxAbs, Math.abs(v));
  });
  return min === null ? null : { min: min, max: max, maxAbs: maxAbs };
}

/**
 * The rule that formats a column, if any.
 * @param {ConditionalFormat[]} rules
 * @param {{id: string, measure: string, source: string}} col a shown column (a pivot column: its value's label and source column)
 * @returns {?ConditionalFormat}
 */
export function ruleFor(rules, col) {
  return (rules || []).find((r) => (r.column && (r.column === col.id || r.column === col.source)) || (r.measure && r.measure === col.measure)) || null;
}

/**
 * The columns a phone shows (ELEMENTS.md O2): a grid keeps every column with the first one pinned (it scrolls across); a pivot collapses
 * to its row headings and one value column, the grand total of the measure picked in the switcher.
 * @param {Array<{col: Object, i: number, pin: ?string}>} display the shown columns with their pinning
 * @param {boolean} pivot
 * @param {string} [measure] the measure picked (default: the first)
 * @returns {Array<{col: Object, i: number, pin: ?string}>}
 */
export function phoneColumns(display, pivot, measure) {
  if (!pivot) {
    if (!display.length || display.some((d) => d.pin === 'left')) { return display; }
    return [Object.assign({}, display[0], { pin: 'left' })].concat(display.slice(1));
  }
  const head = display.filter((d) => d.col.rowHeader).map((d) => Object.assign({}, d, { pin: 'left' }));
  const measures = pivotMeasures(display.map((d) => d.col));
  const m = measures.indexOf(measure) >= 0 ? measure : measures[0];
  const mine = display.filter((d) => !d.col.rowHeader && d.col.measure === m);
  const one = mine.filter((d) => d.col.kind === 'grand')[0] || mine[mine.length - 1];
  return head.concat(one ? [Object.assign({}, one, { pin: null })] : []);
}

/**
 * The measures of pivot columns, in order, once each.
 * @param {Object[]} cols shown pivot columns
 * @returns {string[]}
 */
export function pivotMeasures(cols) {
  const out = [];
  cols.forEach((c) => { if (!c.rowHeader && c.measure && out.indexOf(c.measure) < 0) { out.push(c.measure); } });
  return out;
}

/**
 * @typedef {Object} MenuItem
 * @property {string} [id] the action (absent on headings and separators)
 * @property {string} [label]
 * @property {string} [key] the shortcut, as words
 * @property {string} [icon]
 * @property {boolean} [checked] a check or radio item that is on
 * @property {boolean} [disabled]
 * @property {string} [why] why it is disabled, in words
 * @property {string} [heading] a heading line instead of an item
 * @property {boolean} [sep] a separator
 * @property {boolean} [keep] the menu stays open after it runs (a member's check)
 * @property {{placeholder: string, value: string}} [input] an item with a field and an Apply button
 */

/**
 * The column menu's items: sort, filter (members, top N, a value filter), aggregation, hide, autosize, pin, Explain.
 * @param {Object} c what the menu is for
 * @param {string} c.header the column's heading
 * @param {boolean} c.pivot a pivot column
 * @param {?string} c.sort its sort now: asc, desc or null
 * @param {boolean} c.numeric
 * @param {boolean} c.additive
 * @param {Array<{name: string, label: string}>} [c.aggs] the aggregations it may take
 * @param {string} [c.aggFunc] its aggregation now
 * @param {?string} [c.pinned] left, right or null
 * @param {boolean} [c.pinTotals] the pivot's totals are pinned
 * @param {boolean} [c.explain] the grid has an Explain hook
 * @param {boolean} [c.rowHeader] the pivot's row heading column
 * @returns {MenuItem[]}
 */
export function columnMenuItems(c) {
  const items = [{ heading: c.header + (c.additive === false ? ' ◆' : '') },
    { id: 'sort-desc', label: 'Sort descending', checked: c.sort === 'desc' }, { id: 'sort-asc', label: 'Sort ascending', checked: c.sort === 'asc' },
    { id: 'sort-clear', label: 'Clear sort', disabled: !c.sort }, { sep: true }];
  if (c.numeric && !c.rowHeader) {
    items.push({ id: 'top-5', label: 'Top 5 rows by this column', icon: '⌕' }, { id: 'top-10', label: 'Top 10 rows by this column', icon: '⌕' },
      { id: 'value-filter', label: 'Value filter…', icon: '⌕' });
  } else if (!c.pivot) { items.push({ id: 'member-filter', label: 'Filter members…', icon: '⌕' }); }
  items.push({ id: 'clear-filter', label: 'Clear filters' }, { sep: true });
  if (c.aggs && c.aggs.length && !c.rowHeader) {
    items.push({ heading: c.additive === false ? 'Aggregation · computed by the server' : 'Aggregation' });
    if (c.additive === false) { items.push({ id: 'agg:server', label: c.aggFunc || 'the server\'s measure', checked: true, disabled: true, why: c.header + ' is not additive: every total is the server\'s figure for its group' }); }
    else { c.aggs.forEach((a) => items.push({ id: 'agg:' + a.name, label: a.label, checked: c.aggFunc === a.name })); }
    items.push({ sep: true });
  }
  items.push({ id: 'hide', label: 'Hide this column', icon: '⊘', disabled: !!c.rowHeader }, { id: 'show-all', label: 'Show all columns' },
    { id: 'autosize', label: 'Autosize this column', icon: '↔' }, { id: 'autosize-all', label: 'Autosize all columns' });
  if (c.pivot) { items.push({ id: 'pin-totals', label: 'Pin the totals on the right', checked: !!c.pinTotals }); }
  else {
    items.push({ id: 'pin-left', label: 'Pin left', checked: c.pinned === 'left' }, { id: 'pin-right', label: 'Pin right', checked: c.pinned === 'right' },
      { id: 'unpin', label: 'Unpin', disabled: !c.pinned });
  }
  items.push({ sep: true }, { id: 'explain-column', label: 'Explain the total', icon: '?', disabled: !c.explain, why: c.explain ? null : 'this grid has no Explain hook' });
  return items;
}

/**
 * The context menu's items for a cell: Explain, copy, export, chart the range, the rows behind it, pin, group.
 * @param {Object} c what the menu is for
 * @param {boolean} c.explain the grid has an Explain hook
 * @param {boolean} c.pivot
 * @param {number} c.cells cells in the range
 * @param {boolean} c.drill there are rows behind the cell to show
 * @param {?string} c.groupBy the field rows would be grouped by (null: none)
 * @param {?string} c.pinned the column's pinning (grid mode)
 * @param {boolean} c.pinTotals the pivot's totals are pinned
 * @returns {MenuItem[]}
 */
export function cellMenuItems(c) {
  const cells = c.cells > 1 ? c.cells + ' cells' : 'the cell';
  return [
    { id: 'explain', label: 'Explain this figure', key: 'E', icon: '?', disabled: !c.explain, why: c.explain ? null : 'this grid has no Explain hook' },
    { sep: true },
    { id: 'copy', label: 'Copy ' + cells, key: 'Ctrl+C', icon: '⧉' }, { id: 'copy-headers', label: 'Copy with headers', key: 'Ctrl+Shift+C' },
    { id: 'export-csv', label: 'Export CSV (as shown)', icon: '⤓' }, { id: 'export-xlsx', label: 'Export Excel (values and outline)', key: 'F8' },
    { sep: true },
    { id: 'chart', label: 'Chart this range', key: 'F7', icon: '▤' },
    { id: 'drill', label: 'Show the rows behind it', key: 'F9', icon: '≡', disabled: !c.drill, why: c.drill ? null : 'no rows behind this cell' },
    { id: 'group', label: c.groupBy ? 'Group rows by ' + c.groupBy : 'Group rows', icon: '⊞', disabled: !c.groupBy, why: c.groupBy ? null : 'nothing to group by here' },
    c.pivot ? { id: 'pin-totals', label: (c.pinTotals ? 'Unpin' : 'Pin') + ' the totals on the right' }
      : { id: c.pinned ? 'unpin' : 'pin-left', label: c.pinned ? 'Unpin this column' : 'Pin this column left' }];
}

const KEYWORDS = { ROWS: 'rows', ROW: 'rows', BY: 'columns', COLS: 'columns', COLUMNS: 'columns', VALUES: 'values', VALS: 'values', FILTER: 'filters', FILT: 'filters', FILTERS: 'filters' };

/**
 * The terminal's command line for wells: PIVOT ROWS a b BY c VALUES d:avg FILTER e.
 * @param {{rows: string[], columns: string[], values: Array<{colId: string, aggFunc: string}>, filters: Array<{colId: string}>}} wells
 * @returns {string}
 */
export function commandText(wells) {
  const up = (s) => String(s).toUpperCase();
  const parts = ['PIVOT'];
  if (wells.rows.length) { parts.push('ROWS', wells.rows.map(up).join(' ')); }
  if (wells.columns.length) { parts.push('BY', wells.columns.map(up).join(' ')); }
  if (wells.values.length) { parts.push('VALUES', wells.values.map((v) => up(v.colId) + (v.aggFunc && v.aggFunc !== 'sum' ? ':' + v.aggFunc : '')).join(' ')); }
  if (wells.filters.length) { parts.push('FILTER', wells.filters.map((f) => up(f.colId)).join(' ')); }
  return parts.join(' ');
}

/**
 * Reads a command line back into wells: words name fields by id or heading (case ignored; "quoted words" for a heading with spaces);
 * ROWS (or no keyword), BY (columns), VALUES (field:aggregation), FILTER; PIVOT and GO are skipped.
 * @param {string} text
 * @param {Array<{id: string, header: string}>} fields
 * @returns {{wells: ?Object, error: ?string}}
 */
export function parseCommand(text, fields) {
  const words = (String(text).match(/"[^"]*"|\S+/g) || []).map((w) => w.replace(/^"|"$/g, ''));
  const norm = (s) => String(s).toLowerCase().replace(/[\s_]+/g, '');
  const find = (w) => fields.find((f) => norm(f.id) === norm(w)) || fields.find((f) => norm(f.header) === norm(w)) || null;
  const wells = { rows: [], columns: [], values: [], filters: [] };
  let section = 'rows';
  for (const w of words) {
    const u = w.toUpperCase();
    if (u === 'PIVOT' || u === 'GO' || u === '<GO>') { continue; }
    if (KEYWORDS[u]) { section = KEYWORDS[u]; continue; }
    const [name, agg] = w.split(':');
    const f = find(name);
    if (!f) { return { wells: null, error: 'no field ' + name }; }
    if (section === 'values') { wells.values.push({ colId: f.id, aggFunc: agg || 'sum' }); }
    else if (section === 'filters') { wells.filters.push({ colId: f.id, filter: null }); }
    else { wells[section].push(f.id); }
  }
  if (!wells.values.length) { return { wells: null, error: 'name at least one value (VALUES field)' }; }
  return { wells: wells, error: null };
}

const OPS = { '>': 'gt', '>=': 'gte', '<': 'lt', '<=': 'lte', '=': 'equals', '==': 'equals', '!=': 'notEquals', '<>': 'notEquals' };
const OP_TEXT = { gt: '>', gte: '≥', lt: '<', lte: '≤', equals: '=', notEquals: '≠', between: 'between' };
const UNITS = { k: 1e3, m: 1e6, mm: 1e6, bn: 1e9, b: 1e9 };

/**
 * Reads a value filter's condition as a person types it: "> 400k", ">= 1.5m", "< 0", "= 12"; null when it is not one.
 * @param {string} text
 * @returns {?{op: string, value: number}}
 */
export function parseCondition(text) {
  const m = /^\s*(>=|<=|<>|!=|==|>|<|=)\s*(-?[\d.,]+)\s*(k|m|mm|bn|b)?\s*$/i.exec(String(text || ''));
  if (!m) { return null; }
  const n = parseFloat(m[2].replace(/,/g, ''));
  if (!isFinite(n)) { return null; }
  return { op: OPS[m[1]], value: n * (m[3] ? UNITS[m[3].toLowerCase()] : 1) };
}

/**
 * A value filter as words: "VaR > 400,000".
 * @param {{by: string, op: string, value: *, valueTo: *}} f
 * @param {function(*): string} [fmt] the figure's format
 * @returns {string}
 */
export function conditionText(f, fmt) {
  const t = fmt || ((x) => String(x));
  return f.by + ' ' + (OP_TEXT[f.op] || f.op) + ' ' + t(f.value) + (f.op === 'between' ? ' – ' + t(f.valueTo) : '');
}

/**
 * The status bar's figures of a range, as words: count, sum, average, min and max (each formatted), or one cell's figure; a warning when a
 * non-additive figure is in it (a sum of VaR is not a VaR).
 * @param {{cells: number, count: number, sum: ?number, avg: ?number, min: ?number, max: ?number, warning: ?string, nonAdditive: string[]}} s
 * @param {function(number): string} fmt
 * @returns {Array<{label: string, value: string, warn: boolean}>}
 */
export function statusItems(s, fmt) {
  if (!s || !s.cells) { return []; }
  if (s.cells === 1) { return s.count ? [{ label: '1 cell', value: fmt(s.min), warn: false }] : [{ label: '1 cell', value: '', warn: false }]; }
  const out = [{ label: 'Count', value: String(s.count), warn: false }];
  if (s.count) {
    out.push({ label: 'Sum', value: s.sum === null ? '—' : fmt(s.sum), warn: false }, { label: 'Average', value: s.avg === null ? '—' : fmt(s.avg), warn: false },
      { label: 'Min', value: fmt(s.min), warn: false }, { label: 'Max', value: fmt(s.max), warn: false });
  }
  if (s.warning) { out.push({ label: '◆', value: 'a sum of ' + s.nonAdditive.join(', ') + ' is not a ' + s.nonAdditive[0] + ': Explain the range for the server\'s figure', warn: true }); }
  return out;
}

/**
 * Remembers the figures drawn and tells which changed after live data came in: up or down, while the change is younger than flashMs.
 */
export class FlashTracker {
  /**
   * @param {{flashMs: number, now: function(): number}} [o] flashMs default 1000
   */
  constructor(o) {
    this.flashMs = (o && o.flashMs) || 1000;
    this.now = (o && o.now) || (() => Date.now());
    this.last = new Map();
    this.flashes = new Map();
    this.armed = false;
  }

  /**
   * Live data came in: the next figures drawn that differ from the last ones flash.
   * @returns {void}
   */
  arm() { this.armed = true; }

  /**
   * A figure drawn at a key (a row's identity and a column): returns its flash ({dir, age} or null).
   * @param {string} key
   * @param {*} value
   * @returns {?{dir: string, age: number}}
   */
  seen(key, value) {
    const had = this.last.has(key), old = this.last.get(key);
    this.last.set(key, value);
    const t = this.now();
    if (this.armed && had && typeof old === 'number' && typeof value === 'number' && old !== value) { this.flashes.set(key, { dir: value > old ? 'up' : 'down', at: t }); }
    const f = this.flashes.get(key);
    if (!f) { return null; }
    const age = t - f.at;
    if (age > this.flashMs) { this.flashes.delete(key); return null; }
    return { dir: f.dir, age: age };
  }

  /**
   * The drawing is over: changes are no longer expected until arm() again.
   * @returns {void}
   */
  settle() { this.armed = false; }

  /**
   * Whether some flash is still running (the view draws again when it ends).
   * @returns {boolean}
   */
  active() {
    const t = this.now();
    for (const [k, f] of this.flashes) { if (t - f.at > this.flashMs) { this.flashes.delete(k); } }
    return this.flashes.size > 0;
  }

  /**
   * Forgets every figure (a new pivot, new columns).
   * @returns {void}
   */
  reset() { this.last.clear(); this.flashes.clear(); this.armed = false; }
}
