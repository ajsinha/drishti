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
/* The Design tab: the real preview with the editing layer on top. The markup is the one every user sees (the console's Studio
 * partial); this module only decorates it: a hover outline, a click selects a panel (or the title or the strip), a panel is dragged
 * by its heading to a new place, its right and bottom edges are dragged to size it on the 12-column grid, Delete removes it.
 * Every change is an operation handed to WB.Actions; the page is then redrawn from the server's answer, so what you see is
 * what is saved. Panels inside a tabs body are not separate targets (the tabs panel is).
 *
 * Keyboard (grid-keys.js, shared with layout mode): arrows select, Alt+arrows move, Shift+arrows size, Enter opens the inspector,
 * Delete removes, A natural height; a live region says what happened.
 *
 *   new WB.Canvas(frameEl, store, actions, hooks) -> {select(sel), selected(), ids(), el(id), focus()}
 *   hooks: {onSelect(sel), openInspector(), menuAdd(), menuBind()}      sel: {type: 'panel', id} | {type: 'title'} | {type: 'strip'} | null */
(function () {
  'use strict';
  var WB = window.DrishtiWB = window.DrishtiWB || {};
  var R = window.DrishtiLayoutRules, GK = window.DrishtiGridKeys, GRID = R.GRID, EDGE_PX = 18;

  WB.Canvas = function (frame, store, actions, hooks) {
    var sel = null, indicator = null, overEl = null;
    var title = function (p) { var h = p.querySelector('.pnl-h h3'); return (h && h.textContent.trim()) || p.getAttribute('data-panel'); };
    var say = function (t, bad) { store.emit('say', t, bad); };
    function columns() { return { main: frame.querySelector('.vmain'), right: frame.querySelector('.vright') }; }
    function panels(col) {
      var c = columns(), list = col ? [col] : [c.main, c.right], out = [];
      list.forEach(function (x) { if (x) { Array.prototype.forEach.call(x.children, function (el) { if (el.matches('.pnl[data-panel]')) { out.push(el); } }); } });
      return out;
    }
    function areaOf(p) { return p.parentNode === columns().right ? 'right' : 'main'; }
    function byId(id) { return panels().filter(function (p) { return p.getAttribute('data-panel') === id; })[0] || null; }
    function spanOf(p) { return +p.getAttribute('data-span') || GRID; }
    function heightOf(p) { return +p.getAttribute('data-height') || 0; }
    function regionEl(s) { return !s ? null : s.type === 'panel' ? byId(s.id) : frame.querySelector(s.type === 'title' ? '.vtitle' : 'dl.strip'); }

    // ---- decorating the preview --------------------------------------------------------------------------------------
    function badge(p) { var b = p.querySelector(':scope > .lay-size'); if (b) { b.textContent = spanOf(p) + '/12' + (heightOf(p) ? ' · ' + heightOf(p) + ' rows' : ''); } }
    function swap(p, prefix, n, keep) {
      p.className = p.className.split(/\s+/).filter(function (c) { return c.indexOf(prefix) !== 0; }).join(' ');
      if (keep) { p.classList.add(prefix + n); }
    }
    function liveSpan(p, n) { swap(p, 'c-span-', n, n < GRID); p.setAttribute('data-span', String(n)); badge(p); }
    function liveHeight(p, n) { swap(p, 'c-h-', n, n > 0); p.setAttribute('data-height', String(n)); badge(p); }
    function decorate() {
      var all = panels();
      all.forEach(function (p, i) {
        p.tabIndex = -1;
        p.setAttribute('aria-roledescription', 'panel on the canvas');
        p.setAttribute('aria-label', title(p) + ', ' + p.getAttribute('data-kind') + ' panel');
        ['lh-e', 'lh-s'].forEach(function (cls) {
          var h = WB.el('span', cls, null, { 'aria-hidden': 'true' });
          h.title = cls === 'lh-e' ? 'Drag to change the width (Shift+Left, Shift+Right)' : 'Drag to change the height (Shift+Up, Shift+Down); double-click: natural height';
          p.appendChild(h);
        });
        p.appendChild(WB.el('span', 'lay-size', null, { 'aria-hidden': 'true' }));
        badge(p);
        var head = p.querySelector('.pnl-h');
        if (head) {
          head.title = 'Drag to move · drag the edges to resize'; head.classList.add('wb-grab');
          if (!head.querySelector('.wb-trash')) {
            var trash = WB.el('button', 'wb-trash', null, { type: 'button', 'aria-label': 'Remove panel ' + title(p), title: 'Remove this panel (Undo brings it back)' });
            trash.appendChild(WB.el('i', 'bi bi-trash', null, { 'aria-hidden': 'true' }));
            ['pointerdown', 'mousedown', 'dblclick'].forEach(function (ev) { trash.addEventListener(ev, function (x) { x.stopPropagation(); }); });
            trash.addEventListener('click', function (x) { x.stopPropagation(); removePanel(p.getAttribute('data-panel')); });
            head.appendChild(trash);
          }
          if (!head.querySelector('.wb-grip')) { head.insertBefore(WB.el('i', 'bi bi-grip-vertical wb-grip', null, { 'aria-hidden': 'true' }), head.firstChild); }
        }
      });
      [['.vtitle', 'title', 'Title'], ['dl.strip', 'strip', 'Key figures strip']].forEach(function (r) {
        var e = frame.querySelector(r[0]);
        if (e) { e.tabIndex = -1; e.setAttribute('data-wb-region', r[1]); e.setAttribute('aria-label', r[2]); }
      });
      var fe = regionEl(sel) || (all[0] || frame.querySelector('[data-wb-region]'));
      if (fe) { fe.tabIndex = 0; }
    }
    function mark() {
      frame.querySelectorAll('.wb-sel').forEach(function (e) { e.classList.remove('wb-sel'); e.removeAttribute('aria-current'); });
      var e = regionEl(sel);
      if (e) { e.classList.add('wb-sel'); e.setAttribute('aria-current', 'true'); }
    }
    function select(s, quiet) {
      var same = sel && s && sel.type === s.type && sel.id === s.id;
      sel = s;
      mark();
      var e = regionEl(s);
      frame.querySelectorAll('[tabindex="0"]').forEach(function (x) { if (x !== e) { x.tabIndex = -1; } });
      if (e) { e.tabIndex = 0; }
      if (!same && hooks.onSelect) { hooks.onSelect(s); }
      if (!quiet && e) { say(s.type === 'panel' ? title(e) + ', ' + e.getAttribute('data-kind') + ' panel, ' + spanOf(e) + ' of 12 columns' : (s.type === 'title' ? 'Title' : 'Key figures strip') + ' selected'); }
    }
    function focusSel() { var e = regionEl(sel); if (e) { e.focus({ preventScroll: true }); } }

    /** An empty design: one large way in (the chooser of the 20 kinds). */
    function callToAction() {
      var wrap = WB.el('div', 'wb-first'), none = !WB.model(store.state.yaml).panels.length;
      if (!none) { wrap.hidden = true; return wrap; }
      var b = WB.el('button', 'btn-pill btn-accent wb-first-b', 'Add your first panel', { type: 'button', 'data-first-panel': '' });
      b.insertBefore(WB.el('i', 'bi bi-plus-lg', null, { 'aria-hidden': 'true' }), b.firstChild);
      b.addEventListener('click', function () { if (hooks.menuAdd) { hooks.menuAdd(b); } });
      wrap.appendChild(b);
      return wrap;
    }
    store.on('preview', function (p) {
      var had = frame.contains(document.activeElement);
      frame.textContent = '';
      if (p.error) { frame.appendChild(callToAction()); frame.appendChild(WB.el('p', 'bs-status bad', p.error)); return; }
      if (!p.html) {
        frame.appendChild(callToAction());
        frame.appendChild(p.failed ? WB.el('p', 'bs-status bad wb-empty', 'The Sutra has problems that stop it from drawing: see the Problems tab.')
          : WB.el('p', 'text-muted-d wb-empty', 'Nothing to draw yet: the design needs a Sutra with panels and a sample.'));
        return;
      }
      frame.innerHTML = p.html;
      if (p.dropped && p.dropped.length) {
        var note = WB.el('p', 'bs-status bad wb-dropped', null, { role: 'note' });
        note.textContent = 'Not drawn: ' + p.dropped.map(function (d) { return d.panel + (d.option ? ' (' + d.option + ')' : ''); }).join(', ') + '. See the Problems tab.';
        frame.insertBefore(note, frame.firstChild);
      }
      if (window.drishti) { window.drishti.enhance(frame); window.drishti.redraw(); }
      decorate();
      if (!panels().length) { frame.insertBefore(callToAction(), frame.firstChild); }
      if (sel && !regionEl(sel)) { sel = null; if (hooks.onSelect) { hooks.onSelect(null); } }
      mark();
      if (had) { focusSel(); }
    });

    // ---- pointing: hover, select ---------------------------------------------------------------------------------------
    frame.addEventListener('click', function (e) {
      var p = e.target.closest('.pnl[data-panel]');
      if (p && panels().indexOf(p) >= 0) { select({ type: 'panel', id: p.getAttribute('data-panel') }); p.focus({ preventScroll: true }); if (hooks.picked) { hooks.picked(); } return; }
      var r = e.target.closest('[data-wb-region]');
      if (r) { select({ type: r.getAttribute('data-wb-region') }); r.focus({ preventScroll: true }); if (hooks.picked) { hooks.picked(); } return; }
      if (e.target.closest('.studio-view')) { select(null); }
    });
    frame.addEventListener('dblclick', function (e) {
      var h = e.target.closest('.lh-s');
      if (h) { var p = h.closest('.pnl'); actions.resize(p.getAttribute('data-panel'), { height: 0 }); return; }
      if (e.target.closest('.pnl[data-panel], [data-wb-region]') && hooks.openInspector) { hooks.openInspector(); }
    });
    frame.addEventListener('mouseover', function (e) {
      var p = e.target.closest && e.target.closest('.pnl[data-panel], [data-wb-region]');
      if (p !== overEl) { if (overEl) { overEl.classList.remove('wb-hover'); } overEl = p; if (p) { p.classList.add('wb-hover'); } }
    });
    frame.addEventListener('mouseleave', function () { if (overEl) { overEl.classList.remove('wb-hover'); overEl = null; } });

    // ---- dragging a panel by its heading, sizing it by its edges -------------------------------------------------------
    function hit(x, y) {
      var el = document.elementFromPoint(x, y), c = columns();
      if (!el || !frame.contains(el)) { return null; }
      var p = el.closest('.pnl[data-panel]');
      if (p && panels().indexOf(p) >= 0) {
        var r = p.getBoundingClientRect(), cr = p.parentNode.getBoundingClientRect(), side = r.width < cr.width * 0.9;
        var before = side ? x < r.left + r.width / 2 : y < r.top + r.height / 2;
        var edge = Math.min(x - r.left, r.right - x, y - r.top, r.bottom - y) < EDGE_PX;          // the rim of a panel is "between panels" for a dropped field
        return { type: 'panel', el: p, id: p.getAttribute('data-panel'), area: areaOf(p), rel: before ? 'before' : 'after', side: side, rect: r, edge: edge };
      }
      if (el.closest('dl.strip')) { return { type: 'strip', el: frame.querySelector('dl.strip') }; }
      if (el.closest('.vtitle')) { return { type: 'title', el: frame.querySelector('.vtitle') }; }
      var col = el.closest('.vmain, .vright') || [c.main, c.right].filter(function (k) { if (!k) { return false; } var r2 = k.getBoundingClientRect(); return x >= r2.left && x <= r2.right && y >= r2.top - 40 && y <= r2.bottom + 40; })[0];
      if (col) {
        var area = col === c.right ? 'right' : 'main', list = panels(col), last = list[list.length - 1];
        if (last) { return { type: 'panel', el: last, id: last.getAttribute('data-panel'), area: area, rel: 'after', side: false, rect: last.getBoundingClientRect(), gap: true }; }
        return { type: 'column', area: area, rect: col.getBoundingClientRect() };
      }
      return { type: 'empty' };
    }
    function showDrop(h, payload) {
      clearDrop();
      var box = WB.el('div', 'wb-drop');
      if (h && h.type === 'panel' && payload.type === 'field' && !h.gap && !h.edge) { h.el.classList.add('wb-drop-on'); indicator = h.el; return; }
      if (h && (h.type === 'panel' || h.type === 'column')) {
        var r = h.rect;
        if (h.type === 'column') { box.classList.add('wb-drop-box'); box.style.cssText = ''; place(box, r.left, r.top, r.width, Math.max(r.height, 60)); }
        else if (h.side) { place(box, h.rel === 'before' ? r.left - 4 : r.right - 2, r.top, 6, r.height); }
        else { place(box, r.left, h.rel === 'before' ? r.top - 4 : r.bottom - 2, r.width, 6); }
      } else if (h && h.type === 'strip' && payload.type === 'field') { h.el.classList.add('wb-drop-on'); indicator = h.el; return; }
      else { return; }
      document.body.appendChild(box);
      indicator = box;
    }
    function place(b, l, t, w, hgt) { b.style.setProperty('--l', l + 'px'); b.style.setProperty('--t', t + 'px'); b.style.setProperty('--w', w + 'px'); b.style.setProperty('--h', hgt + 'px'); }
    function clearDrop() {
      if (indicator) { if (indicator.classList.contains('wb-drop')) { indicator.remove(); } else { indicator.classList.remove('wb-drop-on'); } indicator = null; }
    }
    WB.drag.drop = {
      over: function (x, y, payload) { showDrop(hit(x, y), payload); },
      end: function (x, y, payload, committed) {
        clearDrop();
        if (!committed) { say('Drop cancelled.'); return; }
        var h = hit(x, y);
        if (!h || h.type === 'empty') { say(payload.type === 'panel' ? 'Dropped outside the columns: nothing moved.' : 'Drop on the canvas, in a column.', true); return; }
        var at = h.type === 'panel' ? { area: h.area, rel: h.rel, id: h.id } : { area: h.area || 'main' };
        actions.dropped(payload, h, at, { x: x, y: y });
      }
    };
    frame.addEventListener('pointerdown', function (e) {
      var edge = e.target.closest('.lh-e, .lh-s');
      if (edge) { resizeStart(edge.closest('.pnl'), edge.classList.contains('lh-e'), e); return; }
      var head = e.target.closest('.pnl-h'), p = head && head.closest('.pnl[data-panel]');
      if (!p || panels().indexOf(p) < 0 || e.target.closest('a, button, input, select')) { return; }
      if (e.pointerType === 'mouse' && e.button !== 0) { return; }
      var id = p.getAttribute('data-panel'), ghost;
      WB.drag.track(e, {
        start: function () { select({ type: 'panel', id: id }, true); ghost = WB.el('div', 'wb-ghost', '⠿ ' + title(p)); document.body.appendChild(ghost); document.body.classList.add('wb-dragging'); p.classList.add('dragging'); },
        move: function (ev) { ghost.style.transform = 'translate(' + (ev.clientX + 14) + 'px,' + (ev.clientY + 14) + 'px)'; showDrop(hit(ev.clientX, ev.clientY), { type: 'panel', id: id }); if (ev.clientY < 48) { window.scrollBy(0, -12); } else if (ev.clientY > window.innerHeight - 48) { window.scrollBy(0, 12); } },
        end: function (ev, committed, moved) {
          if (ghost) { ghost.remove(); }
          clearDrop(); document.body.classList.remove('wb-dragging'); p.classList.remove('dragging');
          if (!moved || !committed) { return; }
          var h = hit(ev.clientX, ev.clientY);
          if (!h || h.type === 'empty' || h.type === 'title' || h.type === 'strip') { say('Dropped outside the columns: nothing moved.', true); return; }
          if (h.id === id) { return; }
          actions.dropped({ type: 'panel', id: id }, h, h.type === 'panel' ? { area: h.area, rel: h.rel, id: h.id } : { area: h.area }, null);
        }
      });
    });
    function resizeStart(p, wide, e) {
      e.preventDefault();
      var col = p.parentNode, cs = getComputedStyle(col), gap = parseFloat(cs.columnGap) || 0, cw = col.getBoundingClientRect().width;
      var unit = (cw - gap * (GRID - 1)) / GRID, row = parseFloat(getComputedStyle(document.documentElement).fontSize || '16') * 2.5;
      var id = p.getAttribute('data-panel'), was = wide ? spanOf(p) : heightOf(p), now = was;
      p.classList.add('resizing');
      WB.drag.track(e, {
        move: function (ev) {
          var r = p.getBoundingClientRect();
          if (wide) { now = R.clampSpan((ev.clientX - r.left + gap) / (unit + gap), 1); liveSpan(p, now); } else { now = R.clampHeight(Math.max(1, Math.round((ev.clientY - r.top) / row))); liveHeight(p, now); }
          window.dispatchEvent(new Event('resize'));
        },
        end: function (ev, committed, moved) {
          p.classList.remove('resizing');
          if (!moved) { return; }
          if (!committed || now === was) { if (wide) { liveSpan(p, was); } else { liveHeight(p, was); } return; }
          actions.resize(id, wide ? { span: now } : { height: now });
        }
      });
    }

    // ---- the keyboard -----------------------------------------------------------------------------------------------------
    function order() {
      var out = [];
      if (frame.querySelector('.vtitle')) { out.push({ type: 'title' }); }
      if (frame.querySelector('dl.strip')) { out.push({ type: 'strip' }); }
      panels(columns().main).forEach(function (p) { out.push({ type: 'panel', id: p.getAttribute('data-panel') }); });
      panels(columns().right).forEach(function (p) { out.push({ type: 'panel', id: p.getAttribute('data-panel') }); });
      return out;
    }
    function selectDir(dir) {
      var list = order(), i = -1;
      list.forEach(function (s, k) { if (sel && s.type === sel.type && s.id === sel.id) { i = k; } });
      var cur = sel && sel.type === 'panel' ? byId(sel.id) : null;
      var j = dir === 'first' ? 0 : dir === 'last' ? list.length - 1 : dir === 'next' ? Math.min(list.length - 1, i + 1) : dir === 'prev' ? Math.max(0, i - 1) : i;
      if (dir === 'main' || dir === 'side') {
        var want = dir === 'side' ? columns().right : columns().main, ps = panels(want);
        if (!ps.length) { say('No panels in the ' + (dir === 'side' ? 'side' : 'main') + ' column.'); return; }
        var from = cur ? panels(cur.parentNode).indexOf(cur) : 0;
        var target = ps[Math.min(from, ps.length - 1)];
        j = list.map(function (s) { return s.id; }).indexOf(target.getAttribute('data-panel'));
      }
      if (j < 0 || !list[j]) { return; }
      select(list[j]); focusSel();
    }
    frame.addEventListener('keydown', function (e) {
      var t = e.target, tag = (t.tagName || '').toLowerCase();
      if (tag === 'input' || tag === 'textarea' || tag === 'select' || t.isContentEditable) { return; }
      var reg = t.closest('.pnl[data-panel], [data-wb-region]');
      if (!reg || t !== reg) { return; }
      if (!sel || regionEl(sel) !== reg) { select(reg.matches('[data-wb-region]') ? { type: reg.getAttribute('data-wb-region') } : { type: 'panel', id: reg.getAttribute('data-panel') }, true); }
      if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); if (hooks.openInspector) { hooks.openInspector(); } return; }
      if (e.key === 'Escape') { select(null); return; }
      if (e.key === 'b' || e.key === 'B') { e.preventDefault(); if (hooks.menuBind) { hooks.menuBind(); } return; }
      var act = GK.interpret(e, GK.WORKBENCH);
      if (!act) { return; }
      e.preventDefault(); e.stopPropagation();
      if (act.type === 'select') { selectDir(act.dir); return; }
      if (!sel || sel.type !== 'panel') { say('Select a panel first: the title and the strip are edited in the inspector.', true); return; }
      var p = byId(sel.id), list = panels(p.parentNode), i = list.indexOf(p);
      switch (act.type) {
        case 'span': actions.resize(sel.id, { span: R.clampSpan(spanOf(p) + act.delta, 1) }); break;
        case 'height': actions.resize(sel.id, { height: R.clampHeight(Math.max(1, (heightOf(p) || R.rowsFromPixels(p.getBoundingClientRect().height, 40)) + act.delta)) }); break;
        case 'natural': actions.resize(sel.id, { height: 0 }); break;
        case 'move':
          if (i + act.step < 0 || i + act.step >= list.length) { say(title(p) + ' is already ' + (act.step < 0 ? 'first' : 'last') + ' in the ' + (areaOf(p) === 'right' ? 'side' : 'main') + ' column.'); break; }
          actions.dropped({ type: 'panel', id: sel.id }, null, { area: areaOf(p), rel: act.step < 0 ? 'before' : 'after', id: list[i + act.step].getAttribute('data-panel') }, null); break;
        case 'column':
          if (areaOf(p) === act.area) { say(title(p) + ' is already in the ' + (act.area === 'right' ? 'side' : 'main') + ' column.'); break; }
          var to = panels(act.area === 'right' ? columns().right : columns().main);
          actions.dropped({ type: 'panel', id: sel.id }, null, to.length ? { area: act.area, rel: 'before', id: to[Math.min(i, to.length - 1)].getAttribute('data-panel') } : { area: act.area }, null); break;
        case 'remove': removePanel(sel.id); break;
        default: break;
      }
    });
    /** Removes a panel (one operation, undoable) and selects the one next to it. */
    function removePanel(id) {
      var p = byId(id);
      if (!p) { return Promise.resolve(); }
      var list = panels(p.parentNode);
      return actions.remove(id, neighbour(list, list.indexOf(p)), title(p));
    }
    function movePanel(id, step) {
      var p = byId(id), list = p ? panels(p.parentNode) : [], i = list.indexOf(p);
      if (!p || i + step < 0 || i + step >= list.length) { say(p ? title(p) + ' is already ' + (step < 0 ? 'first' : 'last') + ' in its column.' : 'This panel is gone.', true); return Promise.resolve(); }
      return actions.dropped({ type: 'panel', id: id }, null, { area: areaOf(p), rel: step < 0 ? 'before' : 'after', id: list[i + step].getAttribute('data-panel') }, null);
    }
    // a right click on a panel: the things you do to a panel, without remembering keys
    frame.addEventListener('contextmenu', function (e) {
      var p = e.target.closest && e.target.closest('.pnl[data-panel]');
      if (!p || panels().indexOf(p) < 0) { return; }
      e.preventDefault();
      var id = p.getAttribute('data-panel');
      select({ type: 'panel', id: id }, true);
      WB.menu.open({ title: title(p), at: { x: e.clientX, y: e.clientY }, items: [
        { label: 'Move up', detail: 'One place earlier in its column', value: 'up' }, { label: 'Move down', detail: 'One place later in its column', value: 'down' },
        { label: 'Duplicate', detail: 'A copy right after it', value: 'copy' }, { label: 'Remove panel', detail: 'Undo brings it back', value: 'remove' }],
        onPick: function (it) {
          if (it.value === 'up') { movePanel(id, -1); } else if (it.value === 'down') { movePanel(id, 1); }
          else if (it.value === 'copy') { actions.duplicate(id); } else { removePanel(id); }
        } });
    });
    function neighbour(list, i) { var n = list[i + 1] || list[i - 1]; return n ? { type: 'panel', id: n.getAttribute('data-panel') } : null; }
    // arrows scroll the page when a panel has focus, which is not wanted here
    frame.addEventListener('keydown', function (e) { if (/^Arrow|^Home$|^End$/.test(e.key) && e.target.matches && e.target.matches('.pnl[data-panel], [data-wb-region]')) { e.preventDefault(); } }, true);

    return { select: select, selected: function () { return sel; }, ids: function () { return panels().map(function (p) { return p.getAttribute('data-panel'); }); },
             el: byId, remove: removePanel, move: movePanel, focus: focusSel, areaOf: function (id) { var p = byId(id); return p ? areaOf(p) : null; }, columns: columns, panels: panels };
  };
})();
