/*
 * Drishti Elements
 *
 * Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>.
 * All rights reserved.
 *
 * PROPRIETARY.
 *
 * This file is the proprietary property of Ashutosh Sinha.
 * Copying, use, modification or distribution of this file, via any medium,
 * is not permitted except with the express prior written permission of the
 * copyright holder.
 *
 * See the LICENSE file in the root of this repository.
 */
/* The builders: view() writes a ViewModel of contract 1 (stamped 1.1 only when it uses what 1.1 adds: datasets, grid, tree, chart, map
   points) and frame() a frame of its live stream, one method per kind and per op. They guess
   the dull parts (a panel's id from its title, the fields contract 1 requires and allows to be null, from the schema itself) and fail
   early, in words: every panel is checked when it is added, and toJSON() checks the whole, throwing a ViewError that names the path and
   the fix. sse() writes a view's stream as Server-Sent Events: the read-only form of the events protocol (welcome, subd, then msg frames
   with a rising seq) or, with raw, the plain form (events named view and frame). No DOM is touched: Node runs this module unchanged. */
import { SCHEMA, CONTRACT } from './drishti_views-schema.js';
import { validate, formatIssue } from './drishti_views-validate.js';

/** The record each kind's data is (contract 1). */
export const KIND_DATA = Object.freeze({ kv: 'Fields', status: 'Fields', provenance: 'Fields', table: 'Table', ladder: 'Table', tabs: 'Tabs', line: 'Chart',
  area: 'Chart', hbar: 'Bars', links: 'Links', markdown: 'Text', gauge: 'Gauge', surface: 'Surface', waterfall: 'Waterfall', histogram: 'Histogram',
  scatter: 'Scatter', candlestick: 'Candles', graph: 'Graph', timeline: 'Timeline', pivot: 'Pivot', metric: 'Metric', map: 'GeoMap',
  grid: 'Grid', tree: 'Tree', chart: 'DataChart' });

/** What contract 1.1 adds to a document (the stamp is the smallest minor that describes it). */
const KINDS_1_1 = ['grid', 'tree', 'chart'];

/**
 * The stamp of a ViewModel: 1.1 when it has datasets, a grid, tree or chart panel (in tabs too) or a map with points; else 1.0.
 * @param {Object} vm
 * @returns {string}
 */
export function stampOf(vm) {
  const uses = (panels) => (panels || []).some((p) => p && (KINDS_1_1.indexOf(p.kind) >= 0 || (p.kind === 'map' && p.data && Array.isArray(p.data.points) && p.data.points.length)
    || (p.kind === 'tabs' && p.data && Array.isArray(p.data.tabs) && p.data.tabs.some((t) => t && uses(t.panels)))));
  return (vm.datasets && Object.keys(vm.datasets).length) || uses(vm.panels) ? '1.1' : '1.0';
}

const TYPE = (v) => (typeof v === 'number' ? 'number' : typeof v === 'boolean' ? 'boolean' : typeof v === 'string' && /^\d{4}-\d{2}-\d{2}$/.test(v) ? 'date'
  : typeof v === 'string' && /^\d{4}-\d{2}-\d{2}T/.test(v) ? 'datetime' : 'string');

/** A ViewModel or frame the builders refused: the problems, each with its path and what to write. */
export class ViewError extends Error {
  /**
   * @param {string} message
   * @param {Object[]} issues the validator's issues
   */
  constructor(message, issues) {
    super(message);
    this.name = 'ViewError';
    /** @type {Object[]} */ this.issues = issues || [];
  }
}

const DEFS = SCHEMA.$defs;
const copy = (v) => (v === undefined ? undefined : JSON.parse(JSON.stringify(v)));

function defOf(node, value) {
  if (!node) { return null; }
  if (node.$ref) { return DEFS[node.$ref.slice('#/$defs/'.length)] || null; }
  if (node.allOf && node.allOf.length === 1) { return defOf(node.allOf[0], value); }
  if (node.anyOf) {
    const want = Array.isArray(value) ? 'array' : value === null ? 'null' : typeof value === 'object' ? 'object' : null;
    const b = node.anyOf.find((x) => (want === 'array' ? x.type === 'array' : want === 'object' ? (x.$ref || x.type === 'object') : false));
    return b ? defOf(b, value) : null;
  }
  return node;
}

function nullable(node) { return !!(node && node.anyOf && node.anyOf.some((x) => x.type === 'null')); }

