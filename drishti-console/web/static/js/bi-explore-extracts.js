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
/* Analyse (docs/guides/USER_GUIDE.md › Analyse: slice, SQL and Python, section 2): more extracts in one notebook. Each is a masked extract of a dataset (the rows the signed-in person may see,
   built by the server as for the first), named so that a Python cell reads it as extract("name") and a SQL cell as a table of that name. The cells post them
   with every run (bi-explore-cells.js); the notebook stores them under `extracts:`. Text only, never markup. */
(function () {
  'use strict';
  var B = window.drishtiBi, X = B && B.explore, root = document.getElementById('bx');
  if (!X || !root) { return; }
  var h = B.h, q = B.q, qa = B.qa, clear = B.clear;
  var list = q('[data-extras]', root), form = q('[data-add-extract]', root), sel = q('[data-edataset]', root), box = q('[data-efields-box]', root), fields = q('[data-efields]', root), add = q('[data-eadd]', root);
  var info = {};                                    // name -> what the server said of the extract: rows, masked

  function draw() {
    clear(list);
    X.extras.forEach(function (e, i) {
      var n = info[e.name];
      var rm = h('button', { type: 'button', class: 'btn-pill btn-ghost bx-small', 'aria-label': 'Remove the extract ' + e.name, 'data-remove-extract': e.name }, B.icon('x-lg'));
      rm.addEventListener('click', function () { X.extras.splice(i, 1); draw(); });
      list.appendChild(h('li', { class: 'bx-extra', 'data-extra': e.name }, h('code', { text: e.name }), ' ← ' + e.dataset + ' (' + e.fields.length + ' fields' + (n ? ', ' + n.rows + ' rows' + (n.masked.length ? ', masked for you: ' + n.masked.join(', ') : '') : '') + ') ', rm));
    });
    q('[data-more]', root).open = q('[data-more]', root).open || X.extras.length > 0;
  }

  function loadDatasets() {
    return X.sources().then(function (groups) { B.layer.fillSelect(sel, groups, '(choose a model or a dataset)'); });
  }
  sel.addEventListener('change', function () {
    clear(fields); box.hidden = true; add.disabled = true;
    if (!sel.value) { return; }
    X.fieldsOf(sel.value).then(function (m) {
      (m.tables || []).forEach(function (t) {
        var cols = (t.columns || []).filter(function (c) { return c.name !== 'id'; });
        if (!cols.length) { return; }
        fields.appendChild(h('div', { class: 'bx-table' }, h('strong', { text: t.name }), cols.map(function (c) {
          return h('label', { class: 'bx-check' }, h('input', { type: 'checkbox', name: 'ef', value: t.name + '.' + c.name, checked: !c.masked && cols.indexOf(c) < 8 }), ' ' + c.name + (c.masked ? ' (masked)' : ''));
        })));
      });
      var name = form.elements.ename;
      if (!name.value) { name.value = (m.name || m.title || sel.value).replace(/[^A-Za-z0-9_]+/g, '_').slice(0, 40); }
      box.hidden = false; add.disabled = false;
    }).catch(function (e) { X.say(e.message, true); });
  });

  form.addEventListener('submit', function (ev) {
    ev.preventDefault();
    var name = form.elements.ename.value.trim(), picked = qa('input[name=ef]:checked', fields).map(function (i) { return i.value; });
    if (!name || !sel.value || !picked.length) { X.say('Name the extract, choose a dataset and at least one field.', true); return; }
    if (X.extras.some(function (e) { return e.name === name; })) { X.say('There is already an extract called ' + name + '.', true); return; }
    X.say('Opening the extract…');
    X.post('/bi/explore/extract', { dataset: sel.value, fields: picked }).then(function (ex) {
      X.extras.push({ name: name, dataset: sel.value, fields: picked });
      info[name] = { rows: ex.rowCount, masked: ex.masked };
      form.elements.ename.value = '';
      X.say('Added the extract ' + name + ': ' + ex.rowCount + ' rows. A cell reads it as extract("' + name + '").');
      draw();
      document.dispatchEvent(new CustomEvent('bx:extras'));
    }).catch(function (e) { X.say(e.message, true); });
  });

  document.addEventListener('bx:extras', draw);
  loadDatasets().then(draw).catch(function (e) { X.say(e.message, true); });
})();
