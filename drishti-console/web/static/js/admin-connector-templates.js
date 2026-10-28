/* Project Drishti · Any data. Any domain. One grammar.
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
/* Admin → Connectors → Template updates: the buttons. Each sends the connector's version as If-Match; Regenerate and Remove obsolete
   ask first; the page reloads after a change so it shows what is left. */
(function () {
  'use strict';
  var root = document.querySelector('[data-template-updates]');
  if (!root) { return; }
  var msg = root.querySelector('[data-msg]');
  function say(text, bad) { msg.textContent = text; msg.classList.toggle('t-bad', !!bad); msg.classList.toggle('t-ok', !bad && !!text); }
  function post(path, etag) {
    var h = {};
    if (etag) { h['If-Match'] = etag; }
    return fetch('/admin/connectors/api/template-updates/' + path, { method: 'POST', headers: h }).then(function (r) {
      return r.json().catch(function () { return {}; }).then(function (b) { return { ok: r.ok, status: r.status, body: b }; });
    });
  }
  function why(r) { return (r.body.code ? r.body.code + ' ' : '') + (r.body.detail || 'The request failed (HTTP ' + r.status + ')'); }
  var ASK = {
    'regenerate': 'Regenerate {n} from its pack\'s template? Every edit in it is replaced; the current text is kept in the history and can be restored.',
    'remove-obsolete': 'Remove the kinds no pack serves from {n}? The current text is kept in the history.'
  };
  var DONE = { 'add-missing': '{n}: the missing kinds and settings are added; your edits are kept.', 'regenerate': '{n} is regenerated from the template.',
    'remove-obsolete': '{n}: the obsolete kinds are removed.' };
  root.addEventListener('click', function (ev) {
    var b = ev.target.closest('button');
    if (!b || b.disabled) { return; }
    if (b.hasAttribute('data-fix-all')) {
      b.disabled = true;
      post('fix-all').then(function (r) {
        if (!r.ok) { say(why(r), true); b.disabled = false; return; }
        say('Fixed: ' + ((r.body.fixed || []).join(', ') || 'nothing to fix') + '.');
        setTimeout(function () { location.reload(); }, 800);
      });
      return;
    }
    if (b.hasAttribute('data-use-template')) {
      var row = b.closest('[data-conflict]');
      var drift = b.closest('[data-drift]');
      var key = row.getAttribute('data-conflict');
      var n = drift.getAttribute('data-drift');
      if (!window.confirm('Use the pack\'s value for ' + key + ' in ' + n + '? The current text is kept in the history.')) { return; }
      b.disabled = true;
      fetch('/admin/connectors/api/template-updates/' + encodeURIComponent(n) + '/use-template-value', {
        method: 'POST', headers: { 'Content-Type': 'application/json', 'If-Match': drift.getAttribute('data-etag') },
        body: JSON.stringify({ where: row.getAttribute('data-where'), key: key })
      }).then(function (r) {
        return r.json().catch(function () { return {}; }).then(function (body) { return { ok: r.ok, status: r.status, body: body }; });
      }).then(function (r) {
        if (!r.ok) { say(why(r), true); b.disabled = false; return; }
        say(n + ': ' + key + ' now has the pack\'s value.');
        setTimeout(function () { location.reload(); }, 800);
      });
      return;
    }
    var act = b.getAttribute('data-act');
    var card = b.closest('[data-drift]');
    if (!act || !card) { return; }
    var name = card.getAttribute('data-drift');
    if (ASK[act] && !window.confirm(ASK[act].replace('{n}', name))) { return; }
    b.disabled = true;
    post(encodeURIComponent(name) + '/' + act, card.getAttribute('data-etag')).then(function (r) {
      if (!r.ok) { say(why(r), true); b.disabled = false; return; }
      say(DONE[act].replace('{n}', name));
      setTimeout(function () { location.reload(); }, 800);
    });
  });
}());
