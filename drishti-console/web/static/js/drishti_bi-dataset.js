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
/* Rupaka phase 2 (docs/architecture/BI_DESIGN.md › Rūpaka: the original design and every phase as built, sections 6 and 7): the datasets list, a model started from the packs, and one dataset's page: a minimal
   read-only Model view and a query preview grid. Same-origin JSON only (/bi/api/datasets/..., /bi/api/model/...), passed on to the server under the
   signed-in user's identity; the server applies the row rules and masks. Everything is built with DOM calls and textContent, never markup strings. */
(function () {
  'use strict';

  var canvas = document.getElementById('biCanvas');
  if (!canvas) { return; }
  var page = canvas.getAttribute('data-bi-page');
  if (page !== 'datasets') { return; }

  function h(tag, attrs) {
    var el = document.createElement(tag);
    Object.keys(attrs || {}).forEach(function (k) {
      if (k === 'text') { el.textContent = attrs[k]; } else if (k === 'class') { el.className = attrs[k]; } else if (attrs[k] === true) { el.setAttribute(k, ''); } else if (attrs[k] !== false && attrs[k] != null) { el.setAttribute(k, attrs[k]); }
    });
    (function add(list) {
      list.forEach(function (c) {
        if (c == null || c === false) { return; }
        if (Array.isArray(c)) { add(c); } else { el.appendChild(typeof c === 'string' ? document.createTextNode(c) : c); }
      });
    })(Array.prototype.slice.call(arguments, 2));
    return el;
  }
  function q(sel, scope) { return (scope || canvas).querySelector(sel); }
  function clear(el) { while (el.firstChild) { el.removeChild(el.firstChild); } }
  function icon(name) { return h('i', { class: 'bi bi-' + name, 'aria-hidden': 'true' }); }
  function say(text, bad) {
    var m = q('[data-msg]');
    m.textContent = text || '';
    m.className = m.className.replace(/\s*bi-bad/, '') + (bad ? ' bi-bad' : '');
  }
  function api(method, path, body) {
    var opt = { method: method, headers: { 'Accept': 'application/json' }, credentials: 'same-origin' };
    if (body !== undefined && method !== 'GET') { opt.headers['Content-Type'] = 'application/json'; opt.body = JSON.stringify(body); }
    return fetch('/bi/api' + path, opt).then(function (r) {
      return r.json().catch(function () { return {}; }).then(function (data) {
        if (!r.ok) { var e = new Error((data.code ? data.code + ' ' : '') + (data.detail || r.statusText)); e.code = data.code; e.status = r.status; throw e; }
        return data;
      });
    });
  }
  function fail(e) { say(e.message, true); }
  /** A message beside the control that caused it (drishti_bi-lib.js draws it); the top line when that script is absent. */
  function id2path(id) { return encodeURIComponent(id).replace(/%3A/g, ':'); }
  function badge(text, cls, ic) { return h('span', { class: 'bi-badge ' + (cls || '') }, ic ? icon(ic) : null, ' ' + text); }

  // -- the list -----------------------------------------------------------------------------------------------------------
  function list() {
    var box = q('[data-datasets]');
    api('GET', '/datasets').then(function (d) {
      clear(box);
      d.datasets.forEach(function (x) {
        box.appendChild(h('article', { class: 'bi-card', 'data-id': x.id, 'data-kind': 'dataset' },
          h('header', {}, h('span', { class: 'bi-kind' }, icon('hdd-stack'), ' dataset')),
          h('h3', {}, h('a', { href: '/bi/d/' + id2path(x.id), text: x.title })),
          x.description ? h('p', { class: 'bi-card-desc', text: x.description }) : null,
          h('p', { class: 'bi-card-badges' }, x.endorsement === 'promoted' ? badge('promoted', 'bi-promoted', 'bookmark-star') : x.endorsement === 'certified' ? badge('certified', 'bi-certified', 'patch-check') : null,
            x.sample ? badge('sample', '', 'mortarboard') : null, x.valid ? null : badge(x.errors + ' error' + (x.errors === 1 ? '' : 's'), 'bi-status', 'exclamation-triangle')),
          h('p', { class: 'bi-card-meta', text: x.tables + ' table' + (x.tables === 1 ? '' : 's') + ' · ' + x.relationships + ' relationship' + (x.relationships === 1 ? '' : 's') + ' · ' + x.measures + (x.measures === 1 ? ' measure' : ' measures') })));
      });
      q('[data-empty]').hidden = d.datasets.length > 0;
    }).catch(fail);
    api('GET', '/me').then(function (me) {
      if (me.powers.indexOf('bi-author') >= 0) { q('[data-new-wizard-wrap]').hidden = false; }
    }).catch(fail);
  }

  list();
})();
