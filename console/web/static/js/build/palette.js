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
/* The palette: the 20 panel kinds of Rachana with an icon and one line each (what the panel shows and what it needs). A kind is
 * dragged onto the canvas (the pointer drag of drag.js) or chosen from the "Add panel..." menu / the Add button on a row: all three
 * give AddPanel at the chosen place. The list is the language's, not a pack's; the kinds the server's schema knows are checked
 * against it when the schema arrives (a kind the schema has and this list lacks is still offered, with no icon text).
 *
 *   WB.palette.kinds                      [{kind, icon, text, family}]
 *   WB.palette.render(listEl, onAdd)      fills the list; each row has an Add button for the keyboard
 *   WB.palette.items()                    the kinds as menu items */
(function () {
  'use strict';
  var WB = window.DrishtiWB = window.DrishtiWB || {};
  var KINDS = [
    ['kv', 'list-ul', 'Labelled values: the key facts of the entity, one field per row', 'facts'],
    ['status', 'check2-circle', 'Statuses and flags, each coloured by its state', 'facts'],
    ['provenance', 'clock-history', 'Where the data came from: sources, times and versions', 'facts'],
    ['markdown', 'markdown', 'A note or explanation in plain text', 'facts'],
    ['table', 'table', 'Rows and columns from a list; filter, totals, sub-rows, a pivot tab', 'tables'],
    ['ladder', 'distribute-vertical', 'A table with a highlighted row, for ladders and buckets', 'tables'],
    ['pivot', 'grid-3x3', 'A pivot of a list: rows, columns and a measure', 'tables'],
    ['tabs', 'columns-gap', 'One panel per row of a list, each in its own tab', 'tables'],
    ['line', 'graph-up', 'A series over time or another axis', 'series'],
    ['area', 'graph-up-arrow', 'Several series stacked or shaded', 'series'],
    ['candlestick', 'reception-4', 'Open, high, low and close per period', 'market'],
    ['surface', 'layers-half', 'A surface: two axes and a value, for vol and curves', 'market'],
    ['histogram', 'bar-chart', 'The spread of a list of numbers in bins', 'risk'],
    ['scatter', 'bounding-box-circles', 'Two measures against each other, one dot per row', 'risk'],
    ['gauge', 'speedometer2', 'One value against its limit', 'risk'],
    ['waterfall', 'bar-chart-steps', 'A start, the steps that change it and the end', 'pnl'],
    ['hbar', 'bar-chart-line', 'Horizontal bars: a label and a value per row', 'pnl'],
    ['graph', 'diagram-3', 'Nodes and edges: how entities relate', 'relations'],
    ['links', 'link-45deg', 'Links to related entities', 'relations'],
    ['timeline', 'calendar3-range', 'Events along a time axis', 'operations']
  ];
  var list = KINDS.map(function (k) { return { kind: k[0], icon: k[1], text: k[2], family: k[3] }; });

  function render(box, onAdd) {
    box.textContent = '';
    list.forEach(function (k) {
      var li = WB.el('li', 'wb-kind', null, { 'data-kind': k.kind });
      var i = WB.el('i', 'bi bi-' + k.icon, null, { 'aria-hidden': 'true' });
      var t = WB.el('span', 'wb-kind-t');
      t.appendChild(WB.el('b', 'mono', k.kind));
      t.appendChild(WB.el('small', null, k.text));
      var b = WB.el('button', 'btn-pill btn-ghost wb-kind-add', 'Add', { type: 'button', 'aria-label': 'Add a ' + k.kind + ' panel', title: 'Add a ' + k.kind + ' panel after the selected one' });
      b.addEventListener('click', function () { onAdd(k.kind); });
      li.appendChild(i); li.appendChild(t); li.appendChild(b);
      li.title = 'Drag onto the canvas to add a ' + k.kind + ' panel there';
      WB.drag.attach(li, { payload: function () { return { type: 'kind', kind: k.kind }; }, label: function () { return '+ ' + k.kind; } });
      box.appendChild(li);
    });
  }
  function items() { return list.map(function (k) { return { label: k.kind, detail: k.text, badge: k.family, value: k.kind }; }); }

  WB.palette = { kinds: list, render: render, items: items };
})();
