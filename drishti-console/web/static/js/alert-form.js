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
/* The alert form (alert_form.html): shows only the fields that belong to the chosen source, condition and trigger (without this file all of them show),
   and "Check now" asks the server what the alert would read for you right now, without saving it. The answer is your own number, read with your masks:
   a masked number is reported as masked, never shown. */
(function () {
  'use strict';
  var form = document.querySelector('[data-alert-form]');
  if (!form) { return; }
  function sync() {
    form.querySelectorAll('[data-pick]').forEach(function (sel) {
      var group = sel.getAttribute('data-pick');
      if (group === 'range') { return; }
      form.querySelectorAll('[data-group="' + group + '"]').forEach(function (g) { g.hidden = g.getAttribute('data-when') !== sel.value; });
    });
    var op = form.elements.op;
    var high = form.querySelector('[data-range-high]');
    if (op && high) { high.hidden = op.value !== 'between' && op.value !== 'outside'; }
  }
  form.addEventListener('change', sync);
  sync();

  /** The report, page and visual by their titles (the fields hold their ids): "Report: Daily risk, Page: Overview, Visual: MTM by desk". */
  var titles = form.querySelector('[data-visual-titles]');
  var titleTimer = null;
  /** The visual's own columns as the choice of what to watch (a visual with several rows has several cells); the free text stays when the visual is not found. */
  function offerColumns(vis) {
    var input = form.querySelector('[data-visual-column]'), note = form.querySelector('[data-visual-cells]');
    if (!input || !vis || !vis.wells) { return; }
    var names = [];
    Object.keys(vis.wells).forEach(function (w) { (vis.wells[w] || []).forEach(function (n) { if (typeof n === 'string' && names.indexOf(n) < 0) { names.push(n); } }); });
    if (!names.length) { return; }
    var sel = document.createElement('select');
    sel.className = 'studio-select'; sel.name = 'measure'; sel.setAttribute('data-visual-column', '');
    var first = document.createElement('option'); first.value = ''; first.textContent = '(the first number)'; sel.appendChild(first);
    names.forEach(function (n) { var o = document.createElement('option'); o.value = n; o.textContent = n; sel.appendChild(o); });
    var keep = input.value;
    if (keep && names.indexOf(keep) < 0) { var o2 = document.createElement('option'); o2.value = keep; o2.textContent = keep; sel.appendChild(o2); }
    sel.value = keep;
    input.parentNode.replaceChild(sel, input);
    var dims = names.length > 1;
    if (note) { note.textContent = dims ? 'This visual lists several values: choose the column to watch, then whether to watch its first row, the sum of all rows, the largest, and so on.' : ''; }
  }
  function showTitles() {
    if (!titles) { return; }
    var rid = form.elements.report && form.elements.report.value.trim();
    if (!rid) { titles.textContent = ''; return; }
    fetch('/bi/api/reports/' + encodeURIComponent(rid).replace(/%3A/g, ':'), { credentials: 'same-origin', headers: { Accept: 'application/json' } })
      .then(function (r) { return r.ok ? r.json() : null; })
      .then(function (rep) {
        if (!rep) { titles.textContent = 'No report with that id is visible to you.'; return; }
        var pid = form.elements.page.value.trim(), vid = form.elements.visual.value.trim();
        var pages = (rep.report && rep.report.pages) || [];
        var page = pages.filter(function (p) { return p.id === pid; })[0];
        var vis = page && (page.visuals || []).filter(function (v) { return v.id === vid; })[0];
        var text = 'Report: ' + (rep.title || rid);
        if (pid) { text += ' / Page: ' + (page ? page.title || page.id : pid + ' (not in this report)'); }
        if (vid) { text += ' / Visual: ' + (vis ? vis.title || vis.id : vid + ' (not on this page)'); }
        titles.textContent = text;
        offerColumns(vis);
      })
      .catch(function () { titles.textContent = ''; });
  }
  ['report', 'page', 'visual'].forEach(function (n) { if (form.elements[n]) { form.elements[n].addEventListener('input', function () { clearTimeout(titleTimer); titleTimer = setTimeout(showTitles, 300); }); } });
  showTitles();

  var out = form.querySelector('[data-check-out]');
  var button = form.querySelector('[data-check-now]');
  function fields() {
    writeQuery();
    var body = {};
    new FormData(form).forEach(function (v, k) { body[k] = String(v); });
    ['d_inbox', 'd_email', 'd_bridge', 'enabled'].forEach(function (k) { body[k] = form.elements[k] && form.elements[k].checked ? 'on' : ''; });
    return body;
  }
  function say(text, bad) {
    out.textContent = text;
    out.classList.toggle('t-bad', !!bad);
  }
  function number(v) { return typeof v === 'number' ? v.toLocaleString(undefined, { maximumFractionDigits: 4 }) : String(v); }
  button.addEventListener('click', function () {
    say('Reading…');
    fetch('/alerts/preview', { method: 'POST', credentials: 'same-origin', headers: { 'Content-Type': 'application/json', Accept: 'application/json' }, body: JSON.stringify(fields()) })
      .then(function (r) { return r.json().then(function (b) { return { ok: r.ok, b: b }; }); })
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

  // ---- a measure of a model: the model, then its measures and fields as pickers, never typed ids -----------------------------------------------------
  var modelSel = form.querySelector('[data-al-model]'), measureSel = form.querySelector('[data-al-measure]'), filterBox = form.querySelector('[data-al-filters]');
  var queryField = form.elements.model_query, described = null;
  var OPS = [['eq', 'is'], ['ne', 'is not'], ['in', 'is one of'], ['not-in', 'is none of'], ['gt', 'is above'], ['ge', 'is at least'], ['lt', 'is below'], ['le', 'is at most'], ['contains', 'contains']];
  function el(tag, attrs, text) { var e = document.createElement(tag); Object.keys(attrs || {}).forEach(function (k) { e.setAttribute(k, attrs[k]); }); if (text != null) { e.textContent = text; } return e; }
  function stored() { try { return queryField && queryField.value ? JSON.parse(queryField.value) : null; } catch (e) { return null; } }
  function fieldNames() { var out = []; ((described && described.tables) || []).forEach(function (t) { (t.columns || []).forEach(function (c) { out.push(t.name + '.' + c.name); }); }); return out; }
  function addFilter(w) {
    var row = el('div', { class: 'al-filter', 'data-al-filter': '' });
    var f = el('select', { class: 'studio-select', 'aria-label': 'Field' });
    fieldNames().forEach(function (n) { f.appendChild(el('option', { value: n }, n)); });
    var o = el('select', { class: 'studio-select', 'aria-label': 'Operator' });
    OPS.forEach(function (x) { o.appendChild(el('option', { value: x[0] }, x[1])); });
    var v = el('input', { class: 'studio-in', 'aria-label': 'Value (several separated by commas)', placeholder: 'FX, Rates', autocomplete: 'off' });
    var rm = el('button', { type: 'button', class: 'btn-pill btn-ghost', 'aria-label': 'Remove this filter' }, 'Remove');
    rm.addEventListener('click', function () { row.remove(); });
    if (w) { f.value = w.field; o.value = w.op || 'eq'; v.value = [].concat(w.values != null ? w.values : (w.value != null ? [w.value] : [])).join(', '); }
    row.appendChild(f); row.appendChild(o); row.appendChild(v); row.appendChild(rm);
    filterBox.appendChild(row);
  }
  function writeQuery() {
    if (!queryField || !modelSel || !measureSel.value) { if (queryField) { queryField.value = ''; } return; }
    var where = [];
    filterBox.querySelectorAll('[data-al-filter]').forEach(function (row) {
      var sels = row.querySelectorAll('select'), val = row.querySelector('input').value.trim();
      if (!val) { return; }
      var op = sels[1].value, vals = val.split(',').map(function (x) { return x.trim(); }).filter(Boolean);
      var w = { field: sels[0].value, op: op };
      if (op === 'in' || op === 'not-in') { w.values = vals; } else { w.value = vals.join(', '); }
      where.push(w);
    });
    var qy = { select: { fields: [], measures: [measureSel.value] } };
    if (where.length) { qy.where = where; }
    queryField.value = JSON.stringify(qy);
  }
  function loadMeasures(keep) {
    measureSel.textContent = '';
    filterBox.textContent = '';
    described = null;
    if (!modelSel.value) { measureSel.appendChild(el('option', { value: '' }, '(choose a model first)')); return Promise.resolve(); }
    return fetch('/bi/model-api/fields?ref=' + encodeURIComponent(modelSel.value), { credentials: 'same-origin', headers: { Accept: 'application/json' } })
      .then(function (r) { return r.json().then(function (d) { return { ok: r.ok, d: d }; }); })
      .then(function (res) {
        if (!res.ok) { measureSel.appendChild(el('option', { value: '' }, '(could not read this model)')); return; }
        described = res.d;
        measureSel.appendChild(el('option', { value: '' }, '(choose a measure)'));
        (described.measures || []).forEach(function (m) { measureSel.appendChild(el('option', { value: m.name }, m.name + (m.masked ? ' (masked for you)' : ''))); });
        if (keep) {
          var qy = stored() || {};
          measureSel.value = ((qy.select || {}).measures || [])[0] || '';
          (qy.where || []).forEach(addFilter);
        } else if ((described.measures || []).length === 1) { measureSel.value = described.measures[0].name; }
      });
  }
  if (modelSel) {
    fetch('/bi/model-api/datasets', { credentials: 'same-origin', headers: { Accept: 'application/json' } })
      .then(function (r) { return r.ok ? r.json() : { models: [], items: [] }; })
      .then(function (d) {
        var want = modelSel.getAttribute('data-value');
        modelSel.textContent = '';
        modelSel.appendChild(el('option', { value: '' }, '(choose a model)'));
        var add = function (label, list) {
          if (!list.length) { return; }
          var g = el('optgroup', { label: label });
          list.forEach(function (x) { g.appendChild(el('option', { value: x.ref }, x.text)); });
          modelSel.appendChild(g);
        };
        add('Models', (d.models || []).filter(function (m) { return m.kind !== 'compat'; }).map(function (m) { return { ref: m.query || (m.name + '@1'), text: (m.title || m.name) + ' (model ' + (m.version || '1') + ', ' + (m.status || 'draft') + ')' }; }));
        add('Your datasets', (d.items || []).map(function (x) { return { ref: 'dataset:' + x.name, text: x.title || x.name }; }));
        var byPack = {};
        (d.packs || []).filter(function (x) { return x.columns == null || x.columns > 0; }).forEach(function (x) { (byPack[x.pack] = byPack[x.pack] || []).push(x); });
        Object.keys(byPack).sort().forEach(function (pk) { add('Pack ' + pk, byPack[pk].map(function (x) { return { ref: 'dataset:' + x.name, text: x.title || x.name }; })); });
        if (want && !Array.prototype.some.call(modelSel.options, function (o) { return o.value === want; })) { modelSel.appendChild(el('option', { value: want }, want)); }
        modelSel.value = want || '';
        return loadMeasures(!!want);
      }).catch(function () {});
    modelSel.addEventListener('change', function () { loadMeasures(false); });
    form.querySelector('[data-al-addfilter]').addEventListener('click', function () { if (!described) { return; } addFilter(null); });
  }

  // ---- a masked number cannot be saved: Save looks first, and says why -----------------------------------------------------------------------------
  var checked = false;
  form.addEventListener('submit', function (ev) {
    if (checked) { return; }
    ev.preventDefault();
    say('Reading the number before saving…');
    fetch('/alerts/preview', { method: 'POST', credentials: 'same-origin', headers: { 'Content-Type': 'application/json', Accept: 'application/json' }, body: JSON.stringify(fields()) })
      .then(function (r) { return r.json().catch(function () { return {}; }); })
      .then(function (b) {
        if (b && b.status === 'masked') { say('Not saved: that number is masked for you, so the alert could never judge it. Watch a number you may see, or ask for the access.', true); return; }
        say('');
        checked = true; form.submit();
      })
      .catch(function () { say(''); checked = true; form.submit(); });
  });
})();
