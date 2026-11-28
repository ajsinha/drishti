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
(function (g) {
  'use strict';
  /* Your Home's behaviour; nothing is drawn here. The page holds the person's Home as a ViewModel (the server composed it from the cards their
     modules offer and the home tables it serves for them); the Drishti Elements painter (drishti-views) draws it, buttons and all (each card's
     panel actions). What a button does comes back here as a host effect: pin, hide, move, snooze and reset are the person's own setting on the
     server (POST /api/home/cards/prefs answers the Home again); Explain opens the one Explain's side panel; Open runs the card's command. */
  var root = document.querySelector('[data-home-cards]');
  if (!root) { return; }
  var D = (g.Drishti = g.Drishti || {});
  var host = root.querySelector('[data-home-cards-view]');
  var data = JSON.parse(document.getElementById('home-cards-data').getAttribute('data-home'));
  var handle = null;

  function csrf() {
    var m = document.querySelector('meta[name="csrf-token"]');
    return m ? m.getAttribute('content') : '';
  }

  function href(link) {
    var id = String((link && link.id) || '');
    if (link && link.kind === 'command') { return drishti_url('/go?q=' + encodeURIComponent(id + ' <GO>')); }
    if (link && link.kind === 'href') { return /^\/(?!\/)/.test(id) ? drishti_url(id) : null; }
    return link && link.kind && id ? drishti_url('/v/' + encodeURIComponent(link.kind) + '/' + encodeURIComponent(id)) : null;
  }

  /* "View as": All, and one button per Home the person has (a Drishti Elements button group, one pressed); a choice is the person's own setting on the
     server, and the Home is drawn again in place. Hidden for a person with one Home. */
  var ALL = '*', switcher = root.querySelector('[data-home-viewas]'), group = null;      /* the All button's value (a button group keeps a value that is not empty) */
  function viewAs(next) {
    var v = next.viewAs || { options: [] };
    switcher.hidden = v.options.length < 2;
    if (switcher.hidden) { return Promise.resolve(); }
    return D.elements.load('drishti-inputs').then(function () {
      var box = switcher.querySelector('[data-home-viewas-group]');
      if (group && group.destroy) { group.destroy(); }
      box.textContent = '';
      group = new D.elements.inputs.ButtonGroup(box, {
        label: 'View as', mode: 'single', value: v.value || ALL,
        buttons: v.options.map(function (o) { return { label: o.label, data: { value: o.value || ALL }, attrs: { title: o.value ? 'Show the Home of ' + o.label : 'Show every Home you have' } }; })
      });
      group.on('change', function (d) {
        send('viewas', d && d.value !== ALL ? d.value : '').then(function (n) { if (n) { data = n; paint(n.view); } });
      });
    });
  }

  function paint(view) {
    root.setAttribute('data-layout', (view.provenance && view.provenance.layout) || 'calm');
    if (handle) { handle.dispose(); handle = null; }
    host.textContent = '';
    return D.elements.load('drishti-views').then(function () {
      handle = D.elements.views.paint(host, view, {
        shadow: false, header: 'full', density: view.layout && view.layout.density === 'compact' ? 'compact' : 'comfortable',
        links: { href: href },
        host: { run: run }
      });
      handle.on('link', function (e) {
        var to = href(e.detail && e.detail.link);
        if (to) { g.location.href = to; }
      });
      root.dispatchEvent(new CustomEvent('drishti:home-drawn', { bubbles: true, detail: { cards: data.cards } }));
    });
  }

  function send(action, card, keys) {
    return fetch(drishti_url('/api/home/cards/prefs'), {
      method: 'POST', credentials: 'same-origin',
      headers: { 'Content-Type': 'application/json', 'X-CSRF-Token': csrf() },
      body: JSON.stringify({ action: action, card: card || '', keys: keys || [] })
    }).then(function (r) { return r.ok ? r.json() : null; });
  }

  /* a button of a panel: { do: 'host', action, with: { card, ... } } */
  function run(effect) {
    var w = effect.with || {}, a = effect.action;
    if (a === 'explain') {
      if (D.Explain && D.Explain.open) { D.Explain.open(w.request || {}, 'formula', host); }
    } else if (a === 'open') {
      if (typeof w.command === 'string' && w.command.trim()) { g.location.href = drishti_url('/go?q=' + encodeURIComponent(w.command.trim())); }
    } else {
      send(a, w.card, w.keys).then(function (next) { if (next) { data = next; viewAs(next); paint(next.view); } });
    }
  }

  viewAs(data);
  paint(data.view).then(function () { send('visited'); });       /* one visit per half hour is kept: "last visit" in the strip */
  D.homeCards = { root: root, paint: paint };
})(typeof window !== 'undefined' ? window : globalThis);
