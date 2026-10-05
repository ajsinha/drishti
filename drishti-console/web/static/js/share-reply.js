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
/* The reply box on a shared view (COLLABORATION.md, Share with a note): the sender and the people the share reached answer in the banner
   (POST /api/share/{id}/replies, Ctrl+Enter sends); the other party is told by the server. The reply is drawn with textContent from what
   the server returns (scrubbed for this reader, its time in the top bar's zone). */
(function () {
  'use strict';
  var box = document.querySelector('[data-share-replies]');
  var form = box && box.querySelector('[data-share-reply-form]');
  if (!form) { return; }
  var id = box.getAttribute('data-share-replies');
  var text = form.querySelector('[data-share-reply-text]'), msg = form.querySelector('[data-share-reply-msg]'), send = form.querySelector('[data-share-reply-send]');
  var list = box.querySelector('[data-share-reply-list]'), busy = false;

  function say(t, bad) { msg.textContent = t || ''; msg.classList.toggle('bad', !!bad); }

  function draw(c) {
    var li = document.createElement('li'), b = document.createElement('b'), tm = document.createElement('time'), sp = document.createElement('span');
    b.textContent = c.authorName || c.author || '';
    tm.className = 'mono'; tm.textContent = c.createdAtLocal || ''; tm.setAttribute('datetime', c.createdAt || '');
    sp.textContent = c.body || '';
    li.appendChild(b); li.appendChild(document.createTextNode(' ')); li.appendChild(tm); li.appendChild(document.createTextNode(' ')); li.appendChild(sp);
    list.appendChild(li);
  }

  function submit() {
    var note = text.value.trim();
    if (busy) { return; }
    if (!note) { say('Write a reply first.', true); text.focus(); return; }
    busy = true; send.disabled = true; say('Sending…');
    fetch('/api/share/' + encodeURIComponent(id) + '/replies', { method: 'POST', credentials: 'same-origin',
      headers: { 'Content-Type': 'application/json', Accept: 'application/json' }, body: JSON.stringify({ note: note }) })
      .then(function (r) { return r.json().catch(function () { return {}; }).then(function (j) { return { ok: r.ok, j: j }; }); })
      .then(function (r) {
        busy = false; send.disabled = false;
        if (!r.ok) { say((r.j.code ? r.j.code + ': ' : '') + (r.j.detail || 'the reply could not be sent'), true); return; }
        draw(r.j); text.value = ''; say('Reply sent.'); text.focus();
      })
      .catch(function () { busy = false; send.disabled = false; say('The console could not be reached. Try again.', true); });
  }

  form.addEventListener('submit', function (e) { e.preventDefault(); submit(); });
  text.addEventListener('keydown', function (e) { if (e.key === 'Enter' && (e.ctrlKey || e.metaKey)) { e.preventDefault(); submit(); } });
})();
