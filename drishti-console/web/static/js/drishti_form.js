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
/* Query by form (FORM <pack>). The fields are drishti-inputs' Form, drawn from the schema the server sends with the page (core/forms.py
   form_schema) and live inside the page's own GET form: each control carries the address's name (op.<path>, v.<path>, v2.<path>,
   cs.<path>), so a search is the same address it always was. The second box appears for "between", a date gives way to words for a
   relative range, a dependent list offers only what goes with the values chosen in its parent, a long list (thousands of counterparties)
   is found by typing, Esc clears every field, Ctrl+Enter submits from a text box, and the result's buttons copy, save and share. */
(function () {
  'use strict';
  var form = document.querySelector('[data-qform]');
  var I = window.Drishti.elements && window.Drishti.elements.inputs;

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

  /* A constant the address carries (the one way a field compares): nothing to draw, the form writes its hidden input. */
  function constantWidget() { var s = document.createElement('span'); s.hidden = true; return { el: s }; }

  /* A long pick list: the values chosen as chips, and a field that finds the others as you type (the console asks /form/<pack>/values);
     picked ones stay when the search changes, so several can be chosen. */
  function bigWidget(w) {
    var o = w.field.options || {}, picked = (w.value || []).slice();
    var box = document.createElement('div'), chips = document.createElement('ul'), host = document.createElement('div');
    box.className = 'qf-big'; chips.className = 'qf-big-chips'; chips.setAttribute('aria-label', w.label + ': chosen');
    function draw() {
      chips.textContent = '';
      picked.forEach(function (v) {
        var li = document.createElement('li'), x = document.createElement('button');
        li.className = 'di-chip'; li.textContent = v + ' '; li.setAttribute('data-qf-big-value', v);
        x.type = 'button'; x.className = 'di-chip-x'; x.textContent = '×'; x.setAttribute('aria-label', 'Remove ' + v);
        x.addEventListener('click', function () { picked = picked.filter(function (y) { return y !== v; }); draw(); w.set(picked.slice()); });
        li.appendChild(x); chips.appendChild(li);
      });
    }
    var cb = new I.Combobox(host, { label: 'Find a ' + w.label, placeholder: 'Type to find among ' + (o.count || 'the') + ' values', minChars: 1, debounceMs: 200,
      source: function (q, signal) {
        return fetch(o.url + '&q=' + encodeURIComponent(q), { credentials: 'same-origin', signal: signal })
          .then(function (r) { return r.ok ? r.json() : { values: [] }; })
          .then(function (res) { return (res.values || []).filter(function (v) { return picked.indexOf(v) < 0; }); });
      } });
    cb.input.id = w.id;
    cb.input.setAttribute('data-big-q', '');
    cb.on('change', function (e) { if (e.value && picked.indexOf(e.value.value) < 0) { picked.push(e.value.value); draw(); w.set(picked.slice()); } cb.setValue(null); });
    box.appendChild(chips); box.appendChild(host);
    draw();
    return { el: box, input: cb.input };
  }

  var qform = null, host = form && form.querySelector('[data-qf-fields][data-di-form]');
  if (form && host && I) {                                          // the one server-drawn form (core/forms.form_host): the page adds its two widgets
    qform = I.formOf(host, { widgets: { 'qf-constant': constantWidget, 'qf-big': bigWidget } });
  }
  /** Every field back to empty; how each field compares is kept. */
  function clearAll() {
    if (!qform) { return; }
    var v = JSON.parse(JSON.stringify(qform.getValue()));
    Object.keys(v).forEach(function (g) { Object.keys(v[g] || {}).forEach(function (f) { var x = v[g][f]; v[g][f] = { op: x.op }; if (x.v === 'any' || (x.v && /^(true|false)$/.test(x.v))) { v[g][f].v = 'any'; } }); });
    qform.setValue(v);
    var first = host.querySelector('input:not([type=hidden]), textarea, select');
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
    window.Drishti.keys.register({ id: 'form.fold', keys: ['Alt+F'], scope: 'screen', area: 'form', group: 'Search form', typing: true,
      description: 'Show or hide the search form', run: function () { fold(!folded(), folded() ? 'show' : 'hide'); } });
    form.addEventListener('submit', function () {
      var kept = ''; try { kept = sessionStorage.getItem('drishti.form') || ''; } catch (e) { kept = ''; }
      if (kept === 'shown') { var h = document.createElement('input'); h.type = 'hidden'; h.name = 'form'; h.value = 'shown'; form.appendChild(h); }
    });
  }

  if (form) {
    form.addEventListener('keydown', function (e) {
      if (e.key === 'Escape' && !e.defaultPrevented) {
        e.preventDefault(); clearAll();
      } else if (e.key === 'Enter' && (e.ctrlKey || e.metaKey) && e.target.tagName === 'TEXTAREA') {
        e.preventDefault(); form.requestSubmit ? form.requestSubmit() : form.submit();
      }
    });
    var clear = document.querySelector('[data-qf-clear]');
    if (clear) { clear.addEventListener('click', clearAll); }
    form.addEventListener('submit', function (e) {
      if (qform && !qform.validate().ok) { e.preventDefault(); return; }   // a required field left empty: said beside it, nothing is asked
      // empty boxes stay out of the address: only what was filled in is a criterion (and a field with none keeps no op. or cs.)
      each(form.querySelectorAll('[name^="v."], [name^="v2."]'), function (x) { if (!x.disabled && !String(x.value).trim()) { x.disabled = true; } });
      each(form.querySelectorAll('.qf-field[data-type="text"], .qf-field[data-type="number"], .qf-field[data-type="amount"], .qf-field[data-type="date"], .qf-field[data-type="id"]'), function (box) {
        var p = box.getAttribute('data-field');
        var filled = Array.prototype.some.call(form.querySelectorAll('[name="v.' + p + '"]'), function (x) { return !x.disabled && String(x.value).trim() !== ''; });
        if (!filled) { each(form.querySelectorAll('[name="op.' + p + '"], [name="cs.' + p + '"]'), function (x) { x.disabled = true; }); }
      });
    });
    window.addEventListener('pageshow', function () { if (qform) { qform.refresh(); } each(form.querySelectorAll('.qf-result-opts :disabled'), function (x) { x.disabled = false; }); });
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
