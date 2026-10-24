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
/* Customize's "Let people edit this table" (SUTRA_NEXT.md 10, SCREEN_DESIGNER section 31): pick a table, how its records are edited (a form
   beside it, or in its cells, or not at all), which columns and as what (guessed, or text, a number, money, a date, a date and time, a
   linked record when the packs say the column names one), and whether New opens a drawer; Set writes form: on the table
   (core/designer_forms.py) and the page is drawn with it. designer.js calls window.drishtiDesignerForms(side, state, helpers). */
(function () {
  'use strict';
  window.drishtiDesignerForms = function (side, state, h) {
    var tables = (state.tables || []).filter(function (t) { return (t.columns || []).length > 1; });
    if (!tables.length) { return; }
    var el = h.el;
    side.appendChild(el('h3', { 'class': 'dz-h', text: 'Let people edit' }));
    var part = h.select('the table people may edit', tables.map(function (t) { return [t.id, t.title]; }), 'data-dz-form-table');
    var how = h.select('how they edit it', [['beside', 'in a form beside it'], ['cells', 'in its cells'], ['off', 'not at all']], 'data-dz-form-how');
    var cols = el('div', { 'class': 'dz-cols', 'data-dz-form-cols': '' });
    var isNew = el('input', { type: 'checkbox', 'data-dz-form-new': '' });
    var KINDS = [['auto', 'as guessed'], ['text', 'text'], ['number', 'a number'], ['money', 'money'], ['date', 'a date'], ['datetime', 'a date and time']];
    function fill() {
      var t = tables.filter(function (x) { return x.id === part.value; })[0] || tables[0];
      var f = t.form || { mode: 'off', fields: [], 'new': false };
      how.value = f.mode;
      isNew.checked = !!f['new'];
      cols.innerHTML = '';
      t.columns.slice(1).forEach(function (c) {
        var box = el('input', { type: 'checkbox', value: c });
        box.checked = !f.fields.length || f.fields.indexOf(c) >= 0;
        var as = el('select', { 'class': 'dz-col-type', 'data-dz-form-type': c, 'aria-label': c + ' is' });
        KINDS.concat((t.links || []).indexOf(c) >= 0 ? [['entity', 'a linked record']] : []).forEach(function (k) { as.appendChild(el('option', { value: k[0], text: k[1] })); });
        var had = (f.types || {})[c];
        as.value = had && had !== 'text' && as.querySelector('option[value="' + had + '"]') ? had : 'auto';
        cols.appendChild(el('div', { 'class': 'dz-col-row' }, [el('label', { 'class': 'dz-col' }, [box, ' ' + c]), as]));
      });
    }
    part.addEventListener('change', fill);
    fill();
    side.appendChild(el('div', { 'class': 'dz-sentence' }, ['Let people edit ', part, ' ', how]));
    side.appendChild(el('p', { 'class': 'dz-note', text: 'Which columns:' }));
    side.appendChild(cols);
    side.appendChild(el('label', { 'class': 'dz-col' }, [isNew, ' and start new ones in a guided drawer']));
    side.appendChild(el('div', { 'class': 'dz-row' }, [
      el('button', { type: 'button', 'class': 'dz-btn dz-primary', 'data-dz-form-apply': '', text: 'Set', on: { click: function () {
        var chosen = Array.prototype.slice.call(cols.querySelectorAll('input:checked')).map(function (i) { return i.value; });
        var types = {};
        cols.querySelectorAll('[data-dz-form-type]').forEach(function (s) { if (s.value !== 'auto') { types[s.getAttribute('data-dz-form-type')] = s.value; } });
        h.step({ op: 'form', panel: part.value, mode: how.value, fields: chosen, types: types, 'new': isNew.checked }).catch(function () {});
      } } })]));
    side.appendChild(el('p', { 'class': 'dz-note', text: 'Saving keeps a draft for each person; sending it to the source arrives in 2.8.0.' }));
  };
}());
