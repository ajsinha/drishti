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
/* Field history: click a number in a view (a strip value, a field, a table cell) to see it over the last business
   days: a line chart and the values, read from the dated sources for each day. Alt+click (or the strip's number while
   a link is under it) still follows the link. */
(function (g) { g.Drishti = g.Drishti || {}; })(typeof window !== 'undefined' ? window : globalThis); /* one namespace for everything the console puts on window (tools/dev/asset_prefix.py) */
(function () {
  'use strict';
  // Ages: "updated 3 min ago" from an ISO time, kept current while the page is open.
  function ago(iso) {
    var s = Math.max(0, Math.round((Date.now() - Date.parse(iso)) / 1000));
    if (isNaN(s)) { return iso; }
    return s < 60 ? s + ' s ago' : s < 3600 ? Math.round(s / 60) + ' min ago' : s < 172800 ? Math.round(s / 3600) + ' h ago' : Math.round(s / 86400) + ' days ago';
  }
  function ages() { document.querySelectorAll('[data-age]').forEach(function (el) { el.textContent = ago(el.getAttribute('data-age')); }); }
  ages();
  setInterval(ages, 30000);
  var view = document.querySelector('[data-view]');
  if (!view || document.querySelector('[data-embed]')) { return; }
  var kind = view.getAttribute('data-kind'), id = view.getAttribute('data-id');
  var NUMBER = /^[+\-−]?[\d,]+(\.\d+)?\s*(%|bp|k|m|bn)?$/i;
  var dlg = null, chart = null, current = null;

  var F = window.Drishti.elements.core;                                       // numbers by drishti-core
  function shown(v) { return v == null ? '—' : typeof v === 'number' ? F.formatNumber(v, { maxDecimals: 3, minus: '-' }) : v; }
  /** drishti-chart, loaded with its engine the first time a history is opened (a page with numbers pays nothing until then) */
  var chartLib = null;
  function charts() {
    if (!chartLib) { chartLib = F.load('drishti-chart'); chartLib.catch(function () { chartLib = null; }); }
    return chartLib;
  }
  function build() {
    dlg = document.createElement('dialog');
    dlg.className = 'hist-dlg';
    dlg.innerHTML = '<header class="hist-h"><h3 data-h></h3><span class="hist-tools">'
      + '<drishti-select data-days label="Business days"><option value="10">10 days</option><option value="30" selected>30 days</option>'
      + '<option value="90">90 days</option><option value="250">250 days</option></drishti-select>'
      + '<a class="fk" data-alert-me href="/alerts/new" title="Be told when this number crosses a limit, moves too far or does not arrive">Alert me</a>'
      + '<button type="button" class="fk" data-note title="Discuss this field">Discuss</button>'
      + '<button type="button" class="fk" data-close aria-label="Close">×</button></span></header>'
      + '<p class="hist-origin" data-origin></p><p class="hist-msg text-muted-d" data-msg></p><div class="hist-chart" data-chart-box></div>'
      + '<div class="tbl-wrap hist-tbl"><table class="tbl" data-plain><thead><tr><th>Business date</th><th class="num">Value</th>'
      + '<th>Data for</th><th>Source</th></tr></thead><tbody data-rows></tbody></table></div>';
    document.body.appendChild(dlg);
    dlg.querySelector('[data-close]').addEventListener('click', function () { dlg.close(); });
    dlg.querySelector('[data-note]').addEventListener('click', function () {
      dlg.close();
      if (window.Drishti.discussion) { window.Drishti.discussion.open({ path: current.path }); }
    });
    dlg.querySelector('[data-days]').addEventListener('drishti:change', function () { load(); });   // drishti-inputs' Select
    // Raw document opens the viewer on this page at the field (drishti_raw-viewer.js); a Ctrl/⌘ or middle click still opens the JSON in a tab
    dlg.querySelector('[data-origin]').addEventListener('click', function (e) {
      var a = e.target.closest('[data-raw-open]');
      if (!a || !window.Drishti.Raw || e.ctrlKey || e.metaKey || e.shiftKey || e.button !== 0) { return; }
      e.preventDefault();
      window.Drishti.Raw.open({ kind: kind, id: id, path: current.path, date: a.getAttribute('data-date') || null, opener: a });
    });
    dlg.addEventListener('close', function () { if (chart) { chart.destroy(); chart = null; } });
  }
  function esc(s) { return String(s == null ? '' : s).replace(/[&<>"]/g, function (c) { return { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[c]; }); }

  function load() {
    var days = dlg.querySelector('[data-days]').value, msg = dlg.querySelector('[data-msg]');
    msg.textContent = 'Reading ' + days + ' business days…';
    fetch('/api/series/' + encodeURIComponent(kind) + '/' + encodeURIComponent(id) + '?days=' + days + '&path=' + encodeURIComponent(current.path))
      .then(function (r) { return r.json().then(function (b) { return { ok: r.ok, body: b }; }); })
      .then(function (res) {
        if (!res.ok) { msg.textContent = Drishti.drsMessage(res.body); return; }
        var pts = res.body.points || [], numeric = pts.filter(function (p) { return typeof p.value === 'number'; });
        var latest = pts[pts.length - 1] || {};
        dlg.querySelector('[data-origin]').innerHTML = latest.source
          ? 'Where it comes from: <b class="mono">' + esc(shown(latest.value))
            + '</b> at <span class="mono">' + esc(res.body.path) + '</span> in ' + esc(kind) + ' <span class="mono">' + esc(id) + '</span>, from <b class="mono">'
            + esc(latest.source) + '</b>' + (latest.dataDate ? ', data for <span class="mono">' + esc(latest.dataDate) + '</span>' : ', undated (current data)')
            + (latest.generation != null ? ', generation <span class="mono">' + esc(latest.generation) + '</span>' : '') + '. '
            + '<a class="lnk" data-raw-open href="/api/raw/' + encodeURIComponent(kind) + '/' + encodeURIComponent(id) + '" data-date="' + esc(latest.dataDate || '') + '" title="Open the document on this page, at this field">Raw document</a>'
          : '';
        msg.textContent = res.body.dated ? numeric.length + ' of ' + pts.length + ' business days have a value.'
          : 'No source keeps history for this entity: the value is today’s, the same every day.';
        dlg.querySelector('[data-rows]').innerHTML = pts.slice().reverse().map(function (p) {
          var carried = p.dataDate && p.dataDate !== p.date;
          return '<tr><td class="mono">' + esc(p.date) + '</td><td class="num mono">' + esc(shown(p.value))
            + '</td><td class="mono' + (carried ? ' t-warn' : '') + '">' + esc(p.dataDate || '—') + '</td><td class="mono">' + esc(p.source || '—') + '</td></tr>';
        }).join('');
        if (!numeric.length) { return; }
        var spec = { kind: 'line', title: current.label.trim(), id: 'date', onClick: 'none', legend: 'none', tone: 'link',
          data: { rows: pts.map(function (p) { return { date: p.date, value: typeof p.value === 'number' ? p.value : null }; }) },
          x: { field: 'date', type: 'category' }, y: { field: 'value', format: 'number' } };
        return charts().then(function (E) {
          if (!dlg.open) { return; }
          if (chart) { chart.setSpec(spec); } else { chart = new E.Chart(dlg.querySelector('[data-chart-box]'), spec, { height: 220 }); }
        });
      })
      .catch(function (e) { msg.textContent = 'Could not read the history: ' + e; });
  }

  view.addEventListener('click', function (e) {
    if (e.altKey || e.ctrlKey || e.metaKey) { return; }
    var cell = e.target.closest('[data-path]');
    if (!cell || cell.tagName === 'TR' || e.target.closest('a')) { return; }
    var text = cell.textContent.trim();
    if (!NUMBER.test(text)) { return; }
    current = { path: cell.getAttribute('data-path'), label: (cell.previousElementSibling && cell.previousElementSibling.tagName === 'DT' ? cell.previousElementSibling.textContent : cell.getAttribute('data-path')) };
    if (!dlg) { build(); }
    dlg.querySelector('[data-h]').textContent = current.label.trim() + ' · ' + id + ' over time';
    dlg.querySelector('[data-alert-me]').href = '/alerts/new?source=field&kind=' + encodeURIComponent(kind) + '&id=' + encodeURIComponent(id)
      + '&field=' + encodeURIComponent(current.path) + '&title=' + encodeURIComponent(current.label.trim() + ' of ' + id);
    dlg.showModal();
    load();
  });
})();
