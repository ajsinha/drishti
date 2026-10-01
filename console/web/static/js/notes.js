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
/* Field history: click a number in a view (a strip value, a field, a table cell) to see it over the last business
/* Notes on a view's entity and its fields: listed in a drawer, added by anyone who may open the entity, edited by
   their author, deleted by their author or an administrator. A field with a note shows a marker. */
(function () {
  'use strict';
  var view = document.querySelector('[data-view]'), drawer = document.getElementById('notesDrawer');
  if (!view || !drawer) { return; }
  var kind = view.getAttribute('data-kind'), id = view.getAttribute('data-id'), me = view.getAttribute('data-me');
  var admin = view.hasAttribute('data-admin');
  var list = drawer.querySelector('[data-notes-list]'), form = drawer.querySelector('[data-notes-form]');
  var pathSel = drawer.querySelector('[data-notes-path]'), msg = drawer.querySelector('[data-notes-msg]');
  var count = document.querySelector('[data-notes-count]'), labels = {};
  var base = '/api/notes/' + encodeURIComponent(kind) + '/' + encodeURIComponent(id);

  // the fields one can write about: every labelled value in the view (strip and key-value panels)
  view.querySelectorAll('[data-path]').forEach(function (el) {
    var p = el.getAttribute('data-path'), dt = el.previousElementSibling;
    var label = dt && dt.tagName === 'DT' ? dt.textContent.trim() : null;
    if (!p || !label || labels[p]) { return; }
    labels[p] = label;
    var o = document.createElement('option');
    o.value = p; o.textContent = label;
    pathSel.appendChild(o);
  });

  function say(t, bad) { msg.textContent = t; msg.classList.toggle('t-bad', !!bad); }
  function when(iso) { return iso ? iso.slice(0, 16).replace('T', ' ') : ''; }
  function json(r) { return r.json().then(function (b) { return { ok: r.ok, b: b }; }); }

  function draw(notes) {
    if (count) {                                   // no count in a workspace pane (the view's tools are left out there)
      count.textContent = String(notes.length);
      count.hidden = !notes.length;
    }
    view.querySelectorAll('.has-note').forEach(function (el) { el.classList.remove('has-note'); el.removeAttribute('data-note'); });
    list.innerHTML = '';
    if (!notes.length) {
      var empty = document.createElement('li');
      empty.className = 'text-muted-d';
      empty.textContent = 'No notes yet.';
      list.appendChild(empty);
    }
    notes.forEach(function (n) {
      if (n.path) {
        view.querySelectorAll('[data-path="' + CSS.escape(n.path) + '"]').forEach(function (el) {
          el.classList.add('has-note');
          el.setAttribute('data-note', n.body);
        });
      }
      var li = document.createElement('li');
      li.className = 'note';
      var head = document.createElement('div');
      head.className = 'note-h';
      head.textContent = (n.path ? (labels[n.path] || n.path) : 'Whole entity') + ' · ' + n.author + ' · ' + when(n.updatedAt)
        + (n.updatedAt !== n.createdAt ? ' (edited)' : '');
      var body = document.createElement('p');
      body.className = 'note-b';
      body.textContent = n.body;
      li.appendChild(head);
      li.appendChild(body);
      if (n.author === me || admin) {
        var tools = document.createElement('div');
        tools.className = 'note-tools';
        if (n.author === me) {
          var edit = document.createElement('button');
          edit.type = 'button'; edit.className = 'fk'; edit.textContent = 'Edit';
          edit.addEventListener('click', function () {
            var text = window.prompt('Edit your note:', n.body);
            if (text == null || !text.trim()) { return; }
            fetch('/api/notes/' + n.id, { method: 'PUT', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ body: text }) })
              .then(json).then(function (res) { if (res.ok) { load(); } else { say(res.b.detail, true); } });
          });
          tools.appendChild(edit);
        }
        var del = document.createElement('button');
        del.type = 'button'; del.className = 'fk'; del.textContent = 'Delete';
        del.addEventListener('click', function () {
          if (!window.confirm('Delete this note?')) { return; }
          fetch('/api/notes/' + n.id, { method: 'DELETE' }).then(json)
            .then(function (res) { if (res.ok) { load(); } else { say(res.b.detail, true); } });
        });
        tools.appendChild(del);
        li.appendChild(tools);
      }
      list.appendChild(li);
    });
  }

  function load() {
    return fetch(base).then(json).then(function (res) { if (res.ok) { draw(res.b); } });
  }

  form.addEventListener('submit', function (e) {
    e.preventDefault();
    fetch(base, { method: 'POST', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ body: form.body.value, path: pathSel.value || null }) })
      .then(json).then(function (res) {
        if (!res.ok) { say((res.b.code || 'Error') + ': ' + res.b.detail, true); return; }
        form.body.value = '';
        say('Added.');
        load();
      });
  });
  function openAt(path) {
    drawer.hidden = false;
    if (path != null) { pathSel.value = path; }
    form.body.focus();
  }
  document.querySelectorAll('[data-notes-open]').forEach(function (b) { b.addEventListener('click', function () { openAt(null); }); });
  drawer.querySelector('[data-notes-close]').addEventListener('click', function () { drawer.hidden = true; });
  window.drishtiNotes = { open: openAt };
  load();
})();