function emptyOf(node) {
  if (nullable(node)) { return null; }
  const d = node && (node.$ref || (node.allOf && node.allOf.length === 1)) ? defOf(node, {}) : null;   // a record behind a $ref or an allOf (ref, provenance): its own required fields filled in
  if (d && d.properties) { return fill({}, d); }
  const t = node && node.type;
  return t === 'array' ? [] : t === 'boolean' ? false : t === 'integer' || t === 'number' ? 0 : t === 'string' ? '' : t === 'object' ? {} : null;
}

/**
 * The value with every field contract 1 requires and the producer left out filled in: null where the schema allows it, otherwise the
 * type's empty value (false, 0, '', [], {}); nested records alike. Nothing is removed or changed.
 * @param {*} value
 * @param {string} def a record of the schema ("ViewModel", "PanelView", "Frame", "Metric", ...)
 * @returns {*}
 */
export function complete(value, def) { return fill(value, { $ref: '#/$defs/' + def }); }

function fill(v, node) {
  if (Array.isArray(v)) {
    const d = defOf(node, v);
    return d && d.items ? v.map((x) => fill(x, d.items)) : v;
  }
  if (!v || typeof v !== 'object') { return v; }
  const d = defOf(node, v);
  if (!d || !d.properties) { return v; }
  (d.required || []).forEach((k) => { if (v[k] === undefined && d.properties[k]) { v[k] = emptyOf(d.properties[k]); } });
  Object.keys(v).forEach((k) => {
    if (k === 'data' && d === DEFS.PanelView && KIND_DATA[v.kind]) { v.data = fill(v.data, { $ref: '#/$defs/' + KIND_DATA[v.kind] }); return; }
    if (d.properties[k]) { v[k] = fill(v[k], d.properties[k]); }
  });
  return v;
}

function refuse(what, issues) {
  const errors = issues.filter((i) => i.level === 'error');
  if (errors.length) { throw new ViewError(what + ': ' + errors.slice(0, 5).map(formatIssue).join('; '), errors); }
}

function slug(s) { return String(s).toLowerCase().replace(/[^a-z0-9]+/g, '-').replace(/^-+|-+$/g, '') || 'panel'; }

/** The ViewModel builder: view(title) answers one. */
export class ViewBuilder {
  /**
   * @param {string} [title] the title line's words (title.id); a pill is title(pill, id)
   */
  constructor(title) {
    this.vm = { contract: '1.0', ref: { kind: null, id: null }, title: { pill: null, id: title ? String(title) : null, with: null }, strip: [], panels: [], keys: [],
      provenance: {}, timings: {} };
    this.ids = new Set();
  }

  /** The subject of the view ({kind, id}): links and the live topic name it. @param {string} kind @param {string} id @returns {ViewBuilder} */
  ref(kind, id) { this.vm.ref = { kind: kind === undefined ? null : kind, id: id === undefined ? null : String(id) }; return this; }

  /** The title line: its pill, words and the cell beside them. @param {string} pill @param {string} [id] @param {Object} [withCell] @returns {ViewBuilder} */
  title(pill, id, withCell) { this.vm.title = { pill: pill === undefined ? null : pill, id: id === undefined ? this.vm.title.id : id, with: withCell || null }; return this; }

  /** The mnemonic the view answers to. @param {string} m @returns {ViewBuilder} */
  mnemonic(m) { this.vm.mnemonic = String(m); return this; }

  /** The strip of figures ({label, text, tone?, emphasis?, link?}). @param {Object[]} cells @returns {ViewBuilder} */
  strip(cells) { this.vm.strip = copy(cells || []); return this; }

  /** A function key ({key, label, action, panel?, link?}). @param {Object} k @returns {ViewBuilder} */
  key(k) { this.vm.keys.push(copy(k)); return this; }

  /** The view's provenance (source, updatedAt, businessDate, live, stale, ...). @param {Object} p @returns {ViewBuilder} */
  provenance(p) { this.vm.provenance = Object.assign({}, this.vm.provenance, copy(p)); return this; }

  /** The layout (areas, tabs, sections, the split). @param {Object} l @returns {ViewBuilder} */
  layout(l) { this.vm.layout = copy(l); return this; }

