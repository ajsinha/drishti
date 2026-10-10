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
/* The shared multi-select dropdown (vendored, no library): a closed control the size of a select that shows a summary ("All", "REPO",
   "3 selected: ABS, REPO, +1"); opened, it holds a search box (type to filter), "Select all" / "Clear" and a checkbox per option (counts where
   the option text carries them). A disclosure (details/summary) over a labelled group of real checkboxes, so keyboard (Enter or Space opens,
   Tab walks, Up/Down in the search box enter the list, Space toggles, Esc closes and returns), screen readers and form submission are the
   browser's own. Used by the query-by-form renderer (every enum field with more than SMALL options; fewer stay a compact checkbox row) and
   by the controls of a Sutra (controls.js), also inside <drishti-view>.
     window.drishtiModules.multiselect.decorate(details, {label, boxes, onChange}) -> {refresh}
     window.drishtiModules.multiselect.enhance(root)   wraps every  .qf-enum[data-ms]  (a group of checkbox labels) under root           */
(function () {
  'use strict';
  var reg = (window.drishtiModules = window.drishtiModules || {});
  var SMALL = 6;

  function el(tag, cls, text, attrs) {
    var e = document.createElement(tag);
    if (cls) { e.className = cls; }
    if (text != null) { e.textContent = text; }
    if (attrs) { Object.keys(attrs).forEach(function (k) { e.setAttribute(k, attrs[k]); }); }
    return e;
  }
  function summaryText(boxes) {
    var on = boxes.filter(function (b) { return b.checked; }).map(function (b) { return b.getAttribute('data-text') || b.value; });
    if (!on.length || on.length === boxes.length) { return 'All'; }
    if (on.length === 1) { return on[0]; }
    return on.length + ' selected: ' + on.slice(0, 2).join(', ') + (on.length > 2 ? ', +' + (on.length - 2) : '');
  }

  /** Builds the pop-up inside `details` (which already holds its <summary>) around the checkbox `boxes`, whose <label>s are moved into the list. */
  function decorate(details, o) {
    var sum = details.querySelector('summary'), boxes = o.boxes, id = 'ms-' + Math.random().toString(36).slice(2, 8);
    var pop = el('div', 'ms-pop');
    var q = el('input', 'ms-q', null, { type: 'search', placeholder: 'Type to filter', autocomplete: 'off', 'aria-label': 'Filter ' + (o.label || 'options') });
    var all = el('button', 'ms-act', 'Select all', { type: 'button' }), none = el('button', 'ms-act', 'Clear', { type: 'button' });
    var acts = el('div', 'ms-acts'); acts.appendChild(all); acts.appendChild(none);
    var list = el('div', 'ms-list', null, { role: 'group', 'aria-label': o.label || 'Options', id: id });
    boxes.forEach(function (b) { var l = b.closest('label') || b.parentNode; list.appendChild(l); l.classList.add('ms-item'); });
    pop.appendChild(q); pop.appendChild(acts); pop.appendChild(list); details.appendChild(pop);
    details.classList.add('ms');
    sum.classList.add('ms-sum'); sum.setAttribute('aria-controls', id);
    function refresh() { sum.textContent = summaryText(boxes); sum.setAttribute('aria-label', (o.label || 'Options') + ': ' + sum.textContent); }
    function changed() { refresh(); if (o.onChange) { o.onChange(); } }
    function visible() { return boxes.filter(function (b) { return !b.closest('label').hidden; }); }
    boxes.forEach(function (b) { b.addEventListener('change', changed); });
    all.addEventListener('click', function () { visible().forEach(function (b) { b.checked = true; }); changed(); });
    none.addEventListener('click', function () { visible().forEach(function (b) { b.checked = false; }); changed(); });
    q.addEventListener('input', function () {
      var t = q.value.trim().toLowerCase();
      boxes.forEach(function (b) { b.closest('label').hidden = !!t && (b.getAttribute('data-text') || b.closest('label').textContent).toLowerCase().indexOf(t) < 0; });
    });
    q.addEventListener('keydown', function (e) {
      if (e.key === 'ArrowDown') { var v = visible()[0]; if (v) { e.preventDefault(); v.focus(); } }
      else if (e.key === 'Enter') { e.preventDefault(); details.open = false; sum.focus(); }
    });
    list.addEventListener('keydown', function (e) {
      var v = visible(), i = v.indexOf(e.target);
      if (i < 0) { return; }
      if (e.key === 'ArrowDown' && i < v.length - 1) { e.preventDefault(); v[i + 1].focus(); }
      else if (e.key === 'ArrowUp') { e.preventDefault(); (i ? v[i - 1] : q).focus(); }
      else if (e.key === 'Enter') { e.preventDefault(); details.open = false; sum.focus(); }
      else if (e.key.length === 1 && /\S/.test(e.key) && !e.ctrlKey && !e.metaKey && !e.altKey) {       // type-ahead: the next option that begins with it
        var c = e.key.toLowerCase(), n = v.slice(i + 1).concat(v.slice(0, i + 1)).filter(function (b) { return (b.getAttribute('data-text') || b.value).toLowerCase().indexOf(c) === 0; })[0];
        if (n) { n.focus(); }
      }
    });
    details.addEventListener('keydown', function (e) { if (e.key === 'Escape' && details.open) { details.open = false; sum.focus(); e.stopPropagation(); } });
    details.addEventListener('toggle', function () { if (details.open) { q.value = ''; boxes.forEach(function (b) { b.closest('label').hidden = false; }); q.focus(); } });
    var root = details.getRootNode();
    root.addEventListener('click', function (e) { if (details.open && !details.contains(e.target)) { details.open = false; } });
    refresh();
    return { refresh: refresh };
  }

  /** Wraps each `.qf-enum[data-ms]` group of checkbox labels into the dropdown (the checkboxes, their names and the form's encoding stay as they are). */
  function enhance(root) {
    (root || document).querySelectorAll('.qf-enum[data-ms]:not([data-ms-done])').forEach(function (g) {
      var boxes = Array.prototype.slice.call(g.querySelectorAll('input[type=checkbox]'));
      if (boxes.length <= SMALL) { g.setAttribute('data-ms-done', ''); return; }
      boxes.forEach(function (b) { if (!b.hasAttribute('data-text')) { var s = b.closest('label').querySelector('span'); b.setAttribute('data-text', s ? s.textContent.trim() : b.value); } });
      var det = el('details', 'ms-box'), sum = el('summary');
      det.appendChild(sum);
      det.setAttribute('data-ms-for', g.id || '');
      g.parentNode.insertBefore(det, g);
      decorate(det, { label: g.getAttribute('aria-label') || '', boxes: boxes });      // the labels move into the list; the empty group goes
      g.remove();
    });
  }

  reg.multiselect = { decorate: decorate, enhance: enhance, summary: summaryText, SMALL: SMALL };
  if (document.readyState === 'loading') { document.addEventListener('DOMContentLoaded', function () { enhance(document); }); } else { enhance(document); }
})();
