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
(function () {
  'use strict';
  var view = document.querySelector('[data-view]');
  if (!view || document.querySelector('[data-embed]')) { return; }
  var kind = view.getAttribute('data-kind'), id = view.getAttribute('data-id');
  var NUMBER = /^[+\-−]?[\d,]+(\.\d+)?\s*(%|bp|k|m|bn)?$/i;
  var dlg = null, chart = null, current = null;

  function tokens() {
    var s = getComputedStyle(document.documentElement);
    return { ink: s.getPropertyValue('--d-ink').trim(), muted: s.getPropertyValue('--d-muted').trim(),
      line: s.getPropertyValue('--d-link').trim(), grid: s.getPropertyValue('--d-border').trim() };
  }
  function build() {
    dlg = document.createElement('dialog');
    dlg.className = 'hist-dlg';
    dlg.innerHTML = '<header class="hist-h"><h3 data-h></h3><span class="hist-tools">'
      + '<select data-days aria-label="Business days"><option value="10">10 days</option><option value="30" selected>30 days</option>'
      + '<option value="90">90 days</option><option value="250">250 days</option></select>'
      + '<button type="button" class="fk" data-close aria-label="Close">×</button></span></header>'
      + '<p class="hist-msg text-muted-d" data-msg></p><div class="hist-chart" data-chart-box></div>'
      + '<div class="tbl-wrap hist-tbl"><table class="tbl" data-plain><thead><tr><th>Business date</th><th class="num">Value</th>'
      + '<th>Data for</th><th>Source</th></tr></thead><tbody data-rows></tbody></table></div>';
    document.body.appendChild(dlg);
    dlg.querySelector('[data-close]').addEventListener('click', function () { dlg.close(); });
    dlg.querySelector('[data-days]').addEventListener('change', function () { load(); });
    dlg.addEventListener('close', function () { if (chart) { chart.dispose(); chart = null; } });
  }
  function esc(s) { return String(s == null ? '' : s).replace(/[&<>"]/g, function (c) { return { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[c]; }); }

  function load() {
    var days = dlg.querySelector('[data-days]').value, msg = dlg.querySelector('[data-msg]');
    msg.textContent = 'Reading ' + days + ' business days…';
    fetch('/api/series/' + encodeURIComponent(kind) + '/' + encodeURIComponent(id) + '?days=' + days + '&path=' + encodeURIComponent(current.path))
      .then(function (r) { return r.json().then(function (b) { return { ok: r.ok, body: b }; }); })
      .then(function (res) {
        if (!res.ok) { msg.textContent = (res.body.code || 'Error') + ': ' + res.body.detail; return; }
        var pts = res.body.points || [], numeric = pts.filter(function (p) { return typeof p.value === 'number'; });
        msg.textContent = res.body.dated ? numeric.length + ' of ' + pts.length + ' business days have a value.'
          : 'No source keeps history for this entity: the value is today’s, the same every day.';
        dlg.querySelector('[data-rows]').innerHTML = pts.slice().reverse().map(function (p) {
          var carried = p.dataDate && p.dataDate !== p.date;
          return '<tr><td class="mono">' + esc(p.date) + '</td><td class="num mono">' + esc(p.value == null ? '—' : typeof p.value === 'number' ? p.value.toLocaleString() : p.value)
            + '</td><td class="mono' + (carried ? ' t-warn' : '') + '">' + esc(p.dataDate || '—') + '</td><td class="mono">' + esc(p.source || '—') + '</td></tr>';
        }).join('');
        if (!window.echarts || !numeric.length) { return; }
        var t = tokens(), box = dlg.querySelector('[data-chart-box]');
        chart = chart || window.echarts.init(box, null, { renderer: 'svg' });
        chart.setOption({
          grid: { left: 70, right: 16, top: 16, bottom: 28 }, tooltip: { trigger: 'axis' },
          xAxis: { type: 'category', data: pts.map(function (p) { return p.date; }), axisLabel: { color: t.muted }, axisLine: { lineStyle: { color: t.grid } } },
          yAxis: { type: 'value', scale: true, axisLabel: { color: t.muted }, splitLine: { lineStyle: { color: t.grid } } },
          series: [{ type: 'line', data: pts.map(function (p) { return typeof p.value === 'number' ? p.value : null; }), connectNulls: false,
            symbolSize: 5, lineStyle: { color: t.line, width: 2 }, itemStyle: { color: t.line } }]
        }, true);
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
    dlg.showModal();
    load();
  });
})();
