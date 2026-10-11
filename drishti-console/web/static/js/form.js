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
/* Query by form (FORM <pack>). The page works without this script (a plain GET form); the script only makes it pleasanter:
   the second box appears for "between", a date field becomes free text for a relative range, Esc clears every field,
   Ctrl+Enter submits from a list box, and the result's buttons copy, save and share. The criteria live in the address. */
(function () {
  'use strict';
  var form = document.querySelector('[data-qform]');

  /* the forms home lists the last forms opened here, per browser and person: pack, kind, a label */
  (function remember() {
    var on = document.querySelector('[data-recent-form]');
    if (!on) { return; }
    try {
      var key = 'drishti.forms.recent.' + (on.getAttribute('data-who') || ''), list = JSON.parse(localStorage.getItem(key) || '[]');
      var href = on.getAttribute('data-recent-form');
      list = [{ href: href, label: on.getAttribute('data-label') }].concat(list.filter(function (r) { return r.href !== href; })).slice(0, 8);
      localStorage.setItem(key, JSON.stringify(list));
    } catch (e) { /* storage refused: no recents, nothing else lost */ }
  })();

  function each(list, fn) { Array.prototype.forEach.call(list, fn); }

  function syncField(box) {
    var op = box.querySelector('.qf-op'), type = box.getAttribute('data-type');
    var v = box.querySelector('.qf-v'), v2 = box.querySelector('.qf-v2');
    var kind = op ? op.value : '';
    if (v2) { v2.hidden = kind !== 'between'; }
    if (type === 'date' && v) {
      var rel = kind === 'relative';
      if ((v.type === 'text') !== rel) { v.value = ''; v.type = rel ? 'text' : 'date'; }
      v.placeholder = rel ? 'last 5 business days' : '';
      v.setAttribute('list', rel ? 'qf-presets' : '');
      if (!rel) { v.removeAttribute('list'); }
    }
  }

  function clearAll() {
    each(form.querySelectorAll('input, textarea, select'), function (e) {
      if (e.type === 'hidden') { return; }
      if (e.type === 'checkbox') { e.checked = false; }
      else if (e.type === 'radio') { e.checked = e.value === 'any'; }
      else if (e.tagName === 'SELECT') { e.selectedIndex = e.name.indexOf('op.') === 0 || e.name === 'limit' ? e.selectedIndex : 0; }
      else { e.value = ''; }
    });
    each(form.querySelectorAll('.qf-field'), syncField);
    var first = form.querySelector('.qf-field .qf-v, .qf-field input:not([type=hidden])');
    if (first) { first.focus(); }
  }

  // Hide form / Show form: the criteria stay in the page (only hidden), so Show form brings them back as they were. The state is in the
  // address (form=hidden or form=shown) and, for this tab, the last choice made by hand is kept: after a Search a form the person
  // has kept open stays open (the setting form.collapse_on_submit decides for everyone else).
  var body = document.getElementById('qfBody'), toggle = document.querySelector('[data-qf-toggle]'), brief = document.querySelector('[data-qf-summary]');
  function folded() { return body.hidden; }
  function fold(hide, by) {
    body.hidden = hide; brief.hidden = !hide;
    toggle.setAttribute('aria-expanded', hide ? 'false' : 'true');
    toggle.querySelector('[data-qf-toggle-text]').textContent = hide ? 'Show form' : 'Hide form';
    toggle.title = (hide ? 'Show' : 'Hide') + ' the form (Alt+F)';
    var icon = toggle.querySelector('i'); if (icon) { icon.className = 'bi bi-chevron-' + (hide ? 'down' : 'up'); }
    var u = new URL(location.href); u.searchParams.set('form', hide ? 'hidden' : 'shown');
    history.replaceState(null, '', u.pathname + u.search);
    if (by) { try { sessionStorage.setItem('drishti.form', hide ? 'hidden' : 'shown'); } catch (e) { /* no storage: the address still carries it */ } }
    if (by === 'show') { var first = form.querySelector('.qf-field .qf-v, .qf-field input:not([type=hidden])'); if (first) { first.focus(); } }
    else if (by === 'hide') { toggle.focus(); }
  }
  if (form && body && toggle && brief) {
    toggle.addEventListener('click', function () { fold(!folded(), folded() ? 'show' : 'hide'); });
    brief.addEventListener('click', function (e) { e.preventDefault(); fold(false, 'show'); });
    document.addEventListener('keydown', function (e) {
      if (e.altKey && !e.ctrlKey && !e.metaKey && !e.shiftKey && (e.code === 'KeyF' || e.key === 'f')) { e.preventDefault(); fold(!folded(), folded() ? 'show' : 'hide'); }
    });
    form.addEventListener('submit', function () {
      var kept = ''; try { kept = sessionStorage.getItem('drishti.form') || ''; } catch (e) { kept = ''; }
      if (kept === 'shown') { var h = document.createElement('input'); h.type = 'hidden'; h.name = 'form'; h.value = 'shown'; form.appendChild(h); }
    });
  }

  if (form) {
    each(form.querySelectorAll('.qf-field'), function (box) {
      syncField(box);
      var op = box.querySelector('.qf-op');
      if (op) { op.addEventListener('change', function () { syncField(box); }); }
    });
    form.addEventListener('keydown', function (e) {
      if (e.key === 'Escape' && !e.defaultPrevented) {
        e.preventDefault(); clearAll();
      } else if (e.key === 'Enter' && (e.ctrlKey || e.metaKey) && e.target.tagName === 'TEXTAREA') {
        e.preventDefault(); form.requestSubmit ? form.requestSubmit() : form.submit();
      }
    });
    var clear = document.querySelector('[data-qf-clear]');
    if (clear) { clear.addEventListener('click', clearAll); }
    form.addEventListener('submit', function () {
      // empty boxes stay out of the address: only what was filled in is a criterion
      each(form.querySelectorAll('.qf-v, .qf-v2'), function (e) { if (!e.value.trim()) { e.disabled = true; } });
      each(form.querySelectorAll('.qf-field'), function (box) {
        var v = box.querySelector('.qf-v'), op = box.querySelector('.qf-op');
        if (op && v && v.disabled) { op.disabled = true; }
        if (box.getAttribute('data-type') === 'text' && v && v.disabled) {
          var cs = box.querySelector('[name^="cs."]'); if (cs) { cs.disabled = true; }
        }
      });
    });
    window.addEventListener('pageshow', function () { each(form.querySelectorAll(':disabled'), function (e) { e.disabled = false; }); });
  }

  function copy(text, button) {
    function done() { var old = button.innerHTML; button.textContent = 'Copied'; setTimeout(function () { button.innerHTML = old; }, 1500); }
    if (navigator.clipboard && navigator.clipboard.writeText) { navigator.clipboard.writeText(text).then(done, function () { window.prompt('Copy:', text); }); }
    else { window.prompt('Copy:', text); }
  }
  each(document.querySelectorAll('[data-qf-copy]'), function (b) {
    b.addEventListener('click', function () { copy(b.getAttribute('data-qf-copy'), b); });
  });
  each(document.querySelectorAll('[data-qf-share]'), function (b) {
    b.addEventListener('click', function () { copy(location.origin + b.getAttribute('data-qf-share'), b); });
  });
  each(document.querySelectorAll('[data-qf-save]'), function (f) {
    f.addEventListener('submit', function (e) {
      e.preventDefault();
      var out = f.querySelector('[data-qf-saved]');
      fetch('/form/save', { method: 'POST', headers: { 'Content-Type': 'application/json', Accept: 'application/json' },
        body: JSON.stringify({ name: f.elements.name.value, path: f.getAttribute('data-qf-save') }) })
        .then(function (r) { return r.json().then(function (b) { return { ok: r.ok, body: b }; }); })
        .then(function (r) { out.textContent = r.ok ? 'Saved. Find it under your saved searches.' : (r.body.code + ': ' + r.body.detail); })
        .catch(function () { out.textContent = 'Could not save: the console did not answer.'; });
    });
  });
})();
