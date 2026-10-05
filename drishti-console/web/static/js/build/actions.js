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
/* What the canvas, the menus and the data pane ask for, as operations: add a panel (from a palette kind, or from the best
 * suggestion for a field), move one, size one, remove one, bind a field. One module so a drag and its keyboard twin (Add panel...,
 * Bind field..., Alt+arrows, Shift+arrows) cannot drift apart: both end here.
 *
 *   new WB.Actions(store, ui) -> {add, dropped, bind, resize, remove, suggest, menuAdd, menuBind, fields(list)}
 *   ui: {canvas(), inspect(sel, opts), selection(), say(text, bad)}                                                           */
(function () {
  'use strict';
  var WB = window.DrishtiWB = window.DrishtiWB || {};

  WB.Actions = function (store, ui) {
    var fieldList = [];
    var say = function (t, bad) { store.emit('say', t, bad); };
    function toAt(a) {
      if (!a) { return { area: 'main' }; }
      var at = { area: a.area || 'main' };
      if (a.id) { at[a.rel === 'before' ? 'before' : 'after'] = a.id; }
      return at;
    }
    function newId(before, after) {
      var old = WB.model(before).ids, now = WB.model(after).ids;
      return now.filter(function (i) { return old.indexOf(i) < 0; })[0] || null;
    }

    /** Values for the options a kind cannot go without, taken from the shape so the new panel draws something at once: the first list for
     *  rows, its first number for a value or an axis, its first text for a grouping; a note for markdown. The inspector marks them to check. */
    var note = '', NUMERIC_ROWS = ['hbar', 'waterfall', 'line', 'area', 'scatter', 'candlestick', 'histogram', 'surface'];
    function defaults(kind) {
      var out = {}, req = (ui.required ? ui.required(kind) : []).filter(function (n) { return n !== 'id' && n !== 'kind'; });
      var direct = function (list, f) { return list.filter(function (x) { return x.path.indexOf(f.path + '[].') === 0 && x.path.slice(f.path.length + 3).search(/[.\[{]/) < 0; }); };
      var numeric = function (f) { return direct(fieldList, f).some(function (x) { return /integer|number/.test(x.type); }); };
      var arrays = fieldList.filter(function (f) { return f.type === 'array' && f.presence >= 0.5 && f.path.indexOf('[]') < 0 && direct(fieldList, f).length; });
      // a chart reads numbers: a list without one would draw "no data" while the check stays green, so lists with a number come first
      if (NUMERIC_ROWS.indexOf(kind) >= 0) { arrays = arrays.filter(numeric).concat(arrays.filter(function (f) { return !numeric(f); })); }
      note = '';
      if (NUMERIC_ROWS.indexOf(kind) >= 0 && (!arrays.length || !numeric(arrays[0]))) { note = 'No list with numbers in your samples, so rows is a placeholder: point it at the data this panel should draw.'; }
      if (kind === 'gauge') { note = 'The gauge compares one value with its maximum (100 unless you set max): set max to your limit.'; }
      var list = arrays[0] || null, rows = list ? list.path : '$.rows', under = list ? direct(fieldList, list) : [];
      var num = under.filter(function (f) { return /integer|number/.test(f.type); })[0], txt = under.filter(function (f) { return f.type === 'string'; })[0];
      var rel = function (f, d) { return f ? '@.' + f.path.slice(list.path.length + 3) : d; };
      var top = fieldList.filter(function (f) { return /integer|number/.test(f.type) && f.path.indexOf('[]') < 0 && f.path.indexOf('{}') < 0; })[0];
      req.forEach(function (n) {
        if (n === 'rows' || n === 'each' || n === 'nodes') { out[n] = rows; }
        else if (n === 'text') { out[n] = 'Write a note here.'; }
        else if ((kind === 'gauge' || kind === 'metric') && n === 'value') { out[n] = top ? top.path : '$.value'; }
        else if (n === 'by' || n === 'across') { out[n] = rel(txt, '@.name'); }
        else { out[n] = rel(num, '@.value'); }
      });
      return out;
    }

    /** AddPanel; the new panel is selected and the inspector opens on it, its required options marked. */
    function add(kind, at, options, why) {
      var blank = !store.state.yaml.trim();
      // a design with no Sutra yet starts from the smallest valid one (the server edits a Sutra, it does not invent one), and the placeholder goes again
      var starter = 'rachana: 1\nsutra: my-layout\nversion: 1\nmatch: { kind: ' + store.state.kind + ' }\ntitle: { pill: "' + store.state.kind + '", id: $.id }\npanels:\n  - { id: refs, kind: links, title: Linked entities, area: right }\n';
      var before = blank ? starter : store.state.yaml, op = { op: 'addPanel', kind: kind, at: toAt(at) };
      options = Object.assign({ title: kind.charAt(0).toUpperCase() + kind.slice(1) }, defaults(kind), options || {});
      why = why || note;
      if (options && Object.keys(options).length) { op.options = options; }
      return store.send(blank ? [{ op: 'text', yaml: starter }, op, { op: 'remove', panel: 'refs' }] : [op]).then(function (r) {
        var id = r.body ? newId(before, r.body.yaml) : null;
        if (!r.applied || !id) { return null; }
        say('Added ' + (/^[aeiou]/.test(kind) ? 'an ' : 'a ') + kind + ' panel (' + id + ')' + (why ? ': ' + why : '') + '. Its options are in the inspector; required ones are marked.');
        if (ui.design) { ui.design(); }                                   // from the YAML or Summary tab too: the new panel is shown
        ui.canvas().select({ type: 'panel', id: id }, true);
        ui.inspect({ type: 'panel', id: id }, { required: true });
        var e = ui.canvas().el(id);
        if (e) {
          if (e.scrollIntoView) { e.scrollIntoView({ block: 'center', behavior: 'auto' }); }
          e.classList.add('wb-new'); setTimeout(function () { e.classList.remove('wb-new'); }, 1800);
        }
        return id;
      });
    }
    function move(id, at) {
      var op = { op: 'move', panel: id, area: at.area || undefined };
      if (at.id) { op[at.rel === 'before' ? 'before' : 'after'] = at.id; }
      return store.send([op], 'Moved ' + id);
    }
    function resize(id, o) {          // SetOption, not Move: a Move with only a size would send the panel to the end of its column
      var op = { op: 'setOption', panel: id };
      if (o.span !== undefined) { op.option = 'span'; op.value = o.span >= 12 ? null : o.span; } else { op.option = 'height'; op.value = o.height ? o.height : null; }
      var said = o.span !== undefined ? (o.span + ' of 12 columns wide') : (o.height ? o.height + ' rows tall' : 'as tall as its content');
      return store.send([op], id + ' is ' + said);
    }
    function remove(id, next, name) {
      return store.send([{ op: 'remove', panel: id }], 'Removed ' + id).then(function (r) {
        if (r.ok) {
          if (ui.toast) { ui.toast("Removed '" + (name || id) + "'", 'Undo', function () { store.undo(); }); }
          ui.canvas().select(next, true);
          if (next) { ui.canvas().focus(); } else { ui.inspect(null); }
          say('Removed ' + id + '. Ctrl+Z undoes it.' + (next ? ' ' + next.id + ' is selected.' : ''));
        }
        return r;
      });
    }
    /** A copy of a panel right after it (its options, a new id, the title marked as a copy). */
    function duplicate(id) {
      var m = WB.model(store.state.yaml).panels.filter(function (p) { return p.id === id; })[0];
      if (!m) { say('This panel is gone.', true); return Promise.resolve(); }
      var o = {};
      Object.keys(m.values).forEach(function (k) { if (k !== 'id' && k !== 'kind') { o[k] = m.values[k]; } });
      o.title = (o.title || m.kind) + ' (copy)';
      return add(m.kind, { area: ui.canvas().areaOf(id) || 'main', rel: 'after', id: id }, o);
    }
    function bind(id, path, role) {
      var op = { op: 'bind', panel: id, path: path };
      if (role) { op.role = role; }
      return store.send([op]).then(function (r) {
        if (r.ok) { say('Bound ' + path + ' to ' + id + '.'); ui.canvas().select({ type: 'panel', id: id }, true); ui.inspect({ type: 'panel', id: id }); }
        else if (!r.problems.length) { /* a conflict or a failed request already said why */ }
        return r;
      });
    }
    function stripAdd(path) {
      var strip = WB.model(store.state.yaml).strip || [], leaf = path.split(/[.\[\]]+/).filter(Boolean).pop() || 'Value';
      if (strip.length >= 8) { say('The strip holds eight figures at most. Remove one first.', true); return Promise.resolve(); }
      var items = strip.concat([{ label: leaf.charAt(0).toUpperCase() + leaf.slice(1), bind: path }]);
      return store.send([{ op: 'setStrip', items: items }], 'Added ' + path + ' to the strip');
    }

    /** What a ranked choice from /suggest becomes: options with the columns it suggests (fields for kv and status). */
    function optionsOf(c) {
      var o = {}, cols = (c.columns || []).map(function (x) {
        var out = { label: x.label, bind: x.bind };
        ['fmt', 'tone'].forEach(function (k) { if (x[k]) { out[k] = x[k]; } });
        ['total', 'link'].forEach(function (k) { if (x[k]) { out[k] = true; } });
        return out;
      });
      Object.keys(c.options || {}).forEach(function (k) { o[k] = c.options[k]; });
      if (c.title) { o.title = c.title; }
      if (cols.length) { o[c.kind === 'kv' || c.kind === 'status' ? 'fields' : 'columns'] = cols; }
      return o;
    }
    function suggest(path, point, at) {
      say('Looking for panels that suit ' + path + '...');
      return WB.call('POST', store.base + '/suggest', { path: path }).then(function (r) {
        var list = r.ok ? (r.body.suggestions || []) : [];
        if (!r.ok) { say(WB.why(r), true); return; }
        if (!list.length) { say(path + ' has no panel that suits it. Drag it onto a panel to bind it instead.', true); return; }
        say(list.length + (list.length === 1 ? ' panel kind suits ' : ' panel kinds suit ') + path + (list.length === 1 ? '. Enter adds it.' : ', best first. Up and Down choose, Enter adds.'));
        WB.menu.open({
          title: 'Panels for ' + path, at: point || { x: 200, y: 200 }, anchor: point ? null : document.querySelector('[data-canvas]'),
          items: list.map(function (c) { return { label: c.kind, badge: Math.round(c.score * 100) + '%', detail: c.reason, value: c }; }),
          onPick: function (it) { add(it.value.kind, at ? { area: at.area || it.value.area, rel: at.rel, id: at.id } : { area: it.value.area }, optionsOf(it.value), it.value.reason); }
        });
      });
    }

    /** A drop on the canvas: payload is what was dragged, hit what is under it, at the place in the column. */
    function dropped(payload, hit, at, point) {
      if (payload.type === 'panel') { if (at.id === payload.id) { return Promise.resolve(); } return move(payload.id, at); }
      if (payload.type === 'kind') { return add(payload.kind, at); }
      if (payload.type === 'field') {
        if (hit && hit.type === 'panel' && !hit.gap && !hit.edge) { return bind(hit.id, payload.path); }
        if (hit && hit.type === 'strip') { return stripAdd(payload.path); }
        if (hit && hit.type === 'title') { say('A field cannot be dropped on the title: drop it on a panel, the strip or an empty place.', true); return Promise.resolve(); }
        return suggest(payload.path, point, at);
      }
      return Promise.resolve();
    }
    function afterSelected() {
      var s = ui.canvas().selected();
      if (s && s.type === 'panel') { return { area: ui.canvas().areaOf(s.id) || 'main', rel: 'after', id: s.id }; }
      var ps = ui.canvas().panels(ui.canvas().columns().main);
      return ps.length ? { area: 'main', rel: 'after', id: ps[ps.length - 1].getAttribute('data-panel') } : { area: 'main' };
    }
    function menuAdd(anchor) {
      WB.menu.open({ title: 'Add panel', anchor: anchor || document.querySelector('[data-add-menu]'), filter: true, items: WB.palette.items(),
        onPick: function (it) { add(it.value, afterSelected()); } });
    }
    function menuBind(anchor) {
      var s = ui.canvas().selected();
      if (!s || s.type !== 'panel') { say('Select a panel first, then bind a field to it.', true); return; }
      if (!fieldList.length) { say('This design has no shape yet: add a sample first.', true); return; }
      WB.menu.open({ title: 'Bind a field to ' + s.id, anchor: anchor || ui.canvas().el(s.id) || document.querySelector('[data-canvas]'), filter: true,
        items: fieldList.map(function (f) { return { label: f.path, badge: f.role || '', detail: f.type + (f.presence < 0.5 ? ' · rare' : ''), value: f.path }; }),
        onPick: function (it) { bind(s.id, it.value); } });
    }
    return { duplicate: duplicate, add: add, dropped: dropped, bind: bind, resize: resize, remove: remove, suggest: suggest, menuAdd: menuAdd, menuBind: menuBind, move: move,
             fields: function (list) { fieldList = list || []; }, afterSelected: afterSelected };
  };
})();
