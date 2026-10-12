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
/* The first script of Calc's Graphic Walker frame (walker.html). The frame is sandboxed without allow-same-origin, so its origin is opaque: reading document.cookie or
   touching localStorage / sessionStorage throws a SecurityError, and Graphic Walker (its language detector, its settings) does both. This gives it what it expects, held
   in memory and gone with the frame: no cookie, empty storage. Nothing here reaches the page's real cookies or storage (the frame cannot: that is what the sandbox is for). */
(function () {
  'use strict';
  function memory() {
    var data = {};
    return {
      get length() { return Object.keys(data).length; },
      key: function (i) { return Object.keys(data)[i] || null; },
      getItem: function (k) { return Object.prototype.hasOwnProperty.call(data, k) ? data[k] : null; },
      setItem: function (k, v) { data[String(k)] = String(v); },
      removeItem: function (k) { delete data[k]; },
      clear: function () { data = {}; }
    };
  }
  function define(target, name, descriptor) {
    try { Object.defineProperty(target, name, Object.assign({ configurable: true }, descriptor)); } catch (e) { /* the browser keeps its own */ }
  }
  define(document, 'cookie', { get: function () { return ''; }, set: function () { /* nowhere to keep it */ } });
  define(window, 'localStorage', { value: memory() });
  define(window, 'sessionStorage', { value: memory() });
})();
