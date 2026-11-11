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
(function (g) { g.Drishti = g.Drishti || {}; })(typeof window !== 'undefined' ? window : globalThis); /* one namespace for everything the console puts on window (tools/dev/asset_prefix.py) */
/* Console-wide behaviour: theme menu. */
/* One message from a problem: its DRS code once, whether or not the detail already starts with it or repeats it (UX-10). */
window.Drishti.drsMessage = function (p, fallback) {
  p = p || {};
  var code = p.code || 'Error', detail = String(p.detail || fallback || '');
  if (/^DRS-\d+$/.test(code)) { detail = detail.split(code + ': ').join('').split(code + ' ').join('').split(code).join('').trim(); }
  return detail ? code + ': ' + detail : code;
};
/* "Back to where I was" on the not-active page (a command an administrator turned off): the page before, when it was one of ours. */
document.addEventListener('click', function (e) {
  var a = e.target && e.target.closest ? e.target.closest('[data-back]') : null;
  if (a && window.history.length > 1 && document.referrer.indexOf(window.location.origin + '/') === 0) { e.preventDefault(); window.history.back(); }
});
(function () {
  'use strict';
  var root = document.documentElement;
  var THEMES = ['terminal', 'light', 'wallstreet', 'blue', 'green', 'crimson', 'crimson-dark', 'terminal-green', 'terminal-crimson', 'terminal-blue'];

  function apply(theme) {
    if (THEMES.indexOf(theme) < 0) { return; }
    root.setAttribute('data-theme', theme);
    root.setAttribute('data-bs-theme', theme === 'light' || theme === 'crimson' ? 'light' : 'dark');
    try { localStorage.setItem('drishti.theme', theme); } catch (e) { /* ignore */ }
    if (root.hasAttribute('data-signed-in') && root.getAttribute('data-user-theme') !== theme) {   // follow the user to other browsers
      root.setAttribute('data-user-theme', theme);
      fetch('/api/settings', { method: 'PATCH', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ theme: theme }) })
        .catch(function () { /* offline: this browser still remembers */ });
    }
    document.querySelectorAll('[data-theme-choice]').forEach(function (b) {
      b.classList.toggle('active', b.getAttribute('data-theme-choice') === theme);
    });
    document.dispatchEvent(new CustomEvent('drishti:theme', { detail: theme }));
  }

  // Business date: picking a date shows that day's data as a static snapshot. Weekends and holidays roll back to
  // the previous business day on the server; the box says so before it submits.
  // the top bar's form only (an embedded pane has none): one exception here would stop the rest of this file, the
  // workspace's pane keys included
  // The Live toggle: green = live (streaming), red = paused. Pausing keeps the current business date as a static snapshot
  // (the date box stays choosable); resuming goes back to live. The choice is the existing as-of cookie, so it follows the
  // user across pages. A button, so Enter and Space work.
  var liveBtn = document.querySelector('.tbar [data-asof-live]');
  if (liveBtn) {
    liveBtn.addEventListener('click', function () {
      var on = liveBtn.getAttribute('aria-pressed') === 'true';
      var url = on ? liveBtn.getAttribute('data-pause-href') : liveBtn.getAttribute('data-live-href');
      if (on && url.indexOf('d=&') >= 0) { url = url.replace('d=&', 'd=' + new Date().toISOString().slice(0, 10) + '&'); }
      window.location.assign(url);
    });
  }
  // The page's own date fields and long checkbox lists are drishti-inputs' pickers (upgraded in place: the field keeps its name and
  // value, so forms submit as before); the as-of date is a business-date picker keyed by the server's holidays (data-holidays), from the
  // oldest kept date to the current business date, with T-1 and month-end: a weekend or holiday is refused in words, naming the
  // business day before it. A picked date shows that day's data as a static snapshot.
  var I = window.Drishti.elements && window.Drishti.elements.inputs;
  var asof = document.querySelector('form[data-asof]');
  var box = asof ? asof.querySelector('#asofDate') : null;
  if (box && !box.max) { var now = new Date(); box.max = box.getAttribute('data-current') || now.getFullYear() + '-' + String(now.getMonth() + 1).padStart(2, '0') + '-' + String(now.getDate()).padStart(2, '0'); }
  if (I) { I.upgradeFields(document, { offClass: 'qf-dep-off' }); }
  if (asof && box) {
    box.addEventListener('change', function () { if (box.value) { asof.submit(); } });
    // Known at (a picked date only): what the date looked like at that time; a restatement shows as a difference.
    var known = asof.querySelector('[data-asof-known]');
    if (known) { known.addEventListener('change', function () { asof.submit(); }); }
    var knownBtn = asof.querySelector('[data-known-toggle]');
    if (knownBtn && known) {
      knownBtn.addEventListener('click', function () {
        knownBtn.hidden = true; known.hidden = false; known.focus();
        if (known.showPicker) { try { known.showPicker(); } catch (e) { /* not allowed here: typing works */ } }
      });
    }
  }

  // Pack switcher: choose which of your packs to see.
  document.addEventListener('submit', function (e) {
    var f = e.target.closest('[data-packs]');
    if (!f) { return; }
    e.preventDefault();
    var active = [].map.call(f.querySelectorAll('input[name="pack"]:checked'), function (i) { return i.value; });
    if (!active.length) { return; }
    fetch('/api/packs', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ active: active }) })
      .then(function (r) { if (r.ok) { location.reload(); } });
  });

  document.addEventListener('click', function (e) {
    var b = e.target.closest('[data-packs-all], [data-packs-none]');
    if (!b) { return; }
    var all = b.hasAttribute('data-packs-all');
    b.closest('[data-packs]').querySelectorAll('input[name="pack"]').forEach(function (i) { i.checked = all; });
  });

  // F1: help for the screen you are on.
  // A view's About this page takes F1 first (drishti_about.js); a screen may register its own F1 (Capacity's section of the guide).
  window.Drishti.keys.register({ id: 'help.context', keys: ['F1', 'Alt+Shift+1'], scope: 'global', group: 'Help', description: 'Help for the screen you are on',
    typing: true, run: function () {
      var go = function () { window.location.href = '/help/context/' + encodeURIComponent(document.body.getAttribute('data-screen') || 'landing'); };
      var held = window.Drishti.beforeLeave ? window.Drishti.beforeLeave() : null;      // a page with an edit waiting sends it first (UX-22)
      if (held && held.then) { held.then(go, go); } else { go(); }
    } });

  document.addEventListener('click', function (e) {
    var b = e.target.closest('[data-theme-choice]');
    if (b) { apply(b.getAttribute('data-theme-choice')); }
  });
  apply(root.getAttribute('data-theme') || root.getAttribute('data-default-theme') || 'terminal');
})();

