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
/* Rupaka phase 4 (docs/guides/BI_GUIDE.md, "Environments"): export a workspace as a bundle, and import one: a dry run that lists what would be created, changed,
   left alone or refused, then the import. The bundle is read in the browser and sent as JSON; the server checks every checksum. Text only, never markup. */
(function () {
  'use strict';
  var B = window.drishtiBi, canvas = document.getElementById('biCanvas');
  if (!B || !canvas || !B.q('[data-export]')) { return; }
  var h = B.h, q = B.q, clear = B.clear;
  var bundle = null, last = null, admin = false;

  function say(text, bad) { B.say(q('[data-msg]'), text, bad); }
  function fail(e) { say(e.message, true); }
  function pairs(text) {
    var m = {};
    (text || '').split(',').forEach(function (p) { var i = p.indexOf(':'); if (i > 0) { m[p.slice(0, i).trim()] = p.slice(i + 1).trim(); } });
    return m;
  }

  function fill(sel, list, blank) {
    clear(sel);
    if (blank) { sel.appendChild(h('option', { value: '', text: blank })); }
    list.forEach(function (w) { sel.appendChild(h('option', { value: w.personal ? 'me' : w.id, text: w.name + (w.personal ? ' (personal)' : '') })); });
  }

  B.api('GET', '/me').then(function (me) {
    var steward = (me.powers || []).indexOf('bi-steward') >= 0;
    admin = (me.powers || []).indexOf('bi-admin') >= 0;
    q('[data-import-need]').hidden = steward;
    q('[data-force-box]').hidden = !admin; q('[data-force-label]').hidden = !admin;
    ['[data-file]', '[data-dry]'].forEach(function (s) { q(s).disabled = !steward; });
    return B.api('GET', '/workspaces');
  }).then(function (list) {
    var mine = list.filter(function (w) { return !w.shipped && (w.role === 'contributor' || w.role === 'member' || w.role === 'admin'); });
    fill(q('[data-ws-select]'), mine);
    fill(q('[data-ws-select-import]'), list.filter(function (w) { return !w.shipped; }), 'the workspace named in the bundle');
    if (!mine.length) { q('[data-export-note]').textContent = 'You are not a contributor in any workspace, so there is nothing to export.'; }
  }).catch(fail);

  q('[data-export]').addEventListener('submit', function (e) {
    e.preventDefault();
    var f = e.target.elements;
    fetch('/bi/api/bundle?workspace=' + encodeURIComponent(f.workspace.value) + '&dependencies=' + (f.dependencies.checked ? 'true' : 'false'), { credentials: 'same-origin' }).then(function (r) {
      return r.json().then(function (d) { if (!r.ok) { var er = new Error((d.code ? d.code + ' ' : '') + (d.detail || r.statusText)); throw er; } return d; });
    }).then(function (d) {
      var blob = new Blob([JSON.stringify(d, null, 2)], { type: 'application/json' });
      var a = h('a', { href: URL.createObjectURL(blob), download: 'drishti-' + f.workspace.value + '.bi-bundle.json' });
      document.body.appendChild(a); a.click(); a.remove();
      say('Bundle downloaded: ' + d.items.length + ' item' + (d.items.length === 1 ? '' : 's') + (d.skipped.length ? ', ' + d.skipped.length + ' skipped (' + d.skipped.map(function (s) { return s.reason; }).join('; ') + ')' : '') + '.');
    }).catch(fail);
  });

  q('[data-file]').addEventListener('change', function (e) {
    var file = e.target.files[0];
    bundle = null; q('[data-apply]').disabled = true;
    if (!file) { return; }
    var rd = new FileReader();
    rd.onload = function () { try { bundle = JSON.parse(rd.result); say('Read ' + file.name + '. Run a dry run to see what it would do.'); } catch (x) { say('That file is not JSON.', true); } };
    rd.readAsText(file);
  });

  function request(dry) {
    var f = q('[data-import]').elements;
    return B.api('POST', '/bundle/import', { bundle: bundle, dryRun: dry, overwrite: f.overwrite.checked, force: !!(f.force && f.force.checked), workspace: f.workspace.value,
      workspaces: pairs(f.workspaces.value), owners: pairs(f.owners.value) });
  }
  function plan(p) {
    last = p;
    var sec = q('[data-plan-sec]'), tb = q('[data-plan] tbody'); clear(tb); sec.hidden = false;
    var c = p.counts;
    q('[data-plan-meta]').textContent = (p.dryRun ? 'Dry run, nothing changed. ' : p.applied ? 'Imported. ' : '') + c.create + ' to create, ' + c.update + ' to change, ' + c.unchanged + ' unchanged, ' + c.conflict + ' in conflict, ' + c.invalid + ' invalid.'
      + ' Bundle made by ' + (p.createdBy || '?') + ' at ' + (p.createdAt || '?') + '.';
    p.items.forEach(function (x) {
      var notes = x.reasons.concat(x.warnings, x.blocks);
      tb.appendChild(h('tr', { 'data-id': x.id, 'data-action': x.action },
        h('th', { scope: 'row', text: x.title }), h('td', { text: x.kind + (x.sample ? ' (sample)' : '') }), h('td', { class: 'bi-plan-' + x.action, text: x.action }),
        h('td', { text: x.workspace }), h('td', { text: x.owner }),
        h('td', {}, h('ul', { class: 'bi-problems' }, notes.map(function (n) { return h('li', { class: x.blocks.indexOf(n) >= 0 ? 'bi-bad-line' : x.warnings.indexOf(n) >= 0 ? 'bi-warn-line' : '', text: n }); })))));
    });
    q('[data-apply]').disabled = !p.dryRun || !p.ok;
  }
  // -- the imports that can be rolled back ----------------------------------------------------------------------------------
  function imports() {
    return B.api('GET', '/imports').then(function (r) {
      var tb = q('[data-imports] tbody'); clear(tb);
      q('[data-no-imports]').hidden = r.imports.length > 0; q('[data-imports]').hidden = !r.imports.length;
      r.imports.forEach(function (x) {
        var btn = h('button', { type: 'button', class: 'btn-pill btn-ghost', 'data-rollback': x.id, text: 'Roll back this import' });
        btn.disabled = x.rolledBack;
        btn.addEventListener('click', function () { rollback(x, false); });
        tb.appendChild(h('tr', { 'data-import-id': x.id },
          h('th', { scope: 'row', text: (x.at || '').slice(0, 19).replace('T', ' ') + ' UTC' }), h('td', { text: x.by }), h('td', { text: x.source }),
          h('td', { text: x.items + ' (' + x.created + ' created, ' + x.replaced + ' replaced)' }),
          h('td', { text: x.rolledBack ? 'rolled back by ' + x.rolledBackBy : 'applied' }), h('td', {}, btn)));
      });
    }).catch(function () { q('[data-imports-sec]').hidden = true; });
  }
  function rollback(x, force) {
    B.api('POST', '/imports/' + encodeURIComponent(x.id) + '/rollback', { force: force }).then(function (r) {
      say('Rolled back: ' + r.restored + ' item(s) put back, ' + r.removed + ' created item(s) removed' + (r.forced ? ' (forced over edits made since)' : '') + '.'); imports();
    }).catch(function (e) {
      if (e.code === 'DRS-9004' && admin && !force && window.confirm(e.detail + ' Roll back anyway and lose that work?')) { rollback(x, true); } else { fail(e); }
    });
  }
  q('[data-import]').addEventListener('input', function () { q('[data-apply]').disabled = true; });         // a changed choice needs a new dry run
  q('[data-import]').addEventListener('submit', function (e) {
    e.preventDefault();
    if (!bundle) { say('Choose a bundle file first.', true); return; }
    request(true).then(function (p) { say(p.ok ? 'The dry run found nothing in the way.' : 'The dry run found items in the way (see the table).', !p.ok); plan(p); }).catch(fail);
  });
  q('[data-apply]').addEventListener('click', function () {
    request(false).then(function (p) { say('Imported ' + (p.counts.create + p.counts.update) + ' item(s).'); plan(p); q('[data-apply]').disabled = true; }).catch(fail);
  });
  q('[data-apply]').addEventListener('click', function () { setTimeout(imports, 600); });
  imports();
})();
