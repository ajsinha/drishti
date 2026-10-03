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
/* What the last auto-design decided, shown above the canvas: the panels it left out because the samples could not fill them, and for
 * every choice the reason and the runner-up kinds. Text only (the numbers and names come from the samples).
 *
 *   WB.draft(detailsEl, result)       result: {pruned: [{panel, kind, action, to, reason}], reasons: {id: text}, alternatives: {id: [{kind, reason}]}} */
(function () {
  'use strict';
  var WB = window.DrishtiWB = window.DrishtiWB || {};

  WB.draft = function (box, d) {
    box.textContent = '';
    var sum = WB.el('summary', null, 'The last auto-design: ' + (d.pruned || []).length + ' left out, a reason for every choice');
    box.appendChild(sum);
    box.appendChild(WB.el('h3', 'bs-h2', 'Left out because the samples could not fill it'));
    var ul = WB.el('ul', 'bs-list');
    (d.pruned || []).forEach(function (p) {
      var li = WB.el('li');
      li.appendChild(WB.el('b', null, p.panel));
      li.appendChild(document.createTextNode(' (' + p.kind + ') ' + p.action + (p.to ? ' to ' + p.to : '') + ': ' + p.reason));
      ul.appendChild(li);
    });
    if (!ul.children.length) { ul.appendChild(WB.el('li', null, 'Nothing: every panel and figure had data in enough samples.')); }
    box.appendChild(ul);
    box.appendChild(WB.el('h3', 'bs-h2', 'Why each choice, and what else was considered'));
    Object.keys(d.reasons || {}).forEach(function (k) {
      var row = WB.el('div', 'bs-why');
      row.appendChild(WB.el('b', null, k));
      row.appendChild(document.createTextNode(': ' + d.reasons[k]));
      var alts = (d.alternatives || {})[k] || [];
      if (alts.length) {
        var al = WB.el('ul', 'bs-alts');
        alts.forEach(function (a) { var li = WB.el('li'); li.appendChild(WB.el('span', 'bs-role', a.kind)); li.appendChild(document.createTextNode(' ' + a.reason)); al.appendChild(li); });
        row.appendChild(al);
      }
      box.appendChild(row);
    });
    box.hidden = false;
    box.open = true;
  };
})();