  /**
   * A dataset (contract 1.1): rows (objects) written as values in the order of their fields; the fields guessed from the first rows
   * (name and type) unless given. o: {key, fields: [{name, type, label, fmt}], formats: {field: fmt}, labels: {field: label}}; or o.name,
   * o.arrow, o.pages, o.server (rows null) for a dataset read from elsewhere.
   * @param {string} name
   * @param {?Object[]} rows
   * @param {Object} [o]
   * @returns {ViewBuilder}
   */
  dataset(name, rows, o) {
    o = o || {};
    const d = {};
    if (o.key) { d.key = [].concat(o.key).map(String); }
    if (Array.isArray(rows)) {
      let fields = o.fields ? copy(o.fields) : null;
      if (!fields) {
        const names = [];
        rows.slice(0, 50).forEach((r) => Object.keys(r || {}).forEach((k) => { if (names.indexOf(k) < 0) { names.push(k); } }));
        fields = names.map((n) => {
          const v = rows.map((r) => r && r[n]).find((x) => x !== null && x !== undefined);
          const f = { name: n, type: v === undefined ? 'string' : TYPE(v) };
          if (o.labels && o.labels[n]) { f.label = o.labels[n]; }
          if (o.formats && o.formats[n]) { f.fmt = o.formats[n]; }
          return f;
        });
      }
      d.fields = fields;
      d.values = rows.map((r) => fields.map((f) => (r && r[f.name] !== undefined ? r[f.name] : null)));
      if (o.more > 0) { d.more = o.more; }
    } else {
      ['name', 'arrow', 'pages', 'server'].forEach((k) => { if (o[k] !== undefined) { d[k] = copy(o[k]); } });
      if (o.fields) { d.fields = copy(o.fields); }
    }
    this.vm.datasets = Object.assign(this.vm.datasets || {}, { [String(name)]: d });
    return this;
  }

  /**
   * A panel of any kind, checked now: {id?, kind, title?, data, span?, height?, ...}. The id is guessed from the title when missing.
   * @param {Object} p
   * @returns {ViewBuilder}
   */
  panel(p) {
    const panel = copy(p) || {};
    if (!panel.id) { let id = slug(panel.title || panel.kind), n = 2; while (this.ids.has(id)) { id = slug(panel.title || panel.kind) + '-' + n++; } panel.id = id; }
    if (this.ids.has(String(panel.id))) { throw new ViewError('panel ' + panel.id + ': another panel has this id; ids are unique in a view', []); }
    complete(panel, 'PanelView');
    const probe = complete({ contract: CONTRACT, datasets: this.vm.datasets, ref: this.vm.ref, title: this.vm.title, strip: [], panels: [panel], keys: [], provenance: {}, timings: {} }, 'ViewModel');
    const at = 'panels[' + this.vm.panels.length + ']';
    refuse('panel ' + panel.id, validate(probe).issues.filter((i) => String(i.path).indexOf('panels[0]') === 0)
      .map((i) => Object.assign({}, i, { path: at + String(i.path).slice('panels[0]'.length) })));
    this.ids.add(String(panel.id));
    this.vm.panels.push(panel);
    return this;
  }

  /**
   * The ViewModel, checked whole (a ViewError when it is not valid).
   * @returns {Object}
   */
  toJSON() {
    const vm = complete(copy(this.vm), 'ViewModel');
    vm.contract = stampOf(vm);
    refuse('the view', validate(vm).issues);
    return vm;
  }
}

Object.keys(KIND_DATA).forEach((kind) => {
  /* one method per kind: (title, data, more) adds a panel of that kind; more holds span, height, id, code, key, area ... (the provenance
     kind is provenancePanel: provenance() sets the view's own) */
  ViewBuilder.prototype[kind === 'provenance' ? 'provenancePanel' : kind] = function (title, data, more) { return this.panel(Object.assign({ kind: kind, title: title, data: data }, more || {})); };
});

/**
 * A new ViewModel builder.
 * @param {string} [title]
 * @returns {ViewBuilder}
 * @example view('EMEA').title('Region · Orders').ref('region', 'EMEA').kv('Summary', { fields: [{ label: 'Owner', text: 'Ann' }] }).toJSON()
 */
export function view(title) { return new ViewBuilder(title); }

/** The frame builder: frame(seq) answers one. */
export class FrameBuilder {
  /** A frame at its place in the stream. @param {number} [seq] */
  constructor(seq) { this.f = { seq: Number.isInteger(seq) ? seq : 0, generation: 0, patches: [], latencyMs: null, p99Ms: null }; }

