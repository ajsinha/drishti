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
/* Build -> New pack: small helpers the page's scripts share (element builder, JSON calls, number formats). Text from files and from the
 * server always goes in as text nodes. */
(function () {
  'use strict';
  var MB = 1048576;

  function el(tag, attrs, kids) {
    var e = document.createElement(tag);
    Object.keys(attrs || {}).forEach(function (k) {
      var v = attrs[k];
      if (v === false || v === null || v === undefined) { return; }
      if (k === 'text') { e.textContent = v; } else if (k === 'class') { e.className = v; } else { e.setAttribute(k, v === true ? '' : v); }
    });
    (kids || []).forEach(function (c) { if (c !== null && c !== undefined) { e.appendChild(typeof c === 'string' ? document.createTextNode(c) : c); } });
    return e;
  }

  /** JSON in, JSON out: resolves {ok, status, body} whatever the status. */
  function api(method, url, body) {
    return fetch(url, { method: method, headers: body === undefined ? {} : { 'Content-Type': 'application/json' }, body: body === undefined ? undefined : JSON.stringify(body) })
      .then(function (r) {
        return (r.status === 204 ? Promise.resolve({}) : r.json().catch(function () { return {}; })).then(function (j) { return { ok: r.ok, status: r.status, body: j }; });
      }, function () { return { ok: false, status: 0, body: { detail: 'The console could not be reached.' } }; });
  }

  function why(r) {
    return window.drsMessage ? window.drsMessage({ code: r.body.code || ('HTTP ' + r.status), detail: r.body.detail }, 'The request failed') : (r.body.detail || 'The request failed');
  }

  function say(node, text, bad) { node.textContent = text; node.classList.toggle('bad', !!bad); }

  function bytes(n) { return n >= MB ? (n / MB).toFixed(1) + ' MB' : n >= 1024 ? Math.round(n / 1024) + ' KB' : n + ' bytes'; }
  function num(n) { return Number(n).toLocaleString('en-US'); }
  function plural(n, one, many) { return n + ' ' + (n === 1 ? one : (many || one + 's')); }

  /** A <select> from [{value, label}] with `current` selected. */
  function select(opts, current, attrs) {
    var s = el('select', Object.assign({ class: 'studio-in' }, attrs || {}));
    opts.forEach(function (o) {
      var op = el('option', { value: o.value, text: o.label });
      if (String(o.value) === String(current)) { op.selected = true; }
      s.appendChild(op);
    });
    return s;
  }

  function clear(node) { while (node.firstChild) { node.removeChild(node.firstChild); } }

  window.DrishtiPackUI = { el: el, api: api, why: why, say: say, bytes: bytes, num: num, plural: plural, select: select, clear: clear };
})();
