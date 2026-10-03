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
/* The Versions tab: the design's Sutra against the live Sutra it came from (its base) or against any earlier version in its log,
 * drawn as the review page draws a diff (moves in words, then the lines); and "Restore", which puts an older text back as an
 * operation, so it is in the log and undo takes it back. The texts come from the server's log (GET /build/designs/{id}/versions);
 * the diff is the console's own sutra_diff, the same code the reviewers use.
 *
 *   new WB.Versions(box, store, base) -> {refresh(), compare(against)}      against: 'base' or a version number                 */
(function () {
  'use strict';
  var WB = window.DrishtiWB = window.DrishtiWB || {};

  WB.Versions = function (box, store, base) {
    var url = '/build/designs/' + encodeURIComponent(store.state.id);
    var pick = WB.el('select', 'studio-in', null, { id: 'wbCompareWith' }), label = WB.el('label', null, 'Compare the design with', { for: 'wbCompareWith' });
    var out = WB.el('div', 'wb-diff', null, { role: 'region', 'aria-label': 'Differences', tabindex: '0' });
    var restore = WB.el('button', 'btn-pill btn-ghost', 'Restore this version', { type: 'button', 'data-restore': '' });
    var head = WB.el('p', 'wb-sum', 'Pick what to compare with.', { role: 'status' });
    var bar = WB.el('div', 'bs-bar');
    bar.appendChild(label); bar.appendChild(pick); bar.appendChild(restore);
    box.appendChild(bar); box.appendChild(head); box.appendChild(out);
    var list = [], seq = 0;

    function when(at) { return at ? new Date(at).toLocaleTimeString() : ''; }
    function fill() {
      var keep = pick.value;
      pick.textContent = '';
      if (base) { pick.appendChild(WB.el('option', null, 'The live Sutra ' + base + ' (where this design came from)', { value: 'base' })); }
      list.slice().reverse().forEach(function (v) {
        if (v.current) { return; }
        pick.appendChild(WB.el('option', null, 'Version ' + v.n + (v.n === 0 ? ': as it started' : ': after ' + v.ops) + (v.at ? ' · ' + when(v.at) : ''), { value: String(v.n) }));
      });
      if (!pick.options.length) { pick.appendChild(WB.el('option', null, 'Nothing earlier to compare with yet', { value: '' })); }
      if (keep && Array.prototype.some.call(pick.options, function (o) { return o.value === keep; })) { pick.value = keep; }
    }
    function compare(against) {
      var mine = ++seq;
      restore.disabled = true;
      if (against === undefined || against === '') { out.textContent = ''; head.textContent = pick.value === '' ? 'Nothing earlier to compare with yet: make a change first.' : ''; return Promise.resolve(); }
      head.textContent = 'Comparing...';
      return WB.call('GET', url + '/diff?against=' + encodeURIComponent(against)).then(function (r) {
        if (mine !== seq) { return; }
        if (!r.ok) { out.textContent = ''; head.textContent = WB.why(r); head.className = 'wb-sum bad'; return; }
        out.innerHTML = r.body.html;          // the console's own template output: every value is escaped there
        head.className = 'wb-sum';
        head.textContent = r.body.same ? 'The design is the same as ' + r.body.label + '.' : 'The design against ' + r.body.label + ': removed lines in red, added in green.';
        restore.disabled = false;
        restore.textContent = against === 'base' ? 'Restore the live Sutra' : 'Restore version ' + against;
      });
    }
    function refresh() {
      return WB.call('GET', url + '/versions').then(function (r) {
        if (!r.ok) { head.textContent = WB.why(r); return; }
        list = r.body.versions || []; base = r.body.base || base; fill(); return compare(pick.value);
      });
    }
    pick.addEventListener('change', function () { compare(pick.value); });
    restore.addEventListener('click', function () {
      var against = pick.value;
      var text = against === 'base'
        ? fetch('/studio/source/' + encodeURIComponent(base.split('@')[0]) + '/' + encodeURIComponent(base.split('@')[1])).then(function (x) { return x.ok ? x.text() : null; })
        : WB.call('GET', url + '/versions/' + encodeURIComponent(against)).then(function (r) { return r.ok ? r.body.yaml : null; });
      text.then(function (yaml) {
        if (yaml === null || yaml === undefined) { store.emit('say', 'That version could not be read.', true); return; }
        store.send([{ op: 'text', yaml: yaml }], against === 'base' ? 'Restored the live Sutra' : 'Restored version ' + against).then(function () { refresh(); });
      });
    });
    store.on('doc', function (d) { if (d.changed && !box.hidden) { refresh(); } });
    return { refresh: refresh, compare: function (a) { pick.value = String(a); return compare(String(a)); } };
  };
})();
