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
/* Customize's settings of a part from the UI kit, and Remove with Undo (SCREEN_DESIGNER.md section 31, core/designer_settings.py).
   A part just added opens its settings in the side panel: one plain-word question for each of the fragment's parameters (Which number?
   [NOTIONAL], Label, Format), the choices from the record's own fields; Apply writes them on the part's with:. A part the person added
   whose required setting names nothing in the record says so on the page, with a button to its settings: never an empty panel without a
   word. Remove (only on parts the person added; the pack's panels are hidden) shows a toast with Undo for a few seconds.
   designer.js calls window.drishtiDesignerSettings with its helpers. */
(function () {
  'use strict';
  var UNDO_MS = 8000;

  window.drishtiDesignerSettings = function (h) {
    var el = h.el;

    /* the settings box at the top of the side panel, for the part h.open() names */
    function box(side, index, focus) {
      var wrap = el('section', { 'class': 'dz-settings', 'data-dz-settings-box': String(index), 'aria-label': 'Settings' }, [
        el('p', { 'class': 'dz-note', text: 'Reading the settings…' })]);
      side.insertBefore(wrap, side.firstChild);
      h.api('GET', '/designer/settings/' + encodeURIComponent(h.draft) + '?index=' + index + '&kind=' + encodeURIComponent(h.kind) + '&id=' + encodeURIComponent(h.id))
        .then(function (f) { fill(wrap, f, focus); })
        .catch(function (e) { wrap.innerHTML = ''; wrap.appendChild(el('p', { 'class': 'dz-note', text: e.message })); });
      return wrap;
    }

    function input(f) {
      var c;
      if (f.input === 'choice') {
        c = el('select', { 'class': 'dz-pill', 'data-dz-setting': f.name, 'aria-label': f.question });
        f.choices.forEach(function (o) { c.appendChild(el('option', { value: o[0], text: o[1] })); });
        c.value = f.value;
      } else {
        c = el('input', { type: 'text', 'class': 'dz-search', 'data-dz-setting': f.name, 'aria-label': f.question, value: f.value });
      }
      return c;
    }

    function fill(wrap, f, focus) {
      wrap.innerHTML = '';
      wrap.appendChild(el('h3', { 'class': 'dz-h', text: 'Settings · ' + (f.title || 'this part') }));
      if (f.unset.length) {
        wrap.appendChild(el('p', { 'class': 'dz-need-words', 'data-dz-settings-need': '', text: 'Choose ' + f.unset.join(', ').replace(/\?/g, '').toLowerCase() + ': until then the panel shows no data.' }));
      }
      var more = el('details', { 'class': 'dz-more' }, [el('summary', { text: 'More settings' })]);
      f.fields.forEach(function (x) {
        var row = el('label', { 'class': 'dz-setting' + (x.unset ? ' dz-unset' : ''), title: x.meaning }, [el('span', { text: x.question }), input(x)]);
        (x.advanced ? more : wrap).appendChild(row);
      });
      if (more.children.length > 1) { wrap.appendChild(more); }
      wrap.appendChild(el('div', { 'class': 'dz-row' }, [
        el('button', { type: 'button', 'class': 'dz-btn dz-primary', 'data-dz-settings-apply': '', text: 'Apply', on: { click: function (e) {
          var values = {};
          wrap.querySelectorAll('[data-dz-setting]').forEach(function (c) { values[c.getAttribute('data-dz-setting')] = c.value; });
          e.target.disabled = true;
          h.step({ op: 'settings', index: f.index, values: values }).catch(function () { e.target.disabled = false; });
        } } }),
        el('button', { type: 'button', 'class': 'dz-btn', 'data-dz-settings-close': '', text: 'Close', on: { click: function () { h.open(null); } } })]));
      var first = wrap.querySelector('.dz-unset [data-dz-setting]') || wrap.querySelector('[data-dz-setting]');
      if (first && focus) { first.focus({ preventScroll: true }); }
    }

    /* the tools of one part: Settings (a used part), Remove (a part this person added) */
    function tools(part, row, sec, first) {
      if (!first) { return; }
      var hide = row.querySelector('[data-dz-hide]');
      if (part.used) {
        row.insertBefore(el('button', { type: 'button', 'class': 'dz-mini', 'data-dz-settings': '', text: 'Settings',
                                        on: { click: function () { h.open(part.index, true); } } }), hide);
      }
      if (part.added && hide) {
        hide.replaceWith(el('button', { type: 'button', 'class': 'dz-mini', 'data-dz-remove': '', 'aria-label': 'Remove ' + part.title, text: 'Remove',
                                        on: { click: function () { remove(part); } } }));
      }
      if (part.added && part.unset && part.unset.length) {             // never silently empty: say what to choose, and where
        var need = el('div', { 'class': 'dz-need', role: 'note', 'data-dz-need': '' }, [
          el('span', { text: 'Choose ' + part.unset.join(', ').replace(/\?/g, '').toLowerCase() + ' to show data here.' }),
          el('button', { type: 'button', 'class': 'dz-mini', 'data-dz-need-open': '', text: 'Open its settings', on: { click: function () { h.open(part.index, true); } } })]);
        row.parentNode.insertBefore(need, row.nextSibling);
      }
    }

    var toast = null, timer = null;
    function remove(part) {
      h.open(null);                                                       // its settings go with it
      h.step({ op: 'remove', index: part.index }).then(function () {
        if (toast) { toast.remove(); }
        clearTimeout(timer);
        toast = el('div', { 'class': 'dz-toast', role: 'status', 'data-dz-toast': '' }, [
          el('span', { text: '“' + part.title + '” removed.' }),
          el('button', { type: 'button', 'class': 'dz-btn', 'data-dz-undo': '', text: 'Undo', on: { click: function (e) {
            e.target.disabled = true;
            clearTimeout(timer);
            h.step({ op: 'undo-remove' }).then(function () { if (toast) { toast.remove(); toast = null; } }).catch(function () { e.target.disabled = false; });
          } } })]);
        document.body.appendChild(toast);
        timer = setTimeout(function () { if (toast) { toast.remove(); toast = null; } }, UNDO_MS);
      }).catch(function () {});
    }

    return { box: box, tools: tools };
  };
})();
