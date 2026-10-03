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
/* Build workbench: choosing and reading files in the browser, shared by New and a Design's page. A folder (directory input) gives
 * each file's relative path as its name. Only .json and .jsonl are taken; others are counted and left out. The text of a file over
 * the per-file limit is not read (the console reports it). Nothing leaves the browser until the page sends it. */
(function () {
  'use strict';
  var MB = 1048576;
  function ext(name) { var m = /\.([^./]+)$/.exec(name.toLowerCase()); return m ? m[1] : ''; }

  window.DrishtiFiles = {
    mb: function (n) { return n >= MB ? (n / MB).toFixed(1) + ' MB' : n >= 1024 ? Math.round(n / 1024) + ' KB' : n + ' bytes'; },

    /** Entries {name, file} from a FileList; `ignored` counts what was not .json or .jsonl. */
    pick: function (fileList) {
      var out = [], ignored = 0;
      Array.prototype.forEach.call(fileList, function (f) {
        var name = f.webkitRelativePath || f.name, e = ext(name);
        if (e === 'json' || e === 'jsonl') { out.push({ name: name, file: f }); } else { ignored++; }
      });
      return { entries: out, ignored: ignored };
    },

    /** Promise of [{name, text}] (or {name, size} for a file over maxFile bytes, {name} when unreadable). */
    read: function (entries, maxFile) {
      return Promise.all(entries.map(function (e) {
        if (e.file.size > maxFile) { return Promise.resolve({ name: e.name, size: e.file.size }); }
        return e.file.text().then(function (t) { return { name: e.name, text: t }; }, function () { return { name: e.name }; });
      }));
    },

    /** POSTs files to a Design; resolves to {ok, status, body}. */
    send: function (id, files) {
      return fetch('/build/designs/' + encodeURIComponent(id) + '/files', {
        method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ files: files })
      }).then(function (r) { return r.json().then(function (j) { return { ok: r.ok, status: r.status, body: j }; }); });
    }
  };
})();
