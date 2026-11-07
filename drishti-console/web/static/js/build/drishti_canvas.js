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
(function (g) { g.Drishti = g.Drishti || {}; })(typeof window !== 'undefined' ? window : globalThis); /* one namespace for everything the console puts on window (tools/dev/asset_prefix.py) */
/* The Design tab: the real preview with drishti-designer's layout canvas on top (a flow layout: the screen's main and side columns,
 * each panel with its span in columns and its height in rows). The markup is the one every user sees (the console's Studio partial);
 * the canvas only decorates it: a click selects a panel (the title and the strip are selected here too), a panel is dragged by its
 * heading to a new place, its right and bottom edges size it on the 12-column grid, the palette's kinds and the shape's fields are
 * dropped on it. Every change is an operation handed to WB.Actions; the page is then redrawn from the server's answer, so what you see
 * is what is saved.
 *
 * Keyboard (the designer kit's "select" keys): arrows select, Alt+arrows move, Shift+arrows size, Enter opens the inspector, Delete
 * removes, A natural height, B binds a field; a live region says what happened.
 *
 *   new WB.Canvas(frameEl, store, actions, hooks) -> {select(sel), selected(), ids(), el(id), focus()}
 *   hooks: {onSelect(sel), openInspector(), menuAdd(), menuBind(), picked()}      sel: {type: 'panel', id} | {type: 'title'} | {type: 'strip'} | null */
