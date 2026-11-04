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
/* Did you know: fills the Home tile (templates/_dyk.html) from /api/home/facts once the page has drawn. The facts come first-of-the-day
   first; "Another fact" walks the rest and wraps. No fact today (or no answer): the tile is removed and the headline centres again.
   Everything is set as text (never HTML); **words** in a fact's sentence are drawn emphasised. */
(function () {
  'use strict';
  var tile = document.querySelector('[data-dyk]');
  if (!tile) { return; }
  var home = document.querySelector('[data-lp-home]');
  var $ = function (sel) { return tile.querySelector(sel); };
  var facts = [];
  var at = 0;
  var date = '';
  var SVG = 'http://www.w3.org/2000/svg';

  function el(tag, cls, text) {
    var e = document.createElement(tag);
    if (cls) { e.className = cls; }
    if (text != null) { e.textContent = text; }
    return e;
  }

  function hide() {
    tile.remove();
    if (home) { home.classList.remove('has-dyk'); }
  }

  function sentence(node, text) {
    node.textContent = '';
    String(text).split('**').forEach(function (part, i) {
      if (!part) { return; }
      node.appendChild(i % 2 ? el('span', 'dyk-em', part) : document.createTextNode(part));
    });
  }

  function bars(box, p) {
    var said = [];
    p.bars.forEach(function (b) {
      var row = el('div', 'dyk-bar');
      row.appendChild(el('span', 'dyk-bar-label', b.label));
      var track = el('span', 'dyk-bar-track');
      var fill = el('span', 'dyk-bar-fill tone-' + (b.tone || 'base'));
      fill.style.display = 'block';
      fill.style.width = Math.max(0, Math.min(100, b.width)) + '%';
      track.appendChild(fill);
      row.appendChild(track);
      row.appendChild(el('span', 'dyk-bar-value', b.display));
      box.appendChild(row);
      said.push(b.label + ' ' + b.display);
    });
    return said.join(', ');
  }

  function share(box, p) {
    var whole = Math.max(1, p.ratio);
    var bar = el('div', 'dyk-share');
    var fill = el('div', 'dyk-share-fill' + (p.ratio > 1 ? ' over' : ''));
    fill.style.width = (Math.max(0, p.ratio) / whole * 100) + '%';
    bar.appendChild(fill);
    if (p.ratio > 1) {
      var mark = el('span', 'dyk-share-mark');
      mark.style.left = (100 / whole) + '%';
      mark.title = '100%';
      bar.appendChild(mark);
    }
    box.appendChild(bar);
    var row = el('div', 'dyk-share-row');
    row.appendChild(el('span', '', p.label || ''));
    row.appendChild(el('b', '', p.display));
    box.appendChild(row);
    return (p.label ? p.label + ' ' : '') + p.display;
  }

  function line(box, p) {
    var vals = p.values.map(function (v) { return typeof v === 'number' ? v : null; });
    var nums = vals.filter(function (v) { return v !== null; });
    var lo = Math.min.apply(null, nums.concat([0]));
    var hi = Math.max.apply(null, nums);
    var span = hi - lo || 1;
    var w = 300;
    var h = 72;
    var step = vals.length > 1 ? w / (vals.length - 1) : w;
    var svg = document.createElementNS(SVG, 'svg');
    svg.setAttribute('class', 'dyk-line');
    svg.setAttribute('viewBox', '0 0 ' + w + ' ' + h);
    svg.setAttribute('preserveAspectRatio', 'none');
    svg.setAttribute('aria-hidden', 'true');
    var pts = [];
    vals.forEach(function (v, i) { if (v !== null) { pts.push((i * step).toFixed(1) + ',' + (h - 4 - (v - lo) / span * (h - 8)).toFixed(1)); } });
    var pl = document.createElementNS(SVG, 'polyline');
    pl.setAttribute('points', pts.join(' '));
    svg.appendChild(pl);
    if (typeof p.mark === 'number' && vals[p.mark] !== null && vals[p.mark] !== undefined) {
      var c = document.createElementNS(SVG, 'circle');
      c.setAttribute('cx', (p.mark * step).toFixed(1));
      c.setAttribute('cy', (h - 4 - (vals[p.mark] - lo) / span * (h - 8)).toFixed(1));
      c.setAttribute('r', '4');
      svg.appendChild(c);
    }
    box.appendChild(svg);
    if (p.labels && p.labels.length) {
      var ends = el('div', 'dyk-line-ends');
      ends.appendChild(el('span', '', String(p.labels[0])));
      ends.appendChild(el('span', '', String(p.labels[p.labels.length - 1])));
      box.appendChild(ends);
    }
    return '';
  }

  function picture(p) {
    var box = $('[data-dyk-pic]');
    box.textContent = '';
    if (!p) { box.hidden = true; return; }
    var said = p.type === 'bars' ? bars(box, p) : p.type === 'share' ? share(box, p) : line(box, p);
    if (p.caption) { box.appendChild(el('span', 'dyk-caption', p.caption)); }
    box.setAttribute('aria-label', [p.caption, said].filter(Boolean).join(': '));
    box.hidden = false;
  }

  function show(i) {
    var f = facts[i];
    $('[data-dyk-kicker]').textContent = i === 0 ? 'Fact of the day' : 'Did you know';
    $('[data-dyk-domain]').textContent = f.domain || '';
    $('[data-dyk-date]').textContent = date || '';
    sentence($('[data-dyk-text]'), f.text);
    picture(f.picture);
    var why = $('[data-dyk-why]');
    why.open = false;
    why.hidden = !f.why;
    $('[data-dyk-why-text]').textContent = f.why || '';
    var more = $('[data-dyk-more]');
    more.hidden = !f.knowledgeHref;
    if (f.knowledgeHref) { more.href = f.knowledgeHref; }
    var open = $('[data-dyk-open]');
    open.textContent = 'Open ' + f.command;
    open.href = f.href;
    var what = $('[data-dyk-what]');
    what.hidden = !f.whatHref;
    if (f.whatHref) { what.href = f.whatHref; what.textContent = 'WHAT ' + f.what; }
    $('[data-dyk-next]').hidden = facts.length < 2;
    $('[data-dyk-actions]').hidden = false;
    tile.setAttribute('data-fact', f.id);
    tile.setAttribute('aria-busy', 'false');
  }

  $('[data-dyk-next]').addEventListener('click', function () {
    if (facts.length < 2) { return; }
    at = (at + 1) % facts.length;
    show(at);
  });

  function load() {
    fetch('/api/home/facts', { credentials: 'same-origin', headers: { Accept: 'application/json' } })
      .then(function (r) { return r.ok ? r.json() : { facts: [] }; })
      .then(function (d) {
        facts = (d && d.facts) || [];
        date = (d && d.date) || '';
        if (!facts.length) { hide(); return; }
        show(at = 0);
      })
      .catch(hide);
  }

  if (document.readyState === 'complete') { load(); } else { window.addEventListener('load', load); }
}());
