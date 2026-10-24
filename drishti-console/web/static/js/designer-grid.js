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
/* The Sutra designer's Grid mode (docs/guides/SCREEN_DESIGNER.md section 31; core/designer_grid.py). The screen as a spreadsheet: columns
   A, B, C, rows 1, 2, 3, each cell (or merged range) an area of the screen holding panels written as formulas. Picking a cell shows its
   formula in the formula bar; Enter keeps a typed formula (the server reads it; a formula that does not read is shown with its red part
   and the cell keeps what it had); suggestions come from the UI kit, the panels on the page and what they emit. Merge, split, insert and
   delete rows and columns, drag a cell onto another; arrows, Shift+arrows, Enter and Esc. After every step the live preview is drawn
   again in place (its panels swapped, no reload) and the grid is read again. */
(function () {
  'use strict';
  var root = document.querySelector('[data-grid]');
  if (!root || !root.dataset.draft) { return; }
  var draft = root.dataset.draft, kind = root.dataset.kind, id = root.dataset.id;
  var sheet = root.querySelector('[data-gd-sheet]'), msg = root.querySelector('[data-gd-msg]'), input = root.querySelector('[data-gd-formula]');
  var nameBox = root.querySelector('[data-gd-name]'), list = root.querySelector('[data-gd-suggest]'), frame = root.querySelector('[data-gd-preview]');
  var errBox = root.querySelector('[data-gd-error]'), sutra = root.querySelector('[data-gd-sutra]'), info = root.querySelector('[data-gd-info]');
  var nest = root.querySelector('[data-gd-nest]'), nestWrap = root.querySelector('[data-gd-nest-wrap]');
  var state = null, sel = { r: 0, c: 0 }, anchor = null, rev = 0, dragging = null, choices = [], pick = -1;

  function el(tag, attrs, kids) {
    var e = document.createElement(tag);
    Object.keys(attrs || {}).forEach(function (k) {
      if (k === 'text') { e.textContent = attrs[k]; } else if (attrs[k] != null && attrs[k] !== false) { e.setAttribute(k, attrs[k] === true ? '' : attrs[k]); }
    });
    (kids || []).forEach(function (c) { if (c) { e.appendChild(typeof c === 'string' ? document.createTextNode(c) : c); } });
    return e;
  }
  function api(method, url, body) {
    return fetch(url, { method: method, credentials: 'same-origin', headers: body ? { 'Content-Type': 'application/json' } : {}, body: body ? JSON.stringify(body) : undefined })
      .then(function (r) { return r.json().catch(function () { return {}; }).then(function (j) { if (!r.ok) { var e = new Error(j.detail || 'Something went wrong'); e.body = j; throw e; } return j; }); });
  }
  function say(t, ok) { msg.textContent = t || ''; msg.className = 'dz-msg' + (ok ? ' dz-ok' : ''); }
  function letters(c) { return (c >= 26 ? String.fromCharCode(64 + Math.floor(c / 26)) : '') + String.fromCharCode(65 + (c % 26)); }
  function ref(r, c) { return letters(c) + (r + 1); }

  /* ---- the cells --------------------------------------------------------------------------------------------------------------- */
  function cells() {
    var out = state.grid.cells.slice();
    if (state.grid.mode === 'flow') {                                       // a row past the end: type a formula to add a panel
      var r = out.length;
      out.push({ ref: ref(r, 0), range: ref(r, 0), r: r, c: 0, rs: 1, cs: 1, area: '', panels: [], formulas: [], formula: '', kept: [], ids: [], titles: [], grid: [], children: [] });
    }
    return out;
  }
  function rows() { return state.grid.rows + (state.grid.mode === 'flow' ? 1 : 0); }
  function cellAt(r, c) {
    return cells().filter(function (x) { return x.r <= r && r < x.r + x.rs && x.c <= c && c < x.c + x.cs; })[0];
  }
  function current() { return cellAt(sel.r, sel.c); }
  function range() {
    if (!anchor) { var x = current(); return { r0: x.r, c0: x.c, r1: x.r + x.rs - 1, c1: x.c + x.cs - 1 }; }
    return { r0: Math.min(anchor.r, sel.r), c0: Math.min(anchor.c, sel.c), r1: Math.max(anchor.r, sel.r), c1: Math.max(anchor.c, sel.c) };
  }

  function draw() {
    var g = state.grid, n = rows();
    sheet.innerHTML = '';
    sheet.style.gridTemplateColumns = '2.6rem ' + g.widths.map(function (w) { return 'minmax(8.5rem, ' + w + 'fr)'; }).join(' ');
    sheet.style.gridTemplateRows = '1.9rem repeat(' + n + ', minmax(5.2rem, auto))';
    sheet.appendChild(el('div', { 'class': 'gd-corner', 'aria-hidden': 'true' }));
    for (var c = 0; c < g.cols; c++) {
      var h = el('div', { 'class': 'gd-colh', role: 'columnheader', 'data-gd-col': String(c) }, [letters(c), el('span', { 'class': 'gd-w', text: g.mode === 'areas' ? ' · ' + g.widths[c] + '/12' : '' })]);
      h.style.gridColumn = String(c + 2); h.style.gridRow = '1';
      sheet.appendChild(h);
    }
    for (var r = 0; r < n; r++) {
      var rh = el('div', { 'class': 'gd-rowh', role: 'rowheader', text: String(r + 1) });
      rh.style.gridColumn = '1'; rh.style.gridRow = String(r + 2);
      sheet.appendChild(rh);
    }
    var rg = range();
    cells().forEach(function (x) {
      var picked = x.r <= sel.r && sel.r < x.r + x.rs && x.c <= sel.c && sel.c < x.c + x.cs;
      var inRange = anchor && x.r >= rg.r0 && x.r + x.rs - 1 <= rg.r1 && x.c >= rg.c0 && x.c + x.cs - 1 <= rg.c1;
      var body = [el('span', { 'class': 'gd-ref', text: x.range })];
      if (!x.panels.length) { body.push(el('span', { 'class': 'gd-empty', text: g.mode === 'flow' ? 'type a formula to add a panel' : 'empty' })); }
      x.panels.forEach(function (p, k) {
        body.push(el('strong', { 'class': 'gd-title', text: x.titles[k] }));
        body.push(el('code', { 'class': 'gd-f', text: x.formulas[k] }));
        var notes = [];
        if ((x.kept[k] || []).length) { notes.push('keeps ' + x.kept[k].join(', ')); }
        Object.keys(x.grid[k] || {}).forEach(function (key) { notes.push(key + ' ' + x.grid[k][key]); });
        if ((x.children[k] || []).length) { notes.push('↳ a click opens ' + x.children[k].join(', ')); }
        if (notes.length) { body.push(el('span', { 'class': 'gd-notes', text: notes.join(' · ') })); }
      });
      var cell = el('div', { 'class': 'gd-cell' + (picked ? ' gd-sel' : '') + (inRange ? ' gd-in' : '') + (x.panels.length ? '' : ' gd-blank'), role: 'gridcell',
        tabindex: picked ? '0' : '-1', 'aria-selected': picked || inRange ? 'true' : 'false', 'data-gd-cell': x.ref, 'data-gd-range': x.range,
        draggable: x.panels.length ? 'true' : null }, body);
      cell.style.gridRow = (x.r + 2) + ' / span ' + x.rs; cell.style.gridColumn = (x.c + 2) + ' / span ' + x.cs;
      cell.addEventListener('click', function (e) {
        if (e.shiftKey && state.grid.mode === 'areas') { if (!anchor) { anchor = { r: sel.r, c: sel.c }; } } else { anchor = null; }
        sel = { r: x.r, c: x.c }; select(true);
      });
      cell.addEventListener('dblclick', function () { edit(); });
      cell.addEventListener('dragstart', function (e) { dragging = x.ref; e.dataTransfer.setData('text/x-gd', x.ref); e.dataTransfer.effectAllowed = 'move'; });
      cell.addEventListener('dragend', function () { dragging = null; });
      cell.addEventListener('dragover', function (e) { if (dragging) { e.preventDefault(); cell.classList.add('gd-over'); } });
      cell.addEventListener('dragleave', function () { cell.classList.remove('gd-over'); });
      cell.addEventListener('drop', function (e) {
        cell.classList.remove('gd-over');
        if (!dragging) { return; }
        e.preventDefault();
        var from = dragging; dragging = null;
        if (from !== x.ref) { sel = { r: x.r, c: x.c }; step({ op: 'move', from: from, to: x.ref }); }
      });
      sheet.appendChild(cell);
    });
    sutra.textContent = state.yaml || '';
    var flow = g.mode === 'flow';
    root.querySelectorAll('[data-gd-merge], [data-gd-split], [data-gd-insert], [data-gd-delete]').forEach(function (b) { b.hidden = flow; });
    root.querySelector('[data-gd-make]').hidden = !flow;
    root.setAttribute('data-gd-mode', g.mode);
    root.setAttribute('data-gd-rev', String(++rev));
  }

  function select(focus) {
    var x = current(), rg = range();
    sheet.querySelectorAll('.gd-cell').forEach(function (n) {
      var on = n.getAttribute('data-gd-cell') === x.ref;
      var a = anchor && (function () { var c = cells().filter(function (q) { return q.ref === n.getAttribute('data-gd-cell'); })[0];
        return c && c.r >= rg.r0 && c.r + c.rs - 1 <= rg.r1 && c.c >= rg.c0 && c.c + c.cs - 1 <= rg.c1; })();
      n.classList.toggle('gd-sel', on); n.classList.toggle('gd-in', !!a);
      n.setAttribute('aria-selected', on || a ? 'true' : 'false'); n.setAttribute('tabindex', on ? '0' : '-1');
      if (on && focus) { n.focus(); }
    });
    nameBox.textContent = anchor ? ref(rg.r0, rg.c0) + ':' + ref(rg.r1, rg.c1) : x.range;
    input.value = x.formula || '';
    fit();
    hideError(); closeList();
    var tables = state.suggest.panels.filter(function (p) { return p.table && x.panels.indexOf(p.index) < 0; });
    nestWrap.hidden = !(x.panels.length === 1 && tables.length);
    nest.innerHTML = '';
    nest.appendChild(el('option', { value: '', text: '— (its own cell)' }));
    tables.forEach(function (p) { nest.appendChild(el('option', { value: p.id, text: (p.cell || p.id) + ' · ' + p.title })); });
  }

  function edit() { input.focus(); input.setSelectionRange(input.value.length, input.value.length); }
  function fit() { input.style.height = 'auto'; input.style.height = Math.min(input.scrollHeight + 2, 190) + 'px'; }   /* the whole formula shows, up to about eight lines */

  /* ---- steps -------------------------------------------------------------------------------------------------------------------- */
  function step(body) {
    body.kind = kind; body.id = id;
    say('Saving…', true);
    return api('POST', '/designer/grid/op/' + encodeURIComponent(draft), body)
      .then(function () { return Promise.all([load(), redraw()]); })
      .then(function () { say('Saved', true); return true; })
      .catch(function (e) {
        say('');
        showError(body.op === 'formula' ? body.text : '', e.body && e.body.start != null ? e.body : null, e.message);
        return false;
      });
  }
  function showError(text, at, why) {
    var bad = root.querySelector('[data-gd-bad]');
    bad.innerHTML = '';
    if (at && text) {
      bad.appendChild(document.createTextNode(text.slice(0, at.start)));
      bad.appendChild(el('mark', { 'class': 'gd-red', 'data-gd-red': '', text: text.slice(at.start, at.end) || ' ' }));
      bad.appendChild(document.createTextNode(text.slice(at.end)));
    }
    bad.hidden = !(at && text);
    root.querySelector('[data-gd-why]').textContent = why;
    errBox.hidden = false;
    input.setAttribute('aria-invalid', 'true');
  }
  function hideError() { errBox.hidden = true; input.removeAttribute('aria-invalid'); }

  /* the preview, drawn again in place: the new page's grid swapped into the frame (its scripts set it up), else a reload */
  function redraw() {
    var w = frame.contentWindow, d = frame.contentDocument;
    var url = '/v/' + encodeURIComponent(kind) + '/' + encodeURIComponent(id) + '?design=' + encodeURIComponent(draft) + '&embed=1';
    return fetch(url, { credentials: 'same-origin', cache: 'no-store', headers: { Accept: 'text/html' } }).then(function (r) { return r.text(); }).then(function (html) {
      var fresh = new DOMParser().parseFromString(html, 'text/html').querySelector('[data-view] .vgrid'), old = d && d.querySelector('[data-view] .vgrid');
      var needsGrid = fresh && fresh.hasAttribute('data-sgrid') && !(w && w.drishtiScreenGrid);
      var needsWire = fresh && fresh.querySelector('section.pnl[data-wire]') && !(w && w.drishtiWire);
      if (!fresh || !old || needsGrid || needsWire) { frame.setAttribute('src', url); return; }
      var next = d.importNode(fresh, true);
      old.replaceWith(next);
      if (w.drishtiScreenGrid && next.hasAttribute('data-sgrid')) { w.drishtiScreenGrid.init(next); }
      if (w.drishti && w.drishti.enhance) { w.drishti.enhance(next); }
      frame.setAttribute('data-gd-rev', String(rev));
    });
  }

  function load() {
    return api('GET', '/designer/grid/state/' + encodeURIComponent(draft)).then(function (s) {
      state = s;
      if (sel.r >= rows()) { sel.r = rows() - 1; }
      if (sel.c >= s.grid.cols) { sel.c = s.grid.cols - 1; }
      draw(); select(false);
    });
  }

  /* ---- suggestions --------------------------------------------------------------------------------------------------------------- */
  function context() {
    var v = input.value, at = input.selectionStart, start = at;
    while (start > 0 && /[\w@.\-\/$]/.test(v[start - 1])) { start--; }
    var lineStart = v.lastIndexOf('\n', start - 1) + 1, before = v.slice(lineStart, start).replace(/^\s*[A-Z]{1,2}\d{1,3}\s*=\s*/, '').replace(/^\s*=/, '');
    var head = (v.slice(lineStart).match(/^\s*(?:[A-Z]{1,2}\d{1,3}\s*=\s*)?=?\s*([\w@\/-]+)\(/) || [])[1] || '';
    return { word: v.slice(start, at), start: start, end: at, first: !before.trim(), after: (before.match(/[(,;]\s*$/) || [''])[0], head: head };
  }
  function suggest() {
    var cx = context(), w = cx.word.toLowerCase(), out = [];
    if (cx.word.charAt(0) === '@') {
      state.suggest.panels.forEach(function (p) {
        (p.emits || []).forEach(function (ev) {
          (ev.fields.length ? ev.fields : ['']).forEach(function (f) {
            var t = '@' + (p.cell || p.id) + '.' + ev.name + (f ? '.' + f : '');
            out.push({ text: t, doc: p.title + ': a click on a row (' + ev.name + ')' });
          });
        });
      });
      out.push({ text: '@parent.', doc: 'what the parent panel emitted (a panel a click opens)' });
    } else if (cx.first) {
      state.suggest.fragments.forEach(function (f) { out.push({ text: f.ref, doc: f.summary, head: true }); });
      state.suggest.kinds.forEach(function (k) { out.push({ text: k, doc: 'a panel of kind ' + k, head: true }); });
    } else if (cx.after && cx.head) {
      var frag = state.suggest.fragments.filter(function (f) { return f.ref === cx.head || f.ref.split('@')[0].split('/').pop() === cx.head; })[0];
      ((frag && frag.params) || ['id', 'title', 'rows', 'source', 'when']).forEach(function (p) { out.push({ text: p, doc: 'a parameter', param: true }); });
    }
    choices = out.filter(function (o) { return !w || o.text.toLowerCase().indexOf(w) >= 0; }).slice(0, 8);
    pick = choices.length ? 0 : -1;
    list.innerHTML = '';
    choices.forEach(function (o, k) {
      var li = el('li', { role: 'option', id: 'gdOpt' + k, 'aria-selected': k === pick ? 'true' : 'false', 'data-gd-option': o.text }, [el('code', { text: o.text }), o.doc ? el('span', { text: ' ' + o.doc }) : null]);
      li.addEventListener('mousedown', function (e) { e.preventDefault(); accept(k); });
      list.appendChild(li);
    });
    list.hidden = !choices.length || (!cx.word && !cx.after && !cx.first);
    input.setAttribute('aria-expanded', String(!list.hidden));
  }
  function closeList() { list.hidden = true; choices = []; pick = -1; input.setAttribute('aria-expanded', 'false'); }
  function accept(k) {
    var o = choices[k], cx = context();
    if (!o) { return; }
    var add = o.text + (o.head ? '(' : o.param ? ': ' : '');
    input.value = input.value.slice(0, cx.start) + add + input.value.slice(cx.end);
    var caret = cx.start + add.length;
    input.setSelectionRange(caret, caret);
    closeList(); fit();
  }
  function mark() { list.querySelectorAll('li').forEach(function (li, k) { li.setAttribute('aria-selected', k === pick ? 'true' : 'false'); }); input.setAttribute('aria-activedescendant', pick >= 0 ? 'gdOpt' + pick : ''); }

  input.addEventListener('input', function () { fit(); suggest(); });
  input.addEventListener('blur', function () { setTimeout(closeList, 150); });
  input.addEventListener('keydown', function (e) {
    if (!list.hidden && choices.length) {
      if (e.key === 'ArrowDown') { e.preventDefault(); pick = (pick + 1) % choices.length; mark(); return; }
      if (e.key === 'ArrowUp') { e.preventDefault(); pick = (pick - 1 + choices.length) % choices.length; mark(); return; }
      if (e.key === 'Tab' || (e.key === 'Enter' && !e.shiftKey)) { e.preventDefault(); accept(pick); return; }
      if (e.key === 'Escape') { e.preventDefault(); closeList(); return; }
    }
    if (e.key === 'Enter' && !e.shiftKey) {
      e.preventDefault();
      var x = current();
      if (input.value === (x.formula || '')) { select(true); return; }
      step({ op: 'formula', cell: x.ref, text: input.value }).then(function (ok) { if (ok) { select(true); } });
    } else if (e.key === 'Escape') {
      e.preventDefault(); select(true);                                    // back to the cell, the formula as it was
    }
  });

  /* ---- the keyboard on the sheet --------------------------------------------------------------------------------------------------- */
  sheet.addEventListener('keydown', function (e) {
    var moves = { ArrowUp: [-1, 0], ArrowDown: [1, 0], ArrowLeft: [0, -1], ArrowRight: [0, 1] }, d = moves[e.key];
    if (d) {
      e.preventDefault();
      var x = current();
      if (e.shiftKey && state.grid.mode === 'areas') { if (!anchor) { anchor = { r: sel.r, c: sel.c }; } } else { anchor = null; }
      var r = d[0] > 0 ? x.r + x.rs : d[0] < 0 ? x.r - 1 : sel.r, c = d[1] > 0 ? x.c + x.cs : d[1] < 0 ? x.c - 1 : sel.c;
      if (e.shiftKey) { r = sel.r + d[0]; c = sel.c + d[1]; }
      if (r >= 0 && r < rows() && c >= 0 && c < state.grid.cols) { sel = { r: r, c: c }; }
      select(true);
    } else if (e.key === 'Enter' || e.key === 'F2') {
      e.preventDefault(); edit();
    } else if (e.key === 'Escape') {
      anchor = null; select(true);
    }
  });

  /* ---- the tools ---------------------------------------------------------------------------------------------------------------------- */
  root.querySelector('[data-gd-merge]').addEventListener('click', function () {
    var rg = range();
    if (!anchor) { say('Pick a range first: Shift+click or Shift+arrows'); return; }
    anchor = null;
    step({ op: 'merge', from: ref(rg.r0, rg.c0), to: ref(rg.r1, rg.c1) });
  });
  root.querySelector('[data-gd-split]').addEventListener('click', function () { step({ op: 'split', cell: current().ref }); });
  root.querySelectorAll('[data-gd-insert]').forEach(function (b) {
    b.addEventListener('click', function () {
      var x = current(), how = b.getAttribute('data-gd-insert');
      var at = { 'row-above': x.r, 'row-below': x.r + x.rs, 'col-left': x.c, 'col-right': x.c + x.cs }[how];
      step({ op: 'insert', axis: how.indexOf('row') === 0 ? 'row' : 'col', at: at });
    });
  });
  root.querySelectorAll('[data-gd-delete]').forEach(function (b) {
    b.addEventListener('click', function () { var axis = b.getAttribute('data-gd-delete'); step({ op: 'delete', axis: axis, at: axis === 'row' ? sel.r : sel.c }); });
  });
  root.querySelector('[data-gd-make]').addEventListener('click', function () { step({ op: 'make-grid' }); });
  nest.addEventListener('change', function () {
    var x = current();
    if (nest.value) { step({ op: 'nest', index: x.panels[0], under: nest.value, place: 'detail-below' }); }
  });
  var yamlBtn = root.querySelector('[data-gd-yaml]');
  yamlBtn.addEventListener('click', function () { sutra.hidden = !sutra.hidden; yamlBtn.setAttribute('aria-expanded', String(!sutra.hidden)); yamlBtn.textContent = sutra.hidden ? 'Show the Sutra' : 'Hide the Sutra'; });
  root.querySelector('[data-gd-cancel]').addEventListener('click', function () {
    api('POST', '/designer/cancel/' + encodeURIComponent(draft)).finally(function () { location.href = '/v/' + encodeURIComponent(kind) + '/' + encodeURIComponent(id); });
  });
  root.querySelector('[data-gd-done]').addEventListener('click', function () {
    say('Keeping your page…', true);
    api('POST', '/designer/done/' + encodeURIComponent(draft), { kind: kind }).then(function () { location.href = '/v/' + encodeURIComponent(kind) + '/' + encodeURIComponent(id); })
      .catch(function (e) { say(e.message); });
  });
  load().catch(function (e) { say(e.message); });
})();