(function () {
  'use strict';
  var WB = window.Drishti.WB = window.Drishti.WB || {};
  var K = window.Drishti.elements.designer, R = window.Drishti.LayoutRules, GRID = R.GRID, EDGE_PX = 18;

  WB.Canvas = function (frame, store, actions, hooks) {
    var region = null, fired = null;
    var model = new K.LayoutModel({ mode: 'flow', areas: ['main', 'right'], stack: null, cols: GRID, maxH: R.MAX_H, minSpan: 1 });
    var selection = new K.Selection();
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
    function current() { return region || (selection.primary() ? { type: 'panel', id: selection.primary() } : null); }

    var canvas = new K.LayoutCanvas(frame, { model: model, selection: selection, keys: 'select', handle: '.pnl-h', hub: WB.drag.hub, label: function (it) { return it.label; },
      extraKeys: { B: { type: 'bind' }, Space: { type: 'open' } }, onAction: onAction });
    frame.setAttribute('aria-label', 'The screen being designed');

    // ---- decorating the preview --------------------------------------------------------------------------------------
    function badge(p, span, height) { var b = p.querySelector(':scope > .lay-size'); if (b) { b.textContent = span + '/12' + (height ? ' · ' + height + ' rows' : ''); } }
    function swap(p, prefix, n, keep) {
      p.className = p.className.split(/\s+/).filter(function (c) { return c.indexOf(prefix) !== 0; }).join(' ');
      if (keep) { p.classList.add(prefix + n); }
    }
    function decorate() {
      var all = panels(), map = {};
      all.forEach(function (p) {
        map[p.getAttribute('data-panel')] = p;
        if (!p.querySelector(':scope > .lay-size')) { p.appendChild(WB.el('span', 'lay-size', null, { 'aria-hidden': 'true' })); }
        badge(p, spanOf(p), heightOf(p));
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
      model.load(all.map(function (p) { return { id: p.getAttribute('data-panel'), area: areaOf(p), span: spanOf(p), h: heightOf(p), label: title(p) + ', ' + p.getAttribute('data-kind') + ' panel' }; }));
      var c = columns();
      canvas.adopt(map, { main: c.main, right: c.right });
    }
    function mark() {
      frame.querySelectorAll('[data-wb-region].wb-sel').forEach(function (e) { e.classList.remove('wb-sel'); e.removeAttribute('aria-current'); });
      var e = region && regionEl(region);
      if (e) { e.classList.add('wb-sel'); e.setAttribute('aria-current', 'true'); e.tabIndex = 0; }
    }
    function tell(s, quiet) {
      var key = s ? s.type + ':' + (s.id || '') : '';
      if (key !== fired && hooks.onSelect) { fired = key; hooks.onSelect(s); }
      var e = regionEl(s);
      if (!quiet && e) { say(s.type === 'panel' ? title(e) + ', ' + e.getAttribute('data-kind') + ' panel, ' + spanOf(e) + ' of 12 columns' : (s.type === 'title' ? 'Title' : 'Key figures strip') + ' selected'); }
    }
    /** Selects a panel, the title, the strip, or nothing. */
    function select(s, quiet) {
      if (s && s.type !== 'panel') { region = s; selection.clear(); mark(); tell(s, quiet); return; }
      region = null;
      mark();
      selection.set(s ? s.id : null, 'workbench');
      tell(s, quiet);
    }
    selection.on('change', function (c) {
      if (c.source === 'workbench' || c.source === 'prune') { if (c.source === 'prune' && !c.ids.length && !region && hooks.onSelect) { fired = ''; hooks.onSelect(null); } return; }
      if (c.primary) { region = null; mark(); tell({ type: 'panel', id: c.primary }, true); if (hooks.picked) { hooks.picked(); } }
    });
    function focusSel() { var e = regionEl(current()); if (e) { e.focus({ preventScroll: true }); } }

    /** An empty design: one large way in (the chooser of the 22 kinds). */
    function callToAction() {
      var m = WB.model(store.state.yaml), wrap = WB.el('div', 'wb-first'), none = !m.panels.length && !m.uses.length;
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
      if (p.error) { frame.appendChild(callToAction()); frame.appendChild(WB.el('p', 'bs-status bad', p.error)); canvas.adopt({}, {}); return; }
      if (!p.html) {
        frame.appendChild(callToAction());
        frame.appendChild(p.failed ? WB.el('p', 'bs-status bad wb-empty', 'The Sutra has problems that stop it from drawing: see the Problems tab.')
          : WB.el('p', 'text-muted-d wb-empty', 'Nothing to draw yet: the design needs a Sutra with panels and a sample.'));
        canvas.adopt({}, {});
        return;
      }
      frame.innerHTML = p.html;
      if (p.dropped && p.dropped.length) {
        var note = WB.el('p', 'bs-status bad wb-dropped', null, { role: 'note' });
        note.textContent = 'Not drawn: ' + p.dropped.map(function (d) { return d.panel + (d.option ? ' (' + d.option + ')' : ''); }).join(', ') + '. See the Problems tab.';
        frame.insertBefore(note, frame.firstChild);
      }
      if (window.Drishti.view) { window.Drishti.view.enhance(frame); window.Drishti.view.redraw(); }
      decorate();
      if (!panels().length) { frame.insertBefore(callToAction(), frame.firstChild); }
      if (region && !regionEl(region)) { region = null; fired = ''; if (hooks.onSelect) { hooks.onSelect(null); } }
      mark();
      if (had) { focusSel(); }
    });

    // ---- pointing: the title and the strip, a click on the background, a double click -----------------------------------------
    frame.addEventListener('click', function (e) {
      var p = e.target.closest('.pnl[data-panel]');
      if (p && panels().indexOf(p) >= 0) { p.focus({ preventScroll: true }); return; }               // the canvas selected it
      var r = e.target.closest('[data-wb-region]');
      if (r) { select({ type: r.getAttribute('data-wb-region') }); r.focus({ preventScroll: true }); if (hooks.picked) { hooks.picked(); } return; }
      if (e.target.closest('.studio-view')) { select(null); }
    });
    canvas.on('open', function () { if (hooks.openInspector) { hooks.openInspector(); } });
    frame.addEventListener('dblclick', function (e) {
      var edge = e.target.closest('[data-dd-edge="s"]');
      if (edge) { actions.resize(edge.closest('.pnl').getAttribute('data-panel'), { height: 0 }); return; }
      if (e.target.closest('[data-wb-region]') && hooks.openInspector) { hooks.openInspector(); }
    });

    // ---- drags: a panel by its heading, its edges, a kind or a field from the left ------------------------------------------------
    canvas.on('resizing', function (r) {
      var p = byId(r.id);
      if (!p) { return; }
      var span = r.edge === 'e' && r.value !== null ? r.value : spanOf(p), height = r.edge === 's' && r.value !== null ? r.value : heightOf(p);
      p.classList.toggle('resizing', r.value !== null);
      swap(p, 'c-span-', span, span < GRID); swap(p, 'c-h-', height, height > 0);
      badge(p, span, height);
      window.dispatchEvent(new Event('resize'));
    });
    model.on('commit', function (c) {                 // an edge let go: one operation; the server's answer draws the panel again
      var id = c.ids[0], was = c.before.filter(function (i) { return i.id === id; })[0], now = c.after.filter(function (i) { return i.id === id; })[0];
      if (!was || !now) { return; }
      if (now.span !== was.span) { actions.resize(id, { span: now.span }); } else if (now.h !== was.h) { actions.resize(id, { height: now.h }); }
    });
    /** The old shape of a drop place, for WB.Actions: a panel (before or after it, or on it), a column, the strip, the title. */
    function hit(x, y, t) {
      var el = document.elementFromPoint(x, y);
      if (!el || !frame.contains(el)) { return null; }
      if (t && t.id) {
        var pe = byId(t.id);
        return { type: 'panel', el: pe, id: t.id, area: t.area, rel: t.rel, side: !!t.side, rect: t.rect, edge: !!t.edge, gap: !!t.gap };
      }
      if (t) { return { type: 'column', area: t.area, rect: t.rect }; }
      if (el.closest('dl.strip')) { return { type: 'strip', el: frame.querySelector('dl.strip') }; }
      if (el.closest('.vtitle')) { return { type: 'title', el: frame.querySelector('.vtitle') }; }
      return { type: 'empty' };
    }
    canvas.on('drop', function (d) {
      var payload = d.payload.item ? { type: 'panel', id: d.payload.item } : d.payload, h = hit(d.x, d.y, d.target);
      if (!h || h.type === 'empty' || (payload.type === 'panel' && (h.type === 'title' || h.type === 'strip'))) {
        say(payload.type === 'panel' ? 'Dropped outside the columns: nothing moved.' : 'Drop on the canvas, in a column.', true);
        return;
      }
      if (payload.type === 'panel' && h.id === payload.id) { return; }
      var at = h.type === 'panel' ? { area: h.area, rel: h.rel, id: h.id } : { area: h.area || 'main' };
      actions.dropped(payload, h, at, d.payload.item ? null : { x: d.x, y: d.y });
    });

    // ---- the keys: the kit reads them, the workbench does them as operations ---------------------------------------------------------
    function order() {
      var out = [];
      if (frame.querySelector('.vtitle')) { out.push({ type: 'title' }); }
      if (frame.querySelector('dl.strip')) { out.push({ type: 'strip' }); }
      panels(columns().main).forEach(function (p) { out.push({ type: 'panel', id: p.getAttribute('data-panel') }); });
      panels(columns().right).forEach(function (p) { out.push({ type: 'panel', id: p.getAttribute('data-panel') }); });
      return out;
    }
    function selectDir(dir) {
      var list = order(), cur = current(), i = -1;
      list.forEach(function (s, k) { if (cur && s.type === cur.type && s.id === cur.id) { i = k; } });
      var pe = cur && cur.type === 'panel' ? byId(cur.id) : null;
      var j = dir === 'first' ? 0 : dir === 'last' ? list.length - 1 : dir === 'next' ? Math.min(list.length - 1, i + 1) : dir === 'prev' ? Math.max(0, i - 1) : i;
      if (dir === 'main' || dir === 'side') {
        var want = dir === 'side' ? columns().right : columns().main, ps = panels(want);
        if (!ps.length) { say('No panels in the ' + (dir === 'side' ? 'side' : 'main') + ' column.'); return; }
        var from = pe ? panels(pe.parentNode).indexOf(pe) : 0;
        j = list.map(function (s) { return s.id; }).indexOf(ps[Math.min(from, ps.length - 1)].getAttribute('data-panel'));
      }
      if (j < 0 || !list[j]) { return; }
      select(list[j]); focusSel();
    }
    function onAction(type, a) {
      var cur = current();
      if (type === 'select') { selectDir(a.dir); return true; }
      if (type === 'open') { if (hooks.openInspector) { hooks.openInspector(); } return true; }
      if (type === 'escape') { select(null); return true; }
      if (type === 'bind') { if (hooks.menuBind) { hooks.menuBind(); } return true; }
      if (['move', 'size', 'natural', 'column', 'delete', 'duplicate'].indexOf(type) < 0) { return false; }      // undo, copy ... are the page's
      if (!cur || cur.type !== 'panel') { say('Select a panel first: the title and the strip are edited in the inspector.', true); return true; }
      var p = byId(cur.id);
      if (!p) { return true; }
      var list = panels(p.parentNode), i = list.indexOf(p);
      if (type === 'size' && a.dx) { actions.resize(cur.id, { span: R.clampSpan(spanOf(p) + a.dx, 1) }); }
      else if (type === 'size') { actions.resize(cur.id, { height: R.clampHeight(Math.max(1, (heightOf(p) || R.rowsFromPixels(p.getBoundingClientRect().height, 40)) + a.dy)) }); }
      else if (type === 'natural') { actions.resize(cur.id, { height: 0 }); }
      else if (type === 'move') { movePanel(cur.id, a.dy); }
      else if (type === 'column') {
        if (areaOf(p) === a.area) { say(title(p) + ' is already in the ' + (a.area === 'right' ? 'side' : 'main') + ' column.'); return true; }
        var to = panels(a.area === 'right' ? columns().right : columns().main);
        actions.dropped({ type: 'panel', id: cur.id }, null, to.length ? { area: a.area, rel: 'before', id: to[Math.min(i, to.length - 1)].getAttribute('data-panel') } : { area: a.area }, null);
      }
      else if (type === 'delete') { removePanel(cur.id); }
      else if (type === 'duplicate') { actions.duplicate(cur.id); }
      return true;
    }
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
    canvas.on('contextmenu', function (c) {
      var p = byId(c.id);
      if (!p) { return; }
      select({ type: 'panel', id: c.id }, true);
      WB.menu.open({ title: title(p), at: { x: c.x, y: c.y }, items: [
        { label: 'Move up', detail: 'One place earlier in its column', value: 'up' }, { label: 'Move down', detail: 'One place later in its column', value: 'down' },
        { label: 'Duplicate', detail: 'A copy right after it', value: 'copy' }, { label: 'Remove panel', detail: 'Undo brings it back', value: 'remove' }],
        onPick: function (it) {
          if (it.value === 'up') { movePanel(c.id, -1); } else if (it.value === 'down') { movePanel(c.id, 1); }
          else if (it.value === 'copy') { actions.duplicate(c.id); } else { removePanel(c.id); }
        } });
    });
    function neighbour(list, i) { var n = list[i + 1] || list[i - 1]; return n ? { type: 'panel', id: n.getAttribute('data-panel') } : null; }
    // arrows scroll the page when a panel has focus, which is not wanted here
    frame.addEventListener('keydown', function (e) { if (/^Arrow|^Home$|^End$/.test(e.key) && e.target.matches && e.target.matches('.pnl[data-panel], [data-wb-region]')) { e.preventDefault(); } }, true);

    return { select: select, selected: current, ids: function () { return panels().map(function (p) { return p.getAttribute('data-panel'); }); },
             el: byId, remove: removePanel, move: movePanel, focus: focusSel, areaOf: function (id) { var p = byId(id); return p ? areaOf(p) : null; }, columns: columns, panels: panels };
  };
  WB.Canvas.EDGE_PX = EDGE_PX;
})();
