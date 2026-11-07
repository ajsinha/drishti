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
/* The one Approvals queue (templates/approvals.html; SHARED_SCREENS.md section 2): reads /approvals/api/list, draws the due-soon items
   pinned on top and the rest newest first, opens an item (/approvals/api/item/<kind>/<id>) with its diff, effect, rules and questions, and
   posts the decision or a question. The server decides who may do what: the buttons drawn are the actions it answers. */
(function () {
  'use strict';
  var root = document.querySelector('[data-approvals]');
  if (!root) { return; }
  var $ = function (s, el) { return (el || root).querySelector(s); };
  var $$ = function (s, el) { return Array.prototype.slice.call((el || root).querySelectorAll(s)); };
  var state = { box: root.getAttribute('data-box') || 'inbox', group: root.getAttribute('data-group') || '', kind: root.getAttribute('data-kind') || '',
    open: null, list: null };

  function h(tag, attrs, text) {
    var el = document.createElement(tag);
    Object.keys(attrs || {}).forEach(function (k) { el.setAttribute(k, attrs[k]); });
    if (text !== undefined && text !== null) { el.textContent = String(text); }
    return el;
  }
  function clear(el) { while (el.firstChild) { el.removeChild(el.firstChild); } }
  function answer(r) {
    return r.json().catch(function () { return {}; }).then(function (j) {
      if (!r.ok) { var e = new Error((j && (j.detail || j.code)) || ('HTTP ' + r.status)); e.code = j && j.code; throw e; }
      return j;
    });
  }
  function get(path) { return fetch('/approvals/api/' + path, { headers: { Accept: 'application/json' }, credentials: 'same-origin' }).then(answer); }
  function post(path, body) {
    return fetch('/approvals/api/' + path, { method: 'POST', credentials: 'same-origin',
      headers: { 'Content-Type': 'application/json', Accept: 'application/json' }, body: JSON.stringify(body || {}) }).then(answer);
  }
  function enc(s) { return encodeURIComponent(s).replace(/%3A/g, ':').replace(/%40/g, '@'); }
  function when(t) { return t ? String(t).slice(0, 16).replace('T', ' ') : ''; }
  function say(text, bad) { var s = $('[data-ap-said]'); s.textContent = text || ''; s.classList.toggle('bad', !!bad); }

  // ---- the list ---------------------------------------------------------------------------------------------------------------------------

  function syncChips() {
    $$('[data-ap-box]').forEach(function (b) { var on = b.getAttribute('data-ap-box') === state.box; b.classList.toggle('on', on); b.setAttribute('aria-pressed', on ? 'true' : 'false'); });
    $$('[data-ap-group]').forEach(function (b) { var on = b.getAttribute('data-ap-group') === state.group; b.classList.toggle('on', on); b.setAttribute('aria-pressed', on ? 'true' : 'false'); });
    $('[data-ap-kind]').value = state.kind;
  }
  function remember() {
    var q = new URLSearchParams();
    if (state.box !== 'inbox') { q.set('box', state.box); }
    if (state.group) { q.set('group', state.group); }
    if (state.kind) { q.set('kind', state.kind); }
    var path = state.open ? '/approvals/' + state.open.kind + '/' + enc(state.open.id) : '/approvals';
    history.replaceState(null, '', path + (q.toString() ? '?' + q : ''));
  }
  function row(e) {
    var i = e.item, li = h('li', { class: 'ap-row' + (e.dueSoon ? ' soon' : ''), 'data-ap-row': i.kind + '/' + i.id });
    var b = h('button', { type: 'button', class: 'ap-pick', 'data-ap-open': i.kind + '/' + i.id });
    b.appendChild(h('span', { class: 'ap-kindtag' }, e.label));
    b.appendChild(h('span', { class: 'ap-row-title' }, i.title));
    b.appendChild(h('span', { class: 'ap-row-meta' }, (i.maker || '') + ' · ' + (e.dueSoon && i.due ? 'due ' + i.due : when(i.at))
      + (i.stage ? ' · ' + i.stage : '') + (i.pending ? '' : ' · ' + i.status) + (e.questions ? ' · ' + e.questions + ' question' + (e.questions === 1 ? '' : 's') : '')));
    b.addEventListener('click', function () { open(i.kind, i.id); });
    li.appendChild(b);
    return li;
  }
  function load() {
    syncChips();
    $('[data-ap-meta]').textContent = 'loading…';
    var q = new URLSearchParams({ box: state.box });
    if (state.group) { q.set('group', state.group); }
    if (state.kind) { q.set('kind', state.kind); }
    return get('list?' + q).then(function (d) {
      state.list = d;
      var kinds = $('[data-ap-kind]');
      if (kinds.options.length === 1) {
        (d.kinds || []).filter(function (k) { return k.enabled; }).forEach(function (k) { kinds.appendChild(h('option', { value: k.kind }, k.label)); });
        kinds.value = state.kind;
      }
      $('[data-ap-inbox]').textContent = d.inbox ? String(d.inbox) : '';
      $('[data-ap-meta]').textContent = d.inbox + ' waiting for you' + (d.dueSoon ? ', ' + d.dueSoon + ' due soon' : '') + '. The maker never approves their own; everything is audited.';
      var soon = (d.items || []).filter(function (e) { return e.dueSoon; }), rest = (d.items || []).filter(function (e) { return !e.dueSoon; });
      var sl = $('[data-ap-soon-items]'), il = $('[data-ap-items]');
      clear(sl); clear(il);
      soon.forEach(function (e) { sl.appendChild(row(e)); });
      rest.forEach(function (e) { il.appendChild(row(e)); });
      $('[data-ap-soon]').hidden = !soon.length;
      $('[data-ap-none]').hidden = !!(d.items || []).length;
      var un = $('[data-ap-unavailable]');
      un.hidden = !(d.unavailable || []).length;
      un.textContent = (d.unavailable || []).length ? 'Could not be read now: ' + d.unavailable.join('; ') : '';
      mark();
    }).catch(function (e) { $('[data-ap-meta]').textContent = 'The queue could not be read: ' + e.message; });
  }
  function mark() {
    $$('[data-ap-row]').forEach(function (li) {
      var on = !!state.open && li.getAttribute('data-ap-row') === state.open.kind + '/' + state.open.id;
      li.classList.toggle('on', on);
      var b = li.querySelector('[data-ap-open]'); if (b) { b.setAttribute('aria-current', on ? 'true' : 'false'); }
    });
  }

  // ---- one item -------------------------------------------------------------------------------------------------------------------------

  function diff(d) {
    var box = $('[data-ap-diff]'); clear(box);
    if (!d || !d.rows || !d.rows.length) { box.appendChild(h('p', { class: 'text-muted-d' }, d && d.identical ? 'No change in the text.' : 'Nothing to compare.')); return; }
    if (d.format === 'fields') {
      var t = h('table', { class: 'tbl ap-fields', 'data-ap-diff-fields': '' });
      var hr = h('tr'); ['Field', 'Before', 'After'].forEach(function (c) { hr.appendChild(h('th', { scope: 'col' }, c)); });
      var th = h('thead'); th.appendChild(hr); t.appendChild(th);
      var tb = h('tbody');
      d.rows.forEach(function (r) {
        var tr = h('tr'); tr.appendChild(h('td', { class: 'mono' }, r.field));
        tr.appendChild(h('td', { class: 'mono d' }, r.before === null || r.before === undefined ? '—' : typeof r.before === 'object' ? JSON.stringify(r.before) : r.before));
        tr.appendChild(h('td', { class: 'mono a' }, r.after === null || r.after === undefined ? '—' : typeof r.after === 'object' ? JSON.stringify(r.after) : r.after));
        tb.appendChild(tr);
      });
      t.appendChild(tb); box.appendChild(t); return;
    }
    var pre = h('pre', { class: 'ap-lines-diff', 'data-ap-diff-lines': '' });
    d.rows.forEach(function (r) {
      if (r.op === 'same') { pre.appendChild(h('span', { class: 's' }, '  ' + (r.right !== undefined ? r.right : r.left) + '\n')); return; }
      if (r.left !== null && r.left !== undefined && r.op !== 'added') { pre.appendChild(h('span', { class: 'd' }, '- ' + r.left + '\n')); }
      if (r.right !== null && r.right !== undefined && r.op !== 'removed') { pre.appendChild(h('span', { class: 'a' }, '+ ' + r.right + '\n')); }
    });
    box.appendChild(pre);
  }
  function lines(sel, list) {
    var ul = $(sel); clear(ul);
    (list || []).forEach(function (l) { ul.appendChild(h('li', {}, l)); });
    if (!(list || []).length) { ul.appendChild(h('li', { class: 'text-muted-d' }, 'None.')); }
  }
  function thread(posts) {
    var ol = $('[data-ap-thread]'); clear(ol);
    (posts || []).forEach(function (p) {
      var li = h('li', { class: 'ap-post', 'data-ap-post': '' });
      li.appendChild(h('span', { class: 'ap-post-by' }, p.by + ' · ' + when(p.at)));
      li.appendChild(h('span', { class: 'ap-post-text' }, p.text));
      ol.appendChild(li);
    });
    if (!(posts || []).length) { ol.appendChild(h('li', { class: 'text-muted-d' }, 'No questions yet.')); }
  }
  function facts(f) {
    var dl = $('[data-ap-more-list]'); clear(dl);
    var drawn = state.open && state.open.kind === 'sutra' ? ['before', 'after', 'evidence', 'newVersion', 'stale'] : [];     // the Sutra view draws these
    var keys = Object.keys(f || {}).filter(function (k) { return f[k] !== null && f[k] !== undefined && f[k] !== '' && drawn.indexOf(k) < 0; });
    keys.forEach(function (k) {
      dl.appendChild(h('dt', {}, k));
      var v = f[k], dd = h('dd', k === 'evidence' ? { 'data-ap-fact': k, 'data-evidence': '' } : { 'data-ap-fact': k });
      if (typeof v === 'object') { dd.appendChild(h('pre', { class: 'ap-json' }, JSON.stringify(v, null, 2))); } else { dd.textContent = String(v); }
      dl.appendChild(dd);
    });
    $('[data-ap-more]').hidden = !keys.length;
    $('[data-ap-more]').open = keys.indexOf('evidence') >= 0;           // a shipped design's evidence (its checks, the samples) is read first
  }
  function params(kind) {
    var box = $('[data-ap-params]'); clear(box);
    if (kind === 'function') {
      var l = h('label', { class: 'ap-param' }, 'For ');
      var s = h('input', { type: 'text', value: 'everyone', 'data-ap-param': 'scope', 'aria-label': 'Who may use it: everyone or group:<name>', size: '14' });
      l.appendChild(s); box.appendChild(l);
    }
    if (kind === 'bi') {                                    // a dataset change that renames, or one refused as breaking (a bi-admin may force it)
      [['applyRenames', 'and update the reports that use renamed names'], ['force', 'anyway (bi-admin: over a breaking change)']].forEach(function (p) {
        var lb = h('label', { class: 'ap-param' });
        lb.appendChild(h('input', { type: 'checkbox', 'data-ap-flag': p[0] }));
        lb.appendChild(document.createTextNode(' ' + p[1]));
        box.appendChild(lb);
      });
    }
  }
  function draw(o) {
    var it = o.detail.item;
    state.open = { kind: it.kind, id: it.id };
    $('[data-ap-pick]').hidden = true;
    $('[data-ap-item]').hidden = false;
    $('[data-ap-item-title]').textContent = o.label + ': ' + it.title;
    var st = $('[data-ap-item-status]');
    st.textContent = it.pending ? (o.dueSoon && it.due ? 'waiting · due ' + it.due : 'waiting') + (it.stage ? ' · ' + it.stage : '') : it.status;
    st.className = 'ap-status ' + (it.pending ? 'wait' : it.status === 'approved' || it.status === 'signed' ? 'ok' : 'no');
    var link = $('[data-ap-item-link]'); link.href = it.link || '#'; link.hidden = !it.link;
    $('[data-ap-item-facts]').textContent = 'Asked by ' + (it.maker || '?') + ' ' + when(it.at) + (it.reason ? ': “' + it.reason + '”' : '')
      + (it.decidedBy ? ' · decided by ' + it.decidedBy + ' ' + when(it.decidedAt) : '');
    ['approve', 'reject', 'withdraw'].forEach(function (a) { $('[data-ap-act="' + a + '"]').hidden = o.actions.indexOf(a) < 0; });
    $('[data-ap-comment]').hidden = !o.actions.some(function (a) { return a !== 'ask'; });
    params(o.actions.indexOf('approve') >= 0 ? it.kind : '');
    diff(o.detail.diff);
    if (it.kind === 'sutra') {                              // a Sutra: its diff with the moved panels, and the workbench's evidence (the console draws them)
      fetch('/approvals/api/view/sutra/' + enc(it.id), { credentials: 'same-origin', headers: { Accept: 'text/html' } })
        .then(function (r) { return r.ok ? r.text() : null; })
        .then(function (html) {
          if (html && state.open && state.open.kind === 'sutra' && state.open.id === it.id) {
            var box = $('[data-ap-diff]'); clear(box);
            box.appendChild(document.createRange().createContextualFragment(html));
          }
        }).catch(function () { /* the plain line diff stays */ });
    }
    lines('[data-ap-effect]', o.detail.effect);
    lines('[data-ap-rules]', o.detail.rules);
    facts(o.detail.facts);
    thread(o.questions);
    say('');
    mark();
    remember();
  }
  function open(kind, id) {
    return get('item/' + kind + '/' + enc(id)).then(draw).catch(function (e) {
      $('[data-ap-pick]').hidden = false; $('[data-ap-item]').hidden = true;
      $('[data-ap-pick]').textContent = 'The item could not be opened: ' + e.message;
    });
  }
  function act(a) {
    if (!state.open) { return; }
    var comment = $('[data-ap-comment]').value.trim();
    if (a === 'reject' && !comment) { say('Say why it is rejected (the comment box), then Reject.', true); $('[data-ap-comment]').focus(); return; }
    var p = {};
    $$('[data-ap-param]').forEach(function (i) { if (i.value.trim()) { p[i.getAttribute('data-ap-param')] = i.value.trim(); } });
    $$('[data-ap-flag]').forEach(function (i) { if (i.checked) { p[i.getAttribute('data-ap-flag')] = true; } });
    var btn = $('[data-ap-act="' + a + '"]'); btn.disabled = true;
    post('item/' + state.open.kind + '/' + enc(state.open.id) + '/' + a, { comment: comment, params: p }).then(function (it) {
      $('[data-ap-comment]').value = '';
      return Promise.all([load(), open(it.kind || state.open.kind, it.id || state.open.id)]).then(function () {
        say(a === 'approve' ? 'Approved.' : a === 'reject' ? 'Rejected.' : 'Withdrawn.');
      });
    }).catch(function (e) { say(e.message, true); }).then(function () { btn.disabled = false; });
  }

  // ---- wiring ---------------------------------------------------------------------------------------------------------------------------

  $$('[data-ap-box]').forEach(function (b) { b.addEventListener('click', function () { state.box = b.getAttribute('data-ap-box'); remember(); load(); }); });
  $$('[data-ap-group]').forEach(function (b) { b.addEventListener('click', function () { state.group = b.getAttribute('data-ap-group'); remember(); load(); }); });
  $('[data-ap-kind]').addEventListener('change', function (e) { state.kind = e.target.value; remember(); load(); });
  $('[data-ap-refresh]').addEventListener('click', function () { load(); if (state.open) { open(state.open.kind, state.open.id); } });
  ['approve', 'reject', 'withdraw'].forEach(function (a) { $('[data-ap-act="' + a + '"]').addEventListener('click', function () { act(a); }); });
  $('[data-ap-ask]').addEventListener('submit', function (e) {
    e.preventDefault();
    var q = $('[data-ap-question]'), text = q.value.trim();
    if (!state.open || !text) { return; }
    post('item/' + state.open.kind + '/' + enc(state.open.id) + '/questions', { text: text }).then(function (posts) {
      q.value = ''; thread(posts); say('Asked: the maker is told.');
    }).catch(function (err) { say(err.message, true); });
  });

  load().then(function () {
    var k = root.getAttribute('data-item-kind'), id = root.getAttribute('data-item-id');
    if (k && id) { open(k, id); }
  });
})();
