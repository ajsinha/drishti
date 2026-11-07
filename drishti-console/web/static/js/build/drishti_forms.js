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
(function (g) { g.Drishti = g.Drishti || {}; })(typeof window !== 'undefined' ? window : globalThis); /* one namespace for everything the console puts on window (tools/dev/asset_prefix.py) */
/* The inspector's options that are not plain text (QA UX-03), drawn as drishti-inputs' Form (live): numbers (checked in words), a number or
 * "all", a fixed set (select), true/false (a switch), a list of names, a list of objects, and an object with its own properties (the
 * table's `pivot`, histogram `markers`, a pivot's `by`), and a switch with its settings. A piece of the Rachana schema becomes one field
 * of a form's schema; the value goes in and comes back out in the document's own shape. Every control carries a label.
 *
 *   WB.Forms.edit(schema, value, onChange, name, enums) -> element      onChange(newValue) with null to clear the option
 *   enums: {fmt: [...], tone: [...]} for the properties of those names                                                          */
(function () {
  'use strict';
  var WB = window.Drishti.WB = window.Drishti.WB || {};
  var el = function (t, c, x, a) { return WB.el(t, c, x, a); };
  var SKIP = {};                                       // a value that does not read (a number out of range): the design keeps its own

  function jsType(v) {
    if (Array.isArray(v)) { return 'array'; }
    if (v === null || v === undefined) { return ''; }
    return typeof v === 'number' ? 'integer' : typeof v;
  }
  /** The one kind of control for a schema node and the value now in it. */
  function kindOf(s, value) {
    s = s || {};
    if (s['enum']) { return 'enum'; }
    if (s.oneOf) {
      var consts = s.oneOf.filter(function (o) { return o['const'] !== undefined; });
      var types = s.oneOf.map(function (o) { return o.type; });
      return consts.length && types.indexOf('integer') >= 0 ? 'count-or-const' : (types.indexOf('array') >= 0 ? 'names' : 'text');
    }
    var t = s.type;
    if (Array.isArray(t)) {
      var j = jsType(value);
      if (t.indexOf('boolean') >= 0 && t.indexOf('object') >= 0) { return 'toggle-object'; }
      return t.indexOf(j) >= 0 ? j : t[0];
    }
    if (t === 'number') { return 'integer'; }
    if (t === 'array') { return s.items && s.items.properties ? 'objects' : 'names'; }
    return t || 'text';
  }
  function empty(v) { return v === null || v === undefined || v === '' || v === false; }

  /** A piece of the schema as a form field: {field, into(value), out(formValue)}. */
  function toField(s, value, name, enums) {
    s = s || {};
    enums = enums || {};
    var leaf = name.split('.').pop(), kind = kindOf(s, value);
    if (kind === 'enum' || (enums[leaf] && (kind === 'string' || kind === 'text'))) {
      var opts = (s['enum'] || enums[leaf]).map(String);
      if (value != null && value !== '' && opts.indexOf(String(value)) < 0) { opts = opts.concat([String(value)]); }
      return { field: { type: 'string', enum: opts, widget: 'select', emptyLabel: '(none)', hook: { 'aria-label': name } }, into: function (v) { return v == null || v === '' ? undefined : String(v); },
        out: function (v) { return v === undefined || v === '' ? null : v; } };
    }
    if (kind === 'boolean') { return { field: { type: 'boolean', widget: 'switch', hook: { 'aria-label': name } }, into: function (v) { return v === true; }, out: function (v) { return v ? true : null; } }; }
    if (kind === 'integer') {
      return { field: { type: 'integer', minimum: s.minimum == null ? undefined : s.minimum, maximum: s.maximum == null ? undefined : s.maximum, lazy: true, hook: { 'aria-label': name } },
        into: function (v) { return typeof v === 'number' ? v : undefined; },
        out: function (v) {
          if (v === undefined || v === null) { return null; }
          if (typeof v !== 'number' || !Number.isInteger(v) || (s.minimum != null && v < s.minimum) || (s.maximum != null && v > s.maximum)) { return SKIP; }
          return v;
        } };
    }
    if (kind === 'count-or-const') {
      var c = (s.oneOf.filter(function (o) { return o['const'] !== undefined; })[0] || {})['const'], min = (s.oneOf.filter(function (o) { return o.type === 'integer'; })[0] || {}).minimum;
      return { field: { type: 'object', properties: { n: { type: 'integer', title: leaf, hook: { 'aria-label': name }, minimum: min != null ? min : 1, lazy: true, when: { field: 'all', falsy: true } }, all: { type: 'boolean', title: String(c) } } },
        into: function (v) { return v === c ? { all: true } : { all: false, n: typeof v === 'number' ? v : undefined }; },
        out: function (v) { v = v || {}; return v.all ? c : (typeof v.n === 'number' && !isNaN(v.n) ? v.n : null); } };
    }
    if (kind === 'names') {
      return { field: { type: 'array', addLabel: 'Add ' + name.replace(/s$/, ''), items: { type: 'object', title: name, properties: { value: { type: 'string', title: leaf, lazy: true } } } },
        into: function (v) { return (Array.isArray(v) ? v : v == null ? [] : [v]).map(function (x) { return { value: x }; }); },
        out: function (v) { var o = (v || []).map(function (x) { return (x || {}).value; }).filter(function (x) { return x !== undefined && x !== ''; }); return !o.length ? null : (o.length === 1 && s.oneOf ? o[0] : o); } };
    }
    if (kind === 'objects') {
      var it = s.items || {}, strings = Array.isArray(it.type) && it.type.indexOf('string') >= 0 && !it.properties;
      var inner = strings ? null : toField(Object.assign({ type: 'object' }, it), {}, name, enums);
      return { field: { type: 'array', addLabel: 'Add ' + name.replace(/s$/, ''), items: strings ? { type: 'object', title: name, properties: { value: { type: 'string', title: leaf, lazy: true } } } : Object.assign({ title: name }, inner.field) },
        into: function (v) { return (Array.isArray(v) ? v : []).map(function (x) { return typeof x === 'string' ? { value: x } : inner ? inner.into(x) : { value: x }; }); },
        out: function (v) { var o = (v || []).map(function (x) { return strings || (x && x.value !== undefined && !inner) ? (x || {}).value : inner.out(x) || {}; }); return o.length ? o : null; } };
    }
    if (kind === 'object' && s.properties) {
      var parts = {}, props = {};
      Object.keys(s.properties).forEach(function (k) { parts[k] = toField(Object.assign({}, s.properties[k]), (value || {})[k], name + '.' + k, enums); props[k] = Object.assign({ title: k }, parts[k].field); });
      return { field: { type: 'object', properties: props },
        into: function (v) { var o = {}; v = v && typeof v === 'object' && !Array.isArray(v) ? v : {}; Object.keys(parts).forEach(function (k) { var x = parts[k].into(v[k]); if (x !== undefined) { o[k] = x; } }); Object.keys(v).forEach(function (k) { if (!parts[k]) { o[k] = v[k]; } }); return o; },
        out: function (v) {
          var o = {};
          v = v || {};
          Object.keys(v).forEach(function (k) { var x = parts[k] ? parts[k].out(v[k]) : v[k]; if (x === SKIP) { return; } if (!empty(x)) { o[k] = x; } });
          return Object.keys(o).length ? o : null;
        } };
    }
    if (kind === 'toggle-object') {
      var settings = toField(Object.assign({}, s, { type: 'object' }), value && typeof value === 'object' ? value : {}, name, enums);
      return { field: { type: 'object', properties: { on: { type: 'boolean', widget: 'switch', title: name + ' on' }, settings: Object.assign({ title: 'Settings', when: { field: 'on', truthy: true } }, settings.field) } },
        into: function (v) { return { on: v === true || !!(v && typeof v === 'object'), settings: settings.into(v && typeof v === 'object' ? v : {}) }; },
        out: function (v) { v = v || {}; if (!v.on) { return null; } var o = settings.out(v.settings); return o === SKIP ? SKIP : (o || true); } };
    }
    return { field: { type: 'string', lazy: true, hook: { 'aria-label': name } }, into: function (v) { return v == null ? undefined : String(v); }, out: function (v) { return v === undefined || v === '' ? null : v; } };
  }

  /** The control of one option: a live drishti-inputs Form with one field, the option. */
  function edit(s, value, onChange, name, enums) {
    var I = window.Drishti.elements && window.Drishti.elements.inputs;
    var conv = toField(s, value, name, enums), host = el('div', 'wb-form', null, { 'data-wb-form': name });
    if (!I) { return host; }
    var form = new I.Form(host, { live: true, schema: { type: 'object', properties: { v: Object.assign({}, conv.field, { title: name }) } }, value: { v: conv.into(value) } });
    form.on('change', function () { var out = conv.out(form.getField('v')); if (out !== SKIP) { onChange(out); } });
    return host;
  }

  /** A caption for a control made of several (a number with an "all" box, a switch with its settings). */
  function captioned(text, node) {
    var w = el('fieldset', 'wb-field wb-cap-set');
    w.appendChild(el('legend', 'wb-cap', text)); w.appendChild(node);
    return w;
  }

  WB.Forms = { edit: edit, captioned: captioned };
})();
