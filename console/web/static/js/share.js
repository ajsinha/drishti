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
/* Share with a note (COLLABORATION.md, Share with a note): the dialog behind the view's Share button, a panel's share button and Alt+S.
   It asks the console what the caller may do (GET /api/collab, once); when collaboration is off or the caller may not collaborate the
   button stays what it was, a copy-link button. Otherwise: what (the whole view or one panel), the page's own pin (shown, never typed),
   the people (type-ahead, people.js), a note, Send (POST /api/share, Ctrl+Enter), and Copy link kept as the first, one-click use. The
   server decides who may be reached and tells, per name, what happened; this file only shows it. Keys are ignored while typing in a
   field. A phone gets a full-screen sheet (collab.css). The focus is trapped inside the dialog and returns to the opener on close. */
(function () {
  'use strict';
  var view = document.querySelector('[data-view]');
  var root = document.querySelector('[data-share-dialog]');
  var main = document.querySelector('button[data-share]');
  var conf = null, confAsked = null;

  function $(sel, from) { return (from || root).querySelector(sel); }
  function el(tag, cls, text) { var n = document.createElement(tag); if (cls) { n.className = cls; } if (text != null) { n.textContent = text; } return n; }
  function typing(t) { return t && (t.isContentEditable || /^(INPUT|TEXTAREA|SELECT)$/.test(t.tagName)); }

  function config() {
    if (conf) { return Promise.resolve(conf); }
    if (!confAsked) {
      confAsked = fetch('/api/collab' + (view && view.getAttribute('data-kind') ? '?kind=' + encodeURIComponent(view.getAttribute('data-kind')) : ''), { headers: { Accept: 'application/json' }, credentials: 'same-origin' })
        .then(function (r) { return r.ok ? r.json() : { enabled: false }; })
        .catch(function () { return { enabled: false }; })
        .then(function (c) { conf = c || { enabled: false }; return conf; });
    }
    return confAsked;
  }
  function usable(c) { return !!(c && c.enabled && c.collaborate); }

  // ---- copy link (the old one-click use) -------------------------------------------------------------------
  function copy(text, done) {
    if (navigator.clipboard && navigator.clipboard.writeText) {
      navigator.clipboard.writeText(text).then(function () { done(true); }, function () { window.prompt('Copy this link', text); done(false); });
    } else { window.prompt('Copy this link', text); done(false); }
  }

  if (!view || !root || !main) {                       // a page without the dialog (a workspace pane): nothing to wire
    window.DrishtiShare = null;
    return;
  }

  var form = $('[data-shr-form]'), chips = $('[data-shr-chips]'), to = $('[data-shr-to]'), noteBox = $('[data-shr-note]');
  var warn = $('[data-shr-warn]'), result = $('[data-shr-result]'), count = $('[data-shr-count]'), send = $('[data-shr-send]');
  var picked = [], sending = false, opener = null, panel = '', sent = false, picker = null;

  function taken() { var t = {}; picked.forEach(function (p) { t[p.type + ':' + p.name] = true; }); return t; }

  function drawChips() {
    chips.textContent = '';
    picked.forEach(function (p, i) {
      var li = el('li', 'shr-chip' + (p.type === 'role' ? ' role' : ''));
      li.appendChild(el('span', null, p.type === 'role' ? p.name + ' (role' + (p.size != null ? ', ' + p.size : '') + ')' : (p.displayName || p.name)));
      var x = el('button', 'shr-chip-x', '×'); x.type = 'button'; x.setAttribute('aria-label', 'Remove ' + p.name);
      x.addEventListener('click', function () { picked.splice(i, 1); drawChips(); to.focus(); });
      li.appendChild(x); chips.appendChild(li);
    });
  }

  function limit() { return (conf && conf.maxText) || 2000; }
  function counter() { count.textContent = noteBox.value.length + '/' + limit(); count.classList.toggle('over', noteBox.value.length > limit()); }

  function say(text, bad) { result.textContent = text || ''; result.classList.toggle('bad', !!bad); }

  // ---- the picture (a watermarked snapshot, only where the server allows it) -------------------------------------
  var picRow = $('[data-shr-picture-row]'), picBox = $('[data-shr-picture]'), picBtn = $('[data-shr-preview]'), picImg = $('[data-shr-preview-img]'),
      picMsg = $('[data-shr-picture-msg]');
  function picSay(t, bad) { picMsg.textContent = t || ''; picMsg.classList.toggle('bad', !!bad); }
  function picReset() { picBox.checked = false; picBtn.hidden = true; picImg.hidden = true; picImg.removeAttribute('src'); picSay(''); picRow.hidden = !(conf && conf.snapshots); }
  function preview() {
    if (!picked.length) { picSay('Choose who to send to first: the picture is made for them.', true); return; }
    picSay('Drawing the preview…'); picImg.hidden = true;
    fetch('/api/share/preview-picture', { method: 'POST', headers: { 'Content-Type': 'application/json', Accept: 'image/png' }, credentials: 'same-origin', body: JSON.stringify(body()) })
      .then(function (r) {
        if (!r.ok) { return r.json().catch(function () { return {}; }).then(function (j) { picSay((j.code ? j.code + ': ' : '') + (j.detail || 'no preview'), true); }); }
        return r.blob().then(function (b) {
          var fr = new FileReader();                                  // a data: URL, the page's img-src allows 'self' and data:
          fr.onload = function () { picImg.src = fr.result; picImg.hidden = false; picSay('This is what the people you chose will get.'); };
          fr.readAsDataURL(b);
        });
      })
      .catch(function () { picSay('The console could not be reached. Try again.', true); });
  }
  picBox.addEventListener('change', function () { picBtn.hidden = !picBox.checked; if (picBox.checked) { preview(); } else { picImg.hidden = true; picSay(''); } });
  picBtn.addEventListener('click', preview);

  function pinText(live) {
    var asof = main.getAttribute('data-share-asof') || 'live', known = main.getAttribute('data-share-known-text');
    if (live || asof === 'live') { return 'live: whoever opens it sees the data as it is then'; }
    return asof + (known ? ' as known at ' + known : '') + ' (as you see it now)';
  }

  function url() { return main.getAttribute('data-share') + (panel ? '#p-' + encodeURIComponent(panel) : ''); }

  function reset() {
    picked = []; drawChips(); noteBox.value = ''; counter(); say(''); warn.hidden = true; sent = false; sending = false;
    send.disabled = false; send.firstChild.textContent = 'Send '; $('[data-shr-copy-label]').textContent = 'Copy link'; to.value = '';
    picReset();
  }

  function show(forPanel, from) {
    opener = from || document.activeElement;
    panel = forPanel || '';
    reset();
    var name = main.getAttribute('data-share-title') || '';
    $('[data-shr-title]').textContent = name;
    var opt = $('[data-shr-panel-opt]');
    if (panel) {
      var p = document.getElementById('p-' + panel), h = p && p.querySelector('h3');
      $('[data-shr-panel-name]').textContent = (h && h.textContent.trim()) || panel;
      opt.hidden = false; form.elements.what.value = 'panel';
    } else { opt.hidden = true; form.elements.what.value = 'view'; }
    var asof = main.getAttribute('data-share-asof') || 'live';
    var liveOpt = $('[data-shr-live-opt]');
    liveOpt.hidden = asof === 'live';                 // a live page's link is live already
    form.elements.live.checked = false;
    form.elements.postToThread.checked = !!(conf && conf.postToThread);      // the server's default (drishti.collab.share.post-to-thread); the sender may change it
    $('[data-shr-asof]').textContent = pinText(false);
    root.hidden = false;
    document.body.classList.add('shr-open');
    $('#shrTitle').focus();
    to.focus();
  }

  function close() {
    if (root.hidden) { return; }
    if (picker) { picker.close(); }
    root.hidden = true;
    document.body.classList.remove('shr-open');
    if (opener && opener.isConnected) { opener.focus(); } else { main.focus(); }
  }

  function open(btn, fallback) {
    config().then(function (c) {
      if (!usable(c)) { if (fallback) { fallback(); } return; }
      show(btn && btn.getAttribute && btn.getAttribute('data-share-panel') != null ? btn.getAttribute('data-share-panel') : '', btn);
    });
  }

  // ---- sending ---------------------------------------------------------------------------------------------
  function body() {
    var users = [], roles = [];
    picked.forEach(function (p) { (p.type === 'role' ? roles : users).push(p.name); });
    var what = form.elements.what.value, live = form.elements.live.checked || main.getAttribute('data-share-asof') === 'live';
    var b = {
      kind: view.getAttribute('data-kind'), id: view.getAttribute('data-id'), note: noteBox.value.trim(),
      generation: parseInt(view.getAttribute('data-generation') || '0', 10) || 0,
      to: { users: users, roles: roles }, channels: { inApp: true }, live: !!live, picture: !!(picBox.checked && conf && conf.snapshots), postToThread: !!form.elements.postToThread.checked,
      asOf: main.getAttribute('data-share-asof') || 'live', knownAt: main.getAttribute('data-share-known') || ''
    };
    if (what === 'panel' && panel) {
      b.panel = panel;
      var p = document.getElementById('p-' + panel), g = p && p.getAttribute('data-gate-kind');
      if (g) { b.gateKind = g; }
    }
    return b;
  }

  function report(r) {
    var parts = ['Sent to ' + r.delivered + (r.delivered === 1 ? ' person.' : ' people.')];
    (r.skipped || []).forEach(function (s) { parts.push('Not sent to ' + s.name + ': ' + s.reason + '.'); });
    say(parts.join(' '));
    if (r.warnings && r.warnings.length) { warn.textContent = r.warnings.join(' '); warn.hidden = false; }
  }

  function submit() {
    if (sending) { return; }
    if (sent) { close(); return; }
    var typed = to.value.trim();
    if (!picked.length && typed) { say('Pick the name from the list, so it is clear who it is.', true); to.focus(); return; }
    if (!picked.length) { say('Choose at least one person or role to send to.', true); to.focus(); return; }
    if (noteBox.value.trim().length > limit()) { say('The note is longer than ' + limit() + ' characters.', true); noteBox.focus(); return; }
    sending = true; send.disabled = true; say('Sending…'); warn.hidden = true;
    fetch('/api/share', { method: 'POST', headers: { 'Content-Type': 'application/json', Accept: 'application/json' }, credentials: 'same-origin', body: JSON.stringify(body()) })
      .then(function (r) { return r.json().catch(function () { return {}; }).then(function (j) { return { ok: r.ok, j: j }; }); })
      .then(function (r) {
        sending = false; send.disabled = false;
        if (!r.ok) { say((r.j.code ? r.j.code + ': ' : '') + (r.j.detail || 'the share could not be sent'), true); return; }
        sent = true; report(r.j);
        send.firstChild.textContent = 'Done ';
        send.focus();
      })
      .catch(function () { sending = false; send.disabled = false; say('The console could not be reached. Try again.', true); });
  }

  // ---- wiring ----------------------------------------------------------------------------------------------
  picker = window.DrishtiPeople ? window.DrishtiPeople.attach(to, $('[data-shr-to]').parentNode.querySelector('.shr-list'), {
    kind: view.getAttribute('data-kind'), minQuery: 2, taken: taken,
    onPick: function (e) { picked.push(e); drawChips(); say(''); to.focus(); }
  }) : null;
  config().then(function (c) {
    counter();
  });
  noteBox.addEventListener('input', counter);
  form.addEventListener('submit', function (e) { e.preventDefault(); submit(); });
  form.elements.live.addEventListener('change', function () { $('[data-shr-asof]').textContent = pinText(form.elements.live.checked); });
  $('[data-shr-close]').addEventListener('click', close);
  $('[data-shr-cancel]').addEventListener('click', close);
  root.addEventListener('mousedown', function (e) { if (e.target === root) { close(); } });
  $('[data-shr-copy]').addEventListener('click', function () {
    var label = $('[data-shr-copy-label]');
    copy(url(), function (ok) { label.textContent = ok ? 'Link copied' : 'Copy link'; });
  });
  to.addEventListener('keydown', function (e) {
    if (e.key === 'Backspace' && !to.value && picked.length) { picked.pop(); drawChips(); }
  });
  root.addEventListener('keydown', function (e) {                    // bubbling: the picker closes its own list first
    if (e.key === 'Escape') { e.preventDefault(); close(); return; }
    if (e.key === 'Enter' && (e.ctrlKey || e.metaKey)) { e.preventDefault(); submit(); return; }
    if (e.key === 'Tab') {                                           // the focus stays inside the dialog
      var f = Array.prototype.filter.call(root.querySelectorAll('a[href], button:not([disabled]), input:not([type=hidden]), textarea, select'),
        function (n) { return n.offsetParent !== null; });
      if (!f.length) { return; }
      if (e.shiftKey && (document.activeElement === f[0] || !root.contains(document.activeElement))) { e.preventDefault(); f[f.length - 1].focus(); }
      else if (!e.shiftKey && document.activeElement === f[f.length - 1]) { e.preventDefault(); f[0].focus(); }
    }
  });

  // a panel's own share button, put on each panel once we know sharing is on
  config().then(function (c) {
    if (!usable(c)) { return; }
    main.setAttribute('aria-haspopup', 'dialog');
    document.querySelectorAll('.pnl[data-panel] .pnl-code').forEach(function (code) {
      var id = code.closest('.pnl').getAttribute('data-panel'), name = (code.closest('.pnl').querySelector('h3') || {}).textContent || id;
      var b = el('button', 'pnl-help'); b.type = 'button';
      b.setAttribute('data-share-panel', id);
      b.title = 'Share this panel'; b.setAttribute('aria-label', 'Share ' + name.trim());
      var i = el('i', 'bi bi-send'); i.setAttribute('aria-hidden', 'true'); b.appendChild(i);
      b.addEventListener('click', function () { open(b); });
      code.appendChild(b);
    });
  });

  document.addEventListener('keydown', function (e) {
    if (e.altKey && !e.ctrlKey && !e.metaKey && !e.shiftKey && (e.code === 'KeyS' || e.key === 's' || e.key === 'S') && !typing(e.target) && root.hidden) {
      e.preventDefault(); main.click();
    }
  });

  window.DrishtiShare = { open: open, close: close };
})();
