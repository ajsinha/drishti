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
/* The workbench's state and its one way to change a Design: operations. Everything that edits the screen (the canvas, the
 * inspector, the YAML tab, the menus) calls store.send([...ops]); the store sends them to /build/designs/{id}/ops with the
 * revision it built on, one at a time and in order, and tells everyone what came back.
 *
 *   events: 'doc' {yaml, rev, problems, applied, source}   the Sutra changed (or an operation was refused: problems)
 *           'preview' {html, name, file}                   a fresh preview, the console's own Studio markup
 *           'say' (text, bad)                              a sentence for the status line and the live region
 *           'conflict'                                     a 409: the design moved on; it has been reloaded
 *   The 409 case reloads the Design from the console and says so; the user's edit is not applied twice or lost silently.
 * All text from the server goes in as text; previewHtml is the console's own template output (values escaped there). */
(function () {
  'use strict';
  var WB = window.DrishtiWB = window.DrishtiWB || {};

  WB.el = function (tag, cls, text, attrs) {
    var e = document.createElement(tag);
    if (cls) { e.className = cls; }
    if (text !== undefined && text !== null) { e.textContent = text; }
    if (attrs) { Object.keys(attrs).forEach(function (k) { e.setAttribute(k, attrs[k]); }); }
    return e;
  };
  WB.why = function (r) { return (r.body && r.body.detail ? r.body.detail : 'The request failed') + ' (' + ((r.body && r.body.code) || r.status) + ')'; };
  WB.call = function (method, url, body) {
    return fetch(url, { method: method, headers: { 'Content-Type': 'application/json', Accept: 'application/json' }, body: body === undefined ? undefined : JSON.stringify(body) })
      .then(function (r) { return r.json().catch(function () { return {}; }).then(function (j) { return { ok: r.ok, status: r.status, body: j }; }); },
        function () { return { ok: false, status: 0, body: { detail: 'The console could not be reached. Try again.', code: 'offline' } }; });
  };

  /** A tiny event bus: on(name, fn), emit(name, ...args). */
  function Bus() { this.h = {}; }
  Bus.prototype.on = function (n, f) { (this.h[n] = this.h[n] || []).push(f); };
  Bus.prototype.emit = function (n) {
    var a = Array.prototype.slice.call(arguments, 1);
    (this.h[n] || []).forEach(function (f) { try { f.apply(null, a); } catch (e) { if (window.console) { window.console.error(e); } } });
  };

  WB.Store = function (init) {
    var bus = new Bus(), base = '/build/designs/' + encodeURIComponent(init.id);
    var st = { id: init.id, kind: init.kind, rev: init.rev, yaml: init.yaml || '', problems: [], opsAt: init.opsAt || 0, opsCount: init.opsCount || 0,
               samples: init.samples || [], sample: (init.samples && init.samples[0]) || '', file: null, status: init.status || 'draft' };
    var queue = Promise.resolve();

    function adopt(b, source) {
      var changed = b.yaml !== st.yaml;
      st.rev = b.rev; st.yaml = b.yaml; st.status = b.status || st.status;
      st.opsAt = b.opsAt || 0; st.opsCount = b.opsCount || 0; st.problems = b.problems || [];
      bus.emit('doc', { yaml: st.yaml, rev: st.rev, problems: st.problems, applied: b.applied, changed: changed, source: source });
      if (b.previewHtml !== undefined && !st.file) { bus.emit('preview', { html: b.previewHtml, name: st.sample }); }
      else if (st.file) { previewFile(st.file); }
      if (b.previewError) { bus.emit('say', 'The preview could not be drawn: ' + b.previewError, true); }
    }
    function conflict() {
      return WB.call('GET', base).then(function (r) {
        if (r.ok) { adopt({ rev: r.body.rev, yaml: r.body.yaml || '', status: r.body.status, opsAt: r.body.opsAt, opsCount: r.body.opsCount !== undefined ? r.body.opsCount : (r.body.ops || []).length, problems: [] }, 'reload'); refresh(); }
        bus.emit('conflict');
        bus.emit('say', 'The design changed somewhere else (another tab?), so your last change was not applied. It has been reloaded at revision ' + st.rev + '; make the change again.', true);
      });
    }
    function describe(p) { return (p.name ? p.name + ': ' : '') + p.message + (p.code ? ' (' + p.code + ')' : ''); }

    /** Sends operations after the ones already waiting; resolves with {ok, applied, problems} (never rejects). */
    function send(ops, label) {
      var run = function () {
        return WB.call('POST', base + '/ops', { baseRev: st.rev, ops: ops, sample: st.sample || undefined }).then(function (r) {
          if (r.status === 409) { return conflict().then(function () { return { ok: false, applied: 0, problems: [] }; }); }
          if (!r.ok) { bus.emit('say', WB.why(r), true); return { ok: false, applied: 0, problems: [] }; }
          adopt(r.body, 'ops');
          var ps = r.body.problems || [];
          if (ps.length) { bus.emit('say', ps.map(describe).join(' '), true); } else if (label) { bus.emit('say', label + '. Ctrl+Z undoes it.'); }
          return { ok: !ps.length, applied: r.body.applied, problems: ps, body: r.body };
        });
      };
      var p = queue.then(run, run);
      queue = p.then(function () {}, function () {});
      return p;
    }
    function step(which) {
      var run = function () {
        return WB.call('POST', base + '/' + which, { baseRev: st.rev }).then(function (r) {
          if (r.status === 409 && /nothing to/.test((r.body.detail || ''))) { bus.emit('say', 'Nothing to ' + which + '.'); return false; }
          if (r.status === 409) { return conflict().then(function () { return false; }); }
          if (!r.ok) { bus.emit('say', WB.why(r), true); return false; }
          adopt(r.body, which);
          bus.emit('say', which === 'undo' ? 'Undone.' : 'Done again.');
          return true;
        });
      };
      var p = queue.then(run, run);
      queue = p.then(function () {}, function () {});
      return p;
    }

    function previewFile(f) {
      st.file = f;
      return WB.call('POST', base + '/preview-file', { document: f.document }).then(function (r) {
        if (st.file !== f) { return; }
        if (!r.ok) { bus.emit('say', 'The file could not be previewed: ' + WB.why(r), true); return; }
        bus.emit('preview', { html: r.body.previewHtml, name: f.name, file: true });
      });
    }
    /** Draws the preview of the chosen sample (or of the file being tried). */
    function refresh() {
      if (st.file) { return previewFile(st.file); }
      if (!st.sample) { bus.emit('preview', { html: '', name: '' }); return Promise.resolve(); }
      var name = st.sample;
      return WB.call('GET', base + '/preview?sample=' + encodeURIComponent(name)).then(function (r) {
        if (st.sample !== name || st.file) { return; }
        if (r.status === 403) { bus.emit('preview', { html: '', name: name, error: 'No access: ' + String(r.body.detail || '').replace(/^no access:\s*/i, '') }); return; }
        if (!r.ok) { bus.emit('preview', { html: '', name: name, error: WB.why(r) }); return; }
        bus.emit('preview', { html: r.body.previewHtml, name: name });
      });
    }
    function setSample(name) { st.sample = name; st.file = null; bus.emit('sample', name); return refresh(); }
    function clearFile() { st.file = null; bus.emit('sample', st.sample); return refresh(); }
    function setSamples(list) {
      st.samples = list;
      if (list.indexOf(st.sample) < 0) { st.sample = list[0] || ''; }
      bus.emit('samples', list);
    }

    return { state: st, on: bus.on.bind(bus), emit: bus.emit.bind(bus), send: send, undo: function () { return step('undo'); }, redo: function () { return step('redo'); },
             refresh: refresh, setSample: setSample, previewFile: previewFile, clearFile: clearFile, setSamples: setSamples, base: base, adopt: adopt };
  };
})();