  /** The view's generation the frame belongs to. @param {number} g @returns {FrameBuilder} */
  generation(g) { this.f.generation = g; return this; }

  /** A strip cell at its index. @param {number} index @param {Object} cell @returns {FrameBuilder} */
  strip(index, cell) { this.f.patches.push({ op: 'strip', index: index, cell: copy(cell) }); return this; }

  /** A whole panel again (by its id). @param {Object} panel @returns {FrameBuilder} */
  panel(panel) { this.f.patches.push({ op: 'panel', panel: complete(copy(panel), 'PanelView') }); return this; }

  /** The view's provenance. @param {Object} p @returns {FrameBuilder} */
  provenance(p) { this.f.patches.push({ op: 'provenance', provenance: complete(copy(p), 'Provenance') }); return this; }

  /** The view's subject is gone. @param {string} [at] when (ISO time) @returns {FrameBuilder} */
  deleted(at) { this.f.patches.push(at ? { op: 'deleted', at: at } : { op: 'deleted' }); return this; }

  /** The view's subject is back. @returns {FrameBuilder} */
  restored() { this.f.patches.push({ op: 'restored' }); return this; }

  /**
   * A dataset's row changes by key (contract 1.1): o = {reset, remove, update, add}.
   * @param {string} dataset
   * @param {{reset: Object[], remove: string[], update: Object[], add: Object[]}} o
   * @returns {FrameBuilder}
   */
  rows(dataset, o) {
    const p = { op: 'rows', dataset: String(dataset) };
    ['reset', 'remove', 'update', 'add'].forEach((k) => { if (o && Array.isArray(o[k]) && o[k].length) { p[k] = copy(o[k]); } });
    this.f.patches.push(p);
    return this;
  }

  /**
   * The frame, checked (a ViewError when it is not valid).
   * @returns {Object}
   */
  toJSON() {
    const f = copy(this.f);
    refuse('the frame', validate(f, { as: 'frame' }).issues);
    return f;
  }
}

/**
 * A new frame builder.
 * @param {number} [seq]
 * @returns {FrameBuilder}
 * @example frame(4113).strip(0, { label: 'Booked', text: '18,432,850' }).toJSON()
 */
export function frame(seq) { return new FrameBuilder(seq); }

/**
 * Writes a view's live stream as Server-Sent Events through write(text) (a Node response's write, for one): the read-only form of the
 * events protocol (welcome, subd, then msg frames with a rising seq, ids for resume), or with raw the plain form (events named view and
 * frame). The caller sends the headers (Content-Type: text/event-stream, Cache-Control: no-store).
 * @param {function(string): *} write
 * @param {{topic: string, raw: boolean, epoch: string, session: string, heartbeatMs: number, now: function(): number}} [o] topic: default
 *   "views.view"
 * @returns {{view: function(Object): void, frame: function(Object): void, piece: function(Object): void, beat: function(): void}}
 */
export function sse(write, o) {
  o = Object.assign({ topic: 'views.view', raw: false, epoch: 'e1', session: 's1', heartbeatMs: 15000, now: () => Date.now() }, o);
  let seq = 0;
  const event = (name, data, id) => write((id !== undefined ? 'id: ' + id + '\n' : '') + 'event: ' + name + '\ndata: ' + JSON.stringify(data) + '\n\n');
  if (!o.raw) {
    event('welcome', { t: 'welcome', v: 1, session: o.session, time: o.now(), heartbeatMs: o.heartbeatMs, can: [], limits: {}, resumed: false });
    event('subd', { t: 'subd', id: 1, streams: { [o.topic]: { epoch: o.epoch, seq: 0 } }, recovered: true });
  }
  const send = (kind, data) => {
    seq++;
    if (o.raw) { event(kind === 'view' ? 'view' : 'frame', kind === 'view.piece' ? { seq: seq, generation: 0, patches: [data], latencyMs: null, p99Ms: null } : data, String(seq)); return; }
    event('msg', { t: 'msg', id: o.session + '-' + seq, topic: o.topic, stream: o.topic, seq: seq, kind: kind, time: o.now(), source: 'views', data: data }, String(seq));
  };
  return {
    view: (vm) => send('view', vm),
    frame: (f) => send('view.frame', f),
    piece: (p) => send('view.piece', p),
    beat: () => write(': hb\n\n')
  };
}
