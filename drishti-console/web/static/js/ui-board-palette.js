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
/* The Chip Store palette of the designer (Board and Chip modes; UI_BOARD.md 10, 12.6): finding a chip while designing.
   - Search over the id, title, purpose, category and tags (from the manual) and the pin types ("date", "measure", "rowset of trade",
     "id of book"); every word must match somewhere; title and id matches rank first, then category and tags, purpose, pins.
   - Category chips as quick filters (header, table, chart, ladder, map, kv, timeline, feed, text, control, layout), matched on the chip's
     category and tags, else guessed from its name, so seed chips without them still sort.
   - "Fits this data": with a board instance that has a supply selected, only the chips whose required data pins can be fed by that supplier's
     attributes (each required column by an attribute of a type it accepts).
   - Favourites (the star) and recent chips first, per person, in this browser (localStorage, never required).
   - Each result: title, one line of purpose, the pin summary, the certified badge, a small preview icon. Keys: "/" focuses the search, the
     arrows move through the results, Enter adds (Board mode) or opens a copy (Chip mode). */
(function () {
  'use strict';
  const U = window.drishtiUiBoard, root = document.querySelector('[data-ub-design]');
  if (!U || !U.designer || !root) { return; }
  const D = U.designer, h = U.h, clear = U.clear, $ = (s) => root.querySelector(s);
  const list = $('[data-ub-palette]'), q = $('[data-ub-palette-q]'), cert = $('[data-ub-certified]'), cats = $('[data-ub-cats]'), fitsBox = $('[data-ub-fits]'), fitsNote = $('[data-ub-fits-note]');
  const CATS = [['header', 'layout-text-window'], ['table', 'table'], ['chart', 'graph-up'], ['ladder', 'distribute-vertical'], ['map', 'globe2'], ['kv', 'list-ul'],
    ['timeline', 'calendar3-range'], ['feed', 'broadcast'], ['text', 'markdown'], ['control', 'sliders'], ['layout', 'columns-gap']];
  const GUESS = { header: /header|ticket|strip|title/, table: /table|grid|matrix|legs|payments|flow-schedule|pivot|tabs|links|status|provenance/, chart: /chart|line|area|bar|column|pie|donut|scatter|bubble|histogram|waterfall|gauge|kpi|card|funnel|treemap|heatmap|candlestick|surface|combo|box|vega|graph/,
    ladder: /ladder/, map: /map/, kv: /kv|entity|card|footer/, timeline: /timeline|events/, feed: /feed|live|news/, text: /markdown|text|note/, control: /search|date-range|slicer|button|filter/, layout: /tabs|layout|tile|image/ };
  const STORE = 'drishti.ub.palette';
  let all = null, cat = '', fitsAttrs = null;

  // ---- favourites and recent, per person, in this browser ----------------------------------------------------------------------------------
  function saved() { try { const v = JSON.parse(window.localStorage.getItem(STORE + '.' + (root.getAttribute('data-user') || 'me')) || '{}'); return { fav: v.fav || [], recent: v.recent || [] }; } catch (e) { return { fav: [], recent: [] }; } }
  function save(v) { try { window.localStorage.setItem(STORE + '.' + (root.getAttribute('data-user') || 'me'), JSON.stringify(v)); } catch (e) { /* storage blocked: the palette works without it */ } }
  function used(chip) { const v = saved(); v.recent = [chip].concat(v.recent.filter((x) => x !== chip)).slice(0, 6); save(v); }
  function toggleFav(chip) { const v = saved(); v.fav = v.fav.indexOf(chip) >= 0 ? v.fav.filter((x) => x !== chip) : v.fav.concat([chip]); save(v); paint(); }

  // ---- what a chip is, as text ---------------------------------------------------------------------------------------------------------------
  const pinsOf = (c) => (c.pins && typeof c.pins === 'object') ? c.pins : { in: [], out: [] };
  function pinText(c) {
    const out = [];
    ['in', 'out'].forEach((dir) => (pinsOf(c)[dir] || []).forEach((p) => {
      const of = p.of ? String(p.of) : '', kind = of.indexOf('/') >= 0 ? of.split('/')[1] : of;
      out.push(p.name, p.type, p.type + (kind ? ' of ' + kind : ''), of ? p.type + ' of ' + of : '');
      Object.keys(p.columns || {}).forEach((col) => out.push(col, (p.columns[col] || {}).type));
    }));
    return out.filter(Boolean).join(' ').toLowerCase();
  }
  function summary(c) {
    const s = (dir) => (pinsOf(c)[dir] || []).map((p) => p.name + ' ' + p.type + (p.of && String(p.of).indexOf('/') >= 0 ? ' of ' + String(p.of).split('/')[1] : '') + (p.required ? '*' : '')).join(', ');
    const i = s('in'), o = s('out');
    return (i ? 'in: ' + i : '') + (o ? (i ? ' → ' : '') + 'out: ' + o : '');
  }
  function catsOf(c) {
    const words = [String(c.category || '').toLowerCase().replace(/s$/, '')].concat((c.tags || []).map((t) => String(t).toLowerCase().replace(/s$/, '')));
    const own = CATS.map((x) => x[0]).filter((k) => words.indexOf(k) >= 0 || (k === 'control' && words.indexOf('control') >= 0));
    if (own.length) { return own; }
    return CATS.map((x) => x[0]).filter((k) => GUESS[k].test(c.chip));
  }
  /** A score for the query, or -1 when a word matches nothing. Title and id first. */
  function score(c, words) {
    if (!words.length) { return 0; }
    const id = c.chip.toLowerCase(), title = String(c.title || '').toLowerCase(), tags = (String(c.category || '') + ' ' + (c.tags || []).join(' ')).toLowerCase();
    const purpose = String(c.purpose || '').toLowerCase(), pins = pinText(c), phrase = words.join(' ');
    let total = 0;
    if (pins.indexOf(phrase) >= 0 && words.length > 1) { total += 40; }                  // "rowset of trade", "id of book"
    for (const w of words) {
      let s = -1;
      if (id === w || title === w) { s = 100; } else if (id.indexOf(w) === 0 || title.indexOf(w) === 0) { s = 80; } else if (id.indexOf(w) >= 0 || title.indexOf(w) >= 0) { s = 60; } else if (tags.indexOf(w) >= 0) { s = 30; } else if (purpose.indexOf(w) >= 0) { s = 20; } else if (pins.indexOf(w) >= 0) { s = 15; }
      if (s < 0 && !(words.length > 1 && pins.indexOf(phrase) >= 0 && w === 'of')) { return -1; }
      total += Math.max(s, 0);
    }
    return total;
  }

  // ---- "Fits this data" ------------------------------------------------------------------------------------------------------------------------
  const ACCEPTS = { id: ['id'], date: ['date'], datetime: ['date'], measure: ['measure'], number: ['measure'], dimension: ['dimension', 'id', 'date'], text: ['dimension', 'id', 'date', 'measure'], bool: ['dimension'] };
  function fits(c) {
    if (!fitsAttrs) { return true; }
    return (pinsOf(c).in || []).filter((p) => p.required && (p.role === 'data' || p.type === 'rowset' || p.type === 'document')).every((p) => {
      if (p.type === 'document') { return true; }
      return Object.keys(p.columns || {}).filter((col) => (p.columns[col] || {}).required).every((col) => {
        const want = ACCEPTS[(p.columns[col] || {}).type] || [(p.columns[col] || {}).type];
        return fitsAttrs.some((a) => want.indexOf(a.type) >= 0);
      });
    });
  }
  function refreshFits() {
    const on = fitsBox && fitsBox.checked, i = D.selected && (D.doc.chips || {})[D.selected];
    fitsAttrs = null;
    if (!on) { if (fitsNote) { fitsNote.textContent = ''; } paint(); return; }
    if (!i || !i.supply) { fitsNote.textContent = 'Select a chip that has a supply: the list then shows only the chips its data can feed.'; paint(); return; }
    D.attributes(i.supply).then((attrs) => {
      fitsAttrs = attrs || null;
      fitsNote.textContent = attrs ? 'Chips the data of ' + D.selected + ' (' + (i.supply.connector || i.supply.model || i.supply.live) + ') can feed.' : 'The supplier\'s attributes cannot be listed here: nothing is filtered.';
      paint();
    });
  }

  // ---- the list -------------------------------------------------------------------------------------------------------------------------------
  function item(c, badge) {
    const ref = c.chip + '@' + c.latest, kinds = catsOf(c), icon = (CATS.find((x) => x[0] === kinds[0]) || [null, 'cpu'])[1];
    const purpose = String(c.purpose || '').replace(/\s+/g, ' ').trim();
    const add = h('button', { type: 'button', class: 'ub-pal-add', 'data-chip': c.chip, draggable: 'true', title: purpose + ' (drag onto the canvas, or Enter to add)' },
      h('span', { class: 'ub-pal-top' }, h('i', { class: 'bi bi-' + icon + ' ub-pal-icon', 'aria-hidden': 'true' }), h('span', { class: 'ub-pal-name', text: ' ' + (c.title || c.chip) }),
        c.certified ? h('span', { class: 'ub-pal-cert', title: 'Certified', text: 'certified' }) : null, badge ? h('small', { class: 'ub-pal-badge', text: badge }) : null),
      h('small', { class: 'ub-pal-purpose', text: purpose.length > 90 ? purpose.slice(0, 88) + '…' : purpose }),
      h('small', { class: 'ub-pal-pins mono', text: summary(c) }),
      h('small', { class: 'text-muted-d', text: ref }));
    add.addEventListener('dragstart', (e) => { e.dataTransfer.setData('text/x-ub-chip', ref); e.dataTransfer.setData('text/plain', ref); e.dataTransfer.effectAllowed = 'copy'; });
    add.addEventListener('click', () => { used(c.chip); D.pick(ref, null); });
    const fav = saved().fav.indexOf(c.chip) >= 0;
    const star = h('button', { type: 'button', class: 'btn-pill btn-ghost ub-pal-info', 'data-ub-fav': c.chip, 'aria-pressed': String(fav), 'aria-label': (fav ? 'Unstar ' : 'Star ') + c.chip, title: 'Favourite' }, h('i', { class: 'bi bi-star' + (fav ? '-fill' : ''), 'aria-hidden': 'true' }));
    star.addEventListener('click', () => toggleFav(c.chip));
    const info = h('button', { type: 'button', class: 'btn-pill btn-ghost ub-pal-info', 'aria-label': 'Manual of ' + c.chip, title: 'The datasheet and manual' }, h('i', { class: 'bi bi-journal-text', 'aria-hidden': 'true' }));
    info.addEventListener('click', () => { if (U.canvas && U.canvas.openManual) { U.canvas.openManual(c.chip, c.latest, info); } });
    return h('li', { class: 'ub-pal-item', 'data-category': kinds.join(' '), 'data-chip-item': c.chip }, add, h('span', { class: 'ub-pal-side' }, star, info));
  }
  function paint() {
    if (!all) { return; }
    clear(list);
    const words = q.value.trim().toLowerCase().split(/\s+/).filter(Boolean);
    const shown = all.filter((c) => c.latest != null && (!cert.checked || c.certified) && (!cat || catsOf(c).indexOf(cat) >= 0) && fits(c))
      .map((c) => ({ c, s: score(c, words) })).filter((x) => x.s >= 0);
    if (!words.length) {
      const v = saved(), top = [];
      v.fav.concat(v.recent).forEach((chip) => { const x = shown.find((y) => y.c.chip === chip); if (x && top.indexOf(x) < 0) { top.push(x); } });
      top.forEach((x) => list.appendChild(item(x.c, v.fav.indexOf(x.c.chip) >= 0 ? '★' : 'recent')));
      if (top.length) { list.appendChild(h('li', { class: 'ub-pal-sep', 'aria-hidden': 'true' })); }
      shown.filter((x) => top.indexOf(x) < 0).forEach((x) => list.appendChild(item(x.c)));
    } else {
      shown.sort((a, b) => b.s - a.s || a.c.chip.localeCompare(b.c.chip)).forEach((x) => list.appendChild(item(x.c)));
    }
    if (!list.querySelector('.ub-pal-item')) { list.appendChild(h('li', { class: 'text-muted-d', text: 'No chip matches.' })); }
  }
  function load() {
    return D.api('GET', '/ui/api/chips').then((l) => { all = Array.isArray(l) ? l : []; paint(); }).catch((e) => D.say(e.code + ': ' + e.detail, true));
  }

  // ---- categories, keys --------------------------------------------------------------------------------------------------------------------
  if (cats) {
    CATS.forEach(([k, icon]) => {
      const b = h('button', { type: 'button', class: 'btn-pill btn-ghost ub-cat', 'data-ub-cat': k, 'aria-pressed': 'false' }, h('i', { class: 'bi bi-' + icon, 'aria-hidden': 'true' }), ' ' + k);
      b.addEventListener('click', () => { cat = cat === k ? '' : k; cats.querySelectorAll('[data-ub-cat]').forEach((x) => x.setAttribute('aria-pressed', String(x.getAttribute('data-ub-cat') === cat))); paint(); });
      cats.appendChild(b);
    });
  }
  let t = 0;
  q.addEventListener('input', () => { clearTimeout(t); t = setTimeout(paint, 120); });
  cert.addEventListener('change', paint);
  if (fitsBox) { fitsBox.addEventListener('change', refreshFits); }
  root.addEventListener('ub:mode', () => load());                 // a chip saved or published in Chip mode is offered at once
  root.addEventListener('ub:select',() => { if (fitsBox && fitsBox.checked) { refreshFits(); } });
  const items = () => Array.from(list.querySelectorAll('.ub-pal-add'));
  q.addEventListener('keydown', (e) => {
    if (e.key === 'ArrowDown') { e.preventDefault(); const f = items()[0]; if (f) { f.focus(); } } else if (e.key === 'Enter') { e.preventDefault(); const f = items()[0]; if (f) { f.click(); } }
  });
  list.addEventListener('keydown', (e) => {
    if (e.key !== 'ArrowDown' && e.key !== 'ArrowUp') { return; }
    const all2 = items(), at = all2.indexOf(document.activeElement);
    if (at < 0) { return; }
    e.preventDefault();
    if (e.key === 'ArrowUp' && at === 0) { q.focus(); return; }
    const n = all2[Math.max(0, Math.min(all2.length - 1, at + (e.key === 'ArrowDown' ? 1 : -1)))];
    if (n) { n.focus(); }
  });
  document.addEventListener('keydown', (e) => {
    if (e.key !== '/' || e.ctrlKey || e.metaKey || e.altKey || /input|textarea|select/i.test(e.target.tagName) || e.target.isContentEditable) { return; }
    e.preventDefault(); q.focus(); q.select();
  });

  U.palette = { load, paint, score, fits, catsOf };
  load();
})();
