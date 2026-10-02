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
/* Workspaces: panes are same-origin embedded views (each keeps its own live stream and keys). A link clicked in
   a pane arrives here as a message; if another pane follows that pane, the follower opens the entity, otherwise
   the pane itself navigates. Alt+1..4 focuses a pane and Alt+0 the toolbar, also from inside a pane. A view is dragged
   into a pane from the command line's suggestions (recent ones included) or from another pane's number; the dividers
   between panes are dragged (or moved with the arrow keys) to resize them. Save stores the workspace, sizes included, for the signed-in user. */
(function () {
  'use strict';
  var root = document.querySelector('[data-ws]');
  if (!root) { return; }
  var ws = JSON.parse(root.getAttribute('data-json'));
  var grid = root.querySelector('[data-grid]'), tpl = document.getElementById('paneTpl'), msg = root.querySelector('[data-msg]');
  var layoutSel = root.querySelector('[data-layout]');
  var readonly = root.hasAttribute('data-readonly');           // shared with you: look, navigate, save a copy
  var frames = [];
  function on(sel, ev, fn) { var el = root.querySelector(sel); if (el) { el.addEventListener(ev, fn); } return el; }
  if (readonly) { layoutSel.disabled = true; }

  function say(t, bad) { msg.textContent = t; msg.classList.toggle('t-bad', !!bad); }
  function src(ref) { return ref ? '/v/' + encodeURIComponent(ref.kind) + '/' + encodeURIComponent(ref.id) + '?embed=1' : 'about:blank'; }

  var live = root.querySelector('[data-ws-live]');
  function announce(t) { if (live) { live.textContent = ''; setTimeout(function () { live.textContent = t; }, 30); } }

  function render() {
    grid.className = 'ws-grid ws-' + ws.layout.replace('+', 'p');
    sized();
    layoutSel.value = ws.layout;
    grid.innerHTML = '';
    frames = [];
    ws.panes.forEach(function (p, i) {
      var node = tpl.content.firstElementChild.cloneNode(true);
      node.querySelector('.ws-num').textContent = String(i + 1);
      var input = node.querySelector('[data-pane-input]');
      input.value = p.ref ? p.ref.id : '';
      if (p.hidden) { input.placeholder = 'Not shown: you may not open this'; input.disabled = true; }
      if (readonly) { node.querySelector('[data-remove]').hidden = true; }
      input.setAttribute('aria-label', 'Entity for pane ' + (i + 1));
      var follows = node.querySelector('[data-follows]');
      ws.panes.forEach(function (q, j) {
        if (j !== i) { var o = document.createElement('option'); o.value = j; o.textContent = 'pane ' + (j + 1); follows.appendChild(o); }
      });
      follows.value = p.follows == null ? '' : String(p.follows);
      follows.addEventListener('change', function () { p.follows = follows.value === '' ? null : +follows.value; });
      node.querySelector('[data-pane-cmd]').addEventListener('submit', function (e) {
        e.preventDefault();
        fetch('/api/resolve', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ text: input.value }) })
          .then(function (r) { return r.json().then(function (b) { return { ok: r.ok, b: b }; }); })
          .then(function (res) { if (res.ok) { open(i, res.b.ref); } else { say(res.b.detail || 'Unknown entity', true); } });
      });
      node.querySelector('[data-remove]').addEventListener('click', function () {
        if (ws.panes.length <= 1) { return; }
        ws.panes.splice(i, 1);
        ws.panes.forEach(function (q) { if (q.follows === i) { q.follows = null; } else if (q.follows > i) { q.follows--; } });
        render();
      });
      var frame = node.querySelector('iframe');
      frame.title = 'Pane ' + (i + 1) + (p.title ? ': ' + p.title : '');
      frame.src = src(p.ref);
      node.querySelector('[data-open]').href = p.ref ? '/v/' + p.ref.kind + '/' + p.ref.id : '#';
      frames.push(frame);
      grid.appendChild(node);
    });
    dividers();
  }

  function open(i, ref) {
    ws.panes[i].ref = ref;
    var pane = grid.children[i];
    pane.querySelector('[data-pane-input]').value = ref.id;
    pane.querySelector('[data-open]').href = '/v/' + ref.kind + '/' + ref.id;
    frames[i].src = src(ref);
  }

  window.addEventListener('message', function (e) {
    // only this workspace's own panes (same origin, and the sender is one of its frames) may select or move the focus
    if (e.origin !== location.origin || !e.data || (e.data.type !== 'drishti:select' && e.data.type !== 'drishti:key')) { return; }
    var from = frames.findIndex(function (f) { return f.contentWindow === e.source; });
    if (from < 0) { return; }
    if (e.data.type === 'drishti:key') {                  // Alt+0..4 pressed inside a pane (app.js hands it here)
      if (typeof e.data.n === 'number' && e.data.n >= 0 && e.data.n <= 4) { toPane(e.data.n); }
      return;
    }
    var ref = { kind: e.data.kind, id: e.data.id };
    var followers = ws.panes.map(function (p, j) { return p.follows === from ? j : -1; }).filter(function (j) { return j >= 0; });
    if (followers.length) { followers.forEach(function (j) { open(j, ref); }); } else { open(from, ref); }
  });

  layoutSel.addEventListener('change', function () { ws.layout = layoutSel.value; delete ws.sizes; render(); });
  on('[data-add]', 'click', function () {
    if (ws.panes.length >= 4) { say('A workspace has at most four panes.', true); return; }
    ws.panes.push({ ref: null, follows: null, title: '' });
    render();
  });
  function save(name) {
    var body = { layout: ws.layout, panes: ws.panes.map(function (p) { return { ref: p.ref, follows: p.follows, title: p.title || '' }; }) };
    if (ws.sizes) { body.sizes = ws.sizes; }
    return fetch('/w/api/' + encodeURIComponent(name), { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body) })
      .then(function (r) { return r.json().then(function (b) { return { ok: r.ok, b: b }; }); })
      .then(function (res) {
        if (res.ok) { say('Saved “' + name + '”.'); if (name !== root.dataset.name) { location.href = '/w/' + encodeURIComponent(name); } }
        else { say((res.b.code || 'Error') + ': ' + res.b.detail, true); }
      });
  }
  on('[data-save]', 'click', function () { save(root.dataset.name); });
  on('[data-saveas]', 'click', function () {
    var n = window.prompt(readonly ? 'Save a copy as (your own workspace):' : 'Save workspace as:', root.dataset.name);
    if (n) { save(n.trim()); }
  });
  // sharing (the owner): everyone, or roles and people; they see it as it is kept, read-only
  var shareForm = root.querySelector('[data-share-form]');
  on('[data-share-open]', 'click', function () { shareForm.hidden = !shareForm.hidden; });
  function list(v) { return v.split(',').map(function (x) { return x.trim(); }).filter(Boolean); }
  function share(body) {
    return fetch('/w/api/' + encodeURIComponent(root.dataset.name) + '/share', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body) })
      .then(function (r) { return r.json().then(function (b) { return { ok: r.ok, b: b }; }); });
  }
  if (shareForm) {
    shareForm.addEventListener('submit', function (e) {
      e.preventDefault();
      share({ everyone: shareForm.everyone.checked, roles: list(shareForm.roles.value), users: list(shareForm.users.value) }).then(function (res) {
        if (!res.ok) { say((res.b.code || 'Error') + ': ' + res.b.detail, true); return; }
        say('Shared “' + root.dataset.name + '”' + (res.b.everyone ? ' with everyone.' : '.'));
        root.querySelector('[data-share-stop]').hidden = false;
        root.querySelector('[data-share-open]').textContent = 'Shared';
      });
    });
    on('[data-share-stop]', 'click', function () {
      share({ stop: true }).then(function (res) {
        if (!res.ok) { say((res.b.code || 'Error') + ': ' + res.b.detail, true); return; }
        say('No longer shared.');
        root.querySelector('[data-share-stop]').hidden = true;
        root.querySelector('[data-share-open]').textContent = 'Share…';
      });
    });
  }
  on('[data-delete]', 'click', function () {
    if (!window.confirm('Delete workspace “' + root.dataset.name + '”?')) { return; }
    fetch('/w/api/' + encodeURIComponent(root.dataset.name) + '/delete', { method: 'POST' }).then(function () { location.href = '/w'; });
  });
  // ---- keys: Alt+1..4 moves to a pane, Alt+0 back to the toolbar, wherever the focus is (UX-07). Inside a pane the keys
  // reach the pane's own document, which hands them here (app.js, a same-origin message checked above).
  function toPane(n) {
    if (n === 0) {
      var first = root.querySelector('.ws-bar a[href], .ws-bar button:not([hidden]):not([disabled]), .ws-bar select');
      if (first) { first.focus(); announce('Workspace toolbar'); }
      return !!first;
    }
    var pane = grid.querySelectorAll(':scope > .ws-pane')[n - 1];
    if (!pane) { return false; }
    var input = pane.querySelector('[data-pane-input]');
    if (ws.panes[n - 1] && ws.panes[n - 1].ref) { pane.focus(); frames[n - 1].focus(); }
    else if (input && !input.disabled) { input.focus(); }      // an empty pane: where its command is typed
    else { pane.focus(); }
    announce('Pane ' + n + (ws.panes[n - 1] && ws.panes[n - 1].title ? ': ' + ws.panes[n - 1].title : ''));
    return true;
  }
  document.addEventListener('keydown', function (e) {
    if (!e.altKey || e.ctrlKey || e.metaKey) { return; }
    var m = /^Digit([0-4])$/.exec(e.code || '') || /^([0-4])$/.exec(e.key || '');   // e.code: Alt+digit types a symbol on a Mac
    if (m && toPane(+m[1])) { e.preventDefault(); }
  });

  // ---- dividers: drag (or arrow keys) to share the width or height between two neighbouring columns or rows ----------
  var TRACKS = { '2col': [2, 1], '3col': [3, 1], '2x2': [2, 2], '1+2': [2, 2] };
  var DEFAULTS = { '1+2': { cols: [3, 2] } };
  function weights(axis) {
    var n = TRACKS[ws.layout][axis === 'cols' ? 0 : 1];
    var w = ws.sizes && ws.sizes[axis];
    if (w && w.length === n) { return w.slice(); }
    var d = DEFAULTS[ws.layout] && DEFAULTS[ws.layout][axis];
    return d ? d.slice() : Array.apply(null, Array(n)).map(function () { return 1; });
  }
  function sized() {
    var on = !!ws.sizes;
    grid.classList.toggle('ws-sized', on);
    if (on) {
      grid.style.setProperty('--ws-cols', weights('cols').map(function (w) { return w + 'fr'; }).join(' '));
      grid.style.setProperty('--ws-rows', TRACKS[ws.layout][1] > 1 ? weights('rows').map(function (w) { return w + 'fr'; }).join(' ') : 'auto');
    }
  }
  function tracks(axis) {                       // the grid's columns (or rows) as [start, size] in pixels, from its own box
    var cs = getComputedStyle(grid), list = (axis === 'cols' ? cs.gridTemplateColumns : cs.gridTemplateRows).split(' ').map(parseFloat);
    var gap = parseFloat(axis === 'cols' ? cs.columnGap : cs.rowGap) || 0, at = parseFloat(axis === 'cols' ? cs.paddingLeft : cs.paddingTop) || 0;
    return list.map(function (size) { var t = [at, size, gap]; at += size + gap; return t; });
  }
  function dividers() {
    grid.querySelectorAll('.ws-div').forEach(function (d) { d.remove(); });
    if (readonly || !window.matchMedia('(min-width: 901px)').matches) { return; }
    ['cols', 'rows'].forEach(function (axis) {
      var t = tracks(axis);
      if (TRACKS[ws.layout][axis === 'cols' ? 0 : 1] < 2 || t.length < 2) { return; }
      for (var k = 0; k < t.length - 1; k++) {
        var d = document.createElement('button');
        d.type = 'button';
        d.className = 'ws-div ws-div-' + (axis === 'cols' ? 'v' : 'h');
        d.setAttribute('role', 'separator');
        d.setAttribute('aria-orientation', axis === 'cols' ? 'vertical' : 'horizontal');
        d.setAttribute('aria-label', (axis === 'cols' ? 'Divider between columns ' : 'Divider between rows ') + (k + 1) + ' and ' + (k + 2));
        d.setAttribute('data-axis', axis);
        d.setAttribute('data-k', String(k));
        d.title = 'Drag to resize (or use the arrow keys); double-click: equal sizes';
        place(d, axis, k, t);
        grid.appendChild(d);
      }
    });
  }
  function place(d, axis, k, t) {
    var edge = t[k][0] + t[k][1] + t[k][2] / 2 - 6, w = weights(axis);
    var share = Math.round(100 * w[k] / (w[k] + w[k + 1]));
    d.setAttribute('aria-valuenow', String(share));
    d.setAttribute('aria-valuemin', '10');
    d.setAttribute('aria-valuemax', '90');
    if (axis === 'cols') { d.style.left = edge + 'px'; d.style.top = '0px'; d.style.bottom = '0px'; }
    else {
      var cols = tracks('cols'), from = ws.layout === '1+2' ? cols[1][0] : cols[0][0];   // 1+2: only the right column is split
      d.style.top = edge + 'px'; d.style.left = from + 'px'; d.style.width = (grid.clientWidth - from - (parseFloat(getComputedStyle(grid).paddingRight) || 0)) + 'px';
    }
  }
  function share(axis, k, firstPart) {          // gives track k firstPart (0..1) of what k and k+1 have together
    var w = weights(axis), sum = w[k] + w[k + 1];
    firstPart = Math.max(0.1, Math.min(0.9, firstPart));
    w[k] = Math.round(sum * firstPart * 1000) / 1000;
    w[k + 1] = Math.round((sum - w[k]) * 1000) / 1000;
    ws.sizes = ws.sizes || {};
    ws.sizes[axis] = w;
    if (axis === 'cols' && !ws.sizes.rows && TRACKS[ws.layout][1] > 1) { ws.sizes.rows = weights('rows'); }
    if (axis === 'rows' && !ws.sizes.cols) { ws.sizes.cols = weights('cols'); }
    sized();
    requestAnimationFrame(function () {
      grid.querySelectorAll('.ws-div').forEach(function (d) { place(d, d.getAttribute('data-axis'), +d.getAttribute('data-k'), tracks(d.getAttribute('data-axis'))); });
    });
  }
  var sizing = null;
  grid.addEventListener('pointerdown', function (e) {
    var d = e.target.closest('.ws-div');
    if (!d || (e.pointerType === 'mouse' && e.button !== 0)) { return; }
    e.preventDefault();
    d.setPointerCapture(e.pointerId);
    d.classList.add('active');
    grid.querySelectorAll('iframe').forEach(function (f) { f.style.pointerEvents = 'none'; });
    var axis = d.getAttribute('data-axis'), k = +d.getAttribute('data-k'), t = tracks(axis);
    sizing = { d: d, axis: axis, k: k, start: t[k][0], total: t[k][1] + t[k + 1][1], id: e.pointerId };
  });
  grid.addEventListener('pointermove', function (e) {
    if (!sizing || e.pointerId !== sizing.id) { return; }
    var r = grid.getBoundingClientRect(), pos = sizing.axis === 'cols' ? e.clientX - r.left : e.clientY - r.top;
    share(sizing.axis, sizing.k, (pos - sizing.start) / sizing.total);
  });
  function endSizing() {
    if (!sizing) { return; }
    sizing.d.classList.remove('active');
    grid.querySelectorAll('iframe').forEach(function (f) { f.style.pointerEvents = ''; });
    var w = weights(sizing.axis), k = sizing.k;
    announce('Now ' + Math.round(100 * w[k] / (w[k] + w[k + 1])) + ' to ' + Math.round(100 * w[k + 1] / (w[k] + w[k + 1])) + '. Save to keep it.');
    sizing = null;
  }
  grid.addEventListener('pointerup', endSizing);
  grid.addEventListener('pointercancel', endSizing);
  grid.addEventListener('dblclick', function (e) {
    var d = e.target.closest('.ws-div');
    if (d) { share(d.getAttribute('data-axis'), +d.getAttribute('data-k'), 0.5); announce('Equal sizes. Save to keep it.'); }
  });
  grid.addEventListener('keydown', function (e) {
    var d = e.target.closest && e.target.closest('.ws-div');
    if (!d) { return; }
    var axis = d.getAttribute('data-axis'), k = +d.getAttribute('data-k'), w = weights(axis), now = w[k] / (w[k] + w[k + 1]);
    var less = axis === 'cols' ? 'ArrowLeft' : 'ArrowUp', more = axis === 'cols' ? 'ArrowRight' : 'ArrowDown';
    if (e.key !== less && e.key !== more && e.key !== 'Home') { return; }
    e.preventDefault();
    share(axis, k, e.key === 'Home' ? 0.5 : now + (e.key === more ? 0.05 : -0.05));
    w = weights(axis);
    announce(Math.round(100 * w[k] / (w[k] + w[k + 1])) + ' to ' + Math.round(100 * w[k + 1] / (w[k] + w[k + 1])));
  });
  window.addEventListener('resize', function () { requestAnimationFrame(dividers); });

  // ---- drag a view into a pane: from the command line's suggestions, or another pane's number ----------------------
  var moving = null;
  function paneAt(x, y) {
    var el = document.elementFromPoint(x, y), pane = el && el.closest('.ws-pane');
    return pane && pane.parentNode === grid ? Array.prototype.indexOf.call(grid.querySelectorAll(':scope > .ws-pane'), pane) : -1;
  }
  function begin(ref, label, e, from) {
    var ghost = document.createElement('div');
    ghost.className = 'lay-ghost';
    ghost.textContent = '⠿ ' + label;
    document.body.appendChild(ghost);
    grid.classList.add('ws-dragging');
    moving.on = true; moving.ghost = ghost; moving.ref = ref; moving.label = label; moving.from = from;
    follow(e);
  }
  function follow(e) {
    moving.ghost.style.transform = 'translate(' + (e.clientX + 14) + 'px,' + (e.clientY + 14) + 'px)';
    var i = paneAt(e.clientX, e.clientY);
    grid.querySelectorAll(':scope > .ws-pane').forEach(function (p, j) { p.querySelector('.ws-drop').classList.toggle('over', j === i && j !== moving.from); });
  }
  function finish(e, drop) {
    var m = moving;
    moving = null;
    if (!m) { return; }
    if (!m.on) { if (drop && m.click) { m.click(); } return; }
    m.ghost.remove();
    grid.classList.remove('ws-dragging');
    grid.querySelectorAll('.ws-drop.over').forEach(function (d) { d.classList.remove('over'); });
    var i = drop ? paneAt(e.clientX, e.clientY) : -1;
    if (i >= 0 && i !== m.from && !(ws.panes[i] && ws.panes[i].hidden)) {
      open(i, m.ref);
      say('Pane ' + (i + 1) + ' shows ' + m.label + '. Save to keep it.');
      announce('Pane ' + (i + 1) + ' shows ' + m.label);
    }
  }
  function watch(e, start) {
    moving = start;
    moving.x = e.clientX; moving.y = e.clientY; moving.id = e.pointerId; moving.on = false;
    // the pointer will cross panes (other documents): capture it here so every move and the release come to this page
    try { root.setPointerCapture(e.pointerId); } catch (err) { /* a pointer that has gone already */ }
  }
  document.addEventListener('pointerdown', function (e) {
    if (e.pointerType === 'mouse' && e.button !== 0) { return; }
    var li = e.target.closest('.cmd-list li[data-ref]');
    if (li) {                                    // a suggestion: drag it into a pane, or (no drag) open it as before
      e.preventDefault();
      var i = +li.getAttribute('data-i');
      watch(e, { ref: { kind: li.getAttribute('data-kind'), id: li.getAttribute('data-id') }, label: li.getAttribute('data-label'), from: -1,
        click: function () { if (window.drishtiCommand) { window.drishtiCommand.go(i); } } });
      return;
    }
    var num = e.target.closest('.ws-num');
    if (num && grid.contains(num)) {
      var from = Array.prototype.indexOf.call(grid.querySelectorAll(':scope > .ws-pane'), num.closest('.ws-pane'));
      var p = ws.panes[from];
      if (!p || !p.ref) { return; }
      e.preventDefault();
      watch(e, { ref: { kind: p.ref.kind, id: p.ref.id }, label: p.ref.id, from: from });
    }
  }, true);
  document.addEventListener('pointermove', function (e) {
    if (!moving || e.pointerId !== moving.id) { return; }
    if (!moving.on && Math.abs(e.clientX - moving.x) + Math.abs(e.clientY - moving.y) > 6) {
      if (window.drishtiCommand && moving.from < 0) { window.drishtiCommand.close(); }
      begin(moving.ref, moving.label, e, moving.from);
    } else if (moving.on) { follow(e); }
  });
  document.addEventListener('pointerup', function (e) { if (moving && e.pointerId === moving.id) { finish(e, true); } });
  document.addEventListener('pointercancel', function (e) { if (moving && e.pointerId === moving.id) { finish(e, false); } });
  document.addEventListener('keydown', function (e) { if (e.key === 'Escape' && moving) { finish(e, false); } });

  render();
})();
