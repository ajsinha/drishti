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
/* Live views: listens to /api/stream/<kind>/<id> and applies each frame's patches in place. Strip cells
   are updated text-and-tone, charts move to new data, other panels are swapped for server-rendered HTML
   (same macros as first paint). A changed value flashes. The top bar shows the server's rolling p99. */
(function () {
  'use strict';
  var view = document.querySelector('[data-view]');
  if (!view || !window.EventSource) { return; }
  // a picked business date is a static snapshot: nothing to stream
  if (document.querySelector('[data-asof].asof-past') || view.hasAttribute('data-static')) { return; }
  var liveBox = document.querySelector('.tbar-live');
  var liveText = document.querySelector('[data-live-text]');
  var strip = document.querySelectorAll('.strip-i dd');
  var first = true;

  function state(s, text) {
    if (liveBox) { liveBox.setAttribute('data-live-state', s); }
    if (liveText && text) { liveText.textContent = text; }
  }
  function flash(el) { el.classList.remove('flash'); void el.offsetWidth; el.classList.add('flash'); }
  function esc(s) { return String(s == null ? '' : s).replace(/[&<>"]/g, function (c) { return { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[c]; }); }

  function cellHtml(c) {
    return c.link ? '<a class="lnk" href="/v/' + encodeURIComponent(c.link.kind) + '/' + encodeURIComponent(c.link.id) + '">' + esc(c.text) + '</a>' : esc(c.text);
  }

  function applyStrip(p) {
    var dd = strip[p.index];
    if (!dd) { return; }
    dd.innerHTML = cellHtml(p.cell);
    dd.className = dd.className.replace(/\bt-\w+/g, '').trim() + (p.cell.tone ? ' t-' + p.cell.tone : '');
    flash(dd);
  }

  function applyPanel(p) {
    var old = document.getElementById('p-' + p.panel.id);
    if (!old) { return; }
    if (!p.html) { window.drishti && window.drishti.updateChart(p.panel.id, p.panel.data); return; }
    var chosen = old.querySelector('[role="tab"][aria-selected="true"]');
    var chosenId = chosen ? chosen.id : null;
    var tpl = document.createElement('template');
    tpl.innerHTML = p.html.trim();
    var fresh = tpl.content.firstElementChild;
    old.replaceWith(fresh);
    if (window.drishti) { window.drishti.enhance(fresh); }
    if (chosenId) { var t = document.getElementById(chosenId); if (t) { t.click(); } }
    fresh.querySelectorAll('tbody td, dd').forEach(function (td) { td.classList.add('live-cell'); });
  }

  function applyProvenance(p) {
    var note = document.querySelector('.fk-note');
    if (note) { note.textContent = note.textContent.replace(/gen \d+/, 'gen ' + p.provenance.generation); }
  }

  var es = new EventSource('/api/stream/' + encodeURIComponent(view.dataset.kind) + '/' + encodeURIComponent(view.dataset.id));
  es.addEventListener('view', function () {
    if (!first) { location.reload(); return; }   // reconnected: the server sent a fresh view, so repaint from it
    first = false;
    state('live', 'Live');
  });
  es.addEventListener('frame', function (e) {
    var f = JSON.parse(e.data);
    state('live', 'Live, p99 ' + Math.round(f.p99Ms) + ' ms');
    f.patches.forEach(function (p) {
      try {
        if (p.op === 'strip') { applyStrip(p); } else if (p.op === 'panel') { applyPanel(p); } else if (p.op === 'provenance') { applyProvenance(p); }
      } catch (err) {
        if (window.console) { console.warn('drishti: patch not applied', p.op, err); }
      }
    });
  });
  es.addEventListener('gone', function () { es.close(); state('static', 'Static'); });
  es.onerror = function () { state('reconnecting', 'Reconnecting…'); };
})();
