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
/* UI Board P4b, the chip designer's model (UI_BOARD.md sections 2, 4.1, 9.3, 12.1): what a chip document is, checked in the browser as the
   person types. The server's ChipPublishCheck (POST /ui/chips/check) has the last word; this mirrors its rules so a mistake is red at once:
   - the pin table: names, types (4.1), roles, required, defaults typed and inside the enum values (rule PIN);
   - CP-01 the manual complete; CP-02 the body reads only its pins (no source keys, binds start with @ or %{, every %{x} and @x an in pin);
     CP-03 every out pin driven and every emits declared; CP-04 no clock, random or viewer;
   - the pins the body never reads and the out pins it never drives (the bench's lists), what breaks against an earlier version (9.3),
     and a module chip made from wired chips of a board (9.5, minimal: P6 builds full modules).
   Exposes window.drishtiUiBoard.chipModel. */
(function () {
  'use strict';
  const U = (window.drishtiUiBoard = window.drishtiUiBoard || {});
  const TYPES = ['id', 'text', 'number', 'bool', 'enum', 'date', 'datetime', 'period', 'measure', 'dimension', 'filter', 'selection', 'document', 'rowset'];
  const COLUMN_TYPES = ['id', 'text', 'number', 'bool', 'date', 'datetime', 'measure', 'dimension'];
  const MANUAL_KEYS = ['purpose', 'pins', 'behaviour', 'defaults', 'limits', 'examples', 'not-for', 'accessibility'];
  const SOURCE_KEYS = ['source', 'connector', 'model', 'dataset', 'live', 'url', 'fetch', 'sql'];
  const SLOT_BUILTINS = ['title'];
  const AT_BUILTINS = ['name', 'value', 'filtered', 'row', 'index', 'count', 'total'];
  const NAME = /^[a-z][a-z0-9-]{0,63}$/;
  const PIN = /^[a-z][a-z0-9-]{0,63}$/;
  const SLOT = /%\{([A-Za-z][\w-]*)/g;
  const AT = /(^|[^\w@.])@([A-Za-z][\w-]*)/g;
  const IMPURE = /\b(now|today|random|rand|uuid|clock)\s*\(|%\{\s*(viewer|user|me)\b|@(viewer|user|me)\b/;

  const prob = (rule, path, message, fix, level) => ({ rule, level: level || 'error', path, message, fix: fix || null });
  const ins = (doc) => ((doc && doc.pins && doc.pins.in) || {});
  const outs = (doc) => ((doc && doc.pins && doc.pins.out) || {});

  /** A new chip document: a table over one rowset pin, a selection out, a manual with prompts left empty. */
  function blank(name) {
    return {
      rachana: 1, chip: name || '', version: 1, title: '', category: 'tables', tags: [],
      manual: { purpose: '', pins: {}, behaviour: '', defaults: '', limits: '', examples: [], 'not-for': '', accessibility: '' },
      pins: { in: {}, out: {} },
      body: { panels: [] }
    };
  }

  /** The columns of a rowset or document pin as text ("id:id!, label:dimension, measures:measure*") and back. */
  function shapeText(shape) {
    return Object.keys(shape || {}).map((c) => { const s = shape[c] || {}; return c + ':' + (s.type || 'text') + (s.required ? '!' : '') + (s.many ? '*' : ''); }).join(', ');
  }
  function parseShape(text) {
    const shape = {}, bad = [];
    String(text || '').split(',').map((x) => x.trim()).filter(Boolean).forEach((part) => {
      const m = /^([a-z][a-z0-9_-]*)\s*:\s*([a-z]+)([!*]*)$/.exec(part);
      if (!m || COLUMN_TYPES.indexOf(m[2]) < 0) { bad.push(part); return; }
      shape[m[1]] = { type: m[2] };
      if (m[3].indexOf('!') >= 0) { shape[m[1]].required = true; }
      if (m[3].indexOf('*') >= 0) { shape[m[1]].many = true; }
    });
    return { shape, bad };
  }

  /** The pin table's own rules: one problem per mistake, at pins.<in|out>.<pin>[.<field>]. */
  function pinProblems(doc) {
    const p = [], seen = {};
    [['in', ins(doc)], ['out', outs(doc)]].forEach(([dir, pins]) => Object.keys(pins).forEach((name) => {
      const s = pins[name] || {}, at = 'pins.' + dir + '.' + name;
      if (!PIN.test(name)) { p.push(prob('PIN', at, "'" + name + "' is no pin name: lower-case letters, digits and dashes, starting with a letter", 'rename it')); }
      if (seen[name]) { p.push(prob('PIN', at, "'" + name + "' is both an in and an out pin", 'give one of them another name')); }
      seen[name] = true;
      if (TYPES.indexOf(s.type) < 0) { p.push(prob('PIN', at + '.type', 'choose a type: ' + TYPES.join(', '))); return; }
      if (dir === 'in' && s.role !== 'data' && s.role !== 'control') { p.push(prob('PIN', at + '.role', 'an in pin is data (rows, a document) or control (filters, ids, dates)')); }
      if (dir === 'in' && (s.type === 'rowset' || s.type === 'document') && s.role !== 'data') { p.push(prob('PIN', at + '.role', 'a ' + s.type + ' pin carries data: its role is data')); }
      if (s.type === 'rowset' && dir === 'in' && !Object.keys(s.shape || {}).length) { p.push(prob('PIN', at + '.shape', 'a rowset pin declares the columns the chip reads', 'columns like id:id!, label:dimension')); }
      if (s.type === 'enum' && !(Array.isArray(s.values) && s.values.length)) { p.push(prob('PIN', at + '.values', 'an enum pin lists its values', 'values like day, week, month')); }
      if (s.default !== undefined && s.default !== null && s.default !== '') {
        const d = s.default;
        if ((s.type === 'number' || s.type === 'measure') && typeof d !== 'number') { p.push(prob('PIN', at + '.default', 'the default of a ' + s.type + ' pin is a number')); }
        if (s.type === 'date' && !/^(\d{4}-\d{2}-\d{2}|today|T-\d+|EOM)$/.test(String(d))) { p.push(prob('PIN', at + '.default', 'a date default is yyyy-mm-dd or an AsOf word (today, T-1, EOM)')); }
        if (s.type === 'bool' && typeof d !== 'boolean') { p.push(prob('PIN', at + '.default', 'the default of a bool pin is true or false')); }
        if (s.type === 'enum' && Array.isArray(s.values) && s.values.map(String).indexOf(String(d)) < 0) { p.push(prob('PIN', at + '.default', "the default '" + d + "' is not one of the values")); }
        if (s.type === 'rowset' || s.type === 'document') { p.push(prob('PIN', at + '.default', 'a ' + s.type + ' is never a constant: it has no default')); }
        if (s.required) { p.push(prob('PIN', at + '.required', 'a required pin with a default is never empty: drop one of the two', null, 'warning')); }
      }
      if (typeof s.min === 'number' && typeof s.max === 'number' && s.min > s.max) { p.push(prob('PIN', at + '.min', 'min is above max')); }
    }));
    return p;
  }

  /** Every string under the body with its path. */
  function strings(n, at, out) {
    if (typeof n === 'string') { out.push([at, n]); } else if (Array.isArray(n)) { n.forEach((x, i) => strings(x, at + '[' + i + ']', out)); } else if (n && typeof n === 'object') { Object.keys(n).forEach((k) => strings(n[k], at + '.' + k, out)); }
    return out;
  }
  /** The pins a text reads: {slots: [...], ats: [...]}. */
  function reads(text) {
    const slots = [], ats = [];
    let m;
    SLOT.lastIndex = 0; while ((m = SLOT.exec(text))) { slots.push(m[1]); }
    AT.lastIndex = 0; while ((m = AT.exec(text))) { ats.push(m[2]); }
    return { slots, ats };
  }
  /** The references in one text that name no in pin (CP-02): ['%{x}', '@y']. */
  function badRefs(doc, text) {
    const pins = ins(doc), r = reads(String(text || '')), out = [];
    r.slots.forEach((x) => { if (!(x in pins) && SLOT_BUILTINS.indexOf(x) < 0) { out.push('%{' + x + '}'); } });
    r.ats.forEach((x) => { if (!(x in pins) && AT_BUILTINS.indexOf(x) < 0) { out.push('@' + x); } });
    return out;
  }

  /** The body's problems: CP-02, CP-03, CP-04; plus the out pins it drives. */
  function bodyProblems(doc) {
    const p = [], emitted = {};
    (function walk(n, at) {
      if (Array.isArray(n)) { n.forEach((x, i) => walk(x, at + '[' + i + ']')); return; }
      if (!n || typeof n !== 'object') { return; }
      Object.keys(n).forEach((k) => {
        const v = n[k], here = at + '.' + k;
        if (/^body\.board\.chips\.[^.]+\.supply$/.test(here)) { return; }                 // a composite's supply: CP-07 says it
        if (SOURCE_KEYS.indexOf(k) >= 0) { p.push(prob('CP-02', here, 'a chip body may not name a ' + k + '; it reads only its pins', 'add a data pin and let the board supply it')); return; }
        if (k === 'bind' && typeof v === 'string' && !/^\s*(@|%\{)/.test(v)) { p.push(prob('CP-02', here, "bind '" + v + "' reads a document path; a chip binds only pins (@pin, @.column) and slots (%{pin})", 'bind @<pin> or @.<column>')); }
        if (k === 'emits' && typeof v === 'string') { emitted[v] = true; }
        if (k === 'exposes' && v && typeof v === 'object' && !Array.isArray(v)) { Object.keys(v).forEach((e) => { emitted[e] = true; }); }
        walk(v, here);
      });
    })(doc.body || {}, 'body');
    strings(doc.body || {}, 'body', []).forEach(([at, s]) => {
      if (IMPURE.test(s)) { p.push(prob('CP-04', at, "'" + s + "' reads the clock, a random value or the viewer; a chip paints the same for the same input")); }
      badRefs(doc, s).forEach((r) => p.push(prob('CP-02', at, r + ' names no in pin', 'declare the pin, or pick one of: ' + Object.keys(ins(doc)).join(', '))));
    });
    Object.keys(outs(doc)).forEach((o) => { if (!emitted[o]) { p.push(prob('CP-03', 'pins.out.' + o, "out pin '" + o + "' is never driven by the body", 'add emits: ' + o + ' to an action, or exposes: { ' + o + ': <value> }')); } });
    Object.keys(emitted).forEach((e) => { if (!(e in outs(doc))) { p.push(prob('CP-03', 'body', "the body emits '" + e + "', which is not a declared out pin", 'declare it under pins.out')); } });
    if (!((doc.body || {}).panels || []).length && !(doc.body || {}).board) { p.push(prob('CP-02', 'body.panels', 'the body has no panel yet', 'add a panel from the palette')); }
    return { problems: p, emitted };
  }

  /** CP-01: the manual complete, with a prompt for what is missing. */
  function manualProblems(doc) {
    const p = [], m = doc.manual;
    if (!m || typeof m !== 'object') { return [prob('CP-01', 'manual', 'the chip has no manual; a chip without one is refused at publish')]; }
    const PROMPT = { purpose: 'What does the chip show, and when should a designer use it?', behaviour: 'What can the person do (sort, filter, select), and which out pin does each action drive?',
      defaults: 'What does the chip do with no control input?', limits: 'How many rows, columns, how often; what happens past them?',
      'not-for': 'What should a designer use instead in the common wrong cases?', accessibility: 'How does it work with the keyboard and a screen reader?' };
    MANUAL_KEYS.forEach((k) => {
      const v = m[k];
      const empty = v == null || v === '' || (Array.isArray(v) && !v.length) || (k === 'pins' && typeof v !== 'object');
      if (empty && k !== 'examples' && k !== 'pins') { p.push(prob('CP-01', 'manual.' + k, "the manual has no '" + k + "'", PROMPT[k])); }
    });
    const mp = (m.pins && typeof m.pins === 'object') ? m.pins : {};
    Object.keys(ins(doc)).concat(Object.keys(outs(doc))).forEach((pin) => { if (!mp[pin]) { p.push(prob('CP-01', 'manual.pins.' + pin, "the manual does not say what pin '" + pin + "' means", 'what it means, and what happens when it is empty')); } });
    Object.keys(mp).forEach((pin) => { if (!(pin in ins(doc)) && !(pin in outs(doc))) { p.push(prob('CP-01', 'manual.pins.' + pin, "the manual describes '" + pin + "', which is not a pin", 'remove it or fix the name')); } });
    const ex = Array.isArray(m.examples) ? m.examples : [];
    if (!ex.length) { p.push(prob('CP-01', 'manual.examples', 'the manual has no wiring example', 'add an example: a board fragment that uses the chip')); }
    ex.forEach((e, i) => { if (!e || !e.board) { p.push(prob('CP-01', 'manual.examples[' + i + '].board', 'the example has no board fragment')); } });
    return p;
  }

  /** Everything the browser can tell about a chip document. */
  function check(doc) {
    const p = [];
    if (!NAME.test(String(doc.chip || ''))) { p.push(prob('BC-01', 'chip', 'a chip name is lower-case letters, digits and dashes, starting with a letter')); }
    if (!(Number.isInteger(doc.version) && doc.version > 0)) { p.push(prob('BC-01', 'version', 'the version is a positive whole number')); }
    if (!doc.title) { p.push(prob('BC-01', 'title', 'the chip needs a title for the Chip Store palette')); }
    return p.concat(pinProblems(doc), manualProblems(doc), bodyProblems(doc).problems);
  }

  /** The in pins nothing in the body reads, and the out pins nothing drives (the bench's CP-03 list). */
  function usage(doc) {
    const used = {};
    strings(doc.body || {}, 'body', []).forEach(([, s]) => { const r = reads(s); r.slots.concat(r.ats).forEach((x) => { used[x] = true; }); });
    Object.keys(((doc.body || {}).wires || {})).forEach((x) => { used[x] = true; });          // a module's pins are read by its inner chips
    const driven = bodyProblems(doc).emitted;
    return { unused: Object.keys(ins(doc)).filter((x) => !used[x]), undriven: Object.keys(outs(doc)).filter((x) => !driven[x]) };
  }

  /** What breaks against an earlier version (9.3 step 2): removed or retyped pins, a new required in pin without a default. */
  function breaking(prev, next) {
    const out = [];
    Object.keys(ins(prev)).forEach((k) => {
      const a = ins(prev)[k] || {}, b = ins(next)[k];
      if (!b) { out.push('in pin ' + k + ' was removed'); } else if (a.type !== b.type) { out.push('in pin ' + k + ' changed type ' + a.type + ' → ' + b.type); }
    });
    Object.keys(ins(next)).forEach((k) => { const b = ins(next)[k] || {}; if (!(k in ins(prev)) && b.required && (b.default == null || b.default === '')) { out.push('new required in pin ' + k + ' has no default'); } });
    Object.keys(outs(prev)).forEach((k) => {
      const a = outs(prev)[k] || {}, b = outs(next)[k];
      if (!b) { out.push('out pin ' + k + ' was removed'); } else if (a.type !== b.type) { out.push('out pin ' + k + ' changed type ' + a.type + ' → ' + b.type); }
    });
    return out;
  }

  /**
   * A module chip from wired instances of a board (9.5, minimal): the inner board (the instances, without their supplies, and the traces between
   * them) becomes the body; every in pin not fed inside and every out pin not consumed inside becomes a module pin named <instance>-<pin>.
   * chipDoc(ref) gives each instance's chip document.
   */
  function makeModule(board, chosen, chipDoc, name) {
    const inner = {}, traces = (board.traces || []).filter((t) => chosen.indexOf(t.from.split('.')[0]) >= 0 && chosen.indexOf(t.to.split('.')[0]) >= 0);
    const fed = {}, consumed = {};
    traces.forEach((t) => { fed[t.to] = true; consumed[t.from] = true; });
    const doc = blank(name);
    doc.title = 'Module: ' + chosen.join(' + ');
    doc.category = 'modules';
    doc.body = { board: { chips: inner, traces: traces.map((t) => Object.assign({}, t)) }, wires: {}, exposes: {} };
    chosen.forEach((inst) => {
      const i = (board.chips || {})[inst] || {}, c = chipDoc(i.use) || {};
      inner[inst] = { use: i.use };
      if (i.title) { inner[inst].title = i.title; }
      if (i.controls) { inner[inst].controls = Object.assign({}, i.controls); }
      Object.keys(ins(c)).forEach((pin) => {
        if (fed[inst + '.' + pin] || (i.controls && pin in i.controls)) { return; }        // fed inside: a trace or a constant
        const mp = inst + '-' + pin;
        doc.pins.in[mp] = JSON.parse(JSON.stringify(ins(c)[pin]));
        doc.body.wires[mp] = inst + '.' + pin;
        doc.manual.pins[mp] = ((c.manual || {}).pins || {})[pin] || ('The ' + pin + ' pin of ' + inst + '.');
      });
      Object.keys(outs(c)).forEach((pin) => {
        if (consumed[inst + '.' + pin]) { return; }
        const mp = inst + '-' + pin, spec = JSON.parse(JSON.stringify(outs(c)[pin]));
        delete spec.of; delete spec['shape-of'];
        doc.pins.out[mp] = spec;
        doc.body.exposes[mp] = inst + '.' + pin;
        doc.manual.pins[mp] = ((c.manual || {}).pins || {})[pin] || ('Out. The ' + pin + ' of ' + inst + '.');
      });
    });
    const purposes = chosen.map((inst) => (((chipDoc(((board.chips || {})[inst] || {}).use) || {}).manual || {}).purpose || '').trim()).filter(Boolean);
    doc.manual.purpose = purposes.length ? 'A module of ' + chosen.join(' and ') + '. ' + purposes.join(' ') : '';
    doc.manual.behaviour = 'As its inner chips: ' + traces.map((t) => t.from + ' drives ' + t.to).join('; ') + (traces.length ? '.' : '');
    return doc;
  }

  /**
   * A composite chip's own rules (CP-07, "a board with no connectors"): no supply inside, every inner pin a chip pin is wired to exists with the
   * same type, every required inner in pin fed (an inner trace, a chip pin, a constant or its default), at most 3 levels of chips in chips.
   * chipDoc(ref) gives the inner chips' documents (null while unknown: those rules wait).
   */
  function compositeProblems(doc, chipDoc, depth) {
    const body = doc.body || {}, b = body.board;
    if (!b) { return []; }
    const p = [], inner = b.chips || {}, wires = body.wires || {}, exposes = body.exposes || {};
    const fed = {};
    (b.traces || []).forEach((t) => { fed[t.to] = true; });
    Object.keys(wires).forEach((mp) => { fed[wires[mp]] = true; });
    Object.keys(inner).forEach((inst) => {
      const i = inner[inst] || {}, at = 'body.board.chips.' + inst;
      if (i.supply) { p.push(prob('CP-07', at + '.supply', 'a composite chip has no connector: ' + inst + ' names a supply, but data enters a chip only through its pins', 'remove the supply and expose the pin as a chip pin')); }
      const c = chipDoc(i.use);
      if (!c) { return; }
      Object.keys(ins(c)).forEach((pin) => {
        const s = ins(c)[pin] || {};
        if (s.required && !fed[inst + '.' + pin] && !(i.controls && pin in i.controls) && (s.default == null || s.default === '')) {
          p.push(prob('CP-07', at + '.' + pin, 'the required pin ' + inst + '.' + pin + ' is not fed: expose it as a chip pin, or trace it from an inner chip', 'expose ' + inst + '.' + pin));
        }
      });
      if ((depth || 1) >= 3 && (c.body || {}).board) { p.push(prob('CP-07', at + '.use', 'chips in chips go 3 levels deep at most: ' + i.use + ' is a composite inside a third level')); }
      if ((c.body || {}).board && (depth || 1) < 3) { compositeProblems(c, chipDoc, (depth || 1) + 1).filter((x) => /depth|deep/.test(x.message)).forEach((x) => p.push(x)); }
    });
    [['in', wires, ins], ['out', exposes, (d) => outs(d)]].forEach(([dir, map, side]) => Object.keys(map).forEach((mp) => {
      const [inst, pin] = String(map[mp]).split('.'), i = inner[inst], c = i && chipDoc(i.use);
      const own = (dir === 'in' ? ins(doc) : outs(doc))[mp];
      if (!i) { p.push(prob('CP-07', 'pins.' + dir + '.' + mp, mp + ' is wired to ' + map[mp] + ', which is not an inner chip')); return; }
      if (!c) { return; }
      const inner2 = side(c)[pin];
      if (!inner2) { p.push(prob('CP-07', 'pins.' + dir + '.' + mp, mp + ' is wired to ' + map[mp] + ', which is no ' + dir + ' pin of ' + i.use)); return; }
      if (!own) { p.push(prob('CP-07', 'pins.' + dir + '.' + mp, 'the chip pin ' + mp + ' is wired but not declared')); } else if (own.type !== inner2.type) { p.push(prob('CP-07', 'pins.' + dir + '.' + mp, 'the chip pin ' + mp + ' is ' + own.type + ' but ' + map[mp] + ' is ' + inner2.type, 'give it the inner pin\'s type')); }
    }));
    return p;
  }

  /** A small block-style YAML writer for plain values (the screen designer's inspector reads its Sutra text with Studio's reader). */
  function yaml(v, ind) {
    ind = ind || '';
    const sc = (x) => (x === null || x === undefined ? 'null' : (typeof x === 'string' ? JSON.stringify(x) : String(x)));
    if (Array.isArray(v)) {
      if (!v.length) { return ' []'; }
      return '\n' + v.map((x) => ind + '-' + (x && typeof x === 'object' && !Array.isArray(x) && Object.keys(x).length ? ' ' + yaml(x, ind + '  ').replace(/^\n\s*/, '') : (x && typeof x === 'object' ? yaml(x, ind + '  ') : ' ' + sc(x)))).join('\n');
    }
    if (v && typeof v === 'object') {
      const ks = Object.keys(v);
      if (!ks.length) { return ' {}'; }
      return '\n' + ks.map((k) => ind + k + ':' + (v[k] && typeof v[k] === 'object' ? yaml(v[k], ind + '  ') : ' ' + sc(v[k]))).join('\n');
    }
    return ' ' + sc(v);
  }

  U.chipModel = { compositeProblems, TYPES, COLUMN_TYPES, MANUAL_KEYS, AT_BUILTINS, NAME, blank, shapeText, parseShape, pinProblems, manualProblems, bodyProblems, badRefs, check, usage, breaking, makeModule, yaml };
})();
