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
/* Entity views: function keys, tabs, bars, charts (ECharts, colours from tokens), the F9 raw drawer,
   breadcrumbs (per browser tab) and the market clock. */
(function () {
  'use strict';
  var view = document.querySelector('[data-view]');

  // ---- clock ----------------------------------------------------------------------------------
  var clock = document.querySelector('[data-clock]');
  if (clock) {
    var tz = clock.getAttribute('data-tz'), label = clock.getAttribute('data-tz-label');
    var tick = function () {
      try {
        clock.textContent = new Date().toLocaleTimeString('en-GB', { timeZone: tz, hour12: false }) + ' ' + label;
      } catch (e) { clock.textContent = new Date().toLocaleTimeString(); }
    };
    tick(); setInterval(tick, 1000);
  }

  // ---- bars and gauges: widths from data-w (the CSP forbids inline styles) ---------------------
  function widths(root) {
    root.querySelectorAll('[data-w]').forEach(function (el) {
      var w = parseFloat(el.getAttribute('data-w')) || 0;
      requestAnimationFrame(function () { el.style.width = Math.max(0, Math.min(100, w)) + '%'; });
    });
  }
  widths(document);

  // ---- tabs -----------------------------------------------------------------------------------
  function tabsIn(root) { root.querySelectorAll('[data-tabs]').forEach(function (box) {
    var heads = box.querySelectorAll('[role="tab"]');
    heads.forEach(function (h, i) {
      h.addEventListener('click', function () {
        heads.forEach(function (x, k) {
          x.classList.toggle('on', k === i); x.setAttribute('aria-selected', k === i ? 'true' : 'false');
          document.getElementById(x.getAttribute('aria-controls')).hidden = k !== i;
        });
      });
    });
  }); }
  tabsIn(document);

  // ---- charts ---------------------------------------------------------------------------------
  var charts = [];
  function tokens() {
    var s = getComputedStyle(document.documentElement), t = {};
    ['ink', 'muted', 'faint', 'border', 'link', 'accent', 'neg', 'pos', 'surface'].forEach(function (k) { t[k] = s.getPropertyValue('--d-' + k).trim(); });
    t.mono = s.getPropertyValue('--d-font-mono').trim();
    return t;
  }
  function option(el, t) {
    var d = JSON.parse(el.getAttribute('data-chart')), area = el.getAttribute('data-kind') === 'area';
    var tone = function (n) { return t[n] || t.link; };
    var series = d.series.map(function (s) {
      var o = { type: 'line', name: s.label, data: s.values, smooth: false, symbol: 'circle', symbolSize: 5,
        lineStyle: { width: 1.6, color: tone(s.tone) }, itemStyle: { color: tone(s.tone) } };
      if (area) { o.areaStyle = { color: tone(s.tone), opacity: .12 }; o.symbol = 'none'; }
      if (d.mark) {
        var i = d.x.indexOf(d.mark);
        if (i >= 0) { o.markPoint = { symbol: 'circle', symbolSize: 10, itemStyle: { color: t.accent }, label: { show: false }, data: [{ coord: [i, s.values[i]] }] }; }
      }
      return o;
    });
    if (d.limit != null && series.length) {
      series[0].markLine = { symbol: 'none', silent: true, lineStyle: { type: 'dashed', color: t.neg },
        label: { formatter: (d.limitLabel || 'limit') + ' ' + compact(d.limit), color: t.neg, fontSize: 10 }, data: [{ yAxis: d.limit }] };
    }
    var axis = { axisLine: { lineStyle: { color: t.border } }, axisLabel: { color: t.muted, fontFamily: t.mono, fontSize: 10 }, splitLine: { lineStyle: { color: t.border, opacity: .5 } } };
    return {
      animationDuration: 500, grid: { left: 44, right: 12, top: 12, bottom: 22 },
      tooltip: { trigger: 'axis', backgroundColor: t.surface, borderColor: t.border, textStyle: { color: t.ink, fontFamily: t.mono, fontSize: 11 } },
      xAxis: Object.assign({ type: 'category', data: d.x, boundaryGap: false }, axis),
      yAxis: Object.assign({ type: 'value', scale: !area, axisLabel: { color: t.muted, fontFamily: t.mono, fontSize: 10, formatter: compact } }, { axisLine: axis.axisLine, splitLine: axis.splitLine }),
      series: series
    };
  }
  function compact(v) {
    var a = Math.abs(v);
    if (a >= 1e9) { return (v / 1e9).toFixed(1) + 'bn'; }
    if (a >= 1e6) { return (v / 1e6).toFixed(a >= 1e7 ? 0 : 1) + 'm'; }
    if (a >= 1e4) { return (v / 1e3).toFixed(0) + 'k'; }
    return Math.round(v * 100) / 100;
  }
  function drawCharts() {
    if (!window.echarts) { return; }
    var t = tokens();
    document.querySelectorAll('.chart[data-chart]').forEach(function (el) {
      var c = window.echarts.getInstanceByDom(el) || window.echarts.init(el, null, { renderer: 'svg' });
      c.setOption(option(el, t), true);
      if (charts.indexOf(c) < 0) { charts.push(c); }
    });
  }
  drawCharts();
  window.addEventListener('resize', function () { charts.forEach(function (c) { c.resize(); }); });
  document.addEventListener('drishti:theme', drawCharts);

  /** Hooks for live.js: re-enhance a replaced panel, and move a chart to new data without re-creating it. */
  window.drishti = {
    enhance: function (root) { widths(root); tabsIn(root); },
    redraw: drawCharts,
    updateChart: function (id, data) {
      var el = document.querySelector('#p-' + CSS.escape(id) + ' .chart[data-chart]');
      if (!el || !window.echarts) { return; }
      el.setAttribute('data-chart', JSON.stringify(data));
      var c = window.echarts.getInstanceByDom(el);
      if (c) { c.setOption(option(el, tokens()), false); }
      var foot = el.parentNode.querySelector('.chart-foot .mono');
      if (foot && data.markText) { foot.textContent = data.markText; }
    }
  };

  // ---- raw JSON drawer (F9) -------------------------------------------------------------------
  var raw = document.getElementById('rawDrawer');
  function toggleRaw() {
    if (!raw || !view) { return; }
    if (!raw.hidden) { raw.hidden = true; return; }
    raw.hidden = false;
    var body = raw.querySelector('[data-raw-body]'), meta = raw.querySelector('[data-raw-meta]');
    body.textContent = 'Loading…';
    fetch('/api/raw/' + encodeURIComponent(view.dataset.kind) + '/' + encodeURIComponent(view.dataset.id))
      .then(function (r) { return r.json(); })
      .then(function (d) {
        if (d.provenance) { meta.textContent = d.provenance.source + ' · gen ' + d.provenance.generation; }
        body.textContent = JSON.stringify(d.data !== undefined ? d.data : d, null, 2);
      })
      .catch(function (e) { body.textContent = 'Could not load: ' + e; });
  }
  if (raw) { raw.querySelector('[data-raw-close]').addEventListener('click', toggleRaw); }

  // ---- embedded in a workspace pane: entity links become selections the workspace routes ------------
  if (view && view.hasAttribute('data-embed') && window.parent !== window) {
    document.addEventListener('click', function (e) {
      var a = e.target.closest('a[href^="/v/"]');
      if (!a) { return; }
      var parts = a.getAttribute('href').split('?')[0].split('/');
      e.preventDefault();
      window.parent.postMessage({ type: 'drishti:select', kind: decodeURIComponent(parts[2]), id: decodeURIComponent(parts[3]) }, location.origin);
    });
  }

  // ---- function keys --------------------------------------------------------------------------
  function run(btn) {
    var action = btn.getAttribute('data-action');
    if (action === 'panel') {
      var p = document.getElementById('p-' + btn.getAttribute('data-target'));
      if (p) { p.scrollIntoView({ behavior: 'smooth', block: 'start' }); p.focus({ preventScroll: true }); p.classList.remove('flash'); void p.offsetWidth; p.classList.add('flash'); }
    } else if (action === 'link') { window.location.href = btn.getAttribute('data-href'); }
    else if (action === 'raw') { toggleRaw(); }
    else if (action === 'back') { history.back(); }
    else if (action === 'impact' && view) {
      window.location.href = '/impact/' + encodeURIComponent(view.dataset.kind) + '/' + encodeURIComponent(view.dataset.id);
    }
  }
  document.querySelectorAll('[data-fkey]').forEach(function (b) { b.addEventListener('click', function () { run(b); }); });
  document.addEventListener('keydown', function (e) {
    if (e.altKey && e.key === 'ArrowLeft') { e.preventDefault(); history.back(); return; }
    if (!/^F([1-9]|1[0-2])$/.test(e.key)) { return; }
    if (e.key === 'Escape' && raw && !raw.hidden) { toggleRaw(); return; }
    var b = document.querySelector('[data-fkey="' + e.key + '"]');
    if (b) { e.preventDefault(); run(b); }
  });
  document.addEventListener('keydown', function (e) { if (e.key === 'Escape' && raw && !raw.hidden) { raw.hidden = true; } });

  // ---- breadcrumbs: the path of views followed in this browser tab (not inside workspace panes) ---
  if (view && !view.hasAttribute('data-embed')) {
    var here = { label: view.dataset.label, url: location.pathname };
    var trail = [];
    try { trail = JSON.parse(sessionStorage.getItem('drishti.trail') || '[]'); } catch (e) { trail = []; }
    var at = trail.findIndex(function (c) { return c.url === here.url; });
    trail = at >= 0 ? trail.slice(0, at + 1) : trail.concat([here]).slice(-6);
    try { sessionStorage.setItem('drishti.trail', JSON.stringify(trail)); } catch (e) { /* storage blocked */ }
    var nav = document.querySelector('[data-crumbs]');
    if (nav && trail.length > 1) {
      nav.innerHTML = '← ' + trail.map(function (c, i) {
        var t = String(c.label).replace(/[&<>"]/g, '');
        return i === trail.length - 1 ? '<span>' + t + '</span>' : '<a href="' + encodeURI(c.url) + '">' + t + '</a>';
      }).join('<span class="sep">/</span>');
      nav.hidden = false;
    }
  }
})();
