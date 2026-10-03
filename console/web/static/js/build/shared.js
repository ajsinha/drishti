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
/* The read-only page a share link opens (/build/d/{id}?share=token): previews the shared Sutra against the viewer's own JSON file or a
 * stored entity they may open, and copies the Sutra. The owner's sample contents are not on this page and never were. */
(function () {
  'use strict';
  var root = document.querySelector('[data-shared]');
  if (!root) { return; }
  var $ = function (s) { return root.querySelector(s); };
  var token = root.dataset.token, id = root.dataset.id, say = $('[data-say]'), frame = $('[data-preview]');

  document.querySelectorAll('time[data-ms]').forEach(function (t) { var ms = parseInt(t.dataset.ms, 10); if (ms) { t.textContent = new Date(ms).toLocaleString(); } });

  function tell(text, bad) { say.textContent = text; say.classList.toggle('bad', !!bad); }
  function show(r) {
    return r.json().catch(function () { return {}; }).then(function (j) {
      if (!r.ok) { tell((j.detail || 'The preview failed') + ' (' + (j.code || r.status) + ')', true); return; }
      frame.innerHTML = j.previewHtml || '';
      if (window.drishti) { window.drishti.enhance(frame); window.drishti.redraw(); }
      tell('Previewed with your data. Nothing was saved.');
    });
  }
  function post(body) {
    body.token = token;
    return fetch('/build/shared/' + encodeURIComponent(id) + '/preview', { method: 'POST', headers: { 'Content-Type': 'application/json', Accept: 'application/json' }, body: JSON.stringify(body) }).then(show);
  }

  $('[data-try-file]').addEventListener('change', function (e) {
    var f = e.target.files[0];
    e.target.value = '';
    if (!f) { return; }
    f.text().then(function (t) {
      var doc;
      try { doc = /\.jsonl$/i.test(f.name) ? JSON.parse(t.split('\n').filter(Boolean)[0]) : JSON.parse(t); } catch (x) { tell(f.name + ' is not valid JSON: ' + x.message, true); return; }
      tell('Previewing with ' + f.name + '...');
      post({ document: doc, kind: $('[data-try-kind]').value.trim() });
    });
  });
  $('[data-try-entity]').addEventListener('click', function () {
    var kind = $('[data-try-kind]').value.trim(), eid = $('[data-try-id]').value.trim();
    if (!kind || !eid) { tell('Give a kind and an entity id.', true); return; }
    tell('Previewing ' + kind + ' ' + eid + '...');
    post({ kind: kind, id: eid });
  });
  $('[data-copy-sutra]').addEventListener('click', function () {
    var text = $('[data-shared-sutra]').textContent;
    (navigator.clipboard ? navigator.clipboard.writeText(text) : Promise.reject()).then(function () { tell('Copied the Sutra.'); }, function () { tell('Select the Sutra text and copy it.', true); });
  });
})();
