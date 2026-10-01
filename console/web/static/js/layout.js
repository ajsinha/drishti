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
/* Layout mode (USER_GUIDE.md, Layout mode). Alt+L (or the footer's Layout key) turns it on: each column of the view is a
   12-column grid; a panel is dragged by its heading (Pointer Events: mouse, pen and touch alike) to another place or the
   other column, with a dashed placeholder where it will land; its right edge sets its width in columns and its bottom
   edge its height in rows. The same is done from the keyboard on a focused panel (arrows move, Shift+arrows size, H
   hides, A gives back the natural height), each change announced to screen readers. Esc puts everything back; Save
   (Ctrl+Enter) keeps the layout for this user (an overlay on the Sutra, kept by the server); Reset goes back to the
   Sutra's. An author may promote the layout to the next version of the Sutra, shown as a diff before it is proposed. */
(function () {
  'use strict';
  var view = document.querySelector('[data-view][data-sutra]');
  var bar = view && view.querySelector('[data-layout-bar]');
  if (!view || !bar) { return; }
  var GRID = 12, MAX_H = 24;
  var cols = { main: view.querySelector('.vmain'), right: view.querySelector('.vright') };
  var live = bar.querySelector('[data-layout-live]'), msg = bar.querySelector('[data-layout-msg]');
  var openKey = document.querySelector('[data-layout-open]');
  var base = [];
  try { base = JSON.parse(view.getAttribute('data-layout-base') || '[]'); } catch (e) { base = []; }
  var on = false, dirty = false, snapshot = null, drag = null;
  var url = '/api/layout/' + encodeURIComponent(view.getAttribute('data-sutra')) + '/' + encodeURIComponent(view.dataset.kind);

  // ---- the panels and their sizes -------------------------------------------------------------------
  function panels(col) {
    var list = col ? [col] : [cols.main, cols.right];
    var out = [];
    list.forEach(function (c) { Array.prototype.forEach.call(c.children, function (el) { if (el.matches('.pnl[data-panel]')) { out.push(el); } }); });
    return out;
  }
  function byId(id) { return panels().filter(function (p) { return p.getAttribute('data-panel') === id; })[0]; }
  function areaOf(p) { return p.parentNode === cols.right ? 'right' : 'main'; }
  function title(p) { var h = p.querySelector('.pnl-h h3'); return (h && h.textContent.trim()) || p.getAttribute('data-panel'); }
  function spanOf(p) { return +p.getAttribute('data-span') || GRID; }
  function heightOf(p) { return +p.getAttribute('data-height') || 0; }
  function hidden(p) { return p.classList.contains('pnl-off'); }
  function clamp(n, lo, hi) { return Math.max(lo, Math.min(hi, n)); }
  function swapClass(p, prefix, n, keep) {
    p.className = p.className.split(/\s+/).filter(function (c) { return c.indexOf(prefix) !== 0; }).join(' ');
    if (keep) { p.classList.add(prefix + n); }
  }
  function setSpan(p, n) { n = clamp(n, 1, GRID); swapClass(p, 'c-span-', n, n < GRID); p.setAttribute('data-span', String(n)); badge(p); }
  function setHeight(p, n) { n = n ? clamp(n, 1, MAX_H) : 0; swapClass(p, 'c-h-', n, n > 0); p.setAttribute('data-height', String(n)); badge(p); }
  function setHidden(p, yes) {
    p.classList.toggle('pnl-off', !!yes);
    var b = p.querySelector('[data-lay-hide]');
    if (b) { b.textContent = yes ? 'Show' : 'Hide'; b.setAttribute('aria-pressed', yes ? 'true' : 'false'); }
    badge(p);
  }
  function badge(p) {
    var b = p.querySelector(':scope > .lay-size');
    if (b) { b.textContent = spanOf(p) + '/12' + (heightOf(p) ? ' · ' + heightOf(p) + ' rows' : '') + (hidden(p) ? ' · hidden' : ''); }
  }
  function rowPx() { return parseFloat(getComputedStyle(document.documentElement).fontSize || '16') * 2.5; }

  /** Handles, the hide button and the size badge: added once per panel (and again when a live update replaces it). */
  function decorate(p) {
    if (!p.querySelector(':scope > .lh-e')) {
      ['lh-e', 'lh-s'].forEach(function (cls) {
        var h = document.createElement('span');
        h.className = cls;
        h.setAttribute('aria-hidden', 'true');
        h.title = cls === 'lh-e' ? 'Drag to change the width (Shift+← →)' : 'Drag to change the height (Shift+↑ ↓); double-click: natural height';
        p.appendChild(h);
      });
      var s = document.createElement('span');
      s.className = 'lay-size';
      s.setAttribute('aria-hidden', 'true');
      p.appendChild(s);
    }
    var code = p.querySelector('.pnl-h .pnl-code');
    if (code && !p.querySelector('.lay-tools')) {
      var tools = document.createElement('span');
      tools.className = 'lay-tools';
      var hide = document.createElement('button');
      hide.type = 'button';
      hide.setAttribute('data-lay-hide', '');
      hide.title = 'Hide or show this panel in your layout (H)';
      tools.appendChild(hide);
      code.appendChild(tools);
    }
    setHidden(p, hidden(p));
    if (on) { arm(p); }
  }
  function arm(p) {
    p.tabIndex = 0;
    p.setAttribute('aria-roledescription', 'movable panel');
    p.setAttribute('aria-describedby', 'layoutKeys');
  }
  function disarm(p) { p.tabIndex = -1; p.removeAttribute('aria-roledescription'); p.removeAttribute('aria-describedby'); }

  function capture() {
    return panels().map(function (p) {
      return { id: p.getAttribute('data-panel'), area: areaOf(p), span: spanOf(p), height: heightOf(p), hidden: hidden(p) };
    });
  }
  function restore(state) {
    state.forEach(function (e) {
      var p = byId(e.id);
      if (!p) { return; }
      (e.area === 'right' ? cols.right : cols.main).appendChild(p);
      setSpan(p, e.span || GRID);
      setHeight(p, e.height || 0);
      setHidden(p, !!e.hidden);
    });
    relayout();
  }
  function isBase(state) {
    var b = base.map(function (e) { return { id: e.id, area: e.area || 'main', span: e.span || GRID, height: e.height || 0, hidden: false }; });
    var order = function (list, a) { return list.filter(function (e) { return e.area === a; }).map(function (e) { return [e.id, e.span, e.height, e.hidden].join(':'); }).join(','); };
    return order(b, 'main') === order(state, 'main') && order(b, 'right') === order(state, 'right');
  }
  var pending = 0;
  function relayout() {
    cancelAnimationFrame(pending);
    pending = requestAnimationFrame(function () { window.dispatchEvent(new Event('resize')); });   // charts follow their panel's new size
  }
  function say(text) { live.textContent = ''; setTimeout(function () { live.textContent = text; }, 30); }
  function where(p) {
    var list = panels(p.parentNode);
    return title(p) + ': ' + (list.indexOf(p) + 1) + ' of ' + list.length + ' in the ' + (areaOf(p) === 'right' ? 'side' : 'main') + ' column';
  }
  function size(p) {
    return title(p) + ': ' + spanOf(p) + ' of 12 columns wide, ' + (heightOf(p) ? heightOf(p) + ' rows tall' : 'as tall as its content');
  }
  function changed() { dirty = true; msg.textContent = 'Not saved yet'; msg.classList.remove('t-bad'); }

  // ---- on, off, save --------------------------------------------------------------------------------
  function enter() {
    if (on) { return; }
    on = true;
    dirty = false;
    snapshot = capture();
    view.classList.add('layout-on');
    bar.hidden = false;
    var top = document.querySelector('.tbar');                   // the bar sticks just under the top bar
    bar.style.top = (top ? top.getBoundingClientRect().height : 0) + 'px';
    panels().forEach(function (p) { decorate(p); arm(p); });
    var first = panels()[0];
    if (first) { first.focus({ preventScroll: true }); }
    msg.textContent = '';
    say('Layout mode. Tab moves between panels; arrows move the panel, Shift and arrows size it, H hides it. Ctrl+Enter saves, Escape cancels.');
    relayout();
  }
  function leave() {
    on = false;
    if (drag) { endDrag(false); }
    view.classList.remove('layout-on');
    bar.hidden = true;
    panels().forEach(disarm);
    relayout();
  }
  function cancel() {
    if (snapshot) { restore(snapshot); }
    leave();
    say('Layout mode left; nothing changed.');
  }
  function fkeys() {           // a function key whose panel is hidden is greyed, as on first paint
    document.querySelectorAll('.fk[data-target]').forEach(function (b) {
      if (b.getAttribute('data-action') === 'denied') { return; }
      var p = byId(b.getAttribute('data-target'));
      var off = !!p && hidden(p);
      b.disabled = off;
      if (off) { b.title = 'Hidden in your layout: show it again in layout mode (Alt+L)'; } else { b.removeAttribute('title'); }
    });
  }
  function send(method, body) {
    return fetch(url, { method: method, headers: { 'Content-Type': 'application/json', Accept: 'application/json' }, body: body ? JSON.stringify(body) : undefined })
      .then(function (r) { return r.json().catch(function () { return {}; }).then(function (b) { return { ok: r.ok, b: b }; }); });
  }
  function save(stay) {
    var state = capture();
    var mine = !isBase(state);
    var req = mine ? send('PUT', { panels: state.map(function (e) {
      return { id: e.id, area: e.area, span: e.span < GRID ? e.span : null, height: e.height || null, hidden: e.hidden };
    }) }) : send('DELETE');
    msg.textContent = 'Saving…';
    return req.then(function (res) {
      if (!res.ok) { msg.textContent = (res.b.code || 'Error') + ': ' + (res.b.detail || 'not saved'); msg.classList.add('t-bad'); return false; }
      dirty = false;
      snapshot = capture();
      if (mine) { view.setAttribute('data-layout-mine', ''); } else { view.removeAttribute('data-layout-mine'); }
      if (openKey) {
        var dot = openKey.querySelector('.fk-mine');
        if (mine && !dot) { dot = document.createElement('span'); dot.className = 'fk-mine'; dot.textContent = ' •'; openKey.appendChild(dot); }
        if (!mine && dot) { dot.remove(); }
      }
      fkeys();
      msg.textContent = mine ? 'Saved: this is your layout of ' + view.getAttribute('data-sutra') + '.' : 'Saved: back to the Sutra’s layout.';
      if (!stay) { leave(); }
      say(msg.textContent);
      return true;
    }).catch(function () { msg.textContent = 'Could not reach the console'; msg.classList.add('t-bad'); return false; });
  }

  // ---- the keyboard ---------------------------------------------------------------------------------
  function move(p, step) {
    var list = panels(p.parentNode), i = list.indexOf(p), j = i + step;
    if (j < 0 || j >= list.length) { say(where(p) + ' (already ' + (step < 0 ? 'first' : 'last') + ')'); return; }
    p.parentNode.insertBefore(p, step < 0 ? list[j] : list[j].nextElementSibling);
    p.focus({ preventScroll: true });
    p.scrollIntoView({ block: 'nearest' });
    changed(); relayout(); say(where(p));
  }
  function toColumn(p, area) {
    if (areaOf(p) === area) { say(where(p)); return; }
    var target = area === 'right' ? cols.right : cols.main, list = panels(target), i = panels(p.parentNode).indexOf(p);
    target.insertBefore(p, list[Math.min(i, list.length)] || null);
    p.focus({ preventScroll: true });
    p.scrollIntoView({ block: 'nearest' });
    changed(); relayout(); say(where(p));
  }
  function taller(p, step) {
    var now = heightOf(p) || Math.round(p.getBoundingClientRect().height / rowPx());
    setHeight(p, clamp(now + step, 1, MAX_H));
    changed(); relayout(); say(size(p));
  }
  document.addEventListener('keydown', function (e) {
    var tag = (e.target.tagName || '').toLowerCase(), typing = tag === 'input' || tag === 'textarea' || tag === 'select' || e.target.isContentEditable;
    if (e.altKey && !e.ctrlKey && !e.metaKey && (e.code === 'KeyL' || e.key === 'l' || e.key === 'L')) {
      e.preventDefault();
      if (!on) { enter(); } else if (!dirty) { leave(); say('Layout mode left.'); } else { say('Save with Ctrl+Enter, or cancel with Escape.'); }
      return;
    }
    if (!on) { return; }
    if (e.key === 'Escape') {
      var drawer = document.getElementById('layoutPromote');
      e.preventDefault();
      if (drag) { endDrag(false); say('Move cancelled.'); } else if (drawer && !drawer.hidden) { drawer.hidden = true; } else { cancel(); }
      return;
    }
    if (e.key === 'Enter' && (e.ctrlKey || e.metaKey)) { e.preventDefault(); save(false); return; }
    var p = e.target.closest && e.target.closest('.pnl[data-panel]');
    if (typing || !p || p !== e.target) { return; }
    var k = e.key, done = true;
    if (e.shiftKey && k === 'ArrowLeft') { setSpan(p, spanOf(p) - 1); changed(); relayout(); say(size(p)); }
    else if (e.shiftKey && k === 'ArrowRight') { setSpan(p, spanOf(p) + 1); changed(); relayout(); say(size(p)); }
    else if (e.shiftKey && k === 'ArrowUp') { taller(p, -1); }
    else if (e.shiftKey && k === 'ArrowDown') { taller(p, 1); }
    else if (k === '-' || k === '_') { setSpan(p, spanOf(p) - 1); changed(); relayout(); say(size(p)); }
    else if (k === '+' || k === '=') { setSpan(p, spanOf(p) + 1); changed(); relayout(); say(size(p)); }
    else if (k === 'ArrowUp') { move(p, -1); }
    else if (k === 'ArrowDown') { move(p, 1); }
    else if (k === 'ArrowLeft') { toColumn(p, 'main'); }
    else if (k === 'ArrowRight') { toColumn(p, 'right'); }
    else if (k === 'h' || k === 'H' || k === 'Delete') { setHidden(p, !hidden(p)); changed(); say(title(p) + (hidden(p) ? ' hidden' : ' shown')); }
    else if (k === 'a' || k === 'A') { setHeight(p, 0); changed(); relayout(); say(size(p)); }
    else { done = false; }
    if (done) { e.preventDefault(); e.stopPropagation(); }
  }, true);

  // ---- dragging a panel by its heading ---------------------------------------------------------------
  function placeholder(p) {
    var ph = document.createElement('div');
    ph.className = 'pnl-ph' + (spanOf(p) < GRID ? ' c-span-' + spanOf(p) : '');
    ph.setAttribute('aria-hidden', 'true');
    return ph;
  }
  function startDrag(p, e) {
    var ghost = document.createElement('div');
    ghost.className = 'lay-ghost';
    ghost.textContent = '⠿ ' + title(p);
    document.body.appendChild(ghost);
    var ph = placeholder(p);
    p.parentNode.insertBefore(ph, p.nextElementSibling);
    p.classList.add('dragging');
    drag.on = true; drag.ghost = ghost; drag.ph = ph;
    track(e);
  }
  function track(e) {
    drag.ghost.style.transform = 'translate(' + (e.clientX + 14) + 'px,' + (e.clientY + 14) + 'px)';
    var el = document.elementFromPoint(e.clientX, e.clientY);
    if (!el) { return; }
    var col = el.closest('.vmain, .vright');
    var target = el.closest('.pnl[data-panel]');
    if (target && target !== drag.p && (target.parentNode === cols.main || target.parentNode === cols.right)) {
      var r = target.getBoundingClientRect(), cr = target.parentNode.getBoundingClientRect();
      var before = r.width < cr.width * 0.9 ? e.clientX < r.left + r.width / 2 : e.clientY < r.top + r.height / 2;
      if (before && target.previousElementSibling !== drag.ph) { target.parentNode.insertBefore(drag.ph, target); }
      if (!before && target.nextElementSibling !== drag.ph) { target.parentNode.insertBefore(drag.ph, target.nextElementSibling); }
    } else if (col && !target) {
      var list = panels(col).filter(function (x) { return x !== drag.p; });
      var last = list[list.length - 1];
      if (!last || e.clientY > last.getBoundingClientRect().bottom) { if (col.lastElementChild !== drag.ph) { col.appendChild(drag.ph); } }
    }
    var edge = 48;                                                       // near the window's edge: scroll
    if (e.clientY < edge) { window.scrollBy(0, -14); } else if (e.clientY > window.innerHeight - edge) { window.scrollBy(0, 14); }
  }
  function endDrag(keep) {
    var d = drag;
    drag = null;
    if (!d || !d.on) { return; }
    if (keep && d.ph.parentNode) { d.ph.parentNode.insertBefore(d.p, d.ph); }
    d.ph.remove();
    d.ghost.remove();
    d.p.classList.remove('dragging');
    if (keep) { changed(); relayout(); d.p.focus({ preventScroll: true }); say(where(d.p)); }
  }
  view.addEventListener('pointerdown', function (e) {
    if (!on || (e.pointerType === 'mouse' && e.button !== 0)) { return; }
    var p = e.target.closest('.pnl[data-panel]');
    if (!p || (p.parentNode !== cols.main && p.parentNode !== cols.right)) { return; }
    if (e.target.closest('.lh-e, .lh-s')) { startResize(p, e); return; }
    var head = e.target.closest('.pnl-h');
    if (!head || e.target.closest('a, button, input, select')) { return; }
    e.preventDefault();
    head.setPointerCapture(e.pointerId);
    drag = { p: p, x: e.clientX, y: e.clientY, id: e.pointerId, on: false, head: head };
  });
  view.addEventListener('pointermove', function (e) {
    if (drag && e.pointerId === drag.id) {
      if (!drag.on && Math.abs(e.clientX - drag.x) + Math.abs(e.clientY - drag.y) > 5) { startDrag(drag.p, e); } else if (drag.on) { track(e); }
    } else if (resize && e.pointerId === resize.id) { resizeTo(e); }
  });
  function up(e) {
    if (drag && e.pointerId === drag.id) { endDrag(e.type === 'pointerup'); }
    if (resize && e.pointerId === resize.id) { endResize(); }
  }
  view.addEventListener('pointerup', up);
  view.addEventListener('pointercancel', up);

  // ---- resizing by an edge ----------------------------------------------------------------------------
  var resize = null;
  function startResize(p, e) {
    e.preventDefault();
    e.target.setPointerCapture(e.pointerId);
    var col = p.parentNode, cs = getComputedStyle(col);
    var gap = parseFloat(cs.columnGap) || 0, cw = col.getBoundingClientRect().width;
    resize = { p: p, id: e.pointerId, wide: e.target.classList.contains('lh-e'), gap: gap, unit: (cw - gap * (GRID - 1)) / GRID, row: rowPx() };
    p.classList.add('resizing');
  }
  function resizeTo(e) {
    var r = resize.p.getBoundingClientRect();
    if (resize.wide) {
      var n = clamp(Math.round((e.clientX - r.left + resize.gap) / (resize.unit + resize.gap)), 1, GRID);
      if (n !== spanOf(resize.p)) { setSpan(resize.p, n); relayout(); }
    } else {
      var h = clamp(Math.round((e.clientY - r.top) / resize.row), 1, MAX_H);
      if (h !== heightOf(resize.p)) { setHeight(resize.p, h); }
    }
  }
  function endResize() {
    var p = resize.p;
    resize = null;
    p.classList.remove('resizing');
    changed(); relayout(); say(size(p));
  }
  view.addEventListener('dblclick', function (e) {
    var h = on && e.target.closest('.lh-s');
    if (h) { var p = h.closest('.pnl'); setHeight(p, 0); changed(); relayout(); say(size(p)); }
  });
  view.addEventListener('click', function (e) {
    var b = on && e.target.closest('[data-lay-hide]');
    if (b) { var p = b.closest('.pnl'); setHidden(p, !hidden(p)); changed(); say(title(p) + (hidden(p) ? ' hidden' : ' shown')); }
  });

  // ---- the bar ----------------------------------------------------------------------------------------
  if (openKey) { openKey.addEventListener('click', function () { if (on) { if (!dirty) { leave(); } } else { enter(); } }); }
  bar.querySelector('[data-layout-cancel]').addEventListener('click', cancel);
  bar.querySelector('[data-layout-save]').addEventListener('click', function () { save(false); });
  bar.querySelector('[data-layout-reset]').addEventListener('click', function () {
    restore(base.map(function (e) { return { id: e.id, area: e.area, span: e.span, height: e.height, hidden: false }; }));
    changed();
    say('Back to the Sutra’s layout. Save to keep it.');
  });

  // ---- promote to Sutra (authors): saves the layout, shows the diff, proposes the next version ------------
  var drawer = document.getElementById('layoutPromote');
  var promoteBtn = bar.querySelector('[data-layout-promote]');
  function diffLines(pre, lines, none) {
    pre.textContent = '';
    if (!lines.length) { pre.textContent = none || 'No differences.'; return; }
    lines.forEach(function (l) {
      var s = document.createElement('span');
      s.className = 'd-' + l[0];
      s.textContent = l[1];
      pre.appendChild(s);
    });
  }
  function loadPromotion() {
    var pre = drawer.querySelector('[data-promote-diff]'), ul = drawer.querySelector('[data-promote-changes]');
    var drop = drawer.querySelector('[data-promote-drop]').checked;
    pre.textContent = 'Loading…';
    ul.textContent = '';
    fetch(url + '/promotion?dropHidden=' + drop, { headers: { Accept: 'application/json' } })
      .then(function (r) { return r.json().then(function (b) { return { ok: r.ok, b: b }; }); })
      .then(function (res) {
        if (!res.ok) { pre.textContent = (res.b.code || 'Error') + ': ' + (res.b.detail || ''); return; }
        drawer.querySelector('[data-promote-title]').textContent = res.b.sutra + ' v' + res.b.fromVersion + ' → v' + res.b.version;
        (res.b.changes.length ? res.b.changes : ['Only the version changes: the layout is the Sutra’s.']).forEach(function (c) {
          var li = document.createElement('li'); li.textContent = c; ul.appendChild(li);
        });
        // moved panels in words, the line diff of the other edits, and the full line diff behind a toggle
        var moves = res.b.moves || [], movesUl = drawer.querySelector('[data-promote-moves]'), full = drawer.querySelector('[data-promote-full]');
        if (movesUl) {
          movesUl.textContent = '';
          moves.forEach(function (m) { var li = document.createElement('li'); li.textContent = m; movesUl.appendChild(li); });
          movesUl.hidden = !moves.length;
        }
        if (full) {
          full.hidden = !moves.length;
          diffLines(full.querySelector('[data-promote-full-diff]'), res.b.diff || []);
        }
        diffLines(pre, moves.length ? (res.b.edits || []) : (res.b.diff || []), moves.length ? 'No other edits: only panels moved.' : null);
      });
  }
  if (drawer && promoteBtn) {
    promoteBtn.addEventListener('click', function () {
      if (isBase(capture())) { msg.textContent = 'Nothing to promote: this is the Sutra’s own layout.'; msg.classList.add('t-bad'); return; }
      save(true).then(function (ok) { if (ok) { drawer.hidden = false; loadPromotion(); drawer.querySelector('#promoteNote').focus(); } });
    });
    drawer.querySelector('[data-promote-close]').addEventListener('click', function () { drawer.hidden = true; });
    drawer.querySelector('[data-promote-drop]').addEventListener('change', loadPromotion);
    drawer.querySelector('[data-promote-form]').addEventListener('submit', function (e) {
      e.preventDefault();
      var out = drawer.querySelector('[data-promote-msg]'), btn = drawer.querySelector('[data-promote-submit]');
      btn.disabled = true;
      out.textContent = 'Submitting…';
      fetch(url + '/promotion', { method: 'POST', headers: { 'Content-Type': 'application/json', Accept: 'application/json' },
        body: JSON.stringify({ note: e.target.note.value, dropHidden: drawer.querySelector('[data-promote-drop]').checked }) })
        .then(function (r) { return r.json().then(function (b) { return { ok: r.ok, b: b }; }); })
        .then(function (res) {
          btn.disabled = false;
          out.textContent = '';
          if (!res.ok) { out.textContent = (res.b.code || 'Error') + ': ' + (res.b.detail || ''); out.classList.add('t-bad'); return; }
          out.classList.remove('t-bad');
          if (res.b.proposal) {
            out.appendChild(document.createTextNode('Proposed as ' + res.b.proposal.name + ' v' + res.b.proposal.version + ' (' + res.b.proposal.id + '): '));
            var a = document.createElement('a'); a.className = 'lnk'; a.href = res.b.href; a.textContent = 'open the review'; out.appendChild(a);
          } else if (res.b.saved) {
            out.textContent = res.b.saved.name + ' v' + res.b.saved.version + ' is live.';
          }
          say(out.textContent);
        });
    });
  }

  panels().forEach(decorate);
  fkeys();
  window.drishtiLayout = { decorate: decorate, enter: enter, active: function () { return on; } };
})();
