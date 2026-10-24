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
/* The Sutra designer's Describe mode (docs/guides/SCREEN_DESIGNER.md section 31): a line typed like a command, read by the console's deterministic
   parser; "Understood as" shows one token per part (a token the parser was unsure of offers the fixes), the live result draws the screen
   as the viewer (while the line has errors the result is cleared and says "Fix the red part to see the screen"), "Show the Sutra" shows
   the YAML read-only, and "Save to my workspace" keeps it as a named design. */
(function () {
  'use strict';
  var root = document.querySelector('[data-describe]');
  if (!root) { return; }
  var form = root.querySelector('[data-describe-form]'), input = form.querySelector('input'), tokensBox = root.querySelector('[data-tokens]');
  var messages = root.querySelector('[data-messages]'), result = root.querySelector('[data-result]'), frame = root.querySelector('[data-preview]');
  var yaml = root.querySelector('[data-yaml]'), showSutra = root.querySelector('[data-show-sutra]'), save = root.querySelector('[data-save]');
  var saved = root.querySelector('[data-saved]'), openFull = root.querySelector('[data-open]');
  var fix = root.querySelector('[data-fix]');
  var draft = '', last = null, busy = false;

  function el(tag, cls, text) { var e = document.createElement(tag); if (cls) { e.className = cls; } if (text != null) { e.textContent = text; } return e; }
  function api(url, body) {
    return fetch(url, { method: 'POST', credentials: 'same-origin', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body) })
      .then(function (r) { return r.json().catch(function () { return {}; }).then(function (j) { if (!r.ok) { throw new Error(j.detail || 'Something went wrong'); } return j; }); });
  }
  function replaceClause(clause, by) {
    var at = input.value.indexOf(clause);
    if (at >= 0) { input.value = input.value.slice(0, at) + by + input.value.slice(at + clause.length); }
  }
  function choiceFor(t, choice) {
    if (t.role === 'subject') { return choice; }                       // the whole first part: TRD BOOK-…
    return choice.toLowerCase();                                       // a show's plain name reads back as itself
  }

  function draw(r) {
    tokensBox.innerHTML = '';
    tokensBox.appendChild(el('span', 'text-muted-d', 'Understood as:'));
    r.tokens.forEach(function (t) {
      var chip = el('span', 'dz-token' + (t.unsure ? ' dz-unsure' : '') + (t.role === 'error' ? ' dz-error' : ''));
      chip.setAttribute('data-token', t.role);
      chip.appendChild(el('span', null, t.text));
      if (t.choices && t.choices.length) {
        var s = el('select'); s.setAttribute('aria-label', 'Fix: ' + t.text);
        s.appendChild(el('option', null, 'fix…'));
        t.choices.forEach(function (c) { var o = el('option', null, c); o.value = c; s.appendChild(o); });
        s.addEventListener('change', function () { if (s.value !== 'fix…') { replaceClause(t.clause, choiceFor(t, s.value)); build(); } });
        chip.appendChild(s);
      } else if (t.role !== 'error') {
        var edit = el('button', 'dz-mini', '✎'); edit.type = 'button'; edit.setAttribute('aria-label', 'Change: ' + t.text);
        edit.addEventListener('click', function () { var at = input.value.indexOf(t.clause); input.focus(); if (at >= 0) { input.setSelectionRange(at, at + t.clause.length); } });
        chip.appendChild(edit);
      }
      tokensBox.appendChild(chip);
    });
    messages.innerHTML = '';
    (r.messages || []).concat(r.refreshNote ? [r.refreshNote] : []).forEach(function (m) { messages.appendChild(el('li', null, m)); });
    fix.hidden = r.ok;
    if (!r.ok) {                                                       // no stale screen: the last good one is cleared until the line is whole
      result.hidden = true;
      frame.setAttribute('src', 'about:blank');
      yaml.textContent = ''; saved.textContent = '';
    }
    if (r.ok) {
      draft = r.draft;
      result.hidden = false;
      if (frame.getAttribute('src') !== r.preview) { frame.setAttribute('src', r.preview); } else { frame.contentWindow.location.reload(); }
      openFull.href = r.open;
      yaml.textContent = r.yaml;
      saved.textContent = '';
    }
  }

  function build() {
    if (busy) { return; }
    busy = true;
    api('/designer/describe', { text: input.value, draft: draft }).then(function (r) { last = r; draw(r); })
      .catch(function (e) { messages.innerHTML = ''; messages.appendChild(el('li', null, e.message)); })
      .finally(function () { busy = false; });
  }

  form.addEventListener('submit', function (e) { e.preventDefault(); build(); });
  showSutra.addEventListener('click', function () {
    yaml.hidden = !yaml.hidden;
    showSutra.setAttribute('aria-expanded', String(!yaml.hidden));
    showSutra.textContent = yaml.hidden ? 'Show the Sutra' : 'Hide the Sutra';
  });
  save.addEventListener('click', function () {
    if (!draft || !last) { return; }
    var subject = (last.tokens[0] || {}).text || 'My screen';
    api('/designer/describe/save/' + encodeURIComponent(draft), { name: 'My screen · ' + subject }).then(function (r) {
      saved.textContent = 'Saved to your workspace as "' + r.name + '". Only you see it until you share it. ';
      var a = el('a', null, 'Open it in My designs'); a.href = r.build; saved.appendChild(a);
      draft = '';                                                          // a new Build starts a new draft
    }).catch(function (e) { saved.textContent = e.message; });
  });
  if (input.value) { build(); }
})();
