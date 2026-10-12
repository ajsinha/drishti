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
/* Admin → Artifacts: the two tabs, the filters, Enable, Restore, Delete permanently and the BI file upload. Disable and Uninstall are the
   shared dialog of artifact-uninstall.js. Every call is same-origin JSON (the BI file is the raw body) to /admin/artifacts/api/...;
   the page is reloaded after a change and says what was done. */
(function () {
  'use strict';
  var root = document.querySelector('[data-artifacts]');
  var A = window.DrishtiArtifacts;
  if (!root || !A) { return; }
  function say(text, bad) { A.flash(text, bad); }

  // ---- tabs ------------------------------------------------------------------------------------------------------------------------
  var tabs = Array.prototype.slice.call(root.querySelectorAll('[data-art-tabs] [role=tab]'));
  function pick(name, focus) {
    tabs.forEach(function (t) {
      var on = t.getAttribute('data-tab') === name;
      t.setAttribute('aria-selected', on ? 'true' : 'false');
      t.tabIndex = on ? 0 : -1;
      root.querySelector('[data-pane=' + t.getAttribute('data-tab') + ']').hidden = !on;
      if (on && focus) { t.focus(); }
    });
    if (history.replaceState) { history.replaceState(null, '', name === 'uninstalled' ? '?tab=uninstalled' : location.pathname); }
  }
  tabs.forEach(function (t, i) {
    t.addEventListener('click', function () { pick(t.getAttribute('data-tab')); });
    t.addEventListener('keydown', function (e) {
      if (e.key === 'ArrowRight' || e.key === 'ArrowLeft') {
        e.preventDefault();
        var next = tabs[(i + (e.key === 'ArrowRight' ? 1 : tabs.length - 1)) % tabs.length];
        pick(next.getAttribute('data-tab'), true);
      }
    });
  });

  // ---- filters ---------------------------------------------------------------------------------------------------------------------
  var q = root.querySelector('[data-filter]'), fKind = root.querySelector('[data-kind-filter]'), fState = root.querySelector('[data-state-filter]'), fOrigin = root.querySelector('[data-origin-filter]');
  var rows = Array.prototype.slice.call(root.querySelectorAll('[data-artifact]'));
  var none = root.querySelector('[data-filter-none]');
  function filter() {
    var text = q.value.trim().toLowerCase(), shown = 0;
    rows.forEach(function (r) {
      var ok = (!text || r.getAttribute('data-text').indexOf(text) >= 0) && (!fKind.value || r.getAttribute('data-what') === fKind.value)
        && (!fState.value || r.getAttribute('data-state') === fState.value) && (!fOrigin.value || r.getAttribute('data-origin') === fOrigin.value);
      r.hidden = !ok;
      if (ok) { shown++; }
    });
    none.hidden = shown > 0 || rows.length === 0;
  }
  [q, fKind, fState, fOrigin].forEach(function (c) { c.addEventListener('input', filter); c.addEventListener('change', filter); });
  document.addEventListener('keydown', function (e) {
    var t = e.target, typing = t && (/^(INPUT|TEXTAREA|SELECT)$/.test(t.tagName) || t.isContentEditable);
    if (typing || e.ctrlKey || e.metaKey || e.altKey || document.querySelector('dialog[open]')) { return; }
    if (e.key === '/') { e.preventDefault(); pick('installed'); q.focus(); }
  });

  // ---- Enable ----------------------------------------------------------------------------------------------------------------------
  root.addEventListener('click', function (e) {
    var b = e.target.closest('[data-act=enable]');
    if (!b) { return; }
    var tr = b.closest('[data-artifact]');
    b.disabled = true;
    A.call('POST', 'enable', { kind: tr.getAttribute('data-kind'), subtype: tr.getAttribute('data-subtype'), name: tr.getAttribute('data-name') }).then(function (res) {
      if (res.ok) { A.done('Enabled ' + tr.getAttribute('data-what') + ' ' + tr.getAttribute('data-name') + '.'); } else { b.disabled = false; say(A.words(res.body), true); }
    });
  });

  // ---- Restore and Delete permanently -----------------------------------------------------------------------------------------------
  function restore(id, name, replace) {
    return A.call('POST', 'uninstalled/' + encodeURIComponent(id) + '/restore', { replace: replace, confirm: replace ? name : '' });
  }
  root.addEventListener('click', function (e) {
    var tr = e.target.closest('[data-archive]');
    if (!tr) { return; }
    var id = tr.getAttribute('data-archive'), name = tr.getAttribute('data-name');
    if (e.target.closest('[data-restore]')) {
      restore(id, name, false).then(function (res) {
        if (res.ok) { A.done('Restored ' + name + ': ' + (res.body.note || 'done') + '.'); return; }
        if (res.status === 409 && res.body.code === 'DRS-5042') {
          say(A.words(res.body), true);
          A.typed({ title: 'Replace ' + name + '?', name: name, ok: 'Replace it',
            text: 'A ' + 'one with this name is there now. Replacing archives the current one first, then puts this one back.' }).then(function (typed) {
            if (!typed) { return; }
            restore(id, name, true).then(function (r2) {
              if (r2.ok) { A.done('Restored ' + name + ', replacing the one that was there (it is archived).'); } else { say(A.words(r2.body), true); }
            });
          });
          return;
        }
        say(A.words(res.body), true);
      });
    } else if (e.target.closest('[data-purge]')) {
      A.typed({ title: 'Delete ' + name + ' for good?', name: name, ok: 'Delete permanently',
        text: 'This removes the archive ' + id + ' and everything in it. It cannot be undone.' }).then(function (typed) {
        if (!typed) { return; }
        A.call('DELETE', 'uninstalled/' + encodeURIComponent(id) + '?confirm=' + encodeURIComponent(typed)).then(function (res) {
          if (res.ok) { A.done('Deleted the archive of ' + name + ' permanently.'); } else { say(A.words(res.body), true); }
        });
      });
    }
  });

  // ---- a BI file: check it, see the impact, place it ----------------------------------------------------------------------------------
  var card = root.querySelector('[data-bi-install]'), file = card.querySelector('[data-bi-file]'), out = card.querySelector('[data-bi-result]');
  var place = card.querySelector('[data-bi-place]'), repRow = card.querySelector('[data-bi-replace-row]'), rep = card.querySelector('[data-bi-replace]');
  var picked = null;
  function send(path, f, ok) {
    return fetch('/admin/artifacts/api/' + path, { method: 'POST', headers: { 'Content-Type': 'application/octet-stream' }, body: f })
      .then(function (r) { return r.json().catch(function () { return {}; }).then(function (b) { return { ok: r.ok, status: r.status, body: b }; }); });
  }
  function lines(cls, items) {
    var ul = A.el('ul', cls);
    items.forEach(function (t) { ul.appendChild(A.el('li', '', t)); });
    return ul;
  }
  file.addEventListener('change', function () {
    out.textContent = ''; place.hidden = true; repRow.hidden = true; rep.checked = false; picked = file.files[0] || null;
    if (!picked) { return; }
    send('bi/check?file=' + encodeURIComponent(picked.name), picked).then(function (res) {
      if (!res.ok) { out.appendChild(A.el('p', 't-bad', A.words(res.body))); return; }
      var b = res.body;
      out.appendChild(A.el('p', b.valid ? 't-ok' : 't-bad', b.valid ? 'Valid ' + b.subtype + ' ' + b.name + '.' : 'Not valid; nothing was written.'));
      if (b.problems.length) { out.appendChild(lines('t-bad', b.problems)); }
      if (b.warnings.length) { out.appendChild(lines('t-warn', b.warnings)); }
      if (b.exists) {
        out.appendChild(A.el('p', '', 'A ' + b.subtype + ' named ' + b.name + ' is already in config/bi. Replacing it affects: ' + b.replacesImpact + '.'));
        repRow.hidden = false;
      }
      place.hidden = !b.valid;
      place.disabled = b.exists;
    });
  });
  rep.addEventListener('change', function () { place.disabled = !rep.checked; });
  place.addEventListener('click', function () {
    if (!picked) { return; }
    var name = picked.name.split('.')[0], replacing = rep.checked;
    function go(confirmName) {
      send('bi?file=' + encodeURIComponent(picked.name) + '&replace=' + (replacing ? 'true' : 'false') + '&confirm=' + encodeURIComponent(confirmName || ''), picked).then(function (res) {
        if (res.ok) { A.done('Placed ' + picked.name + ' in config/bi.' + ((res.body.also || []).length ? ' ' + res.body.also.join('; ') + '.' : '')); } else { say(A.words(res.body), true); }
      });
    }
    if (!replacing) { go(''); return; }
    A.typed({ title: 'Replace ' + name + '?', name: name, ok: 'Replace it', text: 'The file that is there is archived first, then this one is placed.' }).then(function (typed) { if (typed) { go(typed); } });
  });
})();
