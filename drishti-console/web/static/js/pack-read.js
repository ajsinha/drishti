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

  /** What a data file read gives the page: the sample, the counts and profile of every row (pack-profile.js), and its design samples. */
  function finish(file, sam, prof, read, truncated) {
    var r = prof.result(), seen = new Set(sam.docs), docs = sam.docs.slice();
    r.design.forEach(function (d) { if (!seen.has(d)) { seen.add(d); docs.push(d); } });
    return { type: 'data', name: file.name, rows: sam.rows, bytes: file.size, bytesRead: read, docs: docs, bad: sam.bad, truncated: truncated,
      counts: r.counts, profile: r.profile, split: r.split, sampled: sam.docs.length, design: r.design.length };
  }

  function profiler(file, opts) {
    var P = window.DrishtiPackProfile;
    return new P.Profiler({ profilePerType: opts.profilePerType, designPerType: opts.designPerType, splitField: opts.splitField, seed: hash(file.name + ':' + file.size) });
  }

  /** Streams a JSON Lines file. opts: {sampleDocs, maxRows, maxBytes, profilePerType, designPerType, splitField}; progress(bytesRead, rows); stop() true cancels. */
  function readJsonl(file, opts, progress, stop) {
    var sam = new Sampler(opts.sampleDocs, hash(file.name + ':' + file.size)), prof = profiler(file, opts);
    var dec = new TextDecoder('utf-8');
    var reader = file.stream().getReader();
    var buf = '', read = 0, truncated = false, lastTick = 0;
    function line(t) {
      t = t.trim();
      if (!t) { return; }
      var o;
      try { o = unwrap(JSON.parse(t)); } catch (e) { sam.bad++; return; }
      sam.add(o);
      if (plain(o)) { prof.add(o, t.length); }
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
      return finish(file, sam, prof, read, truncated);
    });
  }

  /**
   * Streams a JSON array of objects ([{...}, {...}], pretty-printed or not, any size): each element is cut out of the text by counting
   * braces outside strings and parsed alone, so memory holds one element at a time, never the file.
   */
  function readJsonArray(file, opts, progress, stop) {
    var sam = new Sampler(opts.sampleDocs, hash(file.name + ':' + file.size)), prof = profiler(file, opts);
    var dec = new TextDecoder('utf-8');
    var reader = file.stream().getReader();
    var read = 0, truncated = false, lastTick = 0, done = false;
    var depth = 0, inStr = false, esc = false, started = false, cur = '', grab = false, scalarAtTop = 0;
    function element(text) {
      var o;
      try { o = unwrap(JSON.parse(text)); } catch (e) { sam.bad++; return; }
      sam.add(o);
      if (plain(o)) { prof.add(o, text.length); }
    }
    function scan(chunk) {
      var from = grab ? 0 : -1;
      for (var i = 0; i < chunk.length; i++) {
        var ch = chunk.charCodeAt(i);
        if (inStr) {
          if (esc) { esc = false; } else if (ch === 92) { esc = true; } else if (ch === 34) { inStr = false; }
          continue;
        }
        if (ch === 34) { inStr = true; continue; }
        if (!started) { if (ch === 91) { started = true; depth = 1; } else if (ch > 32) { throw new Error('is not a JSON array of objects'); } continue; }
        if (ch === 123 || ch === 91) {
          if (depth === 1 && ch === 123) { grab = true; from = i; cur = ''; }
          else if (depth === 1 && ch === 91) { scalarAtTop++; }
          depth++;
        } else if (ch === 125 || ch === 93) {
          depth--;
          if (depth === 1 && grab && ch === 125) {
            element(cur + chunk.slice(from, i + 1));
            grab = false; cur = ''; from = -1;
            if (sam.rows >= opts.maxRows) { return true; }
          } else if (depth === 0) { done = true; return true; }
        }
      }
      if (grab) { cur += chunk.slice(Math.max(from, 0)); }
      return false;
    }
    function pump() {
      return reader.read().then(function (r) {
        if (stop()) { reader.cancel(); throw new Error('cancelled'); }
        if (r.done) { scan(dec.decode()); return; }
        read += r.value.length;
        var full = scan(dec.decode(r.value, { stream: true }));
        var now = Date.now();
        if (now - lastTick > 80) { lastTick = now; progress(read, sam.rows); }
        if (full || done || read >= opts.maxBytes) {
          truncated = !done && read < file.size;
          reader.cancel();
          return;
        }
        return pump();
      });
    }
    return pump().then(function () {
      progress(read, sam.rows);
      if (scalarAtTop) { sam.bad += scalarAtTop; }
      return finish(file, sam, prof, read, truncated);
    });
  }

  /** The first character of a file that is not white space. */
  function firstChar(file) {
    return file.slice(0, 4096).text().then(function (t) { var m = /\S/.exec(t); return m ? m[0] : ''; });
  }

  /**
   * A .json file: an array is rows, streamed whatever its size; an object is read whole (up to maxJsonBytes) and is a schema when it looks
   * like one, else one document.
   */
  function readJson(file, opts, progress, stop) {
    return firstChar(file).then(function (c) {
      if (c === '[') { return readJsonArray(file, opts, progress, stop); }
      if (file.size > opts.maxJsonBytes) {
        return Promise.reject(new Error('is ' + (file.size / MB).toFixed(1) + ' MB, too big to read as one JSON document; a large file is a JSON array or JSON Lines (.jsonl)'));
      }
      return file.text().then(function (text) {
        progress(file.size, 0);
        var v;
        try { v = JSON.parse(text); } catch (e) { throw new Error('is not valid JSON (' + e.message + ')'); }
        if (looksLikeSchema(v)) { return { type: 'schema', name: file.name, text: text, bytes: file.size }; }
        var sam = new Sampler(opts.sampleDocs, hash(file.name + ':' + file.size)), prof = profiler(file, opts);
        (Array.isArray(v) ? v : [v]).forEach(function (o) { if (sam.rows < opts.maxRows) { o = unwrap(o); sam.add(o); if (plain(o)) { prof.add(o, 0); } } });
        return finish(file, sam, prof, file.size, false);
      });
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
    if (e === 'json') { return readJson(f, opts, progress, stop).then(wrap); }
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
