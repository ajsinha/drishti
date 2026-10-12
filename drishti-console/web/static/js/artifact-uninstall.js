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
/* The safe uninstall, shared by Admin → Artifacts, Packs, Connectors and BI settings. A button with data-uninstall (data-kind,
   data-subtype, data-name) opens the dialog from _uninstall_dialog.html. The dialog asks the server what depends on the artifact before
   offering anything: with nothing depending on it, Uninstall moves it to the archive; with dependants, Uninstall is refused and the
   default is Disable (kept on disk, switched off); taking the dependants along needs the name typed. Every call is same-origin JSON to
   /admin/artifacts/api/...; the page is reloaded after a change and shows what was done (sessionStorage carries the sentence). */
(function () {
  'use strict';
  var API = '/admin/artifacts/api/';
  var FLASH = 'drishti.artifacts.flash';
  var dlg = document.querySelector('[data-uninstall-dialog]');
  var typedDlg = document.querySelector('[data-typed-dialog]');

  function call(method, path, body) {
    return fetch(API + path, { method: method, headers: { 'Content-Type': 'application/json' }, body: body ? JSON.stringify(body) : undefined })
      .then(function (r) { return r.json().catch(function () { return {}; }).then(function (b) { return { ok: r.ok, status: r.status, body: b }; }); });
  }
  function words(b) { return (b.code ? b.code + ' ' : '') + (b.detail || 'the server refused'); }
  function show(d) { if (d.showModal) { d.showModal(); } else { d.setAttribute('open', ''); } }
  function hide(d) { if (d.close) { d.close(); } else { d.removeAttribute('open'); } }
  function el(tag, cls, text) { var e = document.createElement(tag); if (cls) { e.className = cls; } if (text !== undefined) { e.textContent = text; } return e; }

  function flash(text, bad) {
    var m = document.querySelector('.adm-msg[data-msg]');
    if (!m) { return; }
    m.textContent = text;
    m.classList.toggle('t-bad', !!bad);
    m.classList.toggle('t-ok', !bad);
  }
  function showStoredFlash() {
    var t = null;
    try { t = sessionStorage.getItem(FLASH); sessionStorage.removeItem(FLASH); } catch (e) { t = null; }
    if (t) { flash(t, false); }
  }
  function done(text) {
    try { sessionStorage.setItem(FLASH, text); } catch (e) { /* the reload still shows the new state */ }
    location.reload();
  }

  // ---- a plain yes or no in the page's own dialog (never the browser's window.confirm) ---------------------------------------------
  var askDlg = document.querySelector('[data-ask-dialog]');
  function ask(opts) {
    return new Promise(function (resolve) {
      if (!askDlg) { resolve(window.confirm(opts.text)); return; }
      var yes = askDlg.querySelector('[data-ask-yes]'), no = askDlg.querySelector('[data-ask-no]'), settled = false;
      askDlg.querySelector('[data-ask-title]').textContent = opts.title || 'Are you sure?';
      askDlg.querySelector('[data-ask-text]').textContent = opts.text || '';
      yes.textContent = opts.ok || 'Go ahead';
      no.textContent = opts.cancel || 'Keep it as it is';
      yes.classList.toggle('emb-danger-btn', !!opts.danger);
      function finish(v) { if (settled) { return; } settled = true; yes.onclick = null; no.onclick = null; askDlg.onclose = null; hide(askDlg); resolve(v); }
      yes.onclick = function () { finish(true); };
      no.onclick = function () { finish(false); };
      askDlg.onclose = function () { finish(false); };
      show(askDlg);
      no.focus();
    });
  }

  // ---- a name to type, for the destructive cases -------------------------------------------------------------------------------
  function typed(opts) {
    return new Promise(function (resolve) {
      if (!typedDlg) { resolve(window.confirm(opts.text)); return; }
      var input = typedDlg.querySelector('[data-typed-input]'), yes = typedDlg.querySelector('[data-typed-yes]'), msg = typedDlg.querySelector('[data-typed-msg]');
      typedDlg.querySelector('[data-typed-title]').textContent = opts.title;
      typedDlg.querySelector('[data-typed-text]').textContent = opts.text;
      typedDlg.querySelector('[data-typed-name]').textContent = opts.name;
      yes.textContent = opts.ok || 'Go ahead';
      input.value = '';
      msg.textContent = '';
      function finish(v) { yes.onclick = null; typedDlg.querySelector('[data-typed-no]').onclick = null; typedDlg.onclose = null; hide(typedDlg); resolve(v); }
      yes.onclick = function () {
        if (input.value.trim() !== opts.name) { msg.textContent = 'The name does not match.'; msg.classList.add('t-bad'); input.focus(); return; }
        finish(input.value.trim());
      };
      typedDlg.querySelector('[data-typed-no]').onclick = function () { finish(false); };
      typedDlg.onclose = function () { resolve(false); };
      show(typedDlg);
      input.focus();
    });
  }

  // ---- the impact, as a list ---------------------------------------------------------------------------------------------------
  function drawImpact(host, impact) {
    host.textContent = '';
    (impact.groups || []).forEach(function (g) {
      var box = el('section', 'art-group');
      box.setAttribute('data-group', g.category);
      box.appendChild(el('h3', '', g.label + ' (' + g.count + ')'));
      var ul = el('ul', 'art-names');
      g.items.forEach(function (i) {
        var li = el('li');
        li.appendChild(el('span', 'mono', i.name));
        var more = [i.owner ? 'of ' + i.owner : '', i.detail].filter(Boolean).join(', ');
        if (more) { li.appendChild(el('span', 'text-muted-d', ' ' + more)); }
        ul.appendChild(li);
      });
      if (g.more > 0) { ul.appendChild(el('li', 'text-muted-d', 'and ' + g.more + ' more')); }
      box.appendChild(ul);
      host.appendChild(box);
    });
  }

  // ---- the dialog ---------------------------------------------------------------------------------------------------------------
  function open(o) {
    if (!dlg) { return; }
    var kind = o.kind, sub = o.subtype || '', name = o.name, mode = o.mode || 'uninstall';
    var what = kind === 'bi' ? sub : kind;
    var q = '?kind=' + encodeURIComponent(kind) + '&subtype=' + encodeURIComponent(sub) + '&name=' + encodeURIComponent(name);
    var $ = function (s) { return dlg.querySelector(s); };
    var title = $('[data-art-title]'), text = $('[data-art-text]'), host = $('[data-art-impact]'), notes = $('[data-art-notes]'), msg = $('[data-art-msg]');
    var casRow = $('[data-art-cascade-row]'), cas = $('[data-art-cascade]'), casLabel = $('[data-art-cascade-label]'), typedRow = $('[data-art-typed-row]'), typedIn = $('[data-art-typed]');
    var go = $('[data-art-go]'), dis = $('[data-art-disable]'), keep = $('[data-art-keep]');
    $('[data-art-name]').textContent = name;
    title.textContent = (mode === 'disable' ? 'Disable ' : 'Uninstall ') + what + ' ' + name;
    text.textContent = 'Checking what depends on it...';
    host.textContent = ''; notes.textContent = ''; msg.textContent = ''; msg.classList.remove('t-bad');
    casRow.hidden = true; typedRow.hidden = true; cas.checked = false; typedIn.value = '';
    casLabel.textContent = 'Also disable or uninstall what depends on it';
    go.hidden = true; dis.hidden = true; go.disabled = false; dis.disabled = false;
    var impact = null;

    function refresh() {
      var blocked = impact && impact.blocked, cascade = cas.checked;
      typedRow.hidden = !(mode === 'uninstall' && blocked && cascade);
      go.hidden = mode === 'uninstall' && ((impact && impact.templated) || (blocked && !cascade));   // in use: only Disable until the dependants are chosen too
      go.disabled = mode === 'uninstall' && blocked && !(cascade && typedIn.value.trim() === name);
      go.textContent = mode === 'disable' ? 'Disable' : blocked ? 'Uninstall with what depends on it' : 'Uninstall';
    }
    function run(action, cascade, confirmName) {
      msg.textContent = 'Working...'; msg.classList.remove('t-bad');
      go.disabled = true; dis.disabled = true;
      call('POST', action, { kind: kind, subtype: sub, name: name, cascade: !!cascade, confirm: confirmName || '' }).then(function (res) {
        if (res.ok) {
          hide(dlg);
          var b = res.body, also = (b.also && b.also.length) ? ' Also: ' + b.also.join('; ') + '.' : '';
          done((action === 'disable' ? 'Disabled ' : 'Uninstalled ') + what + ' ' + name + ': ' + (b.note || 'done') + '.' + also);
          return;
        }
        msg.textContent = words(res.body); msg.classList.add('t-bad');
        if (res.body.impact) { impact = res.body.impact; drawImpact(host, impact); }
        if (action === 'disable' && res.status === 409 && mode === 'uninstall') {   // a pack others build on: offer to switch those off too
          mode = 'disable';
          title.textContent = 'Disable ' + what + ' ' + name;
          text.textContent = 'Disabling keeps ' + name + ' on disk and switches it off. The packs that build on it have to be switched off with it.';
          casLabel.textContent = 'Also disable the packs that build on it';
          casRow.hidden = false; cas.checked = false; dis.hidden = true; go.hidden = false;
        }
        go.disabled = false; dis.disabled = false; refresh();
      });
    }
    keep.onclick = function () { hide(dlg); };
    cas.onchange = refresh;
    typedIn.oninput = refresh;
    dis.onclick = function () { run('disable', mode === 'disable' ? cas.checked : false, ''); };
    go.onclick = function () { run(mode === 'disable' ? 'disable' : 'uninstall', cas.checked, typedIn.value.trim()); };
    show(dlg);

    fetch(API + 'impact' + q).then(function (r) { return r.json().then(function (b) { return { ok: r.ok, body: b }; }); }).then(function (res) {
      if (!res.ok) { text.textContent = words(res.body); text.classList.add('t-bad'); return; }
      text.classList.remove('t-bad');
      impact = res.body;
      drawImpact(host, impact);
      (impact.notes || []).forEach(function (n) { notes.appendChild(el('li', '', n)); });
      var archiveNote = 'It is moved to the archive, not deleted, and can be restored from Uninstalled.';
      if (mode === 'disable') {
        text.textContent = impact.blocked
          ? 'Disabling keeps ' + name + ' on disk and switches it off. These depend on it and stop working while it is off.'
          : 'Disabling keeps ' + name + ' on disk and switches it off. Nothing depends on it.';
        casRow.hidden = !(what === 'pack' && impact.blocked);
        casLabel.textContent = 'Also disable the packs that build on it';
      } else if (impact.configPinned) {
        text.textContent = name + ' is named in drishti.packs.enabled (DRISHTI_PACKS), so it can only be disabled here: it stays on disk, switched off. To remove it, take it out of that setting and restart.';
      } else if (impact.templated) {
        text.textContent = name + ' cannot be uninstalled: a pack\'s template would write it back. Disable it, or reset it to the pack\'s default, instead.';
      } else if (impact.blocked) {
        text.textContent = name + ' is in use, so it cannot be uninstalled as it is. Disable it (it stays on disk, switched off) or choose to take what depends on it along. ' + archiveNote;
        casRow.hidden = false;
        dis.hidden = false;
      } else {
        text.textContent = 'Nothing depends on ' + name + '. ' + archiveNote;
      }
      if (impact.templated && mode === 'uninstall') { dis.hidden = false; }
      refresh();
      if (impact.configPinned && mode === 'uninstall') { go.hidden = true; casRow.hidden = true; typedRow.hidden = true; dis.hidden = false; }
      (dis.hidden ? (go.hidden ? keep : go) : dis).focus();
    });
  }

  document.addEventListener('click', function (e) {
    var b = e.target.closest('[data-uninstall]');
    if (b) { e.preventDefault(); open({ kind: b.getAttribute('data-kind'), subtype: b.getAttribute('data-subtype'), name: b.getAttribute('data-name'), mode: b.getAttribute('data-mode') }); }
  });
  showStoredFlash();
  window.drishtiAsk = ask;
  window.DrishtiArtifacts = { call: call, ask: ask, typed: typed, open: open, flash: flash, done: done, words: words, el: el };
})();
