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
/* The inbox page and the no-access page (COLLABORATION.md, Inbox): marking notices read (one, all, or by opening one), keeping the bell's
   count right, and the "Switch on <pack>" button of the no-access page. The rows are drawn by the console from the server's answer, so
   what a reader may see is decided there; this file never builds a row. */
(function () {
  'use strict';

  function post(path, body, keep) {
    return fetch(path, { method: 'POST', headers: { 'Content-Type': 'application/json', Accept: 'application/json' }, credentials: 'same-origin',
      body: JSON.stringify(body), keepalive: !!keep }).then(function (r) { return r.json().catch(function () { return {}; }).then(function (j) { return { ok: r.ok, j: j }; }); });
  }
  function bell(unread) {
    var b = document.querySelector('[data-bell-count]');
    if (!b || typeof unread !== 'number') { return; }
    b.textContent = String(unread); b.hidden = unread <= 0;
    document.dispatchEvent(new CustomEvent('drishti:unread', { detail: unread }));
  }
  function say(text) { var m = document.querySelector('[data-inbox-msg]'); if (m) { m.textContent = text; } }

  var page = document.querySelector('[data-inbox]');
  if (page) {
    var rows = function () { return Array.prototype.slice.call(page.querySelectorAll('.inbox-row')); };
    var mark = function (row) {
      row.classList.remove('unread'); row.setAttribute('data-read', 'true');
      var dot = row.querySelector('.inbox-dot'); if (dot) { dot.textContent = ''; }
      var b = row.querySelector('[data-inbox-read]'); if (b) { b.hidden = true; }
      var hid = row.querySelector('.visually-hidden'); if (hid) { hid.remove(); }
    };
    page.addEventListener('click', function (e) {
      var one = e.target.closest('[data-inbox-read]');
      if (one) {
        var row = one.closest('.inbox-row');
        post('/api/inbox/read', { seqs: [parseInt(row.getAttribute('data-seq'), 10)] }).then(function (r) {
          if (!r.ok) { say(r.j.detail || 'could not mark it read'); return; }
          mark(row); bell(r.j.unread); say('Marked read.');
        });
        return;
      }
      var open = e.target.closest('[data-inbox-open]');
      if (open) {                                               // opening a notice reads it; the page may be leaving, so keep the request alive
        var r2 = open.closest('.inbox-row');
        if (r2.getAttribute('data-read') !== 'true') { post('/api/inbox/read', { seqs: [parseInt(r2.getAttribute('data-seq'), 10)] }, true); }
      }
    });
    var all = page.querySelector('[data-inbox-mark-all]');
    if (all) {
      all.addEventListener('click', function () {
        var top = 0;
        rows().forEach(function (r) { top = Math.max(top, parseInt(r.getAttribute('data-seq'), 10) || 0); });
        if (!top) { say('Nothing to mark.'); return; }
        post('/api/inbox/read', { upTo: top }).then(function (r) {
          if (!r.ok) { say(r.j.detail || 'could not mark them read'); return; }
          rows().forEach(mark); bell(r.j.unread); say('All marked read.');
        });
      });
    }
  }

  // no-access page: the pack is assigned but switched off for this person
  var sw = document.querySelector('[data-switch-on]');
  if (sw) {
    sw.addEventListener('click', function () {
      var active = []; try { active = JSON.parse(sw.getAttribute('data-active') || '[]'); } catch (e) { active = []; }
      var name = sw.getAttribute('data-switch-on'), msg = document.querySelector('[data-switch-msg]');
      if (active.indexOf(name) < 0) { active.push(name); }
      sw.disabled = true;
      post('/api/packs', { active: active }).then(function (r) {
        if (r.ok) { window.location.reload(); } else { sw.disabled = false; if (msg) { msg.textContent = r.j.detail || 'could not switch it on'; } }
      });
    });
  }
})();
