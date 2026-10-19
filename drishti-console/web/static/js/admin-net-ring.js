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
/* Admin → Net → Members: the hash ring beside the member table. One arc per member, coloured by its state (the theme's ok, warn and bad
   tokens, so it reads in light and dark), sized by the kinds whose data the node holds (the capability map's holders, "holds" in
   /admin/net/api/members), or equal when no node reports any. This node has an outline, the console's server a dashed outer band. Each arc
   is labelled with its node id and packs; hovering shows the kinds it holds. Clicking an arc (or Enter on it) selects that member's row, and
   clicking a row selects its arc. Redrawn on the page's own refresh (admin-net.js, NET.onMembers): a member that joins grows in, one that
   goes fades out. Plain SVG, no chart library. */
(function () {
  'use strict';
  var NET = window.DrishtiNet;
  var root = document.querySelector('[data-net]');
  var fig = root && root.querySelector('[data-ring]');
  if (!NET || !fig) { return; }
  var SVG = 'http://www.w3.org/2000/svg';
  var CX = 220, CY = 150, R = 82, RIN = 54, BAND = 6, GAP = 0.03;
  var arcs = fig.querySelector('[data-ring-arcs]');
  var drawn = null;            // node ids drawn last time (null before the first drawing: nothing animates in on page load)
  var selected = null;
  var reduce = window.matchMedia && window.matchMedia('(prefers-reduced-motion: reduce)').matches;

  function s(tag, attrs, text) {
    var e = document.createElementNS(SVG, tag);
    Object.keys(attrs || {}).forEach(function (k) { e.setAttribute(k, attrs[k]); });
    if (text !== undefined && text !== null) { e.textContent = String(text); }
    return e;
  }
  function pt(rad, a) { return (CX + rad * Math.sin(a)).toFixed(2) + ',' + (CY - rad * Math.cos(a)).toFixed(2); }
  /** A ring segment from angle a0 to a1 (radians, 0 at the top, clockwise) between radii r0 (inner) and r1 (outer). */
  function segment(a0, a1, r0, r1) {
    var large = a1 - a0 > Math.PI ? 1 : 0;
    return 'M' + pt(r1, a0) + ' A' + r1 + ',' + r1 + ' 0 ' + large + ' 1 ' + pt(r1, a1) + ' L' + pt(r0, a1) + ' A' + r0 + ',' + r0 + ' 0 ' + large + ' 0 ' + pt(r0, a0) + ' Z';
  }
  function bare(url) { return String(url || '').replace(/\/+$/, ''); }
  function short(packs) {
    var names = (packs || []).map(function (p) { return p.name; });
    if (!names.length) { return ''; }
    var text = names.length <= 2 ? names.join(', ') : names[0] + ' +' + (names.length - 1);
    return text.length > 24 ? text.slice(0, 23) + '…' : text;
  }

  function select(id, scroll) {
    selected = id;
    Array.prototype.forEach.call(root.querySelectorAll('[data-members] tbody tr[data-member]'), function (tr) {
      var on = tr.getAttribute('data-member') === id;
      tr.classList.toggle('net-row-sel', on);
      if (on) { tr.setAttribute('data-selected', 'true'); } else { tr.removeAttribute('data-selected'); }
      if (on && scroll && tr.scrollIntoView) { tr.scrollIntoView({ block: 'nearest', inline: 'nearest' }); }
    });
    Array.prototype.forEach.call(arcs.querySelectorAll('[data-arc]'), function (g) {
      g.setAttribute('aria-pressed', g.getAttribute('data-arc') === id ? 'true' : 'false');
    });
  }

  function draw(data) {
    var members = (data.members || []).slice().sort(function (a, b) { return a.nodeId < b.nodeId ? -1 : a.nodeId > b.nodeId ? 1 : 0; });
    fig.hidden = !members.length;
    var holds = data.holds || {};
    var weights = members.map(function (m) { return (holds[m.nodeId] || []).length; });
    var weighted = weights.some(function (w) { return w > 0; });
    weights = weights.map(function (w) { return weighted ? Math.max(w, 1) : 1; });
    var total = weights.reduce(function (a, b) { return a + b; }, 0) || 1;
    var active = bare(data.console && data.console.enabled !== false ? data.console.active : '');
    var before = {};
    Array.prototype.forEach.call(arcs.querySelectorAll('[data-arc]'), function (g) { before[g.getAttribute('data-arc')] = g; });
    arcs.textContent = '';
    var gap = members.length > 1 ? GAP : 0, at = 0, now = {};
    members.forEach(function (m, i) {
      var span = 2 * Math.PI * weights[i] / total;
      var a0 = at + gap / 2, a1 = at + span - gap / 2;
      if (members.length === 1) { a0 = 0; a1 = 2 * Math.PI - 0.0001; }
      at += span;
      now[m.nodeId] = true;
      var kinds = holds[m.nodeId] || [];
      var isConsole = !!active && bare(m.url) === active;
      var g = s('g', { 'class': 'net-arc', 'data-arc': m.nodeId, 'data-state': m.state, tabindex: '0', role: 'button',
        'aria-pressed': m.nodeId === selected ? 'true' : 'false',
        'aria-label': m.nodeId + ', ' + m.state + (m.self ? ', this node' : '') + (isConsole ? ', the console\'s server' : '')
          + ', holds ' + kinds.length + ' kind' + (kinds.length === 1 ? '' : 's') + ': select its row' });
      if (m.self) { g.setAttribute('data-self', 'true'); }
      if (isConsole) { g.setAttribute('data-console', 'true'); }
      if (drawn && !drawn[m.nodeId] && !reduce) { g.classList.add('net-arc-in'); }
      g.appendChild(s('title', null, m.nodeId + ' · ' + m.state + (m.self ? ' · this node' : '') + (isConsole ? ' · the console\'s server' : '')
        + '\npacks: ' + ((m.packs || []).map(function (p) { return p.name; }).join(', ') || 'none')
        + '\nholds data of: ' + (kinds.length ? kinds.join(', ') : 'no kind')));
      g.appendChild(s('path', { 'class': 'net-arc-fill', d: segment(a0, a1, RIN, R) }));
      if (isConsole) { g.appendChild(s('path', { 'class': 'net-arc-console', d: segment(a0, a1, R + 3, R + 3 + BAND) })); }
      var mid = (a0 + a1) / 2;
      g.setAttribute('data-at', pt((R + RIN) / 2, mid));        // the middle of the arc, in the ring's own units (where a click lands)
      var lx = CX + (R + BAND + 16) * Math.sin(mid), ly = CY - (R + BAND + 16) * Math.cos(mid);
      var anchor = Math.sin(mid) > 0.15 ? 'start' : Math.sin(mid) < -0.15 ? 'end' : 'middle';
      var dy = Math.cos(mid) > 0.6 ? -16 : Math.cos(mid) < -0.6 ? 10 : -4;
      var label = s('text', { 'class': 'net-arc-label', x: lx.toFixed(1), y: (ly + dy).toFixed(1), 'text-anchor': anchor });
      label.appendChild(s('tspan', { 'class': 'net-arc-id' }, m.nodeId + (m.self ? ' ●' : '')));
      var sum = short(m.packs) || (kinds.length ? kinds.length + (kinds.length === 1 ? ' kind' : ' kinds') : 'no data');
      if (sum && members.length <= 10) { label.appendChild(s('tspan', { x: lx.toFixed(1), dy: '17', 'class': 'net-arc-sum' }, sum)); }
      g.appendChild(label);
      arcs.appendChild(g);
    });
    // a member gone from the table fades out where it was
    Object.keys(before).forEach(function (id) {
      if (now[id] || reduce) { return; }
      var old = before[id];
      old.classList.remove('net-arc-in');
      old.classList.add('net-arc-out');
      old.removeAttribute('tabindex');
      old.setAttribute('aria-hidden', 'true');
      arcs.appendChild(old);
      setTimeout(function () { if (old.parentNode) { old.parentNode.removeChild(old); } }, 700);
    });
    drawn = now;
    var alive = members.filter(function (m) { return m.state === 'alive'; }).length;
    fig.querySelector('[data-ring-count]').textContent = alive + ' / ' + members.length;
    fig.querySelector('[data-ring-caption]').textContent = weighted
      ? 'Arcs sized by the kinds each node holds data for (the capability digest); a node holding none still shows. Click an arc to select its row.'
      : 'Equal arcs: no node reports data it holds yet. Click an arc to select its row.';
    if (selected) { select(now[selected] ? selected : null); }
  }

  arcs.addEventListener('click', function (e) {
    var g = e.target.closest('[data-arc]');
    if (g && !g.classList.contains('net-arc-out')) { select(g.getAttribute('data-arc'), true); }
  });
  arcs.addEventListener('keydown', function (e) {
    var g = e.target.closest('[data-arc]');
    if (g && (e.key === 'Enter' || e.key === ' ')) { e.preventDefault(); select(g.getAttribute('data-arc'), true); }
  });
  var table = root.querySelector('[data-members]');
  if (table) {
    table.addEventListener('click', function (e) {
      var tr = e.target.closest('tr[data-member]');
      if (tr && !e.target.closest('button, a, input')) { select(tr.getAttribute('data-member')); }
    });
  }
  NET.onMembers = NET.onMembers || [];
  NET.onMembers.push(draw);
  NET.ring = { draw: draw, select: select };
  if (NET.refresh) { NET.refresh(); }           // draw at once, then with every refresh of the table
})();
