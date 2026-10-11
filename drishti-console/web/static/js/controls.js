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
/* Controls of a Sutra (RUPAKA.md 8.1; RACHANA_REFERENCE.md "Controls"): dropdowns, multi-selects, toggles, date and number ranges, sliders, top-n and
   search, drawn from the view's `data-controls` (the server's ViewModel.controls) in the strip, a side bar or a panel's header.
   Changing one asks the server for only the panels that read it (GET .../panels?panels=a,b&ctl.x=...), swaps them in place, brings the
   other controls up to date (a cascading child's options, a corrected value), writes the values to the address (?ctl.leg=L2) and tells
   the live channel (event drishti:controls) so ticks keep the filter. A click on a row or bar of a panel with `on-select` sets a control.
   Keyboard: every control is a native element (select, input, button, details) or a radio group with arrow keys; 44 px on touch.
   `window.drishtiModules.controls.init(root, options)` starts it on a document or a shadow root (the console, <drishti-view>):
   options.view (the .view element), options.panelsUrl(query) -> url, options.fetchJson(url) -> Promise, options.afterSwap(panel),
   options.urlState (write the address; default true on a page), options.scope (remembering), options.initial (ctl.* text to start from). */
(function () {
  'use strict';
  var me = document.currentScript, auto = !(me && me.hasAttribute('data-manual'));
  var reg = (window.drishtiModules = window.drishtiModules || {});
  var SEARCH_PAUSE = 250;

  function el(tag, cls, text, attrs) {
    var e = document.createElement(tag);
    if (cls) { e.className = cls; }
    if (text != null) { e.textContent = text; }
    if (attrs) { Object.keys(attrs).forEach(function (k) { e.setAttribute(k, attrs[k]); }); }
    return e;
  }
  function parse(text, dflt) { try { return text ? JSON.parse(text) : dflt; } catch (e) { return dflt; } }
  var uid = 0;

  function init(root, options) {
    options = options || {};
    var view = options.view || (root.querySelector ? root.querySelector('[data-view]') : null);
    if (!view) { return null; }
    var defs = parse(view.getAttribute('data-controls'), []);
    var selects = parse(view.getAttribute('data-selects'), []);
    if (!defs.length) { return null; }
    var byId = {}, ui = {}, cur = {}, touched = {}, undo = [], pending = {}, timer = 0, token = 0, ctl = null, status, memo = options.scope || '';
    var urlState = options.urlState !== false && root === document;
    var PFX = options.idPrefix || 'p-';                // the panels' id prefix (the workbench's small preview rewrites the ids it draws)
    var rewrite = options.rewrite || function (h) { return h; };
    defs.forEach(function (d) { byId[d.id] = d; cur[d.id] = d.value || ''; });
    function on(t, type, fn, opt) { t.addEventListener(type, fn, opt); undo.push(function () { t.removeEventListener(type, fn, opt); }); }

    // ---- where the values came from: the address (or the element's attribute) says which the reader had set ------------------------
    var start = options.initial != null ? new URLSearchParams(options.initial) : new URLSearchParams(location.search);
    defs.forEach(function (d) { if (start.has('ctl.' + d.id)) { touched[d.id] = true; } });

    // ---- the pieces -------------------------------------------------------------------------------------------------------------------
    status = el('span', 'visually-hidden', '', { role: 'status', 'aria-live': 'polite', 'data-ctl-status': '' });
    var bars = {};
    function bar(place) {
      if (bars[place]) { return bars[place]; }
      var b;
      if (place === 'side') {
        b = el('aside', 'ctl-bar ctl-side', null, { 'aria-label': 'Filters', 'data-ctl-bar': 'side' });
        var grid = view.querySelector('.vgrid');
        if (grid) { grid.insertBefore(b, grid.firstChild); grid.classList.add('ctl-has-side'); } else { view.appendChild(b); }
      } else {
        b = el('div', 'ctl-bar ctl-strip', null, { role: 'group', 'aria-label': 'View controls', 'data-ctl-bar': 'strip' });
        var head = view.querySelector('.vhead');
        (head || view).appendChild(b);
      }
      bars[place] = b;
      return b;
    }
    function holder(d) {
      if (d.placement === 'header' && d.panel) {
        var h = view.querySelector('#' + PFX + CSS.escape(d.panel) + ' .pnl-h');
        if (h) { var box = h.querySelector('.ctl-head') || h.insertBefore(el('span', 'ctl-head', null, { role: 'group', 'aria-label': 'Controls of this panel' }), h.querySelector('.pnl-code')); return box; }
      }
      return bar(d.placement === 'side' ? 'side' : 'strip');
    }

    // ---- one control ------------------------------------------------------------------------------------------------------------------
    function field(d) {
      var wrap = el('div', 'ctl ctl-' + d.kind, null, { 'data-ctl': d.id });
      var id = 'ctl-' + (++uid);
      var label = el('label', 'ctl-l', d.label || d.id, { for: id });
      var set = function () {};            // updates the widget to the current value
      var focusEl = null;
      if (d.kind === 'dropdown' || d.kind === 'cascading' || d.kind === 'top-n') {
        var s = el('select', 'ctl-in', null, { id: id });
        if (!d.required && d.kind !== 'top-n') { s.appendChild(el('option', null, '(all)', { value: '' })); }
        (d.options || []).forEach(function (o) { s.appendChild(el('option', null, o.label || o.value, { value: o.value })); });
        s.value = cur[d.id];
        s.addEventListener('change', function () { change(d.id, s.value); });
        wrap.appendChild(label); wrap.appendChild(s); focusEl = s;
        set = function () { s.value = cur[d.id]; };
      } else if (d.kind === 'multi-select') {
        wrap = el('div', 'ctl ctl-multi', null, { 'data-ctl': d.id });
        var det = el('details', 'ctl-pop'), sum = el('summary', 'ctl-sum', null, { id: id });
        var boxes = [];
        det.appendChild(sum);
        (d.options || []).forEach(function (o) {
          var l = el('label', 'ctl-item'), c = el('input', null, null, { type: 'checkbox', value: o.value, 'data-text': o.label || o.value });
          l.appendChild(c); l.appendChild(el('span', null, o.label || o.value)); det.appendChild(l); boxes.push(c);
        });
        var ms = reg.multiselect.decorate(det, { label: d.label || d.id, boxes: boxes, onChange: function () {
          change(d.id, boxes.filter(function (x) { return x.checked; }).map(function (x) { return x.value; }).join(',')); } });
        wrap.appendChild(el('span', 'ctl-l', d.label || d.id, { id: id + '-l' })); wrap.appendChild(det); focusEl = sum;
        set = function () {
          var chosen = cur[d.id] ? cur[d.id].split(',') : [];
          boxes.forEach(function (x) { x.checked = chosen.indexOf(x.value) >= 0; });
          ms.refresh();
        };
      } else if (d.kind === 'toggle' && (d.options || []).length === 2) {
        var sw = el('button', 'ctl-switch', null, { type: 'button', role: 'switch', id: id });
        sw.addEventListener('click', function () { var o = d.options; change(d.id, cur[d.id] === o[1].value ? o[0].value : o[1].value); });
        wrap.appendChild(label); wrap.appendChild(sw); focusEl = sw;
        set = function () {
          var on = cur[d.id] === d.options[1].value;
          sw.setAttribute('aria-checked', on ? 'true' : 'false');
          sw.textContent = (d.options.filter(function (o) { return o.value === cur[d.id]; })[0] || d.options[0]).label;
          sw.setAttribute('aria-label', (d.label || d.id) + ': ' + sw.textContent + ' (switch to ' + d.options[on ? 0 : 1].label + ')');
        };
      } else if (d.kind === 'toggle' || d.kind === 'segmented') {
        var grp = el('div', 'ctl-seg', null, { role: 'radiogroup', 'aria-labelledby': id + '-l' }), btns = [];
        wrap.appendChild(el('span', 'ctl-l', d.label || d.id, { id: id + '-l' }));
        (d.options || []).forEach(function (o, i) {
          var b = el('button', 'ctl-seg-b', o.label || o.value, { type: 'button', role: 'radio', 'data-value': o.value });
          b.addEventListener('click', function () { change(d.id, o.value); });
          b.addEventListener('keydown', function (e) {
            var k = e.key === 'ArrowRight' || e.key === 'ArrowDown' ? 1 : e.key === 'ArrowLeft' || e.key === 'ArrowUp' ? -1 : 0;
            if (!k) { return; }
            e.preventDefault();
            var n = btns[(i + k + btns.length) % btns.length];
            n.focus(); change(d.id, n.getAttribute('data-value'));
          });
          btns.push(b); grp.appendChild(b);
        });
        wrap.appendChild(grp); focusEl = btns[0];
        set = function () {
          var at = btns.some(function (b) { return b.getAttribute('data-value') === cur[d.id]; }) ? cur[d.id] : null;
          btns.forEach(function (b, i) {
            var on = b.getAttribute('data-value') === at;
            b.setAttribute('aria-checked', on ? 'true' : 'false'); b.tabIndex = on || (at == null && i === 0) ? 0 : -1;
          });
        };
      } else if (d.kind === 'date') {
        var di = el('input', 'ctl-in', null, { type: 'date', id: id });
        di.addEventListener('change', function () { change(d.id, di.value); });
        di.addEventListener('input', function () { change(d.id, di.value); });
        wrap.appendChild(label); wrap.appendChild(di); focusEl = di;
        set = function () { di.value = cur[d.id]; };
      } else if (d.kind === 'slider') {
        // a slider: one thumb, or two (d.range, value from..to). Keys: arrows (one step), PageUp / PageDown (ten steps), Home, End; a drag with
        // a mouse, finger or pen. Each thumb is role=slider with aria-valuemin / -max / -now / -valuetext; the thumbs are 44 px on touch.
        wrap = el('div', 'ctl ctl-slider', null, { 'data-ctl': d.id, role: 'group', 'aria-labelledby': id + '-l' });
        wrap.appendChild(el('span', 'ctl-l', d.label || d.id, { id: id + '-l' }));
        var lo = parseFloat(d.min), hi = parseFloat(d.max), stp = parseFloat(d.step) > 0 ? parseFloat(d.step) : 1, ready = isFinite(lo) && isFinite(hi) && hi > lo;
        var track = el('div', 'ctl-sl-track', null, { 'data-ctl-track': '' }), fill = el('div', 'ctl-sl-fill'), out = el('output', 'ctl-sl-out', null, { for: id });
        var thumbs = (d.range ? [0, 1] : [0]).map(function (i) {
          var t = el('div', 'ctl-sl-thumb', null, { role: 'slider', tabindex: ready ? '0' : '-1', 'data-thumb': String(i),
            'aria-orientation': 'horizontal', 'aria-label': (d.label || d.id) + (d.range ? (i ? ' to' : ' from') : '') });
          if (i === 0) { t.id = id; }
          if (ready) { t.setAttribute('aria-valuemin', String(lo)); t.setAttribute('aria-valuemax', String(hi)); } else { t.setAttribute('aria-disabled', 'true'); }
          return t;
        });
        track.appendChild(fill); thumbs.forEach(function (t) { track.appendChild(t); });
        wrap.appendChild(track); wrap.appendChild(out);
        function slVals() {
          var p = (cur[d.id] || '').split('..'), a0 = parseFloat(p[0]), b0 = parseFloat(p[1]);
          if (d.range) { return [isFinite(a0) ? a0 : lo, isFinite(b0) ? b0 : hi]; }
          return [isFinite(a0) ? a0 : lo];
        }
        function slPct(v) { return ready ? Math.max(0, Math.min(100, (v - lo) / (hi - lo) * 100)) : 0; }
        function slSnap(v) {
          v = Math.max(lo, Math.min(hi, v));
          return Math.min(hi, Math.round((lo + Math.round((v - lo) / stp) * stp) * 1e9) / 1e9);
        }
        function slText(v) { return String(Math.round(v * 1e9) / 1e9); }
        function slPush(vals, pause) { change(d.id, d.range ? slText(Math.min(vals[0], vals[1])) + '..' + slText(Math.max(vals[0], vals[1])) : slText(vals[0]), pause); }
        function slMove(i, v, pause) {
          var vals = slVals(); v = slSnap(v);
          if (d.range) { v = i === 0 ? Math.min(v, vals[1]) : Math.max(v, vals[0]); }
          vals[i] = v; slPush(vals, pause);
        }
        thumbs.forEach(function (t, i) {
          t.addEventListener('keydown', function (e) {
            var v = slVals()[i], k = e.key, nv = null;
            if (!ready) { return; }
            if (k === 'ArrowRight' || k === 'ArrowUp') { nv = v + stp; } else if (k === 'ArrowLeft' || k === 'ArrowDown') { nv = v - stp; }
            else if (k === 'PageUp') { nv = v + stp * 10; } else if (k === 'PageDown') { nv = v - stp * 10; }
            else if (k === 'Home') { nv = d.range && i === 1 ? slVals()[0] : lo; } else if (k === 'End') { nv = d.range && i === 0 ? slVals()[1] : hi; }
            if (nv == null) { return; }
            e.preventDefault(); slMove(i, nv, 0);
          });
          t.addEventListener('pointerdown', function (e) {
            if (!ready) { return; }
            e.preventDefault(); t.focus(); t.setPointerCapture(e.pointerId);
            var r = track.getBoundingClientRect();
            function to(ev, pause) { slMove(i, lo + Math.max(0, Math.min(1, (ev.clientX - r.left) / (r.width || 1))) * (hi - lo), pause); }
            function drag(ev) { to(ev, 120); }
            function drop(ev) { t.removeEventListener('pointermove', drag); t.removeEventListener('pointerup', drop); t.removeEventListener('pointercancel', drop); to(ev, 0); }
            t.addEventListener('pointermove', drag); t.addEventListener('pointerup', drop); t.addEventListener('pointercancel', drop);
          });
        });
        track.addEventListener('pointerdown', function (e) {                // a press on the track moves the nearest thumb there, then it can be dragged
          if (!ready || e.target !== track && e.target !== fill) { return; }
          var r = track.getBoundingClientRect(), v = lo + Math.max(0, Math.min(1, (e.clientX - r.left) / (r.width || 1))) * (hi - lo), vals = slVals();
          var i = thumbs.length > 1 && Math.abs(v - vals[1]) < Math.abs(v - vals[0]) ? 1 : 0;
          slMove(i, v, 0); thumbs[i].focus();
        });
        focusEl = thumbs[0];
        set = function () {
          var vals = slVals();
          vals.forEach(function (v, i) {
            thumbs[i].style.setProperty('--ctl-pos', slPct(v) + '%');
            thumbs[i].setAttribute('aria-valuenow', slText(v)); thumbs[i].setAttribute('aria-valuetext', slText(v));
          });
          fill.style.setProperty('--ctl-from', (d.range ? slPct(vals[0]) : 0) + '%'); fill.style.setProperty('--ctl-to', slPct(vals[vals.length - 1]) + '%');
          out.textContent = ready ? (d.range ? slText(vals[0]) + ' – ' + slText(vals[1]) : slText(vals[0])) : 'no bounds';
        };
      } else if (d.kind === 'date-range' || d.kind === 'number-range') {
        var isDate = d.kind === 'date-range';
        wrap = el('div', 'ctl ctl-' + d.kind, null, { 'data-ctl': d.id, role: 'group', 'aria-labelledby': id + '-l' });
        wrap.appendChild(el('span', 'ctl-l', d.label || d.id, { id: id + '-l' }));
        var a = el('input', 'ctl-in ctl-from', null, { type: isDate ? 'date' : 'number', id: id, 'aria-label': (d.label || d.id) + ' from', step: 'any' }),
          z = el('input', 'ctl-in ctl-to', null, { type: isDate ? 'date' : 'number', 'aria-label': (d.label || d.id) + ' to', step: 'any' });
        if (!isDate) {
          if (d.min != null) { a.placeholder = 'min ' + d.min; a.min = d.min; z.min = d.min; }
          if (d.max != null) { z.placeholder = 'max ' + d.max; a.max = d.max; z.max = d.max; }
        }
        function join(pause) { change(d.id, a.value || z.value ? a.value + '..' + z.value : '', pause); }
        [a, z].forEach(function (x) {
          x.addEventListener('change', function () { join(0); });
          x.addEventListener('input', function () { join(isDate ? 0 : SEARCH_PAUSE); });          // typing a number counts when the person pauses
        });
        wrap.appendChild(a); wrap.appendChild(el('span', 'ctl-dash', '–', { 'aria-hidden': 'true' })); wrap.appendChild(z);
        var preset = null;
        if (isDate && (d.options || []).length) {
          preset = el('select', 'ctl-in ctl-preset', null, { 'aria-label': (d.label || d.id) + ' presets' });
          preset.appendChild(el('option', null, 'Range…', { value: '' }));
          d.options.forEach(function (o) { preset.appendChild(el('option', null, o.label, { value: o.value })); });
          preset.addEventListener('change', function () { if (preset.value) { change(d.id, preset.value); preset.value = ''; } });
          wrap.appendChild(preset);
        }
        focusEl = a;
        set = function () { var p = (cur[d.id] || '').split('..'); a.value = p[0] || ''; z.value = p[1] || ''; };
      } else {   // search
        var q = el('input', 'ctl-in', null, { type: 'search', id: id, autocomplete: 'off', placeholder: 'contains…' });
        q.addEventListener('input', function () { change(d.id, q.value, SEARCH_PAUSE); });
        wrap.appendChild(label); wrap.appendChild(q); focusEl = q;
        set = function () { if (document.activeElement !== q && (q.getRootNode() === document ? document.activeElement : q.getRootNode().activeElement) !== q) { q.value = cur[d.id]; } };
      }
      if (d.truncated) { wrap.appendChild(el('span', 'ctl-note', 'first ' + (d.options || []).length + ' shown')); }
      set();
      return { wrap: wrap, set: set, focus: function () { if (focusEl) { focusEl.focus(); } }, sig: JSON.stringify([d.options, d.min, d.max, d.step, d.range]) };
    }
    function place(d) {
      var h = holder(d), u = ui[d.id];
      if (u.wrap.parentNode !== h) { h.appendChild(u.wrap); }
    }
    function paintAll() {
      defs.forEach(function (d) { ui[d.id] = field(d); });
      defs.forEach(place);
      view.insertBefore(status, view.firstChild);
      refreshSelection();
    }

    // ---- change ------------------------------------------------------------------------------------------------------------------------
    function change(id, value, pause) {
      if (cur[id] === value) { return; }
      cur[id] = value; touched[id] = true;
      if (ui[id]) { ui[id].set(); }
      syncUrl(); keep(id, value);
      pending[id] = true; clearTimeout(timer);
      timer = setTimeout(fire, pause || 0);
    }
    function query() {
      var parts = [];
      defs.forEach(function (d) { if (touched[d.id]) { parts.push('ctl.' + encodeURIComponent(d.id) + '=' + encodeURIComponent(cur[d.id])); } });
      return parts.join('&');
    }
    function syncShare() {         // the Share button's link carries the controls too
      var b = view.querySelector('[data-share]');
      if (!b) { return; }
      try {
        var u = new URL(b.getAttribute('data-share'), location.href);
        Array.from(u.searchParams.keys()).forEach(function (k) { if (k.indexOf('ctl.') === 0) { u.searchParams.delete(k); } });
        defs.forEach(function (d) { if (touched[d.id]) { u.searchParams.set('ctl.' + d.id, cur[d.id]); } });
        b.setAttribute('data-share', u.toString().replace(/%2C/g, ',').replace(/%3A/g, ':'));
      } catch (e) { /* a link the browser cannot read stays as it was */ }
    }
    function syncUrl() {
      syncShare();
      if (!urlState) { return; }
      var p = new URLSearchParams(location.search);
      Array.from(p.keys()).forEach(function (k) { if (k.indexOf('ctl.') === 0) { p.delete(k); } });
      defs.forEach(function (d) { if (touched[d.id]) { p.set('ctl.' + d.id, cur[d.id]); } });
      var s = p.toString();
      history.replaceState(history.state, '', location.pathname + (s ? '?' + s.replace(/%2C/g, ',').replace(/%3A/g, ':') : '') + location.hash);
    }
    function memoKey(id) { return 'drishti.ctl.' + (view.getAttribute('data-me') || '') + '.' + memo + '.' + id; }
    function keep(id, value) {
      if (!byId[id].remember) { return; }
      try { localStorage.setItem(memoKey(id), value); } catch (e) { /* a private window: not remembered */ }
    }
    function fire() {
      var ids = Object.keys(pending); pending = {}; timer = 0;
      var parts = {};
      ids.forEach(function (id) { (byId[id].reads || []).forEach(function (p) { parts[p] = true; }); });
      (root === document ? document : root).dispatchEvent(new CustomEvent('drishti:controls', { bubbles: true, composed: true, detail: { query: query(), changed: ids } }));
      if (parts.title) { location.reload(); return; }
      var panels = Object.keys(parts).filter(function (p) { return p !== 'strip'; });
      if (!panels.length && !parts.strip) { reconcileOnly(ids); return; }
      var mine = ++token, t0 = performance.now(), qs = query();
      var url = options.panelsUrl ? options.panelsUrl((panels.length ? 'panels=' + panels.map(encodeURIComponent).join(',') + '&' : (parts.strip ? 'panels=-&' : '')) + qs) : null;
      if (!url) { return; }
      view.setAttribute('aria-busy', 'true');
      Promise.resolve(options.fetchJson(url)).then(function (r) {
        if (mine !== token) { return; }
        view.removeAttribute('aria-busy');
        var done = [];
        (r.panels || []).forEach(function (p) { if (swap(p)) { done.push(p.title || p.id); } });
        if (r.strip) { swapStrip(r.strip); }
        reconcile(r.controls || []);
        var ms = Math.round(performance.now() - t0);
        view.setAttribute('data-ctl-ms', String(ms));
        (window.__drishtiControls = window.__drishtiControls || []).push({ changed: ids, panels: (r.panels || []).map(function (p) { return p.id; }), ms: ms });
        status.textContent = (done.length ? done.join(', ') + ' updated' : 'Updated') + '.';
        root.dispatchEvent(new CustomEvent('drishti:controls-done', { bubbles: true, composed: true, detail: { changed: ids, panels: (r.panels || []).map(function (p) { return p.id; }), ms: ms } }));
        refreshSelection();
      }).catch(function (e) {
        if (mine !== token) { return; }
        view.removeAttribute('aria-busy');
        status.textContent = 'The panels could not be updated: ' + (e && e.message ? e.message : e);
        if (window.console) { console.warn('drishti: controls', e); }
      });
    }
    function reconcileOnly() { refreshSelection(); }

    /** Takes a panel's header controls out before it is replaced; the returned function puts them into the new panel (and gives back the focus). */
    function keepHeader(old) {
      var head = old.querySelector('.ctl-head'), active = root === document ? document.activeElement : root.activeElement;
      var had = head && active && head.contains(active) ? active : null;
      if (head && head.parentNode) { head.parentNode.removeChild(head); }
      return function (fresh) {
        if (!head) { return; }
        var h = fresh.querySelector('.pnl-h');
        if (h) { h.insertBefore(head, h.querySelector('.pnl-code')); }
        if (had && had.isConnected && had.focus) { had.focus(); }
      };
    }

    // ---- applying an answer ---------------------------------------------------------------------------------------------------------------
    function swap(p) {
      var old = view.querySelector('#' + PFX + CSS.escape(p.id));
      if (!old) { return false; }
      if (!p.html) { if (window.drishti && p.data) { window.drishti.updateChart(p.id, p.data); } return true; }
      var chosen = old.querySelector('[role="tab"][aria-selected="true"]'), chosenId = chosen ? chosen.id : null;
      var tpl = document.createElement('template');
      var html = rewrite(p.html);
      options.setHtml ? options.setHtml(tpl, html) : (tpl.innerHTML = html.trim());
      var fresh = tpl.content.firstElementChild;
      if (!fresh) { return false; }
      var mine = /^(c-(span|h)-\d+|pnl-off)$/;     // the reader's own layout survives
      fresh.className = fresh.className.split(/\s+/).filter(function (c) { return !mine.test(c); }).concat(old.className.split(/\s+/).filter(function (c) { return mine.test(c); })).join(' ');
      fresh.setAttribute('data-span', old.getAttribute('data-span') || '12'); fresh.setAttribute('data-height', old.getAttribute('data-height') || '0');
      fresh.tabIndex = old.tabIndex;
      var keep = keepHeader(old);                                       // the header controls keep their place and their focus
      old.replaceWith(fresh);
      if (options.afterSwap) { options.afterSwap(fresh); }
      keep(fresh);
      if (chosenId) { var t = fresh.querySelector('#' + CSS.escape(chosenId)); if (t) { t.click(); } }
      return true;
    }
    function swapStrip(html) {
      var old = view.querySelector('.strip');
      if (!old) { return; }
      var tpl = document.createElement('template');
      options.setHtml ? options.setHtml(tpl, rewrite(html)) : (tpl.innerHTML = rewrite(html).trim());
      if (tpl.content.firstElementChild) { old.replaceWith(tpl.content.firstElementChild); }
    }
    function reconcile(list) {
      list.forEach(function (n) {
        var d = byId[n.id];
        if (!d) { return; }
        var sig = JSON.stringify([n.options, n.min, n.max]), v = n.value || '';
        var focused = ui[n.id] && ui[n.id].wrap.contains(root === document ? document.activeElement : root.activeElement);
        Object.assign(d, n);
        if (ui[n.id] && ui[n.id].sig !== sig && !focused) {
          var was = ui[n.id].wrap; ui[n.id] = field(d); was.replaceWith(ui[n.id].wrap);
        }
        if (cur[n.id] !== v) { cur[n.id] = v; if (ui[n.id]) { ui[n.id].set(); } if (touched[n.id]) { syncUrl(); } }
      });
    }

    // ---- selecting in a panel sets a control ----------------------------------------------------------------------------------------------------
    var selOf = {};
    selects.forEach(function (s) { selOf[s.panel] = s; });
    function pickOf(target) {
      var panel = target.closest ? target.closest('[data-panel]') : null;
      if (!panel || !selOf[panel.getAttribute('data-panel')]) { return null; }
      var row = target.closest('tr[data-pick], li[data-pick]');
      return row ? { panel: panel, row: row, sel: selOf[panel.getAttribute('data-panel')], value: row.getAttribute('data-pick') } : null;
    }
    function choose(p) {
      var by = p.sel.by, now = cur[by] === p.value ? '' : p.value;       // the same value again clears it
      var d = byId[by];
      if (d && d.kind === 'multi-select') { now = cur[by] === p.value ? '' : p.value; }
      change(by, now);
      status.textContent = now ? (d ? d.label : by) + ' set to ' + now + '.' : (d ? d.label : by) + ' cleared.';
    }
    on(view, 'click', function (e) { var p = pickOf(e.target); if (p && !e.target.closest('a, button, input, select, label')) { choose(p); } });
    on(view, 'keydown', function (e) {
      if ((e.key === 'Enter' || e.key === ' ') && e.target.matches && e.target.matches('tr[data-pick], li[data-pick]')) {
        var p = pickOf(e.target); if (p) { e.preventDefault(); choose(p); }
      }
    });
    function decoratePicks(scope) {
      (scope || view).querySelectorAll('[data-panel]').forEach(function (panel) {
        var s = selOf[panel.getAttribute('data-panel')];
        if (!s) { return; }
        panel.setAttribute('data-selects', s.by);
        panel.querySelectorAll('tbody tr[data-pick]').forEach(function (r) { r.tabIndex = 0; r.setAttribute('role', 'row'); r.classList.add('ctl-pick'); });
        panel.querySelectorAll('ul.bars > li').forEach(function (li) {
          if (!li.hasAttribute('data-pick')) { var l = li.querySelector('.bl'); if (l) { li.setAttribute('data-pick', l.textContent.trim()); } }
          li.tabIndex = 0; li.classList.add('ctl-pick');
        });
      });
      refreshSelection();
    }
    function refreshSelection() {
      view.querySelectorAll('[data-pick]').forEach(function (r) {
        var panel = r.closest('[data-panel]'), s = panel && selOf[panel.getAttribute('data-panel')];
        if (!s) { return; }
        var on = cur[s.by] === r.getAttribute('data-pick');
        r.setAttribute('aria-selected', on ? 'true' : 'false'); r.classList.toggle('ctl-picked', on);
      });
    }

    // ---- the pieces stay with their panels: a live update or a swap replaces a panel, its header controls go back in -------------------------------
    var mo = new MutationObserver(function (list) {
      var fresh = false;
      list.forEach(function (m) { m.addedNodes.forEach(function (n) { if (n.nodeType === 1 && (n.matches('[data-panel]') || n.querySelector('[data-panel]'))) { fresh = true; } }); });
      if (!fresh) { return; }
      defs.forEach(function (d) { if (d.placement === 'header' && ui[d.id] && !ui[d.id].wrap.isConnected) { place(d); } });
      defs.forEach(function (d) {
        if (d.placement !== 'header' || !d.panel) { return; }
        var h = view.querySelector('#' + PFX + CSS.escape(d.panel) + ' .pnl-h');
        if (h && ui[d.id] && !h.contains(ui[d.id].wrap)) { place(d); }
      });
      decoratePicks();
    });
    mo.observe(view, { childList: true, subtree: true });
    undo.push(function () { mo.disconnect(); });

    paintAll();
    decoratePicks();
    // a remembered choice the address does not override: applied once, with one request
    defs.forEach(function (d) {
      if (!d.remember || touched[d.id]) { return; }
      var v = null;
      try { v = localStorage.getItem(memoKey(d.id)); } catch (e) { v = null; }
      if (v != null && v !== cur[d.id]) { change(d.id, v); }
    });

    var api = {
      query: query, values: function () { return Object.assign({}, cur); }, focus: function (id) { if (ui[id]) { ui[id].focus(); } },
      set: function (id, value) { change(id, value); }, decorate: decoratePicks, keepHeader: keepHeader,
      dispose: function () { clearTimeout(timer); undo.splice(0).forEach(function (f) { f(); }); Object.keys(bars).forEach(function (k) { bars[k].remove(); }); status.remove(); }
    };
    if (root === document) { window.drishtiControls = api; }
    return api;
  }

  reg.controls = { init: init };
  if (!auto) { return; }
  var v = document.querySelector('[data-view]');
  if (!v || !v.hasAttribute('data-controls')) { return; }
  var base = '/v/' + encodeURIComponent(v.dataset.kind) + '/' + v.dataset.id.split('/').map(encodeURIComponent).join('/');
  init(document, {
    view: v, scope: v.getAttribute('data-view-sutra') || v.dataset.kind,
    panelsUrl: function (q) { return base + '/panels?' + q; },
    fetchJson: function (url) { return fetch(url, { credentials: 'same-origin', headers: { Accept: 'application/json' } }).then(function (r) { if (!r.ok) { throw new Error('HTTP ' + r.status); } return r.json(); }); },
    afterSwap: function (fresh) {
      if (window.drishtiLayout) { window.drishtiLayout.decorate(fresh); }
      if (window.drishti) { window.drishti.enhance(fresh); }
    }
  });
})();
