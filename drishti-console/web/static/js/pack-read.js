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
/* Build -> New pack: reading the user's files in the browser. A JSON Lines file is streamed line by line and only a sample is kept (a
 * reservoir, so every row has the same chance and the same file gives the same sample); a folder, dropped or chosen, is walked. What is
 * kept is a sample of documents and the counts: the file itself is never uploaded. Nothing read goes into the page as HTML. */
(function () {
  'use strict';
  var MB = 1048576;

  function ext(name) { var m = /\.([^./]+)$/.exec(String(name).toLowerCase()); return m ? m[1] : ''; }

  /** A tiny seeded generator (mulberry32): the same file gives the same sample on every read. */
  function prng(seed) {
    var a = seed >>> 0;
    return function () {
      a = (a + 0x6D2B79F5) >>> 0;
      var t = a;
      t = Math.imul(t ^ (t >>> 15), t | 1);
      t ^= t + Math.imul(t ^ (t >>> 7), t | 61);
      return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
    };
  }

  function plain(x) { return x !== null && typeof x === 'object' && !Array.isArray(x); }

  /** A JSON document that is a JSON Schema rather than data: it says so ($schema), or describes an object, or composes / defines. */
  function looksLikeSchema(o) {
    if (!plain(o)) { return false; }
    if (typeof o.$schema === 'string') { return true; }
    if (plain(o.properties) && (o.type === 'object' || o.required || o.title || o.$id)) { return true; }
    if (plain(o.$defs) || plain(o.definitions)) { return true; }
    return !!((o.oneOf || o.allOf || o.anyOf) && (o.title || o.$id || o.discriminator));
  }

  /** The loader's envelope {kind, id, doc}: its document is the data. */
  function unwrap(o) {
    if (plain(o) && o.doc !== undefined && o.kind !== undefined && o.id !== undefined && Object.keys(o).length <= 6) {
      if (typeof o.doc === 'string') { try { var d = JSON.parse(o.doc); return plain(d) ? d : null; } catch (e) { return null; } }
      return plain(o.doc) ? o.doc : null;
    }
    return o;
  }

  function Sampler(k, seed) {
    this.k = k; this.docs = []; this.rows = 0; this.bad = 0; this.rand = prng(seed);
  }
  Sampler.prototype.add = function (o) {
    o = unwrap(o);
    if (!plain(o)) { this.bad++; return; }
    this.rows++;
    if (this.docs.length < this.k) { this.docs.push(o); return; }
    var j = Math.floor(this.rand() * this.rows);
    if (j < this.k) { this.docs[j] = o; }
  };

  function hash(s) { var h = 2166136261; for (var i = 0; i < s.length; i++) { h = Math.imul(h ^ s.charCodeAt(i), 16777619); } return h >>> 0; }

  /** Streams a JSON Lines file. opts: {sampleDocs, maxRows, maxBytes}; progress(bytesRead, rows); stop() true cancels. */
  function readJsonl(file, opts, progress, stop) {
    var sam = new Sampler(opts.sampleDocs, hash(file.name + ':' + file.size));
    var dec = new TextDecoder('utf-8');
    var reader = file.stream().getReader();
    var buf = '', read = 0, truncated = false, lastTick = 0;
    function line(t) {
      t = t.trim();
      if (!t) { return; }
      try { sam.add(JSON.parse(t)); } catch (e) { sam.bad++; }
    }
    function pump() {
      return reader.read().then(function (r) {
        if (stop()) { reader.cancel(); throw new Error('cancelled'); }
        if (r.done) { buf += dec.decode(); line(buf); return; }
        read += r.value.length;
        buf += dec.decode(r.value, { stream: true });
        var parts = buf.split('\n');
        buf = parts.pop();
        var i = 0;
        for (; i < parts.length && sam.rows < opts.maxRows; i++) { line(parts[i]); }
        var now = Date.now();
        if (now - lastTick > 80) { lastTick = now; progress(read, sam.rows); }
        if (sam.rows >= opts.maxRows || read >= opts.maxBytes) {
          truncated = read < file.size || sam.rows >= opts.maxRows && (i < parts.length || buf.trim() !== '');
          reader.cancel();
          return;
        }
        return pump();
      });
    }
    return pump().then(function () {
      progress(read, sam.rows);
      return { type: 'data', name: file.name, rows: sam.rows, bytes: file.size, bytesRead: read, docs: sam.docs, bad: sam.bad, truncated: truncated };
    });
  }

  /** A .json file is read whole: an array of objects is rows, an object that is a JSON Schema is a schema, any other object is one document. */
  function readJson(file, opts, progress) {
    if (file.size > opts.maxJsonBytes) {
      return Promise.reject(new Error('is ' + (file.size / MB).toFixed(1) + ' MB, too big to read as one JSON document; use JSON Lines (.jsonl) for large data'));
    }
    return file.text().then(function (text) {
      progress(file.size, 0);
      var v;
      try { v = JSON.parse(text); } catch (e) { throw new Error('is not valid JSON (' + e.message + ')'); }
      if (looksLikeSchema(v)) { return { type: 'schema', name: file.name, text: text, bytes: file.size }; }
      var sam = new Sampler(opts.sampleDocs, hash(file.name + ':' + file.size));
      (Array.isArray(v) ? v : [v]).forEach(function (o) { if (sam.rows < opts.maxRows) { sam.add(o); } });
      return { type: 'data', name: file.name, rows: sam.rows, bytes: file.size, bytesRead: file.size, docs: sam.docs, bad: sam.bad, truncated: false };
    });
  }

  function readYaml(file) {
    return file.text().then(function (text) { return { type: 'schema', name: file.name, text: text, bytes: file.size }; });
  }

  /** Reads one entry {name, file}; resolves to {type: 'schema'|'data', ...}. */
  function readEntry(entry, opts, progress, stop) {
    var e = ext(entry.name), f = entry.file;
    var wrap = function (r) { r.name = entry.name; return r; };
    if (e === 'jsonl' || e === 'ndjson') { return readJsonl(f, opts, progress, stop).then(wrap); }
    if (e === 'json') { return readJson(f, opts, progress).then(wrap); }
    if (e === 'yaml' || e === 'yml') { return readYaml(f).then(wrap); }
    return Promise.reject(new Error('is not a .json, .jsonl, .yaml or .yml file'));
  }

  var TAKEN = { json: 1, jsonl: 1, ndjson: 1, yaml: 1, yml: 1 };

  /** Entries {name, file} from a FileList (a folder chosen with the directory input gives each file's relative path). */
  function fromFileList(list) {
    var out = [], ignored = 0;
    Array.prototype.forEach.call(list, function (f) {
      var name = f.webkitRelativePath || f.name;
      if (TAKEN[ext(name)]) { out.push({ name: name, file: f }); } else { ignored++; }
    });
    return { entries: out, ignored: ignored };
  }

  function readDir(reader, path, out) {
    return new Promise(function (resolve) {
      var all = [];
      (function more() {
        reader.readEntries(function (batch) {
          if (!batch.length) { resolve(all); return; }
          all = all.concat(Array.prototype.slice.call(batch));
          more();
        }, function () { resolve(all); });
      })();
    });
  }

  function walk(entry, path, out, counts) {
    if (entry.isFile) {
      return new Promise(function (resolve) {
        entry.file(function (f) {
          var name = path + entry.name;
          if (TAKEN[ext(name)]) { out.push({ name: name, file: f }); } else { counts.ignored++; }
          resolve();
        }, resolve);
      });
    }
    if (entry.isDirectory) {
      return readDir(entry.createReader()).then(function (kids) {
        return kids.reduce(function (p, k) { return p.then(function () { return walk(k, path + entry.name + '/', out, counts); }); }, Promise.resolve());
      });
    }
    return Promise.resolve();
  }

  /** Entries from a drop: files and folders (walked), as {entries, ignored}. */
  function fromDrop(dt) {
    var out = [], counts = { ignored: 0 };
    var items = dt.items ? Array.prototype.slice.call(dt.items) : [];
    var entries = items.map(function (i) { return i.webkitGetAsEntry ? i.webkitGetAsEntry() : null; });
    if (!entries.length || entries.some(function (e) { return !e; })) {
      var r = fromFileList(dt.files || []);
      return Promise.resolve(r);
    }
    return entries.reduce(function (p, e) { return p.then(function () { return walk(e, '', out, counts); }); }, Promise.resolve())
      .then(function () { return { entries: out, ignored: counts.ignored }; });
  }

  window.DrishtiPackRead = { readEntry: readEntry, fromFileList: fromFileList, fromDrop: fromDrop, looksLikeSchema: looksLikeSchema, ext: ext };
})();
