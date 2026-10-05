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
/* Admin > Collaboration (COLLABORATION.md, Administrators and compliance): search threads, moderate (hide, unhide, lock), legal holds, the
   compliance export, chain verification, the retention dry run and the bridges. The server decides every right call by call; the page
   only draws the sections the caller's roles open and says in words what the server refused. Everything is put in with textContent;
   times arrive as <name>Local, in the top bar's zone. */
(function () {
  'use strict';
  var root = document.querySelector('[data-admin-collab]');
  if (!root) { return; }
  var isAdmin = root.hasAttribute('data-admin');
  var msg = root.querySelector('[data-acol-msg]');

  function $(sel) { return root.querySelector(sel); }
  function el(tag, cls, text) { var n = document.createElement(tag); if (cls) { n.className = cls; } if (text != null) { n.textContent = text; } return n; }
  function say(text, bad) { msg.textContent = text || ''; msg.classList.toggle('t-bad', !!bad); }
  function btn(label, act, extra) {
    var b = el('button', 'fk', label); b.type = 'button'; b.setAttribute('data-act', act);
    if (extra) { Object.keys(extra).forEach(function (k) { b.setAttribute(k, extra[k]); }); }
    return b;
  }
  function cell(tr, text, cls) { var td = el('td', cls || null, text == null ? '' : String(text)); tr.appendChild(td); return td; }
  function path(id) { return String(id).split('/').map(encodeURIComponent).join('/'); }
  function problem(r) { return (r.j && r.j.code ? r.j.code + ': ' : '') + ((r.j && r.j.detail) || 'the request failed'); }

  function api(method, url, body) {
    return fetch(url, { method: method, credentials: 'same-origin', headers: { 'Content-Type': 'application/json', Accept: 'application/json' },
      body: body ? JSON.stringify(body) : undefined })
      .then(function (r) { return r.json().catch(function () { return {}; }).then(function (j) { return { ok: r.ok, status: r.status, j: j }; }); })
      .catch(function () { return { ok: false, status: 0, j: { detail: 'The console could not be reached. Try again.' } }; });
  }
  function fields(form) {
    var o = {};
    Array.prototype.forEach.call(form.elements, function (f) {
      if (!f.name) { return; }
      if (f.type === 'checkbox') { o[f.name] = f.checked; } else if (f.value.trim()) { o[f.name] = f.value.trim(); }
    });
    return o;
  }
  function query(o) { return Object.keys(o).map(function (k) { return encodeURIComponent(k) + '=' + encodeURIComponent(o[k]); }).join('&'); }
  function clear(node) { while (node.firstChild) { node.removeChild(node.firstChild); } }

  // ---- threads and moderation -------------------------------------------------------------------------------------------------
  var form = $('[data-acol-search]'), table = $('[data-acol-threads]'), body = table.querySelector('tbody'), more = $('[data-acol-more]');
  var pane = $('[data-acol-comments]'), clist = $('[data-acol-clist]'), only = $('[data-acol-hidden-only]'), shown = null, next = null;

  function threadRow(t) {
    var tr = el('tr'); tr.setAttribute('data-thread', t.id);
    cell(tr, t.kind + ' ' + t.entityId, 'mono'); cell(tr, t.state); cell(tr, t.comments, 'num'); cell(tr, t.createdBy, 'mono'); cell(tr, t.lastAtLocal || t.lastAt, 'mono');
    var a = cell(tr, '', 'adm-actions');
    a.appendChild(btn('Comments', 'open', { 'data-kind': t.kind, 'data-eid': t.entityId, 'data-tid': t.id }));
    if (isAdmin) { a.appendChild(btn(t.state === 'locked' ? 'Unlock' : 'Lock', t.state === 'locked' ? 'unlock' : 'lock', { 'data-tid': t.id })); }
    return tr;
  }

  function search(append, keep) {                    // keep: a result message already on screen stays
    var q = fields(form); q.limit = 25;
    if (append && next) { q.after = next; }
    if (!keep) { say('Searching…'); }
    api('GET', '/admin/collab/api/threads?' + query(q)).then(function (r) {
      if (!r.ok) { say(problem(r), true); return; }
      if (!append) { clear(body); }
      (r.j.items || []).forEach(function (t) { body.appendChild(threadRow(t)); });
      table.hidden = false; next = r.j.next || null; more.hidden = !next;
      if (!keep) { say((r.j.items || []).length ? '' : (append ? 'No more threads.' : 'No threads match.')); }
    });
  }

  function drawComments() {
    clear(clist);
    var t = shown; if (!t) { return; }
    var rows = (t.items || []).filter(function (c) { return !only.checked || c.state === 'hidden'; });
    if (!rows.length) { clist.appendChild(el('li', 'muted', only.checked ? 'No hidden comments in this thread.' : 'No comments.')); }
    rows.forEach(function (c) {
      var li = el('li', 'acol-c ' + (c.state || 'live')); li.setAttribute('data-comment', c.id);
      var h = el('div', 'acol-ch'); h.appendChild(el('b', null, c.authorName || c.author)); h.appendChild(el('time', 'mono', c.createdAtLocal || c.createdAt));
      h.appendChild(el('span', 'acol-state', c.state && c.state !== 'live' ? c.state + (c.stateReason ? ': ' + c.stateReason : '') : 'visible'));
      li.appendChild(h);
      li.appendChild(el('p', 'acol-text', c.body == null ? '(text not shown)' : c.body));
      if (isAdmin) {
        var a = el('div', 'adm-actions');
        if (c.state === 'hidden') { a.appendChild(btn('Unhide', 'unhide', { 'data-cid': c.id })); } else if (!c.state || c.state === 'live') { a.appendChild(btn('Hide', 'hide', { 'data-cid': c.id })); }
        li.appendChild(a);
      }
      clist.appendChild(li);
    });
  }

  function openComments(kind, eid, tid, keep) {
    if (!keep) { say('Loading…'); }
    api('GET', '/api/threads/' + encodeURIComponent(kind) + '/' + path(eid)).then(function (r) {
      if (!r.ok) { say(problem(r), true); return; }
      shown = (r.j || []).filter(function (t) { return t.id === tid; })[0] || null;
      if (!shown) { say('That thread is not shown to you here (it may be on another page of the entity).', true); return; }
      if (!keep) { say(''); }
      pane.hidden = false;
      var title = $('[data-acol-comments-title]'); title.textContent = 'Comments in ' + kind + ' ' + eid + ' (' + shown.state + ')'; title.focus();
      drawComments();
    });
  }

  function hideForm(li, cid) {                       // an inline reason box, as the Discussion tab has
    var f = el('form', 'acol-reason'), i = el('input', 'studio-in'), ok = el('button', 'btn-pill btn-sm-pill', 'Hide'), no = btn('Cancel', 'cancel');
    i.setAttribute('aria-label', 'Why this comment is hidden'); i.placeholder = 'Reason (shown to readers)'; i.maxLength = 400; ok.type = 'submit';
    f.appendChild(i); f.appendChild(ok); f.appendChild(no); li.appendChild(f); i.focus();
    f.addEventListener('submit', function (e) {
      e.preventDefault();
      if (!i.value.trim()) { say('Say why the comment is hidden.', true); i.focus(); return; }
      api('POST', '/api/comment/' + encodeURIComponent(cid) + '/hide', { reason: i.value.trim() }).then(function (r) { done(r, 'Comment hidden.'); });
    });
  }
  function done(r, ok) {
    if (!r.ok) { say(problem(r), true); return; }
    say(ok); if (shown) { openComments(shown.kind, shown.entityId, shown.id, true); }
    if (body.children.length) { search(false, true); }
  }

  root.addEventListener('click', function (e) {
    var b = e.target.closest('button[data-act]'); if (!b) { return; }
    var act = b.getAttribute('data-act'), cid = b.getAttribute('data-cid'), tid = b.getAttribute('data-tid');
    if (act === 'open') { openComments(b.getAttribute('data-kind'), b.getAttribute('data-eid'), tid); }
    else if (act === 'hide') { hideForm(b.closest('li'), cid); }
    else if (act === 'cancel') { var f = b.closest('form'); f.parentNode.removeChild(f); }
    else if (act === 'unhide') { api('POST', '/api/comment/' + encodeURIComponent(cid) + '/unhide').then(function (r) { done(r, 'Comment shown again.'); }); }
    else if (act === 'lock' || act === 'unlock') {
      api('POST', '/api/thread/' + encodeURIComponent(tid) + '/state', { state: act === 'lock' ? 'locked' : 'open' }).then(function (r) {
        if (!r.ok) { say(problem(r), true); return; }
        say(act === 'lock' ? 'Thread locked: nobody can reply until it is unlocked.' : 'Thread unlocked.'); search(false, true);
      });
    } else if (act === 'release') {
      api('DELETE', '/admin/collab/api/holds/' + encodeURIComponent(b.getAttribute('data-hold'))).then(function (r) { if (r.ok) { say('Hold released.'); } else { say(problem(r), true); } holds(); });
    } else if (act === 'bridge-test') {
      var name = b.getAttribute('data-name'); say('Testing ' + name + '…');
      api('POST', '/admin/collab/api/bridges/' + encodeURIComponent(name) + '/test').then(function (r) {
        say(r.ok ? 'Test message posted to ' + name + ' (HTTP ' + r.j.status + ').' : problem(r), !r.ok); bridges();
      });
    }
  });
  form.addEventListener('submit', function (e) { e.preventDefault(); search(false); });
  more.addEventListener('click', function () { search(true); });
  only.addEventListener('change', drawComments);

  // ---- legal holds (compliance) ----------------------------------------------------------------------------------------------
  var holdTable = $('[data-acol-holds]'), holdForm = $('[data-acol-hold-form]');
  function covers(h) {
    return h.scope === 'entity' ? 'entity ' + h.kind + ' ' + h.entityId : h.scope === 'kind' ? 'every ' + h.kind : h.scope === 'user' ? 'user ' + h.username
      : h.scope === 'thread' ? 'thread ' + h.threadId : 'everything';
  }
  function holds() {
    if (!holdTable) { return; }
    var active = $('[data-acol-active-holds]').checked;
    api('GET', '/admin/collab/api/holds' + (active ? '?active=true' : '')).then(function (r) {
      var tb = holdTable.querySelector('tbody'); clear(tb);
      if (!r.ok) { say(problem(r), true); return; }
      (r.j || []).forEach(function (h) {
        var tr = el('tr'); tr.setAttribute('data-hold-row', h.id);
        cell(tr, h.id, 'num'); cell(tr, covers(h)); cell(tr, (h.from ? String(h.from).slice(0, 10) : 'any') + ' to ' + (h.to ? String(h.to).slice(0, 10) : 'any'), 'mono');
        cell(tr, h.reason); cell(tr, h.placedBy + ' ' + (h.placedAtLocal || ''), 'mono');
        cell(tr, h.releasedAt ? 'released by ' + h.releasedBy + ' ' + (h.releasedAtLocal || '') : 'active');
        var a = cell(tr, '', 'adm-actions'); if (!h.releasedAt) { a.appendChild(btn('Release', 'release', { 'data-hold': h.id })); }
        tb.appendChild(tr);
      });
      if (!tb.children.length) { var tr2 = el('tr'); var td = cell(tr2, 'No holds.'); td.colSpan = 7; tb.appendChild(tr2); }
    });
  }
  if (holdForm) {
    var scope = $('[data-acol-scope]');
    var sync = function () { holdForm.querySelectorAll('[data-acol-for]').forEach(function (l) { l.hidden = l.getAttribute('data-acol-for').split(' ').indexOf(scope.value) < 0; }); };
    scope.addEventListener('change', sync); sync();
    $('[data-acol-active-holds]').addEventListener('change', holds);
    holdForm.addEventListener('submit', function (e) {
      e.preventDefault();
      var o = fields(holdForm);
      if (!o.reason) { say('Say why the hold is placed.', true); holdForm.elements.reason.focus(); return; }
      api('POST', '/admin/collab/api/holds', o).then(function (r) {
        if (r.ok) { say('Hold ' + r.j.id + ' placed.'); holdForm.elements.reason.value = ''; } else { say(problem(r), true); }
        holds();
      });
    });
    holds();
  }

  // ---- compliance export: start, poll, download once -------------------------------------------------------------------------
  var expForm = $('[data-acol-export-form]'), expOut = $('[data-acol-export]'), timer = null;
  function exportView(j) {
    clear(expOut);
    var c = j.counts || {};
    expOut.appendChild(document.createTextNode('Export ' + j.id + ': ' + j.state + (c.shares != null ? ' (' + c.shares + ' shares, ' + c.threads + ' threads, ' + c.comments + ' comments)' : '') + (j.error ? ' ' + j.error : '') + ' '));
    if (j.state === 'done') {
      var a = el('a', 'fk', 'Download once'); a.href = '/admin/collab/api/exports/' + encodeURIComponent(j.id) + '/download'; a.setAttribute('data-acol-download', '');
      a.addEventListener('click', function () { setTimeout(function () { expOut.textContent = 'Export ' + j.id + ' was downloaded; the server no longer holds it.'; }, 300); });
      expOut.appendChild(a);
    }
  }
  function poll(id) {
    api('GET', '/admin/collab/api/exports/' + encodeURIComponent(id)).then(function (r) {
      if (!r.ok) { say(problem(r), true); return; }
      exportView(r.j);
      if (r.j.state === 'queued' || r.j.state === 'running') { timer = setTimeout(function () { poll(id); }, 1000); }
    });
  }
  if (expForm) {
    expForm.addEventListener('submit', function (e) {
      e.preventDefault(); clearTimeout(timer);
      api('POST', '/admin/collab/api/exports', fields(expForm)).then(function (r) {
        if (!r.ok) { say(problem(r), true); return; }
        say('Export started.'); exportView(r.j); poll(r.j.id);
      });
    });
    var vf = $('[data-acol-verify-form]'), vo = $('[data-acol-verify]'), vp = $('[data-acol-problems]');
    vf.addEventListener('submit', function (e) {
      e.preventDefault(); vo.textContent = 'Verifying…'; clear(vp);
      var t = vf.elements.thread.value.trim();
      api('GET', '/admin/collab/api/verify' + (t ? '?thread=' + encodeURIComponent(t) : '')).then(function (r) {
        if (!r.ok) { vo.textContent = ''; say(problem(r), true); return; }
        var j = r.j;
        if (j.thread) { vo.textContent = j.ok ? 'Thread ' + j.thread + ': chain intact (' + j.steps + ' steps).' : 'Thread ' + j.thread + ': BROKEN. ' + (j.problem || ''); vo.classList.toggle('t-bad', !j.ok); return; }
        var bad = (j.problems || []).length;
        vo.textContent = (bad ? 'Problems found. ' : 'All intact. ') + j.threadsOk + ' of ' + j.threads + ' threads and ' + j.sharesOk + ' of ' + j.shares + ' shares verified' + (j.truncated ? ' (list cut short)' : '') + '.';
        vo.classList.toggle('t-bad', !!bad);
        (j.problems || []).forEach(function (p) { vp.appendChild(el('li', 'mono', p.type + ' ' + p.id + ': ' + p.problem)); });
      });
    });
  }

  // ---- retention dry run and bridges (admin) ---------------------------------------------------------------------------------
  var ret = $('[data-acol-retention]');
  if (ret) {
    ret.addEventListener('click', function () {
      var out = $('[data-acol-ret]'); out.textContent = 'Counting…';
      api('POST', '/admin/collab/api/retention').then(function (r) {
        if (!r.ok) { out.textContent = ''; say(problem(r), true); return; }
        out.textContent = 'Dry run: ' + r.j.threadsPurged + ' threads and ' + r.j.sharesPurged + ' shares would be removed; ' + r.j.threadsHeld + ' threads and ' + r.j.sharesHeld + ' shares are kept by a hold. Nothing was deleted.';
      });
    });
  }
  function bridges() {
    var t = $('[data-acol-bridges]'); if (!t) { return; }
    api('GET', '/admin/collab/api/bridges').then(function (r) {
      var tb = t.querySelector('tbody'); clear(tb);
      if (!r.ok) { say(problem(r), true); return; }
      $('[data-acol-br-head]').textContent = (r.j.enabled ? 'Bridges are on' : 'Bridges are off') + ' (render as ' + r.j.renderAs + ', at most ' + r.j.perMinute + ' a minute).';
      (r.j.bridges || []).forEach(function (b) {
        var tr = el('tr'); cell(tr, b.name, 'mono'); cell(tr, b.format); cell(tr, b.host, 'mono'); cell(tr, b.usable ? b.status : 'not usable: ' + b.status);
        cell(tr, Object.keys(b.outbox || {}).map(function (k) { return k + ' ' + b.outbox[k]; }).join(', ') || 'empty');
        var a = cell(tr, '', 'adm-actions'); a.appendChild(btn('Test', 'bridge-test', { 'data-name': b.name })); tb.appendChild(tr);
      });
      if (!tb.children.length) { var tr2 = el('tr'); var td = cell(tr2, 'No bridges are configured.'); td.colSpan = 6; tb.appendChild(tr2); }
    });
  }
  bridges();
})();
