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
/* Build -> New pack: what a large file holds, learnt while it streams through the browser (the file never leaves it), in memory that
 * does not grow with the file:
 *  - COUNTS over EVERY row of each candidate type column (short text or whole numbers with few values): the split shows every type with
 *    its true row count, rare ones included;
 *  - a PROFILE (counters, never documents) of up to profilePerType rows of each type, spread over the whole file: how often each field
 *    has a value at every depth (blank text and null are no value), and how many items each array holds;
 *  - DESIGN SAMPLES: up to designPerType documents of each type, kept for variety (documents with and without each optional array,
 *    different nested shapes), spread over the file by reservoir sampling.
 * The type column is the one the page asks for, else the one that looks like it (named like a type, a product or a kind, with 2 to 64
 * values) chosen after the first rows, which are kept until then and replayed. */
(function () {
  'use strict';
  var MAX_VALUES = 500, CHOOSE_AT = 1000, BUFFER_BYTES = 32 * 1048576, MAX_SHAPES = 8, MAX_TYPES = 200;
  var TYPEISH = /(type|product|kind|class|category|instrument|record|asset)/i;

  function plain(x) { return x !== null && typeof x === 'object' && !Array.isArray(x); }
  function blank(v) { return v === null || v === undefined || (typeof v === 'string' && v.trim() === ''); }

  /** A tiny seeded generator (mulberry32): the same file gives the same samples. */
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

  /** Counters for one set of rows: {rows, objects: {prefix: n}, paths: {path: {n, blank}}, arrays: {path: {min, max, sum, count, empty}}}. */
  function Counters() { this.rows = 0; this.objects = { '': 0 }; this.paths = {}; this.arrays = {}; }
  Counters.prototype.object = function (o, prefix, depth) {
    this.objects[prefix] = (this.objects[prefix] || 0) + 1;
    if (depth > 8) { return; }
    var keys = Object.keys(o);
    for (var i = 0; i < keys.length && i < 400; i++) {
      var k = keys[i], v = o[k], p = prefix + k;
      var s = this.paths[p] || (this.paths[p] = { n: 0, blank: 0 });
      s.n++;
      if (blank(v) || (Array.isArray(v) && !v.length)) { s.blank++; }
      if (Array.isArray(v)) {
        var a = this.arrays[p] || (this.arrays[p] = { min: v.length, max: v.length, sum: 0, count: 0, empty: 0 });
        a.min = Math.min(a.min, v.length); a.max = Math.max(a.max, v.length); a.sum += v.length; a.count++;
        if (!v.length) { a.empty++; }
        for (var j = 0; j < v.length && j < 400; j++) { if (plain(v[j])) { this.object(v[j], p + '[].', depth + 1); } }
      } else if (plain(v)) {
        this.object(v, p + '.', depth + 1);
      }
    }
  };
  Counters.prototype.add = function (doc) { this.rows++; this.object(doc, '', 0); };
  Counters.prototype.view = function () {
    var arrays = {}, self = this;
    Object.keys(this.arrays).forEach(function (p) {
      var cut = p.lastIndexOf('.'), parent = cut < 0 ? '' : p.slice(0, cut + 1), a = self.arrays[p];
      arrays[p] = { min: a.min, max: a.max, sum: a.sum, count: a.count, empty: a.empty, absent: Math.max(0, (self.objects[parent] || 0) - a.count) };
    });
    return { rows: this.rows, objects: this.objects, paths: this.paths, arrays: arrays };
  };

  function merge(into, c) {
    into.rows += c.rows;
    Object.keys(c.objects).forEach(function (p) { into.objects[p] = (into.objects[p] || 0) + c.objects[p]; });
    Object.keys(c.paths).forEach(function (p) {
      var s = into.paths[p] || (into.paths[p] = { n: 0, blank: 0 });
      s.n += c.paths[p].n; s.blank += c.paths[p].blank;
    });
    Object.keys(c.arrays).forEach(function (p) {
      var a = c.arrays[p], b = into.arrays[p];
      if (!b) { into.arrays[p] = { min: a.min, max: a.max, sum: a.sum, count: a.count, empty: a.empty }; return; }
      b.min = Math.min(b.min, a.min); b.max = Math.max(b.max, a.max); b.sum += a.sum; b.count += a.count; b.empty += a.empty;
    });
  }

  /** The shape of a document for variety: which arrays (two levels down) it has filled, empty or not at all. */
  function shape(doc) {
    var parts = [];
    Object.keys(doc).forEach(function (k) {
      var v = doc[k];
      if (Array.isArray(v)) {
        parts.push(k + (v.length ? '+' : '0'));
        var inner = {};
        v.slice(0, 20).forEach(function (x) { if (plain(x)) { Object.keys(x).forEach(function (kk) { if (Array.isArray(x[kk])) { inner[kk] = inner[kk] || (x[kk].length ? '+' : '0'); } }); } });
        Object.keys(inner).sort().forEach(function (kk) { parts.push(k + '.' + kk + inner[kk]); });
      }
    });
    return parts.sort().join(',');
  }

  /**
   * Profiler(opts): opts {profilePerType, designPerType, splitField (optional), seed}. add(doc) for every row; result() at the end:
   * {counts: {field: {value: rows}}, split: field or '', profile: {by, all, types: {value: counters}}, design: [documents]}.
   */
  function Profiler(opts) {
    this.o = opts || {};
    this.rand = prng((this.o.seed || 7) >>> 0);
    this.counts = {};                // field -> {value: n}; a field over MAX_VALUES values is dropped (an id, not a type)
    this.dropped = {};
    this.split = this.o.splitField || '';
    this.chosen = !!this.split;
    this.buffer = []; this.bufferBytes = 0;
    this.all = new Counters();
    this.types = {};                 // value -> {seen, profiled: Counters, shapes: {shape: {seen, docs}}}
    this.rows = 0;
  }
  Profiler.prototype.count = function (doc) {
    var self = this;
    Object.keys(doc).forEach(function (k) {
      if (self.dropped[k]) { return; }
      var v = doc[k];
      if (v !== null && typeof v === 'object') { self.dropped[k] = true; delete self.counts[k]; return; }
      var key = blank(v) ? '' : (typeof v === 'string' && v.length > 64 ? null : String(v));
      if (key === null || typeof v === 'number' && !Number.isInteger(v)) { self.dropped[k] = true; delete self.counts[k]; return; }
      var c = self.counts[k] || (self.counts[k] = {});
      if (!(key in c) && Object.keys(c).length >= MAX_VALUES) { self.dropped[k] = true; delete self.counts[k]; return; }
      c[key] = (c[key] || 0) + 1;
    });
  };
  /** The type column when none was asked for: named like a type, 2 to 64 values, the fewest values first among those. */
  Profiler.prototype.choose = function () {
    var self = this, best = null;
    Object.keys(this.counts).forEach(function (k) {
      var vals = Object.keys(self.counts[k]).filter(function (v) { return v !== ''; });
      if (!TYPEISH.test(k) || vals.length < 2 || vals.length > 64) { return; }
      var score = (/product.?type|record.?type|^type$/i.test(k) ? 0 : 1) * 1000 + vals.length;
      if (!best || score < best.score) { best = { k: k, score: score }; }
    });
    this.split = best ? best.k : '';
    this.chosen = true;
    var buf = this.buffer;
    this.buffer = null;
    for (var i = 0; i < buf.length; i++) { this.typed(buf[i]); }
  };
  Profiler.prototype.typed = function (doc) {
    var t = this.split ? (blank(doc[this.split]) ? '' : String(doc[this.split])) : '*';
    var e = this.types[t];
    if (!e) {
      if (Object.keys(this.types).length >= MAX_TYPES) { return; }
      e = this.types[t] = { seen: 0, profiled: new Counters(), shapes: {} };
    }
    e.seen++;
    var cap = this.o.profilePerType || 10000;
    if (e.seen <= cap || this.rand() < cap / e.seen) { e.profiled.add(doc); }      // spread over the file: later rows keep a chance
    var per = Math.max(1, Math.ceil((this.o.designPerType || 50) / 4));
    var sh = shape(doc), s = e.shapes[sh];
    if (!s) {
      if (Object.keys(e.shapes).length >= MAX_SHAPES) { sh = Object.keys(e.shapes)[0]; s = e.shapes[sh]; }
      else { s = e.shapes[sh] = { seen: 0, docs: [] }; }
    }
    s.seen++;
    if (s.docs.length < per) { s.docs.push(doc); } else { var j = Math.floor(this.rand() * s.seen); if (j < per) { s.docs[j] = doc; } }
  };
  Profiler.prototype.add = function (doc, bytes) {
    this.rows++;
    this.count(doc);
    if (this.chosen) { this.typed(doc); return; }
    this.buffer.push(doc);
    this.bufferBytes += bytes || 0;
    if (this.buffer.length >= CHOOSE_AT || this.bufferBytes > BUFFER_BYTES) { this.choose(); }
  };
  Profiler.prototype.result = function () {
    if (!this.chosen) { this.choose(); }
    var self = this, types = {}, design = [], want = this.o.designPerType || 50, all = this.all;
    Object.keys(this.types).forEach(function (t) {
      var e = self.types[t];
      merge(all, e.profiled);                  // the whole file's profile: every type's profiled rows together
      types[t] = e.profiled.view();
      types[t].seen = e.seen;
      // variety first: one document of each shape in turn until the type has its share
      var lists = Object.keys(e.shapes).map(function (k) { return e.shapes[k].docs.slice(); }), got = 0;
      while (got < want && lists.some(function (l) { return l.length; })) {
        lists.forEach(function (l) { if (got < want && l.length) { design.push(l.shift()); got++; } });
      }
    });
    var counts = {};
    Object.keys(this.counts).forEach(function (k) { if (Object.keys(self.counts[k]).length > 1 || self.counts[k]['']) { counts[k] = self.counts[k]; } });
    return { counts: counts, split: this.split, design: design,
      profile: { by: this.split, all: this.all.view(), types: types } };
  };

  window.DrishtiPackProfile = { Profiler: Profiler, Counters: Counters, shape: shape };
})();
