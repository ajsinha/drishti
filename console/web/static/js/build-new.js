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
/* Build workbench, New (/build/new): bring data in (files, a folder, a schema, stored entities, an example, an existing Sutra),
 * choose what to start from, and create a Design on the server. The files are read in the browser and sent in one request;
 * the console enforces the limits. All text from files goes in as text nodes, never as HTML. */
(function () {
  'use strict';
  var root = document.querySelector('[data-new]');
  if (!root) { return; }
  var F = window.DrishtiFiles;
  var $ = function (s) { return root.querySelector(s); };
  var MB = 1048576;
  var maxFile = parseFloat(root.dataset.maxFileMb) * MB;
  var maxSamples = parseInt(root.dataset.maxSamples, 10);
  var status = $('[data-status]'), list = $('[data-file-list]'), createStatus = $('[data-create-status]'), clear = $('[data-clear-files]');
  var picked = [];          // [{name, file}] chosen so far

  function say(el, text, bad) { el.textContent = text; el.classList.toggle('bad', !!bad); }
  function el(tag, cls, text) {
    var e = document.createElement(tag);
    if (cls) { e.className = cls; }
    if (text !== undefined && text !== null) { e.textContent = text; }
    return e;
  }
  function json(method, url, body) {
    return fetch(url, { method: method, headers: { 'Content-Type': 'application/json' }, body: body === undefined ? undefined : JSON.stringify(body) })
      .then(function (r) { return (r.status === 204 ? Promise.resolve({}) : r.json()).then(function (j) { return { ok: r.ok, status: r.status, body: j }; }); });
  }
  /** The server's words with its field names turned into the page's (UX-11): 'refs.kind' is the kind of the stored entity. */
  function why(r) {
    var m = window.drsMessage({ code: r.body.code || ('HTTP ' + r.status), detail: r.body.detail }, 'The request failed');
    return m.replace(/'refs\.kind' is required/, 'the kind of the stored entity is required').replace(/'refs\.ids' is required/, 'the id of the stored entity is required');
  }

  // ---- files and folders -----------------------------------------------------------------------------------------
  function add(fileList) {
    var p = F.pick(fileList);
    var byName = {};
    picked.concat(p.entries).forEach(function (e) { byName[e.name] = e; });
    picked = Object.keys(byName).map(function (k) { return byName[k]; });
    if (picked.length > maxSamples) {
      say(status, picked.length + ' files chosen; at most ' + maxSamples + ' are taken. Choose fewer.', true);
    } else {
      say(status, picked.length + ' file' + (picked.length === 1 ? '' : 's') + ' ready' + (p.ignored ? '; ' + p.ignored + ' not .json or .jsonl left out' : '') + '.', false);
    }
    showList();
    checkFiles(p.entries);
  }
  /** A file that is not JSON is said at once, by name (the check reads only files under 2 MB; the server reads the rest). */
  function checkFiles(entries) {
    entries.forEach(function (e) {
      if (e.file.size > 2 * 1048576 || !e.file.text) { return; }
      e.file.text().then(function (t) {
        try { JSON.parse(/\.jsonl$/i.test(e.name) ? (t.split('\n').filter(function (x) { return x.trim(); })[0] || 'null') : t); e.bad = ''; }
        catch (x) { e.bad = 'is not valid JSON'; }
        showList();
        var bad = picked.filter(function (y) { return y.bad; });
        if (bad.length) { say(status, bad.map(function (y) { return y.name + ' ' + y.bad; }).join('; ') + '. Remove ' + (bad.length === 1 ? 'it' : 'them') + ' or fix ' + (bad.length === 1 ? 'it' : 'them') + '; the design needs at least one readable sample.', true); }
      });
    });
  }
  function showList() {
    list.textContent = '';
    picked.forEach(function (e) {
      var li = el('li');
      li.appendChild(el('span', 'mono', e.name));
      li.appendChild(document.createTextNode(' · ' + F.mb(e.file.size)));
      if (e.bad) { li.appendChild(el('b', 'bad', ' · ' + e.bad)); li.classList.add('bad'); }
      list.appendChild(li);
    });
    list.hidden = clear.hidden = !picked.length;
  }
  var input = $('[data-files]'), folder = $('[data-folder]'), drop = $('[data-drop]');
  input.addEventListener('change', function () { add(input.files); input.value = ''; });
  folder.addEventListener('change', function () { add(folder.files); folder.value = ''; });
  clear.addEventListener('click', function () { picked = []; showList(); say(status, ''); });
  ['dragenter', 'dragover'].forEach(function (t) { drop.addEventListener(t, function (e) { e.preventDefault(); drop.classList.add('over'); }); });
  ['dragleave', 'drop'].forEach(function (t) { drop.addEventListener(t, function (e) { e.preventDefault(); drop.classList.remove('over'); }); });
  drop.addEventListener('drop', function (e) { if (e.dataTransfer) { add(e.dataTransfer.files); } });

  // ---- examples: each opens as a new design that is a copy --------------------------------------------------------
  root.querySelectorAll('[data-example]').forEach(function (b) {
    b.addEventListener('click', function () {
      b.disabled = true;
      say(createStatus, 'Opening a copy of ' + b.dataset.example + '...');
      json('POST', '/build/examples/' + encodeURIComponent(b.dataset.example) + '/open', {}).then(function (r) {
        if (!r.ok) { b.disabled = false; say(createStatus, why(r), true); return; }
        window.location.href = r.body.url;
      }, function () { b.disabled = false; say(createStatus, 'The console could not be reached. Try again.', true); });
    });
  });

  // /build/new?example=<name> (the help centre's "Open as a design"): the same as clicking that example's card
  try {
    var asked = new URLSearchParams(window.location.search).get('example');
    var card = asked && root.querySelector('[data-example="' + (window.CSS && CSS.escape ? CSS.escape(asked) : asked) + '"]');
    if (card) { card.click(); }
  } catch (e) { /* no query string support: the card stays there to click */ }

  // ---- create ----------------------------------------------------------------------------------------------------
  function readText(file) { return file.text(); }
  function chosenStart() { return root.querySelector('[name=start]:checked').value; }

  /** What the page was asked to bring besides files: a schema (parsed), store references, a Sutra. */
  function gather() {
    var out = { schema: null, refs: null, yaml: null, base: $('[data-registry]').value };
    var sf = $('[data-schema-file]').files[0], yf = $('[data-yaml-file]').files[0];
    var kind = $('[data-store-kind]').value.trim();
    if (kind) {
      var ids = $('[data-store-ids]').value.split(/[\s,]+/).filter(Boolean);
      out.refs = ids.length ? { kind: kind, ids: ids } : { kind: kind, count: parseInt($('[data-store-count]').value, 10) || 5 };
    }
    var pending = [];
    if (sf) { pending.push(readText(sf).then(function (t) { out.schema = JSON.parse(t); })); }
    if (yf) { pending.push(readText(yf).then(function (t) { out.yaml = t; })); }
    return Promise.all(pending).then(function () { return out; });
  }

  $('[data-create]').addEventListener('click', function () {
    var btn = this, start = chosenStart();
    btn.disabled = true;
    say(createStatus, 'Reading...');
    gather().then(function (more) {
      var hasData = picked.length || more.schema || more.refs;
      if (start === 'auto' && !hasData) { throw new Error('Auto-design needs data: choose files, a folder, a schema or stored entities (or pick an example above).'); }
      if (start === 'existing' && !(more.yaml || more.base)) { throw new Error('Choose the existing Sutra: from the registry, or a .yaml file.'); }
      var body = { name: $('[data-name]').value.trim(), kind: $('[data-kind]').value.trim() };
      if (start === 'existing') { if (more.yaml) { body.sutra = more.yaml; } else { body.base = more.base; } }
      if (start === 'empty') { body.empty = true; }
      say(createStatus, 'Creating the design...');
      return json('POST', '/build/designs', body).then(function (r) {
        if (!r.ok) { throw new Error(why(r)); }
        return run(r.body.id, start, more);
      });
    }).catch(function (e) {
      btn.disabled = false;
      say(createStatus, e && e.message ? e.message : 'The console could not be reached. Try again.', true);
    });
  });

  /** The steps after the design exists; a failure names the step and links to the design as it stands. */
  function run(id, start, more) {
    var url = '/build/d/' + encodeURIComponent(id);
    function step(label, r) {
      if (r.ok) { return r; }
      var e = new Error(label + ': ' + why(r));
      e.partial = url;
      throw e;
    }
    var chain = Promise.resolve();
    if (picked.length) {
      chain = chain.then(function () { say(createStatus, 'Sending ' + picked.length + ' file' + (picked.length === 1 ? '' : 's') + '...'); return F.read(picked, maxFile); })
        .then(function (files) { return F.send(id, files); }).then(function (r) { return step('The files', r); });
    }
    if (more.schema) {
      chain = chain.then(function () { say(createStatus, 'Generating synthetic samples...'); return json('POST', '/build/designs/' + id + '/samples',
        { schema: more.schema, count: parseInt($('[data-schema-count]').value, 10) || 5 }); }).then(function (r) { return step('The schema', r); });
    }
    if (more.refs) {
      chain = chain.then(function () { say(createStatus, 'Adding the stored entities...'); return json('POST', '/build/designs/' + id + '/samples', { refs: more.refs }); })
        .then(function (r) { return step('The stored entities', r); });
    }
    if (start === 'auto') {
      chain = chain.then(function () { say(createStatus, 'Auto-designing a first screen...'); return json('POST', '/build/designs/' + id + '/autodesign', {}); })
        .then(function (r) { return step('Auto-design', r); });
    }
    return chain.then(function () { window.location.href = url; }, function (e) {
      $('[data-create]').disabled = false;
      say(createStatus, e.message + (e.partial ? ' The design was created: ' : ''), true);
      if (e.partial) { var a = el('a', 'lnk', 'open it as it stands'); a.href = e.partial; createStatus.appendChild(a); }
    });
  }
})();
