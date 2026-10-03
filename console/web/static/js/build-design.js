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
/* Build workbench, a Design's page (/build/d/{id}) until the workbench exists: its Data (samples and the shape tree), its Sutra
 * read-only, and its preview with a sample switcher; "Open in Studio" carries the Design and the sample chosen. The preview HTML
 * is the console's own Studio partial (values escaped there); everything else from data goes in as text nodes. */
(function () {
  'use strict';
  var root = document.querySelector('[data-design-page]');
  if (!root) { return; }
  var F = window.DrishtiFiles;
  var id = encodeURIComponent(root.dataset.id);
  var maxFile = parseFloat(root.dataset.maxFileMb) * 1048576;
  var $ = function (s) { return root.querySelector(s); };
  var status = $('[data-status]'), prev = $('[data-preview]'), sw = $('[data-sample-switch]'), studio = $('[data-studio-link]');

  function say(text, bad) { status.textContent = text; status.classList.toggle('bad', !!bad); }
  function el(tag, cls, text) {
    var e = document.createElement(tag);
    if (cls) { e.className = cls; }
    if (text !== undefined && text !== null) { e.textContent = text; }
    return e;
  }
  function call(method, url, body) {
    return fetch(url, { method: method, headers: { 'Content-Type': 'application/json' }, body: body === undefined ? undefined : JSON.stringify(body) })
      .then(function (r) { return r.json().then(function (j) { return { ok: r.ok, status: r.status, body: j }; }); });
  }
  function why(r) { return (r.body.detail || 'The request failed') + ' (' + (r.body.code || r.status) + ')'; }

  // ---- data: the shape tree ---------------------------------------------------------------------------------------
  var shapeBox = $('[data-shape]');
  var tree = window.DrishtiShapeTree(shapeBox);
  if (parseInt(root.dataset.sampleCount, 10) > 0) {
    call('GET', '/build/designs/' + id + '/shape').then(function (r) {
      if (!r.ok) { say('The shape could not be made: ' + why(r), true); return; }
      tree.show(r.body.report);
      shapeBox.hidden = false;
      var sk = $('[data-skipped]');
      (r.body.skipped || []).forEach(function (s) {
        var p = el('p', 'prob');
        p.appendChild(el('span', 'mono', s.name));
        p.appendChild(document.createTextNode(' is left out of the shape: ' + s.reason + '.'));
        sk.appendChild(p);
      });
    }, function () { say('The console could not be reached. Try again.', true); });
  }

  root.querySelectorAll('[data-sample]').forEach(function (li) {
    li.querySelector('[data-remove]').addEventListener('click', function () {
      call('DELETE', '/build/designs/' + id + '/samples?name=' + encodeURIComponent(li.dataset.sample)).then(function (r) {
        if (!r.ok) { say(why(r), true); return; }
        window.location.reload();
      }, function () { say('The console could not be reached. Try again.', true); });
    });
  });

  var add = $('[data-add-files]');
  add.addEventListener('change', function () {
    var p = F.pick(add.files);
    add.value = '';
    if (!p.entries.length) { say('No .json or .jsonl file chosen.', true); return; }
    say('Sending ' + p.entries.length + ' file' + (p.entries.length === 1 ? '' : 's') + '...');
    F.read(p.entries, maxFile).then(function (files) { return F.send(root.dataset.id, files); }).then(function (r) {
      if (!r.ok) { say(why(r), true); return; }
      window.location.reload();
    }, function () { say('The console could not be reached. Try again.', true); });
  });

  // ---- preview with a sample switcher -----------------------------------------------------------------------------
  function showPreview() {
    if (!sw || !prev) { return; }
    var name = sw.value;
    studio.href = '/studio?design=' + id + '&sample=' + encodeURIComponent(name);
    prev.textContent = 'Previewing...';
    call('GET', '/build/designs/' + id + '/preview?sample=' + encodeURIComponent(name)).then(function (r) {
      if (sw.value !== name) { return; }            // a later choice answers for itself
      if (!r.ok) {
        prev.textContent = '';
        prev.appendChild(el('p', r.status === 403 ? 'prob' : 'bs-status bad', r.status === 403 ? 'No access: ' + r.body.detail.replace(/^no access:\s*/i, '') : why(r)));
        return;
      }
      prev.innerHTML = r.body.previewHtml || '';       // the console's own Studio preview partial, values escaped there
      if (window.drishti) { window.drishti.enhance(prev); window.drishti.redraw(); }
    }, function () { prev.textContent = 'The console could not be reached. Try again.'; });
  }
  if (sw) { sw.addEventListener('change', showPreview); showPreview(); } else { studio.href = '/studio?design=' + id; }

  // ---- auto-design: the next revision -----------------------------------------------------------------------------
  $('[data-autodesign]').addEventListener('click', function () {
    var had = root.dataset.hasSutra === 'yes';
    if (had && !window.confirm('Auto-design replaces the Sutra (revision ' + root.dataset.rev + ') with a new draft. Continue?')) { return; }
    say('Drafting a screen...');
    call('POST', '/build/designs/' + id + '/autodesign', {}).then(function (r) {
      if (!r.ok) { say(why(r), true); return; }
      sessionStorage.setItem('drishti-draft-' + root.dataset.id, JSON.stringify({ pruned: r.body.pruned || [], reasons: r.body.reasons || {}, alternatives: r.body.alternatives || {} }));
      window.location.reload();
    }, function () { say('The console could not be reached. Try again.', true); });
  });

  // what the last auto-design left out and why, shown once after the page reloads with the new revision
  try {
    var kept = sessionStorage.getItem('drishti-draft-' + root.dataset.id);
    if (kept) {
      sessionStorage.removeItem('drishti-draft-' + root.dataset.id);
      var d = JSON.parse(kept), box = $('[data-draft]'), pr = $('[data-draft-pruned]'), rs = $('[data-draft-reasons]');
      (d.pruned || []).forEach(function (p) {
        var li = el('li');
        li.appendChild(el('b', null, p.panel));
        li.appendChild(document.createTextNode(' (' + p.kind + ') ' + p.action + (p.to ? ' to ' + p.to : '') + ': ' + p.reason));
        pr.appendChild(li);
      });
      if (!pr.children.length) { pr.appendChild(el('li', null, 'Nothing: every panel and figure had data in enough samples.')); }
      Object.keys(d.reasons || {}).forEach(function (k) {
        var row = el('div', 'bs-why');
        row.appendChild(el('b', null, k));
        row.appendChild(document.createTextNode(': ' + d.reasons[k]));
        var alts = (d.alternatives || {})[k] || [];
        if (alts.length) {
          var ul = el('ul', 'bs-alts');
          alts.forEach(function (a) { var li = el('li'); li.appendChild(el('span', 'bs-role', a.kind)); li.appendChild(document.createTextNode(' ' + a.reason)); ul.appendChild(li); });
          row.appendChild(ul);
        }
        rs.appendChild(row);
      });
      box.hidden = false;
      say('Drafted a screen: revision ' + root.dataset.rev + '; ' + (d.pruned || []).length + ' left out.');
    }
  } catch (e) { /* storage unavailable: the draft is still saved, only this note is skipped */ }
})();
