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
(function (g) { g.Drishti = g.Drishti || {}; })(typeof window !== 'undefined' ? window : globalThis); /* one namespace for everything the console puts on window (tools/dev/asset_prefix.py) */
/* The Build home's "For you" strip (BI Studio's own home, folded in; SHARED_SCREENS.md section 1): Recent, Favourites, Certified for you
   and Shared with me, as the server's BI home answers them for the person signed in (GET /bi/api/home: the console asks as them).
   Drawn with drishti-core (its API client and element maker: text only, no markup strings); the star sets or clears a favourite
   (PUT /bi/api/items/<id>/favourite) and the strip is asked again. Nothing is kept outside this page's own closure. */
(function () {
  'use strict';
  var box = document.querySelector('[data-for-you]');
  var C = window.Drishti.elements && window.Drishti.elements.core;
  if (!box || !C) { return; }
  var doc = document, api = C.createClient({ base: '/bi/api' }), msg = box.querySelector('[data-fy-msg]');
  var SECTIONS = ['recent', 'favourites', 'certified', 'shared'];
  var ICON = { report: 'file-earmark-bar-graph', board: 'grid-1x2', model: 'boxes', dataset: 'hdd-stack', notebook: 'journal-code', transform: 'funnel' };

  function href(it) { return '/bi/i/' + encodeURIComponent(it.id).replace(/%3A/g, ':'); }
  function star(it) {
    return C.h(doc, 'button', { cls: 'bh-fy-star', attrs: { type: 'button', 'aria-pressed': it.favourite ? 'true' : 'false',
      'aria-label': (it.favourite ? 'Remove favourite: ' : 'Favourite: ') + it.title, title: 'Favourite' }, data: { fyStar: it.id },
      on: { click: function () {
        api.request('PUT', 'items/' + encodeURIComponent(it.id) + '/favourite', { on: !it.favourite }).then(function () {
          msg.textContent = it.favourite ? 'Removed from your favourites.' : 'Added to your favourites.';
          return load();
        }, function (e) { msg.textContent = e.message; });
      } } }, [C.h(doc, 'i', { cls: 'bi bi-' + (it.favourite ? 'star-fill' : 'star'), attrs: { 'aria-hidden': 'true' } })]);
  }
  function item(it) {
    return C.h(doc, 'li', { cls: 'bh-fy-item', data: { fyItem: it.id } }, [
      C.h(doc, 'i', { cls: 'bi bi-' + (ICON[it.kind] || 'file-earmark'), attrs: { 'aria-hidden': 'true', title: it.kind } }),
      C.h(doc, 'a', { cls: 'lnk', text: it.title, attrs: { href: href(it) } }),
      C.h(doc, 'span', { cls: 'bh-fy-kind', text: it.kind }),
      star(it)]);
  }
  function draw(d) {
    SECTIONS.forEach(function (s) {
      var card = box.querySelector('[data-fy="' + s + '"]'), list = card.querySelector('[data-fy-list]'), items = (d && d[s]) || [];
      list.textContent = '';
      items.forEach(function (it) { list.appendChild(item(it)); });
      card.querySelector('[data-fy-empty]').hidden = items.length > 0;
    });
    box.hidden = false;
  }
  function load() {
    return api.request('GET', 'home').then(draw, function (e) { box.hidden = false; msg.textContent = 'For you could not be shown: ' + e.message; });
  }
  load();
})();
