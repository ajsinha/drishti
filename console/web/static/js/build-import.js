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
/* Build workbench, New: import a pack folder or a zip as Designs (POST /build/import). A zip is sent as it is; a folder is read in the
 * browser (only .yaml, .yml, .json, .md) and sent as {files: [{path, text}]}. Names from the files go in as text, never as HTML. */
(function () {
  'use strict';
  var root = document.querySelector('[data-import]');
  if (!root) { return; }
  var $ = function (s) { return root.querySelector(s); };
  var status = $('[data-import-status]'), list = $('[data-import-list]');
  var TEXT = /\.(ya?ml|json|md)$/i;

  function say(text, bad) { status.textContent = text; status.classList.toggle('bad', !!bad); }
  function done(r) {
    return r.json().catch(function () { return {}; }).then(function (j) {
      if (!r.ok) { say((j.detail || 'The import failed') + ' (' + (j.code || r.status) + ')', true); return; }
      var ds = j.designs || [];
      say('Made ' + ds.length + ' design' + (ds.length === 1 ? '' : 's') + (j.skipped && j.skipped.length ? '; ' + j.skipped.length + ' note' + (j.skipped.length === 1 ? '' : 's') + ' below.' : '.'));
      list.textContent = '';
      ds.forEach(function (d) {
        var li = document.createElement('li'), a = document.createElement('a');
        a.className = 'lnk'; a.href = '/build/d/' + encodeURIComponent(d.id); a.textContent = d.name || d.id;
        li.appendChild(a);
        li.appendChild(document.createTextNode(' · ' + (d.samples || []).length + ' sample' + ((d.samples || []).length === 1 ? '' : 's')));
        list.appendChild(li);
      });
      (j.skipped || []).forEach(function (n) { var li = document.createElement('li'); li.className = 'text-muted-d'; li.textContent = n; list.appendChild(li); });
      list.hidden = !list.children.length;
    });
  }
  function post(type, body) {
    return fetch('/build/import', { method: 'POST', headers: { 'Content-Type': type, Accept: 'application/json' }, body: body }).then(done, function () { say('The console could not be reached. Try again.', true); });
  }
  $('[data-import-zip]').addEventListener('change', function (e) {
    var f = e.target.files[0];
    e.target.value = '';
    if (!f) { return; }
    say('Importing ' + f.name + '...');
    f.arrayBuffer().then(function (b) { return post('application/zip', b); });
  });
  $('[data-import-folder]').addEventListener('change', function (e) {
    var files = Array.prototype.slice.call(e.target.files).filter(function (f) { return TEXT.test(f.name); });
    e.target.value = '';
    if (!files.length) { say('That folder has no .yaml, .json or .md files.', true); return; }
    say('Reading ' + files.length + ' files...');
    Promise.all(files.map(function (f) { return f.text().then(function (t) { return { path: f.webkitRelativePath || f.name, text: t }; }); }))
      .then(function (all) { say('Importing...'); return post('application/json', JSON.stringify({ files: all })); });
  });
})();
