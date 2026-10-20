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
/* The history page (TRD 1000015 30 <GO>), keyboard first: ↑↓ (j k) move the selected day, ←→ the column, Enter opens the day's view
   as it was, C changes only, D diff, E export, R a new range, 1 3 6 Y T the market ranges, P the next chart field, Alt+S copies a
   link, ? the keys, Esc closes or goes back to today's view. Keys are ignored while typing in a field. The market shape draws its
   chart with ECharts in the theme's own colours (read from the tokens at draw time); the chart's crosshair follows the selected row. */
(function () {
  'use strict';
  var root = document.querySelector('[data-history]');
  if (!root) { return; }
  var today = root.getAttribute('data-today');
  var box = root.querySelector('[data-hx-rows]');
  var rows = function () { return Array.prototype.slice.call(root.querySelectorAll('[data-row]')).filter(function (r) { return r.offsetParent !== null; }); };
  var sel = -1, col = -1, chart = null, series = [], field = 0;

  function selectedRow() { return root.querySelector('[data-row][aria-selected="true"]'); }

  function select(i, focus) {
    var list = rows();
    if (!list.length) { return; }
    i = Math.max(0, Math.min(list.length - 1, i));
    root.querySelectorAll('[data-row][aria-selected="true"]').forEach(function (r) { r.setAttribute('aria-selected', 'false'); r.tabIndex = -1; });
    var r = list[i];
    sel = i;
    r.setAttribute('aria-selected', 'true');
    r.tabIndex = 0;
    if (focus !== false) { r.focus({ preventScroll: true }); }
    r.scrollIntoView({ block: 'nearest' });
    markColumn();
    follow(r.getAttribute('data-date'));
  }

  function markColumn() {
    root.querySelectorAll('.hx-col').forEach(function (c) { c.classList.remove('hx-col'); });
    var r = selectedRow();
    if (!r || col < 0) { return; }
    var cells = r.querySelectorAll('td');
    if (!cells.length) { return; }
    col = Math.min(col, cells.length - 1);
    cells[col].classList.add('hx-col');
    var th = root.querySelectorAll('thead th')[col + 1];
    cells[col].setAttribute('aria-label', (th ? th.textContent.trim() + ': ' : '') + cells[col].textContent.trim()
      + (cells[col].title ? ', ' + cells[col].title : ''));
    cells[col].scrollIntoView({ block: 'nearest', inline: 'nearest' });
  }

  function open() {
    var r = selectedRow();
    if (r && r.getAttribute('data-href')) { window.location.href = r.getAttribute('data-href'); }
  }

  // changes only: the days on which something moved; the header says "7 of 30 days"
  var onlyBtn = root.querySelector('[data-hx-key="c"]');
  function setOnly(on) {
    root.classList.toggle('hx-only', on);
    if (onlyBtn) { onlyBtn.setAttribute('aria-pressed', on ? 'true' : 'false'); }
    var shown = root.querySelector('[data-hx-shown]'), total = root.getAttribute('data-total');
    var changed = root.querySelectorAll('tr[data-row][data-changed]').length;
    if (shown) {
      shown.textContent = on ? changed + ' of ' + total : total;
      root.querySelector('[data-hx-shown-label]').textContent = 'days';
    }
    var url = new URL(window.location.href);
    if (on) { url.searchParams.set('only', '1'); } else { url.searchParams.delete('only'); }
    history.replaceState(null, '', url.toString());
    root.querySelectorAll('[data-hx-export]').forEach(function (a) {
      var u = new URL(a.href, window.location.href);
      if (on) { u.searchParams.set('only', '1'); } else { u.searchParams.delete('only'); }
      a.href = u.pathname + u.search;
    });
    var keep = selectedRow(), at = keep ? rows().indexOf(keep) : -1;   // the selected day stays selected when it is still shown
    select(at < 0 ? 0 : at, false);
  }
  if (onlyBtn) { onlyBtn.addEventListener('click', function () { setOnly(!root.classList.contains('hx-only')); }); }
  if (root.classList.contains('hx-only')) { setOnly(true); }

  // a range typed in place: 30 → days, 3M / YTD → range, FROM a [TO b] → from, to
  var rform = root.querySelector('[data-hx-rform]'), rinput = root.querySelector('[data-hx-rinput]');
  function rangeQuery(text) {
    var t = text.trim().toUpperCase().replace(/\s+/g, ' '), diff = / DIFF$/.test(t) || t === 'DIFF';
    t = t.replace(/ ?DIFF$/, '');
    var m, q = null;
    if (/^[1-9]\d{0,3}$/.test(t)) { q = 'days=' + t; }
    else if ((m = /^([1-9]\d{0,3}) ?([DWMY])$/.exec(t))) { q = 'range=' + m[1] + m[2]; }
    else if (/^(YTD|MTD|QTD)$/.test(t)) { q = 'range=' + t; }
    else if ((m = /^FROM (\d{4}-\d{2}-\d{2})(?: TO (\d{4}-\d{2}-\d{2}))?$/.exec(t))) { q = 'from=' + m[1] + (m[2] ? '&to=' + m[2] : ''); }
    return q === null ? null : q + (diff ? '&diff=1' : '');
  }
  if (rform) {
    rform.addEventListener('submit', function (e) {
      e.preventDefault();
      var q = rangeQuery(rinput.value);
      if (q === null) { rinput.setCustomValidity('Type 30, 3M, YTD or FROM 2026-09-01 TO 2026-09-30'); rinput.reportValidity(); return; }
      window.location.href = window.location.pathname + '?' + q;
    });
    rinput.addEventListener('input', function () { rinput.setCustomValidity(''); });
    rinput.addEventListener('keydown', function (e) { if (e.key === 'Escape') { rinput.blur(); e.stopPropagation(); } });
  }

  // a link to these rows, the end pinned, with "changes only" when it is on. The link is always shown in a field under the toolbar,
  // selected; it is copied with the Clipboard API where the page may use it (https, localhost), else with execCommand('copy') from that
  // field: a console on a plain-http LAN address is not a secure context and has no navigator.clipboard (QA1-07)
  var shareBtn = root.querySelector('[data-hx-key="s"]');
  var linkBox = root.querySelector('[data-hx-linkbox]'), linkText = root.querySelector('[data-hx-link-text]'), linkMsg = root.querySelector('[data-hx-link-msg]');
  function shareUrl() {
    var u = new URL(root.getAttribute('data-share-url'), window.location.href);
    if (root.classList.contains('hx-only')) { u.searchParams.set('only', '1'); } else { u.searchParams.delete('only'); }
    return u.toString();
  }
  function share() {
    var url = shareUrl(), label = root.querySelector('[data-hx-share-label]');
    function said(text, copied) {
      if (linkMsg) { linkMsg.textContent = text; }
      if (label && copied) { label.textContent = 'Copied'; setTimeout(function () { label.textContent = 'Link'; }, 1600); }
    }
    if (linkBox) { linkBox.hidden = false; }
    if (linkText) { linkText.value = url; }
    function byHand() {
      var ok = false;
      if (linkText) {
        linkText.focus();
        linkText.select();
        try { ok = document.execCommand('copy'); } catch (e) { ok = false; }
      }
      said(ok ? 'Copied: the link is on the clipboard.' : 'Select the link and copy it (Ctrl+C).', ok);
    }
    if (window.isSecureContext && navigator.clipboard && navigator.clipboard.writeText) {
      navigator.clipboard.writeText(url).then(function () { said('Copied: the link is on the clipboard.', true); }, byHand);
    } else {
      byHand();
    }
  }
  if (linkText) { linkText.addEventListener('focus', function () { linkText.select(); }); }
  if (shareBtn) { shareBtn.addEventListener('click', share); }

  var help = root.querySelector('[data-hx-help]');
  function showHelp() { if (help && !help.open) { help.showModal(); } }
  if (help) { root.querySelector('[data-hx-help-close]').addEventListener('click', function () { help.close(); }); }
  var helpBtn = root.querySelector('[data-hx-key="?"]');
  if (helpBtn) { helpBtn.addEventListener('click', showHelp); }

  function rangeButton(label) {
    var a = root.querySelector('[data-hx-range-btn="' + label + '"]');
    if (a) { window.location.href = a.href; }
  }

  if (box) {
    box.addEventListener('click', function (e) {
      if (e.target.closest('a')) { return; }
      var r = e.target.closest('[data-row]');
      if (!r) { return; }
      var td = e.target.closest('td');
      if (td) { col = Array.prototype.indexOf.call(r.querySelectorAll('td'), td); }
      select(rows().indexOf(r));
    });
    box.addEventListener('dblclick', function (e) { if (e.target.closest('[data-row]') && !e.target.closest('a')) { open(); } });
  }

  document.addEventListener('keydown', function (e) {
    var tag = (e.target.tagName || '').toLowerCase();
    if (tag === 'input' || tag === 'textarea' || tag === 'select' || e.target.isContentEditable) { return; }
    if (help && help.open) { return; }                 // the dialog closes itself on Esc
    if (e.ctrlKey || e.metaKey) { return; }
    if (e.altKey) { if (e.key === 's' || e.key === 'S') { e.preventDefault(); share(); } return; }
    var page = Math.max(1, Math.floor(((box && box.clientHeight) || 600) / 30) - 1), k = e.key;
    var market = root.getAttribute('data-shape') === 'market';
    var handled = true;
    switch (k) {
      case 'ArrowDown': case 'j': select(sel + 1); break;
      case 'ArrowUp': case 'k': select(sel - 1); break;
      case 'Home': select(0); break;
      case 'End': select(rows().length - 1); break;
      case 'PageDown': select(sel + page); break;
      case 'PageUp': select(sel - page); break;
      case 'ArrowRight': col = col + 1; markColumn(); break;
      case 'ArrowLeft': col = Math.max(-1, col - 1); markColumn(); break;
      case 'Enter': open(); break;
      case 'c': case 'C': if (onlyBtn) { setOnly(!root.classList.contains('hx-only')); } else { handled = false; } break;
      case 'd': case 'D': window.location.href = root.querySelector('[data-hx-key="d"]').href; break;
      case 'e': case 'E': root.querySelector('[data-hx-key="e"]').click(); break;
      case 'r': case 'R': if (rinput) { rinput.focus(); rinput.select(); } break;
      case '?': showHelp(); break;
      case '1': if (market) { rangeButton('1M'); } else { handled = false; } break;
      case '3': if (market) { rangeButton('3M'); } else { handled = false; } break;
      case '6': if (market) { rangeButton('6M'); } else { handled = false; } break;
      case 'y': case 'Y': rangeButton('1Y'); break;
      case 't': case 'T': rangeButton('YTD'); break;
      case 'p': case 'P': if (series.length > 1) { field = (field + 1) % series.length; draw(); } else { handled = false; } break;
      case 'Escape': if (col >= 0) { col = -1; markColumn(); } else if (today) { window.location.href = today; } break;
      default: handled = false;
    }
    if (handled) { e.preventDefault(); }
  });

  // ---- the market shape: the chart --------------------------------------------------------------------------------------------
  var chartEl = root.querySelector('[data-hx-chart]'), seriesEl = chartEl, cross = root.querySelector('[data-hx-cross]');
  function tokens() {
    var s = getComputedStyle(document.documentElement), g = function (n) { return s.getPropertyValue(n).trim(); };
    return { ink: g('--d-ink'), muted: g('--d-muted'), line: g('--d-accent'), link: g('--d-link'), grid: g('--d-border'),
      surface: g('--d-surface'), pos: g('--d-pos'), neg: g('--d-neg'), mono: g('--d-font-mono') };
  }
  function fmt(v, places) { return v == null ? '—' : Number(v).toLocaleString(undefined, { minimumFractionDigits: places, maximumFractionDigits: places }); }
  function draw() {
    if (!chartEl || !window.echarts || !series.length) { return; }
    var t = tokens(), s = series[field], places = s.places == null ? 2 : s.places;
    var dates = s.points.map(function (p) { return p[0]; }), vals = s.points.map(function (p) { return p[1]; });
    var nums = vals.filter(function (v) { return v != null; });
    var up = nums.length > 1 && nums[nums.length - 1] >= nums[0];
    var tone = up ? t.pos : t.neg;
    var reduced = window.matchMedia && window.matchMedia('(prefers-reduced-motion: reduce)').matches;
    var label = root.querySelector('[data-hx-field]');
    if (label) { label.textContent = s.label; }
    chart = chart || window.echarts.init(chartEl, null, { renderer: 'canvas' });
    chart.setOption({
      animation: !reduced,
      textStyle: { fontFamily: t.mono },
      grid: { left: 56, right: 18, top: 14, bottom: 26 },
      tooltip: { trigger: 'axis', backgroundColor: t.surface, borderColor: t.grid, textStyle: { color: t.ink, fontFamily: t.mono, fontSize: 12 },
        axisPointer: { type: 'cross', lineStyle: { color: t.muted, type: 'dashed' }, crossStyle: { color: t.muted },
          label: { backgroundColor: t.grid, color: t.ink, fontFamily: t.mono } },
        formatter: function (ps) { var p = ps[0]; return p ? p.axisValue + '  <b>' + fmt(p.value, places) + '</b>' : ''; } },
      xAxis: { type: 'category', data: dates, boundaryGap: false, axisLabel: { color: t.muted, fontSize: 11, formatter: function (v) { return v.slice(5); } },
        axisLine: { lineStyle: { color: t.grid } }, axisTick: { show: false } },
      yAxis: { type: 'value', scale: true, axisLabel: { color: t.muted, fontSize: 11 }, splitLine: { lineStyle: { color: t.grid, type: 'dashed' } } },
      series: [{ type: 'line', name: s.label, data: vals, showSymbol: false, symbolSize: 7, connectNulls: false, smooth: false,
        lineStyle: { color: tone, width: 2 }, itemStyle: { color: tone },
        areaStyle: { color: new window.echarts.graphic.LinearGradient(0, 0, 0, 1, [{ offset: 0, color: tone }, { offset: 1, color: 'transparent' }]), opacity: 0.18 },
        markLine: { silent: true, symbol: 'none', label: { show: false }, lineStyle: { color: t.muted, type: 'dotted' },
          data: nums.length ? [{ yAxis: nums[0] }] : [] } }]
    }, true);
    chart.off('updateAxisPointer');
    chart.on('updateAxisPointer', function (ev) {
      var i = ev.dataIndex;
      if (i == null && ev.axesInfo && ev.axesInfo.length) { i = ev.axesInfo[0].value; }
      if (i != null && cross && dates[i]) { cross.textContent = '⌖ ' + dates[i] + '  ' + fmt(vals[i], places); }
    });
    var r = selectedRow();
    if (r) { follow(r.getAttribute('data-date')); }
  }
  function follow(date) {
    if (!chart || !series.length) { return; }
    var s = series[field], i = -1;
    for (var k = 0; k < s.points.length; k++) { if (s.points[k][0] === date) { i = k; break; } }
    if (i < 0) { return; }
    chart.dispatchAction({ type: 'showTip', seriesIndex: 0, dataIndex: i });
    if (cross) { cross.textContent = '⌖ ' + date + '  ' + fmt(s.points[i][1], s.places == null ? 2 : s.places); }
  }
  if (seriesEl) {
    try { series = JSON.parse(seriesEl.getAttribute('data-hx-series')) || []; } catch (err) { series = []; }
    draw();
    window.addEventListener('resize', function () { if (chart) { chart.resize(); } });
    // a theme switched while the page is open: redraw in its colours
    new MutationObserver(function () { if (chart) { chart.dispose(); chart = null; draw(); } })
      .observe(document.documentElement, { attributes: true, attributeFilter: ['data-theme'] });
  }

  if (rows().length) { select(0, false); }
  root.setAttribute('data-history-ready', '1');
})();
