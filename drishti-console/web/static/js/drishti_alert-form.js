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
/* The alert form (alert_form.html): drishti-inputs' Form from the server's schema (core/alert_form.py), embedded in the page's POST form,
   every control named as the form always submitted; the fields of the chosen source, condition and trigger show through the schema's
   conditions. Here: the report, page and visual by their titles and the visual's columns as the choice of what to watch; a model's
   measures and its fields for the filters as the server answers them (Form.setOptions), the filters written into the hidden model_query;
   "Check now" asks the server what the alert would read for you right now, without saving it (your own number, read with your masks: a
   masked number is reported as masked, never shown); Save looks first and refuses a masked number. */
(function () {
  'use strict';
  var form = document.querySelector('[data-alert-form]');
  var host = form && form.querySelector('[data-alert-fields][data-di-form]');
  if (!form || !host) { return; }
  var I = window.Drishti.elements.inputs;
  var F = I.formOf(host);                                   // the one server-drawn form (core/form_kit.py): embedded, its fields named as before
  var V = 'source.visual.', M = 'source.model.';
  function wrapOf(path) { return form.querySelector('[data-form-path="' + path + '"]'); }

  /** The report, page and visual by their titles (the fields hold their ids): "Report: Daily risk / Page: Overview / Visual: MTM by desk". */
  var titles = form.querySelector('[data-visual-titles]'), cells = form.querySelector('[data-visual-cells]'), titleTimer = null, offered = null;
  /** The visual's own columns as the choice of what to watch (a visual with several rows has several cells); free text when the visual is not found. */
  function offerColumns(vis) {
    if (!vis || !vis.wells) { return; }
    var names = [];
    Object.keys(vis.wells).forEach(function (w) { (vis.wells[w] || []).forEach(function (n) { if (typeof n === 'string' && names.indexOf(n) < 0) { names.push(n); } }); });
    if (!names.length) { return; }
    var keep = F.getField(V + 'measure') || '';
    if (keep && names.indexOf(keep) < 0) { names.push(keep); }
    if (offered !== names.join('\u001f')) { offered = names.join('\u001f'); F.setOptions(V + 'measure', names); }
    // reduce is sent only when the visual can answer several rows and the screen's filters do not leave one: a card or a KPI is one cell, never reduced
    var single = ['card', 'kpi', 'gauge', 'multi-card'].indexOf(String(vis.type || '')) >= 0, dims = !single && names.length > 1;
    var state = form.elements.state && form.elements.state.value;
    if (single) { F.setField(V + 'reduce', ''); } else if (dims && !F.getField(V + 'reduce') && !state && !F.touched.has(V + 'reduce')) { F.setField(V + 'reduce', 'first'); }
    var rw = wrapOf(V + 'reduce');
    if (rw) { rw.hidden = single; }
    cells.textContent = single ? 'A ' + vis.type + ' answers one cell: it is watched as it is.' : dims ? 'This visual lists several values: choose the column to watch, then one cell (when your filters leave one row), its first row, the sum of all rows, the largest, and so on.' : '';
  }
  function showTitles() {
    var rid = String(F.getField(V + 'report') || '').trim();
    if (F.getField('source.source_type') !== 'visual' || !rid) { titles.textContent = ''; return; }
    fetch('/bi/api/reports/' + encodeURIComponent(rid).replace(/%3A/g, ':'), { credentials: 'same-origin', headers: { Accept: 'application/json' } })
      .then(function (r) { return r.ok ? r.json() : null; })
      .then(function (rep) {
        if (!rep) { titles.textContent = 'No report with that id is visible to you.'; return; }
        var pid = String(F.getField(V + 'page') || '').trim(), vid = String(F.getField(V + 'visual') || '').trim();
        var page = ((rep.report && rep.report.pages) || []).filter(function (p) { return p.id === pid; })[0];
        var vis = page && (page.visuals || []).filter(function (v) { return v.id === vid; })[0];
        var text = 'Report: ' + (rep.title || rid);
        if (pid) { text += ' / Page: ' + (page ? page.title || page.id : pid + ' (not in this report)'); }
        if (vid) { text += ' / Visual: ' + (vis ? vis.title || vis.id : vid + ' (not on this page)'); }
        titles.textContent = text;
        offerColumns(vis);
      })
      .catch(function () { titles.textContent = ''; });
  }

  // ---- a measure of a model: the model, then its measures and fields as pickers, never typed ids -----------------------------------------------------
  var queryField = form.elements.model_query, described = null, asked = 0;
  function stored() { try { return queryField && queryField.value ? JSON.parse(queryField.value) : null; } catch (e) { return null; } }
  function loadModels() {
    return fetch('/bi/model-api/datasets', { credentials: 'same-origin', headers: { Accept: 'application/json' } })
      .then(function (r) { return r.ok ? r.json() : { models: [], items: [] }; })
      .then(function (d) {
        var refs = [], labels = [], want = F.getField(M + 'model_ref') || '';
        function add(group, list) { list.forEach(function (x) { refs.push(x.ref); labels.push(group + ': ' + x.text); }); }
        add('Model', (d.models || []).filter(function (m) { return m.kind !== 'compat'; }).map(function (m) { return { ref: m.query || (m.name + '@1'), text: (m.title || m.name) + ' (model ' + (m.version || '1') + ', ' + (m.status || 'draft') + ')' }; }));
        add('Your dataset', (d.items || []).map(function (x) { return { ref: 'dataset:' + x.name, text: x.title || x.name }; }));
        var byPack = {};
        (d.packs || []).filter(function (x) { return x.columns == null || x.columns > 0; }).forEach(function (x) { (byPack[x.pack] = byPack[x.pack] || []).push(x); });
        Object.keys(byPack).sort().forEach(function (pk) { add('Pack ' + pk, byPack[pk].map(function (x) { return { ref: 'dataset:' + x.name, text: x.title || x.name }; })); });
        if (want && refs.indexOf(want) < 0) { refs.push(want); labels.push(want); }
        setEmpty(M + 'model_ref', '(choose a model)');
        F.setOptions(M + 'model_ref', refs, labels);
        return loadMeasures(!!want);
      }).catch(function () { /* the select says it is loading; Check now says why */ });
  }
  function setEmpty(path, words) { var n = F.schema.properties.source.properties.model.properties[path.split('.').pop()]; if (n) { n.emptyLabel = words; } }
  function loadMeasures(keep) {
    var ref = F.getField(M + 'model_ref'), mine = ++asked;
    described = null;
    if (!ref) { setEmpty(M + 'model_measure', '(choose a model first)'); F.setOptions(M + 'model_measure', []); return Promise.resolve(); }
    return fetch('/bi/model-api/fields?ref=' + encodeURIComponent(ref), { credentials: 'same-origin', headers: { Accept: 'application/json' } })
      .then(function (r) { return r.json().then(function (d) { return { ok: r.ok, d: d }; }); })
      .then(function (res) {
        if (mine !== asked) { return; }
        if (!res.ok) { setEmpty(M + 'model_measure', '(could not read this model)'); F.setOptions(M + 'model_measure', []); return; }
        described = res.d;
        var ms = described.measures || [], fields = [];
        (described.tables || []).forEach(function (t) { (t.columns || []).forEach(function (c) { fields.push(t.name + '.' + c.name); }); });
        setEmpty(M + 'model_measure', '(choose a measure)');
        F.schema.properties.source.properties.model.properties.filters.items.properties.field.enum = fields;
        F.setOptions(M + 'model_measure', ms.map(function (m) { return m.name; }), ms.map(function (m) { return m.name + (m.masked ? ' (masked for you)' : ''); }));
        if (keep) {
          var qy = stored() || {};
          F.setField(M + 'model_measure', ((qy.select || {}).measures || [])[0] || '');
          F.setField(M + 'filters', (qy.where || []).map(function (w) { return { field: w.field, op: w.op || 'eq', value: [].concat(w.values != null ? w.values : (w.value != null ? [w.value] : [])).join(', ') }; }));
        } else {
          F.setField(M + 'filters', []);
          F.setField(M + 'model_measure', ms.length === 1 ? ms[0].name : '');
        }
      });
  }
  function writeQuery() {
    if (!queryField) { return; }
    var measure = F.getField(M + 'model_measure');
    if (F.getField('source.source_type') !== 'model' || !measure) { queryField.value = ''; return; }
    var where = [];
    (F.getField(M + 'filters') || []).forEach(function (row) {
      var val = String(row.value || '').trim();
      if (!val || !row.field) { return; }
      var op = row.op || 'eq', vals = val.split(',').map(function (x) { return x.trim(); }).filter(Boolean), w = { field: row.field, op: op };
      if (op === 'in' || op === 'not-in') { w.values = vals; } else { w.value = vals.join(', '); }
      where.push(w);
    });
    var qy = { select: { fields: [], measures: [measure] } };
    if (where.length) { qy.where = where; }
    queryField.value = JSON.stringify(qy);
  }
  var modelsAsked = false;
  function sourceShown() {
    if (F.getField('source.source_type') === 'model' && !modelsAsked) { modelsAsked = true; loadModels(); }
    if (F.getField('source.source_type') === 'visual') { showTitles(); }
  }
  F.on('change', function (e) {
    if (e.path === 'source.source_type') { sourceShown(); }
    if (e.path === M + 'model_ref') { loadMeasures(false); }
    if (e.path === V + 'report' || e.path === V + 'page' || e.path === V + 'visual') { clearTimeout(titleTimer); titleTimer = setTimeout(showTitles, 300); }
  });
  sourceShown();

  // ---- Check now, and Save that looks first ----------------------------------------------------------------------------------------------------------
  var out = form.querySelector('[data-check-out]');
  function fields() {
    writeQuery();
    var body = {};
    new FormData(form).forEach(function (v, k) { body[k] = String(v); });
    ['d_inbox', 'd_email', 'd_bridge', 'enabled'].forEach(function (k) { body[k] = F.getField('deliver.' + k) ? 'on' : ''; });
    return body;
  }
  function say(text, bad) { out.textContent = text; out.classList.toggle('t-bad', !!bad); }
  function number(v) { return typeof v === 'number' ? window.Drishti.elements.core.formatNumber(v, { maxDecimals: 4, minus: '-' }) : String(v); }
  function preview() {
    return fetch('/alerts/preview', { method: 'POST', credentials: 'same-origin', headers: { 'Content-Type': 'application/json', Accept: 'application/json' }, body: JSON.stringify(fields()) });
  }
  form.querySelector('[data-check-now]').addEventListener('click', function () {
    say('Reading…');
    preview().then(function (r) { return r.json().then(function (b) { return { ok: r.ok, b: b }; }); })
      .then(function (res) {
        if (!res.ok) { say((res.b.code ? res.b.code + ' ' : '') + (res.b.detail || 'could not read'), true); return; }
        var b = res.b;
        if (b.status === 'ok') {
          say('Right now it reads ' + number(b.value) + (b.asOf ? ' (as of ' + b.asOf + ')' : '') + '. ' + (b.holds === true ? 'That is past the limit: it would fire when saved.' : b.holds === false ? 'That is inside the limit.' : '')
            + (b.live ? ' This source is live, so "when it changes" is available.' : ''));
        } else if (b.status === 'masked') {
          say('That number is masked for you, so an alert on it would never judge it. Ask for the access, or watch another number.', true);
        } else {
          say('Nothing to read yet: ' + (b.note || b.status), b.status === 'error');
        }
      })
      .catch(function () { say('Could not reach the server.', true); });
  });
  var checked = false;
  form.addEventListener('submit', function (ev) {
    if (checked) { return; }
    ev.preventDefault();
    writeQuery();
    say('Reading the number before saving…');
    preview().then(function (r) { return r.json().catch(function () { return {}; }); })
      .then(function (b) {
        if (b && b.status === 'masked') { say('Not saved: that number is masked for you, so the alert could never judge it. Watch a number you may see, or ask for the access.', true); return; }
        say('');
        checked = true; form.submit();
      })
      .catch(function () { say(''); checked = true; form.submit(); });
  });
})();