/* In a workspace pane (an embedded page): Alt+0..4 belong to the workspace, which moves the focus between its panes and
   its toolbar (drishti_workspace.js). The pane's document has the keys while it has the focus, so it hands them up, to its own
   origin only; the workspace acts only on messages from its own panes (UX-07). */
(function () {
  'use strict';
  if (window.parent === window || !document.body.classList.contains('embed')) { return; }
  window.Drishti.keys.register({ id: 'workspace.pane.handup', keys: ['Alt+0', 'Alt+1', 'Alt+2', 'Alt+3', 'Alt+4'], scope: 'screen', area: 'workspace-pane',
    group: 'Workspace', description: 'Move to pane 1 to 4 (0: the workspace bar), also from inside a pane', typing: true, capture: true,
    run: function (e, chord) {
      try { window.parent.postMessage({ type: 'drishti:key', n: +chord.slice(-1) }, location.origin); } catch (err) { /* not ours */ }
    } });
})();

/* Server colours (ADR-016): set from data attributes, since the content security policy allows no inline styles. */
(function () {
  'use strict';
  document.querySelectorAll('[data-srv-color]').forEach(function (el) {
    el.style.setProperty('--srv', el.getAttribute('data-srv-color'));
  });
})();

/* The Approvals count (HOW_IT_FITS.md section S2): the items waiting for the signed-in person, on the top bar and in the menu entry; asked
   once a page, after the page has drawn. */
(function () {
  'use strict';
  var top = document.querySelector('[data-approvals-top]');
  if (!top) { return; }
  var show = function (j) {
    if (!j || !(j.inbox > 0)) { return; }
    var n = String(j.inbox), soon = j.dueSoon > 0 ? ', ' + j.dueSoon + ' due soon' : '';
    top.textContent = n; top.hidden = false;
    var tool = top.closest('[data-approvals-tool]');
    if (tool) { tool.setAttribute('aria-label', n + ' approvals waiting for you' + soon); tool.classList.toggle('due', j.dueSoon > 0); }
    document.querySelectorAll('[data-approvals-count]').forEach(function (b) { b.textContent = n; b.hidden = false; b.setAttribute('aria-label', n + ' waiting'); });
  };
  var ask = function () {
    fetch('/approvals/api/count', { headers: { Accept: 'application/json' }, credentials: 'same-origin' })
      .then(function (r) { return r.ok ? r.json() : null; }).then(show).catch(function () { /* the count is a hint: no count, no badge */ });
  };
  if (document.readyState === 'complete') { setTimeout(ask, 300); } else { window.addEventListener('load', function () { setTimeout(ask, 300); }); }
})();
