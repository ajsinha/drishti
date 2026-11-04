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
/* The data map, DATAMAP (docs/guides/USER_GUIDE.md "The data map"; routes/datamap_routes.py). One page, two views sharing one state:
   the view (Map or Cards), the selected kind or domain, the domain chip and Find, all kept in the address (view, kind, domain, chip,
   find) so a link or a reload opens the same picture. A click on a node selects its kind (its joins lit, the others dimmed) and fills the
   side panel: its join fields with how well the data honours them, the kinds that join to it, the commands that open it and its contract,
   guide and WHAT links; a click on a card opens its domain's table. Ctrl/Cmd-click a node to open its kind instead. While the server is
   still counting and checking, /datamap/data is asked again and the counts, cards, panel and findings follow. */
(function () {
  'use strict';
  var root = document.querySelector('[data-datamap]');
  if (!root) { return; }
  var model;
  try { model = JSON.parse(root.getAttribute('data-model') || '{}'); } catch (e) { return; }
  var st = { view: root.getAttribute('data-view') || 'map', kind: root.getAttribute('data-kind') || '', domain: root.getAttribute('data-domain') || '',
             chip: root.getAttribute('data-chip') || '', find: '' };
  var findInput = root.querySelector('[data-find]');
  st.find = findInput ? findInput.value.trim() : '';
  var side = root.querySelector('[data-side-body]');
  var index = {}, domains = {};

  function esc(s) { return String(s == null ? '' : s).replace(/[&<>"']/g, function (c) { return { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]; }); }
  function reindex() {
    index = {}; domains = {};
    (model.kinds || []).forEach(function (k) { index[k.kind] = k; });
    (model.domains || []).forEach(function (d) { domains[d.id] = d; });
  }
  reindex();

  function address() {
    var p = new URLSearchParams();
    if (st.view !== 'map') { p.set('view', st.view); }
    if (st.kind) { p.set('kind', st.kind); } else if (st.domain) { p.set('domain', st.domain); }
    if (st.chip) { p.set('chip', st.chip); }
    if (st.find) { p.set('find', st.find); }
    var q = p.toString();
    try { history.replaceState(null, '', '/datamap' + (q ? '?' + q : '')); } catch (e) { /* a sandboxed frame */ }
    var cmd = document.querySelector('[data-command] .cmd-input');
    if (cmd && document.activeElement !== cmd) { cmd.value = ('DATAMAP ' + (st.kind || st.domain || '')).trim(); }
  }

  function words(k) { return (k.kind + ' ' + k.label + ' ' + (k.code || '')).toLowerCase(); }

  function matches(k) {
    if (!st.find) { return true; }
    var f = st.find.toLowerCase();
    if (words(k).indexOf(f) >= 0) { return true; }
    return (model.joins || []).some(function (j) { return (j.from === k.kind || j.to === k.kind) && j.field.toLowerCase().indexOf(f) >= 0; });
  }

  function paint() {
    root.querySelectorAll('[data-set-view]').forEach(function (a) {
      var on = a.getAttribute('data-set-view') === st.view;
      a.classList.toggle('on', on);
      if (on) { a.setAttribute('aria-current', 'page'); } else { a.removeAttribute('aria-current'); }
    });
    root.querySelectorAll('[data-pane]').forEach(function (p) { p.hidden = p.getAttribute('data-pane') !== st.view; });
    root.querySelectorAll('[data-chip-set]').forEach(function (c) { c.classList.toggle('on', c.getAttribute('data-chip-set') === st.chip); });
    var near = {};
    (model.joins || []).forEach(function (j) {
      if (j.from === st.kind) { near[j.to] = true; }
      if (j.to === st.kind) { near[j.from] = true; }
    });
    root.querySelectorAll('.dm-n').forEach(function (n) {
      var k = index[n.getAttribute('data-kind')];
      var hidden = !!st.chip && n.getAttribute('data-domain') !== st.chip;
      n.classList.toggle('dm-hidden', hidden);
      n.classList.toggle('dm-sel', !!k && k.kind === st.kind);
      n.classList.toggle('dm-near', !!k && !!near[k.kind]);
      n.classList.toggle('dm-dim', !!k && (!matches(k) || (!!st.kind && k.kind !== st.kind && !near[k.kind])));
    });
    root.querySelectorAll('.dm-region').forEach(function (r) {
      r.classList.toggle('dm-hidden', !!st.chip && r.getAttribute('data-domain') !== st.chip);
    });
    root.querySelectorAll('.dm-j').forEach(function (l) {
      var a = l.getAttribute('data-from'), b = l.getAttribute('data-to');
      var on = !!st.kind && (a === st.kind || b === st.kind);
      var hidden = !!st.chip && (!index[a] || index[a].domain !== st.chip) && (!index[b] || index[b].domain !== st.chip);
      l.classList.toggle('dm-on', on);
      l.classList.toggle('dm-dim', !!st.kind && !on);
      l.classList.toggle('dm-hidden', hidden);
    });
    var open = st.domain || (index[st.kind] ? index[st.kind].domain : '');
    root.querySelectorAll('[data-card]').forEach(function (c) {
      var d = domains[c.getAttribute('data-card')];
      c.classList.toggle('on', !!d && d.id === open);
      var shown = (!st.chip || st.chip === d.id) && (!st.find || d.title.toLowerCase().indexOf(st.find.toLowerCase()) >= 0 ||
        d.kinds.some(function (k) { return index[k] && matches(index[k]); }));
      c.hidden = !shown;
    });
    renderSide();
    address();
  }

  function cmdLinks(k) {
    if (!k.commands || !k.commands.length) { return '<p class="text-muted-d">No command opens it for you.</p>'; }
    return '<div class="dm-cmds">' + k.commands.map(function (c) {
      return '<a class="dm-cmd" href="' + esc(c.href) + '" title="Open ' + esc(c.text) + '">' + esc(c.text) + '</a>';
    }).join('') + '</div>';
  }
  function links(list) {
    if (!list || !list.length) { return ''; }
    return '<div class="dm-links">' + list.map(function (l) { return '<a href="' + esc(l.href) + '">' + esc(l.label) + '</a>'; }).join('') + '</div>';
  }
  function kindName(kind) {
    var k = index[kind];
    if (!k) { return esc(kind); }
    return '<a class="dm-pick" href="/datamap?kind=' + encodeURIComponent(kind) + '" data-select="' + esc(kind) + '">' + esc(k.label) + '</a>' +
      (k.computed ? ' <span class="text-muted-d">(computed)</span>' : ' <span class="text-muted-d">· ' + esc(k.countText) + '</span>');
  }
  function cov(j) { return '<span class="dm-cov' + (j.low ? ' dm-low' : '') + '">' + (j.low ? '<span class="st st-warn">finding</span> ' : '') + esc(j.text) + '</span>'; }

  function kindPanel(k) {
    var d = domains[k.domain] || { title: k.domain };
    var out = (model.joins || []).filter(function (j) { return j.from === k.kind; });
    var inn = (model.joins || []).filter(function (j) { return j.to === k.kind; });
    var h = '<span class="dm-eyebrow">Selected · ' + esc(k.pack) + ' pack · ' + esc(d.title) + '</span>' +
      '<h2>' + esc(k.label) + (k.code ? ' <span class="func-word mono">' + esc(k.code) + '</span>' : '') + '</h2>' +
      '<p class="dm-desc">' + (k.computed ? 'Computed on demand, never stored. ' : esc(k.countText) + ' on the business date. ') +
      esc(k.description && k.description !== k.label ? k.description : '') + (k.grain ? ' ' + esc(k.grain) : '') + '</p>';
    h += '<div><h3>Joins to <span class="text-muted-d">(' + out.length + ')</span></h3>' + (out.length ? '<ul class="dm-joins">' + out.map(function (j) {
      return '<li><span class="dm-field">' + esc(j.field) + '</span> → ' + kindName(j.to) + '<br>' + cov(j) + '</li>'; }).join('') + '</ul>'
      : '<p class="text-muted-d">Its contract declares no join.</p>') + '</div>';
    var from = {}, order = [];
    inn.forEach(function (j) {
      if (!from[j.from]) { from[j.from] = []; order.push(j.from); }
      from[j.from].push(j);
    });
    h += '<div><h3>Joined from <span class="text-muted-d">(' + order.length + ' kinds)</span></h3>' + (order.length ? '<ul class="dm-joins">' + order.map(function (kind) {
      var js = from[kind];
      var low = js.filter(function (j) { return j.low; })[0];
      return '<li>' + kindName(kind) + ' <span class="text-muted-d">by</span> ' + js.map(function (j) { return '<span class="dm-field">' + esc(j.field) + '</span>'; }).join(', ') +
        '<br>' + cov(low || js[0]) + '</li>'; }).join('') + '</ul>' : '<p class="text-muted-d">No other kind joins to it.</p>') + '</div>';
    h += '<div><h3>Open it</h3>' + cmdLinks(k) + '</div>' + links(k.links);
    return h;
  }

  function domainPanel(d) {
    var mine = (model.kinds || []).filter(function (k) { return k.domain === d.id; });
    var h = '<span class="dm-eyebrow">' + esc(d.packTitles.join(', ')) + ' · ' + esc(d.packs.join(', ')) + (d.packs.length > 1 ? ' packs' : ' pack') + '</span>' +
      '<h2>' + esc(d.title) + '</h2>';
    h += '<div class="dm-tbl-wrap"><table class="dm-tbl"><thead><tr><th scope="col">Kind</th><th scope="col">Count</th><th scope="col">Joins</th><th scope="col">Open</th></tr></thead><tbody>' +
      mine.map(function (k) {
        var out = (model.joins || []).filter(function (j) { return j.from === k.kind; }).map(function (j) {
          return '→ ' + esc((index[j.to] || {}).label || j.to) + ' <code class="dm-field">' + esc(j.field) + '</code>' + (j.low ? ' <span class="st st-warn">finding</span>' : ''); });
        var inn = (model.joins || []).filter(function (j) { return j.to === k.kind; }).map(function (j) {
          return '← ' + esc((index[j.from] || {}).label || j.from) + ' <code class="dm-field">' + esc(j.field) + '</code>'; });
        var all = out.concat(inn);
        var cmd = k.commands && k.commands[0];
        return '<tr data-row="' + esc(k.kind) + '"><td><a class="dm-pick" href="/datamap?kind=' + encodeURIComponent(k.kind) + '" data-select="' + esc(k.kind) + '">' + esc(k.label) + '</a></td>' +
          '<td>' + esc(k.countText) + '</td><td class="dm-cov">' + (all.length ? all.slice(0, 6).join(' · ') + (all.length > 6 ? ' · ' + (all.length - 6) + ' more' : '') : '—') + '</td>' +
          '<td>' + (cmd ? '<a class="mono" href="' + esc(cmd.href) + '">' + esc(cmd.text) + '</a>' : (k.open ? '<a href="' + esc(k.open) + '">search form</a>' : '—')) + '</td></tr>';
      }).join('') + '</tbody></table></div>';
    h += '<div class="dm-pair"><div class="dm-box"><span class="dm-eyebrow">Comes from</span><p>' +
      (d.sources && d.sources.length ? d.sources.map(esc).join('; ') : 'Its pack names no source in its README.') + '</p></div>' +
      '<div class="dm-box"><span class="dm-eyebrow">Crosses into</span><p>' + (d.crosses && d.crosses.length ? d.crosses.map(function (c) {
        return '<a href="/datamap?domain=' + encodeURIComponent(c.id) + '" data-select-domain="' + esc(c.id) + '">' + esc(c.title) + '</a> through ' +
          c.fields.map(function (f) { return '<code class="dm-field">' + esc(f) + '</code>'; }).join(', '); }).join(' · ') : 'No other domain.') + '</p></div></div>';
    return h + links(d.links);
  }

  function emptyPanel() {
    var f = model.findings || [];
    return '<span class="dm-eyebrow">Nothing selected</span><h2>' + (model.kinds || []).length + ' kinds in ' + (model.domains || []).length + ' domains</h2>' +
      '<p class="dm-desc">' + (model.joins || []).length + ' joins declared by the data contracts' +
      (model.state === 'ready' ? ', each checked against the data' + (f.length ? '; ' + f.length + ' hold ids that mostly name nothing.' : '; every one holds ids that exist.') : '; checking them against the data…') +
      '</p><p class="text-muted-d">Click a kind on the map, or a domain card, to see its joins and the commands that open it.</p>';
  }

  function renderSide() {
    if (!side) { return; }
    var k = index[st.kind];
    var d = domains[st.domain];
    if (st.view === 'cards' && !d && k) { d = domains[k.domain]; }
    side.innerHTML = st.view === 'cards' && d ? domainPanel(d) : k ? kindPanel(k) : d ? domainPanel(d) : emptyPanel();
  }

  function select(kind, domain) { st.kind = kind || ''; st.domain = kind ? '' : (domain || ''); paint(); }

  root.addEventListener('click', function (e) {
    if (e.defaultPrevented || e.button !== 0 || e.ctrlKey || e.metaKey || e.shiftKey || e.altKey) { return; }
    var t = e.target.closest('[data-set-view],[data-chip-set],.dm-n,[data-card],[data-select],[data-select-domain]');
    if (!t || !root.contains(t)) { return; }
    e.preventDefault();
    if (t.hasAttribute('data-set-view')) { st.view = t.getAttribute('data-set-view'); paint(); }
    else if (t.hasAttribute('data-chip-set')) { st.chip = t.getAttribute('data-chip-set'); paint(); }
    else if (t.classList.contains('dm-n')) { var kk = t.getAttribute('data-kind'); select(st.kind === kk ? '' : kk); }
    else if (t.hasAttribute('data-card')) { select('', t.getAttribute('data-card')); }
    else if (t.hasAttribute('data-select')) { select(t.getAttribute('data-select')); }
    else { select('', t.getAttribute('data-select-domain')); }
  });
  root.addEventListener('keydown', function (e) {
    if (e.key === 'Escape' && (st.kind || st.domain)) { select('', ''); }
  });
  if (findInput) {
    findInput.addEventListener('input', function () { st.find = findInput.value.trim(); paint(); });
  }

  function refresh() {
    root.querySelectorAll('.dm-n').forEach(function (n) {
      var k = index[n.getAttribute('data-kind')];
      var c = n.querySelector('[data-count]');
      if (k && c) { c.textContent = k.countText; }
    });
    root.querySelectorAll('[data-card]').forEach(function (c) {
      var d = domains[c.getAttribute('data-card')];
      var s = c.querySelector('[data-summary]');
      if (d && s) { s.textContent = d.summary; }
    });
    var low = {};
    (model.lines || []).forEach(function (l) { low[l.from + '|' + l.to] = l.low; });
    root.querySelectorAll('.dm-j').forEach(function (l) { l.classList.toggle('dm-j-low', !!low[l.getAttribute('data-from') + '|' + l.getAttribute('data-to')]); });
    var state = root.querySelector('[data-state]');
    if (state) {
      state.textContent = model.state === 'ready' ? '' : 'Counting and checking the data: ' + model.counted + ' of ' + (model.kinds || []).length +
        ' kinds counted so far; the map fills in as it goes.';
    }
    var box = root.querySelector('[data-findings-box]');
    var f = model.findings || [];
    if (box) {
      box.innerHTML = f.length ? '<details class="dmap-findings" data-findings><summary><span class="st st-warn">' + f.length + ' finding' + (f.length === 1 ? '' : 's') +
        '</span> joins whose ids mostly name nothing (under ' + Math.round((model.low || 0) * 100) + '% match)</summary><ul>' + f.map(function (x) {
          return '<li><a href="/datamap?kind=' + encodeURIComponent(x.from) + '" data-select="' + esc(x.from) + '">' + esc(x.text) + '</a></li>'; }).join('') + '</ul></details>' : '';
    }
  }

  var tries = 0;
  function follow() {
    if (model.state === 'ready' || tries++ > 200) { return; }
    setTimeout(function () {
      fetch('/datamap/data', { headers: { Accept: 'application/json' }, credentials: 'same-origin' }).then(function (r) {
        return r.ok ? r.json() : null;
      }).then(function (m) {
        if (m && m.kinds) { model = m; reindex(); refresh(); renderSide(); }
        follow();
      }).catch(function () { follow(); });
    }, 3000);
  }

  paint();
  follow();
})();
