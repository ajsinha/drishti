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
/* Admin → Commands (docs/admin/COMMANDS.md). The matrix is server-rendered: a row per command (grouped by pack, with a pack switch), the
   Everyone column, and greyed role columns for later. Ticking a box only records a change: changes are batched (Undo takes back the last
   one), and "Review N changes" opens the side panel, which says everywhere each change blocks, asks for a reason (kept in the audit log) and
   applies them in one call to /admin/api/commands. The page reloads after that, so it always shows what the server holds; a change made
   elsewhere (another administrator, another server) reaches it on the live channel. */
(function () {
  'use strict';
  var root = document.querySelector('[data-commands]');
  if (!root) { return; }
  function $(sel, el) { return (el || root).querySelector(sel); }
  function $$(sel, el) { return Array.prototype.slice.call((el || root).querySelectorAll(sel)); }
  function say(el, text, bad) { el.textContent = text; el.classList.toggle('t-bad', !!bad); el.classList.toggle('t-ok', !bad && !!text); }
  function esc(s) { return String(s == null ? '' : s).replace(/[&<>"]/g, function (c) { return { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[c]; }); }
  var msg = $('[data-msg]'), side = $('[data-side]'), undo = $('[data-undo]'), review = $('[data-review]'), pending = $('[data-pending]');
  var reason = $('[data-reason-input]'), apply = $('[data-apply]'), smsg = $('[data-side-msg]');
  var changes = [];                                   // [{scope, name, on}] in the order made; the newest change of a switch counts

  function original(box) {
    return box.getAttribute('data-switch') === 'pack' ? box.closest('tbody').getAttribute('data-pack-on') === 'true'
      : box.closest('tr').getAttribute('data-on') === 'true';
  }
  function key(c) { return c.scope + ':' + c.name; }
  function net() {                                    // the changes that still differ from what the server holds
    var last = {};
    changes.forEach(function (c) { last[key(c)] = c; });
    return Object.keys(last).map(function (k) { return last[k]; }).filter(function (c) {
      var box = $('[data-switch="' + c.scope + '"][data-name="' + c.name + '"]');
      return box && original(box) !== c.on;
    });
  }
  function paint() {
    var n = net();
    $$('[data-switch]').forEach(function (box) {
      var row = box.closest('tr');
      var changing = original(box) !== box.checked;
      row.classList.toggle('cmd-changing', changing);
      var note = $('.cmd-note', row);
      if (changing && !note) { note = document.createElement('span'); note.className = 'cmd-note'; (box.getAttribute('data-switch') === 'pack' ? $('th', row) : row.children[1]).appendChild(note); }
      if (note) { if (changing) { note.textContent = box.checked ? ' · turning on' : ' · turning off'; } else { note.remove(); } }
      if (box.getAttribute('data-switch') === 'command') { $('th', row).classList.toggle('cmd-off-code', !box.checked); }
    });
    pending.hidden = n.length === 0;
    pending.textContent = n.length + (n.length === 1 ? ' change' : ' changes') + ' not saved';
    undo.disabled = changes.length === 0;
    review.disabled = n.length === 0;
    review.textContent = n.length ? 'Review ' + n.length + (n.length === 1 ? ' change' : ' changes') : 'Review changes';
    if (!side.hidden) { open(); }
  }
  $$('[data-switch]').forEach(function (box) {
    box.addEventListener('change', function () {
      changes.push({ scope: box.getAttribute('data-switch'), name: box.getAttribute('data-name'), on: box.checked });
      paint();
    });
  });
  undo.addEventListener('click', function () {
    var c = changes.pop();
    if (!c) { return; }
    var box = $('[data-switch="' + c.scope + '"][data-name="' + c.name + '"]');
    var before = changes.filter(function (x) { return key(x) === key(c); }).pop();
    box.checked = before ? before.on : original(box);
    paint();
  });

  // ---- find and show ------------------------------------------------------------------------------------------------------
  var find = $('[data-find]'), show = $('[data-show]');
  function filter() {
    var q = find.value.trim().toLowerCase(), mode = show.value;
    $$('tbody[data-pack]').forEach(function (body) {
      var any = false, packHit = body.getAttribute('data-pack').indexOf(q) >= 0;
      $$('tr[data-command]', body).forEach(function (tr) {
        var on = $('[data-switch]', tr).checked;
        var hit = (!q || packHit || tr.getAttribute('data-text').indexOf(q) >= 0) && (mode === 'all' || (mode === 'on') === on);
        tr.hidden = !hit;
        any = any || hit;
      });
      body.hidden = !any;
    });
  }
  find.addEventListener('input', filter);
  show.addEventListener('change', filter);
  document.addEventListener('keydown', function (e) {
    if (e.key === '/' && document.activeElement && !/INPUT|TEXTAREA|SELECT/.test(document.activeElement.tagName)) { e.preventDefault(); find.focus(); }
  });

  // ---- the side panel: what each change blocks, the reason, apply ---------------------------------------------------------
  var BLOCKS = ['Typed command <span class="mono">{code} …</span>', 'Its pages by link or URL <span class="mono">/v/{kind}/…</span>',
    'Its API, CLI, exports and AI tools', 'Search and type-ahead suggestions', 'Links and panels about it on other pages'];
  var impacts = {};
  function title(n) {
    if (n.length === 1) { return 'Turn ' + n[0].name + (n[0].on ? ' on' : ' off'); }
    return 'Review ' + n.length + ' changes';
  }
  function open() {
    var n = net();
    if (!n.length) { side.hidden = true; return; }
    side.hidden = false;
    $('[data-side-title]').textContent = title(n);
    apply.textContent = title(n);
    apply.classList.toggle('cmd-danger', n.some(function (c) { return !c.on; }));
    $('[data-side-changes]').innerHTML = n.map(function (c) {
      var needed = c.scope === 'pack' && !c.on ? $('tbody[data-pack="' + c.name + '"]').getAttribute('data-needed-by') : null;
      return '<li data-change="' + esc(key(c)) + '"><b>' + (c.scope === 'pack' ? 'Pack ' : '') + '<span class="mono">' + esc(c.name) + '</span></b> '
        + (c.on ? 'on' : '<span class="t-bad">off</span>') + (needed ? ' <span class="t-bad">(needed by ' + esc(needed) + ': turn those off first)</span>' : '') + '</li>';
    }).join('');
    var off = n.filter(function (c) { return !c.on; }), blocks = $('[data-side-blocks]');
    blocks.previousElementSibling.hidden = blocks.hidden = !off.length;
    blocks.innerHTML = off.map(function (c) {
      if (c.scope === 'pack') {
        var codes = $$('tbody[data-pack="' + c.name + '"] tr[data-command]').map(function (tr) { return tr.getAttribute('data-command'); });
        return '<li>Every command of the pack <span class="mono">' + esc(c.name) + '</span>: <span class="mono">' + esc(codes.join(', ') || 'none') + '</span></li>';
      }
      var tr = $('tr[data-command="' + c.name + '"]'), kind = tr ? tr.getAttribute('data-kind') : '';
      var items = BLOCKS.map(function (b) { return '<li>' + b.replace('{code}', esc(c.name)).replace('{kind}', esc(kind)) + '</li>'; }).join('');
      return '<li><b class="mono">' + esc(c.name) + '</b><ul>' + items + '<li data-uses="' + esc(c.name) + '">' + uses(c.name) + '</li></ul></li>';
    }).join('');
    off.filter(function (c) { return c.scope === 'command' && !impacts[c.name]; }).forEach(function (c) {
      impacts[c.name] = 'asking';
      fetch('/admin/api/commands/' + encodeURIComponent(c.name) + '/impact', { headers: { Accept: 'application/json' } })
        .then(function (r) { return r.ok ? r.json() : null; })
        .then(function (j) { impacts[c.name] = j || { uses: [] }; var li = $('[data-uses="' + c.name + '"]'); if (li) { li.innerHTML = uses(c.name); } })
        .catch(function () { impacts[c.name] = { uses: [] }; });
    });
  }
  function uses(code) {
    var i = impacts[code];
    if (!i || i === 'asking') { return '<span class="text-muted-d">What reads it: looking…</span>'; }
    if (!i.uses || !i.uses.length) { return 'Nothing saved names it (BI datasets, searches, monitors, alerts, shares)'; }
    return i.uses.map(function (g) {
      return esc(g.label) + ' that read it (' + g.count + ': ' + esc(g.items.slice(0, 3).map(function (x) { return x.name; }).join(', ')) + (g.count > 3 ? '…' : '') + ')';
    }).join('; ');
  }
  review.addEventListener('click', function () { open(); reason.focus(); });
  $('[data-cancel]').addEventListener('click', function () { side.hidden = true; review.focus(); });
  apply.addEventListener('click', function () {
    var n = net();
    if (!n.length) { return; }
    apply.disabled = true;
    say(smsg, 'Applying…');
    fetch('/admin/api/commands', { method: 'POST', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ reason: reason.value.trim(), changes: n }) })
      .then(function (r) { return r.json().catch(function () { return {}; }).then(function (b) { return { ok: r.ok, body: b }; }); })
      .then(function (res) {
        apply.disabled = false;
        if (!res.ok) { say(smsg, window.drsMessage(res.body, 'the change was refused'), true); return; }
        changes = [];
        window.location.reload();
      })
      .catch(function () { apply.disabled = false; say(smsg, 'The console could not reach the server; nothing was changed.', true); });
  });

  // ---- history, and changes made elsewhere -------------------------------------------------------------------------------
  function history() {
    var body = $('[data-history] tbody');
    fetch('/admin/api/commands/history?limit=100', { headers: { Accept: 'application/json' } })
      .then(function (r) { return r.ok ? r.json() : { items: [] }; })
      .then(function (j) {
        var items = j.items || [];
        body.innerHTML = items.length ? items.map(function (e) {
          return '<tr><td class="mono">' + esc(String(e.at || '').slice(0, 19).replace('T', ' ')) + '</td><td>' + (e.scope === 'pack' ? 'Pack ' : '')
            + '<span class="mono">' + esc(e.name) + '</span></td><td>' + (e.on ? '<span class="st st-ok">on</span>' : '<span class="st st-bad">off</span>')
            + '</td><td>' + esc(e.actor) + '</td><td>' + esc(e.reason || '') + '</td><td class="mono">' + esc(e.node) + '</td></tr>';
        }).join('') : '<tr><td colspan="6" class="text-muted-d">No change yet: every command is on.</td></tr>';
      })
      .catch(function () { body.innerHTML = '<tr><td colspan="6" class="text-muted-d">The history could not be read.</td></tr>'; });
  }
  history();
  document.addEventListener('drishti:switches', function (e) {
    if (e.detail && e.detail.hash === root.getAttribute('data-hash')) { return; }
    if (!net().length) { window.location.reload(); return; }
    say(msg, 'The switches were changed elsewhere (another administrator or server). Apply or undo your changes, then reload to see them.', true);
  });
  paint();
})();
