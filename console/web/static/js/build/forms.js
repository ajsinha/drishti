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
/* Form controls made from a piece of the Rachana schema, for the inspector's options that are not plain text (QA UX-03): numbers,
 * a number or "all", a fixed set (select), true/false, a list of names, a list of objects, and an object with its own properties
 * (the table's `pivot`, histogram `markers`, a pivot's `by`). Every control carries a label.
 *
 *   WB.Forms.edit(schema, value, onChange, name, enums) -> element      onChange(newValue) with null to clear the option
 *   enums: {fmt: [...], tone: [...]} for the properties of those names                                                          */
(function () {
  'use strict';
  var WB = window.DrishtiWB = window.DrishtiWB || {};
  var uid = 0;
  var el = function (t, c, x, a) { return WB.el(t, c, x, a); };

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
  function labelled(text, input) {
    var id = 'wbf' + (++uid), w = el('div', 'wb-field');
    input.id = id; w.appendChild(el('label', null, text, { 'for': id })); w.appendChild(input);
    return w;
  }
  /** A caption for a control made of several (a number with an "all" box, a switch with its settings). */
  function captioned(text, node) {
    var w = el('fieldset', 'wb-field wb-cap-set');
    w.appendChild(el('legend', 'wb-cap', text)); w.appendChild(node);
    return w;
  }
  function select(options, value, onChange, aria) {
    var s = el('select', 'studio-in', null, aria ? { 'aria-label': aria } : null);
    if (value && options.indexOf(String(value)) < 0) { options = options.concat([String(value)]); }
    [''].concat(options).forEach(function (o) { var op = el('option', null, o || '(none)', { value: o }); if (String(value == null ? '' : value) === o) { op.selected = true; } s.appendChild(op); });
    s.addEventListener('change', function () { onChange(s.value === '' ? null : s.value); });
    return s;
  }
  function text(value, onChange, aria) {
    var i = el('input', 'studio-in mono', null, { type: 'text', autocomplete: 'off', spellcheck: 'false', 'aria-label': aria });
    i.value = value == null ? '' : String(value);
    i.addEventListener('change', function () { onChange(i.value === '' ? null : i.value); });
    return i;
  }

  /** A list: each item edited by `make(itemValue, setItem)`; add, remove and reorder. `blank` is what Add puts in. */
  function list(name, items, blank, make, onChange) {
    items = items.slice();
    var wrap = el('fieldset', 'wb-list'), body = el('div');
    wrap.appendChild(el('legend', null, name)); wrap.appendChild(body);
    var emit = function () { onChange(items.length ? items.slice() : null); };
    function paint(focusAt) {
      body.textContent = '';
      items.forEach(function (it, idx) {
        var row = el('div', 'wb-item', null, { role: 'group', 'aria-label': name + ' ' + (idx + 1) });
        row.appendChild(make(it, idx, function (v) { items[idx] = v; emit(); }));
        var tools = el('div', 'wb-item-tools');
        [['↑', 'Move up', -1], ['↓', 'Move down', 1]].forEach(function (b) {
          var btn = el('button', 'btn-pill btn-ghost', b[0], { type: 'button', 'aria-label': b[1] + ': ' + name + ' ' + (idx + 1) });
          btn.disabled = idx + b[2] < 0 || idx + b[2] >= items.length;
          btn.addEventListener('click', function () { items.splice(idx + b[2], 0, items.splice(idx, 1)[0]); emit(); paint(idx + b[2]); });
          tools.appendChild(btn);
        });
        var rm = el('button', 'btn-pill btn-ghost', 'Remove', { type: 'button', 'aria-label': 'Remove ' + name + ' ' + (idx + 1) });
        rm.addEventListener('click', function () { items.splice(idx, 1); emit(); paint(Math.max(0, idx - 1)); });
        tools.appendChild(rm); row.appendChild(tools); body.appendChild(row);
      });
      if (focusAt != null) { var f = body.children[focusAt] && body.children[focusAt].querySelector('input, select'); if (f) { f.focus(); } }
    }
    var add = el('button', 'btn-pill btn-ghost', 'Add ' + name.replace(/s$/, ''), { type: 'button' });
    add.addEventListener('click', function () { items.push(typeof blank === 'function' ? blank() : blank); paint(items.length - 1); });
    wrap.appendChild(add);
    paint();
    return wrap;
  }

  function objectFields(s, value, onChange, name, enums) {
    var obj = Object.assign({}, value && typeof value === 'object' && !Array.isArray(value) ? value : {});
    var box = el('div', 'wb-obj');
    Object.keys(s.properties || {}).forEach(function (k) {
      var sub = Object.assign({}, s.properties[k]);
      var ed = edit(sub, obj[k], function (v) { if (v === null || v === undefined || v === '' || v === false) { delete obj[k]; } else { obj[k] = v; } onChange(Object.keys(obj).length ? Object.assign({}, obj) : null); }, name + '.' + k, enums);
      box.appendChild(ed.tagName === 'FIELDSET' ? ed : (ed.tagName === 'INPUT' || ed.tagName === 'SELECT' ? labelled(k, ed) : captioned(k, ed)));
    });
    return box;
  }

  function edit(s, value, onChange, name, enums) {
    s = s || {};
    enums = enums || {};
    var leaf = name.split('.').pop();
    var kind = kindOf(s, value);
    if (kind === 'enum' || (enums[leaf] && (kind === 'string' || kind === 'text'))) { return select(s['enum'] || enums[leaf], value, onChange, name); }
    if (kind === 'boolean') {
      var cb = el('input', null, null, { type: 'checkbox', role: 'switch', 'aria-label': name });
      cb.checked = value === true;
      cb.addEventListener('change', function () { onChange(cb.checked ? true : null); });
      return cb;
    }
    if (kind === 'integer') {
      var n = el('input', 'studio-in', null, { type: 'number', min: s.minimum != null ? s.minimum : '', max: s.maximum != null ? s.maximum : '', 'aria-label': name });
      n.value = value == null ? '' : value;
      n.addEventListener('change', function () {
        if (n.value === '') { value = null; n.setCustomValidity(''); onChange(null); return; }
        var v = Number(n.value), min = s.minimum, max = s.maximum;
        if (!Number.isInteger(v) || (min != null && v < min) || (max != null && v > max)) {      // not a whole number or out of range: say so, keep what the design has
          n.setCustomValidity('A whole number' + (min != null && max != null ? ' from ' + min + ' to ' + max : min != null ? ' of ' + min + ' or more' : '') + '.');
          n.reportValidity(); n.value = value == null ? '' : value; n.setCustomValidity('');
          return;
        }
        value = v; onChange(v);
      });
      return n;
    }
    if (kind === 'count-or-const') {
      var c = (s.oneOf.filter(function (o) { return o['const'] !== undefined; })[0] || {})['const'], min = (s.oneOf.filter(function (o) { return o.type === 'integer'; })[0] || {}).minimum;
      var w = el('div', 'wb-inline'), num = el('input', 'studio-in', null, { type: 'number', min: min != null ? min : 1, 'aria-label': name }), id = 'wbf' + (++uid);
      var all = el('input', null, null, { type: 'checkbox', id: id }), lab = el('label', null, c, { 'for': id });
      num.value = typeof value === 'number' ? value : ''; all.checked = value === c; num.disabled = all.checked;
      num.addEventListener('change', function () { onChange(num.value === '' ? null : parseInt(num.value, 10)); });
      all.addEventListener('change', function () { num.disabled = all.checked; if (all.checked) { num.value = ''; onChange(c); } else { onChange(null); } });
      w.appendChild(num); w.appendChild(all); w.appendChild(lab);
      return w;
    }
    if (kind === 'names') {
      var names = Array.isArray(value) ? value : (value == null ? [] : [value]);
      return list(name, names, '', function (v, i, set) { return labelled(leaf + ' ' + (i + 1), text(v, function (x) { set(x === null ? '' : x); }, name + ' ' + (i + 1))); },
        function (v) { var out = (v || []).filter(function (x) { return x !== ''; }); onChange(!out.length ? null : (out.length === 1 && s.oneOf ? out[0] : out)); });
    }
    if (kind === 'objects') {
      var it = s.items || {};
      return list(name, Array.isArray(value) ? value : [], function () { return Array.isArray(it.type) && it.type.indexOf('string') >= 0 ? '' : {}; },
        function (v, i, set) {
          if (typeof v === 'string') { return labelled(leaf + ' ' + (i + 1), text(v, function (x) { set(x === null ? '' : x); }, name + ' ' + (i + 1))); }
          return objectFields(it, v, function (o) { set(o || {}); }, name + ' ' + (i + 1), enums);
        }, function (v) { onChange(v); });
    }
    if (kind === 'object' && s.properties) { return objectFields(s, value, onChange, name, enums); }
    if (kind === 'toggle-object') {
      var wrap = el('div', 'wb-toggle-object'), inner = el('div'), on = value === true || (value && typeof value === 'object');
      var sw = el('input', null, null, { type: 'checkbox', role: 'switch', 'aria-label': name + ' on' });
      sw.checked = !!on;
      var show = function () { inner.textContent = ''; inner.hidden = !sw.checked; if (sw.checked) { inner.appendChild(objectFields(s, value && typeof value === 'object' ? value : {}, function (o) { onChange(o || true); }, name, enums)); } };
      sw.addEventListener('change', function () { value = sw.checked ? true : null; onChange(value); show(); });
      wrap.appendChild(sw); wrap.appendChild(el('span', null, ' on')); wrap.appendChild(inner); show();
      return wrap;
    }
    return text(value, onChange, name);
  }

  WB.Forms = { edit: edit, list: list, select: select, labelled: labelled, captioned: captioned };
})();
