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
/* Admin → Net: the tabs (arrow keys move between them, the URL keeps the open one), the member table refreshed every 5 s, the
   node's actions (drain, leave, rejoin), seeds, removing a gone member, and the files of config/net/ (check, save with the version
   read as If-Match, delete, history with a line diff against the file). Every call is same-origin JSON to /admin/net/api/...; the
   page never holds or shows a key. Other tabs (capability map, routing, access, security) add their own code in this file. */
(function () {
  'use strict';
  var root = document.querySelector('[data-net]');
  if (!root) { return; }
  var $ = function (sel, at) { return (at || root).querySelector(sel); };
  var $$ = function (sel, at) { return Array.prototype.slice.call((at || root).querySelectorAll(sel)); };
  var msg = $('[data-msg]');
  var NET = window.DrishtiNet = window.DrishtiNet || {};

  function say(text, bad) { msg.textContent = text || ''; msg.classList.toggle('t-bad', !!bad); }
  function el(tag, attrs, text) {
    var e = document.createElement(tag);
    Object.keys(attrs || {}).forEach(function (k) { e.setAttribute(k, attrs[k]); });
    if (text !== undefined && text !== null) { e.textContent = String(text); }
    return e;
  }
  function api(method, url, body, headers) {
    var h = { 'Accept': 'application/json' };
    if (body) { h['Content-Type'] = 'application/json'; }
    Object.keys(headers || {}).forEach(function (k) { h[k] = headers[k]; });
    return fetch(url, { method: method, credentials: 'same-origin', headers: h, body: body ? JSON.stringify(body) : undefined }).then(function (r) {
      return r.json().catch(function () { return {}; }).then(function (j) {
        if (!r.ok) { var e = new Error((j && j.detail) || ('the console said ' + r.status)); e.body = j; e.status = r.status; throw e; }
        return j;
      });
    });
  }
  NET.api = api; NET.say = say; NET.el = el;

  // tabs ----------------------------------------------------------------------------------------------------------
  var tabs = $$('[data-tab]', $('[data-tabs]'));
  function go(name, focus) {
    tabs.forEach(function (t) {
      var on = t.getAttribute('data-tab') === name;
      t.setAttribute('aria-selected', on ? 'true' : 'false');
      t.tabIndex = on ? 0 : -1;
      if (on && focus) { t.focus(); }
    });
    $$('[data-pane]').forEach(function (p) { p.hidden = p.getAttribute('data-pane') !== name; });
    try { history.replaceState(null, '', '/admin/net?tab=' + encodeURIComponent(name)); } catch (e) { /* a sandboxed frame */ }
    root.setAttribute('data-tab', name);
    if (NET.opened && NET.opened[name]) { NET.opened[name](); }
  }
  NET.go = go;
  tabs.forEach(function (t) { t.addEventListener('click', function () { go(t.getAttribute('data-tab')); }); });
  $('[data-tabs]').addEventListener('keydown', function (e) {
    var i = tabs.indexOf(document.activeElement);
    if (i < 0) { return; }
    var next = e.key === 'ArrowRight' ? (i + 1) % tabs.length : e.key === 'ArrowLeft' ? (i + tabs.length - 1) % tabs.length
      : e.key === 'Home' ? 0 : e.key === 'End' ? tabs.length - 1 : -1;
    if (next >= 0) { e.preventDefault(); go(tabs[next].getAttribute('data-tab'), true); }
  });

  // members -------------------------------------------------------------------------------------------------------
  var STATE = { alive: 'st-ok', suspect: 'st-warn', dead: 'st-bad', left: '' };
  function when(iso) { if (!iso) { return ''; } var d = new Date(iso); return isNaN(d) ? iso : d.toLocaleString(); }
  function tag(text, cls) { return el('span', { 'class': 'net-tag' + (cls ? ' ' + cls : '') }, text); }
  function row(m) {
    var tr = el('tr', { 'data-member': m.nodeId, 'data-state': m.state });
    var th = el('th', { scope: 'row', 'class': 'mono' }, m.nodeId);
    if (m.self) { th.appendChild(document.createTextNode(' ')); th.appendChild(tag('this node')); }
    if (m.drained) { th.appendChild(document.createTextNode(' ')); th.appendChild(tag('drained', 'net-warn')); }
    tr.appendChild(th);
    var st = el('td'); st.appendChild(el('span', { 'class': 'st ' + (STATE[m.state] || '') }, m.state)); tr.appendChild(st);
    tr.appendChild(el('td', { 'class': 'mono' }, m.url));
    tr.appendChild(el('td', { 'class': 'mono' }, m.version + (m.protocol !== 1 ? ' protocol ' + m.protocol : '')));
    var tags = el('td'); (m.tags || []).forEach(function (t) { tags.appendChild(tag(t)); }); tr.appendChild(tags);
    var packs = el('td');
    (m.packs || []).forEach(function (p) {
      var s = tag(p.name, p.peerOnly ? 'net-peer-only' : ''); s.title = p.name + ' ' + p.version + (p.peerOnly ? ' (peer-only)' : ''); packs.appendChild(s);
    });
    tr.appendChild(packs);
    tr.appendChild(el('td', null, m.lastHeard ? when(m.lastHeard) : (m.self ? '-' : 'never')));
    tr.appendChild(el('td', null, m.latencyMs === null || m.latencyMs === undefined ? '-' : m.latencyMs + ' ms'));
    tr.appendChild(el('td', { 'class': 'mono' }, m.incarnation));
    var act = el('td');
    if (m.state === 'dead' || m.state === 'left') {
      act.appendChild(el('button', { type: 'button', 'class': 'btn-pill', 'data-remove': m.nodeId, 'aria-label': 'Remove ' + m.nodeId }, 'Remove'));
    }
    tr.appendChild(act);
    return tr;
  }
  function render(data) {
    var body = $('[data-members] tbody');
    if (!body) { return; }
    body.textContent = '';
    (data.members || []).forEach(function (m) { body.appendChild(row(m)); });
    if (!(data.members || []).length) {
      var tr = el('tr'); tr.appendChild(el('td', { colspan: '10', 'class': 'text-muted-d' }, 'No members: the cluster is off on this server.')); body.appendChild(tr);
    }
    Object.keys(data.counts || {}).forEach(function (s) {
      var chip = $('[data-count="' + s + '"]');
      if (chip) { chip.textContent = data.counts[s] + ' ' + s; }
    });
    var rounds = $('[data-rounds]');
    if (rounds && data.node && data.node.gossip) { rounds.textContent = data.node.gossip.rounds; }
    NET.members = data;
    if (NET.onMembers) { NET.onMembers.forEach(function (f) { f(data); }); }
  }
  function refresh() {
    if (document.hidden) { return Promise.resolve(); }
    return api('GET', '/admin/net/api/members').then(render).catch(function () { /* the next poll tries again */ });
  }
  NET.refresh = refresh;
  setInterval(refresh, 5000);

  root.addEventListener('click', function (e) {
    var b = e.target.closest('[data-remove]');
    if (b) {
      var id = b.getAttribute('data-remove');
      api('DELETE', '/admin/net/api/members/' + encodeURIComponent(id)).then(function () { say('Removed ' + id + ' from the member table.'); return refresh(); })
        .catch(function (err) { say(err.message, true); });
      return;
    }
    var a = e.target.closest('[data-action]');
    if (a) {
      var action = a.getAttribute('data-action'), sure = a.getAttribute('data-confirm');
      if (sure && !window.confirm(sure)) { return; }
      a.disabled = true;
      api('POST', '/admin/net/api/action/' + action).then(function () { window.location.reload(); })
        .catch(function (err) { a.disabled = false; say(err.message, true); });
    }
  });
  var seedForm = $('[data-seed-form]');
  if (seedForm) {
    seedForm.addEventListener('submit', function (e) {
      e.preventDefault();
      var url = $('[data-seed]').value.trim();
      if (!url) { say('Type the seed as host:port or an address.', true); return; }
      api('POST', '/admin/net/api/seeds', { url: url }).then(function (r) {
        say('Saved ' + r.file + (r.answered ? '; it answered and its members are listed.' : '; it did not answer yet (it is tried every round).'));
        $('[data-seed]').value = '';
        return refresh();
      }).catch(function (err) { say(err.message, true); });
    });
  }

  // files ---------------------------------------------------------------------------------------------------------
  var editor = $('[data-editor]'), yaml = $('[data-yaml]'), problems = $('[data-problems]'), historyList = $('[data-history]'), diff = $('[data-diff]');
  var current = { id: null, etag: '', text: '' };
  function showProblems(list) {
    problems.textContent = '';
    (list || []).forEach(function (p) {
      problems.appendChild(el('li', { 'class': p.level === 'error' ? 't-bad' : 't-warn' },
        (p.line ? 'line ' + p.line + ': ' : '') + (p.field ? p.field + ': ' : '') + p.message));
    });
  }
  function lineDiff(a, b) {
    var x = a.split('\n'), y = b.split('\n'), out = [], i = 0, j = 0;
    while (i < x.length || j < y.length) {
      if (i < x.length && j < y.length && x[i] === y[j]) { out.push('  ' + x[i]); i++; j++; }
      else if (j < y.length && x.indexOf(y[j], i) < 0) { out.push('+ ' + y[j]); j++; }
      else if (i < x.length) { out.push('- ' + x[i]); i++; }
      else { out.push('+ ' + y[j]); j++; }
    }
    return out.join('\n');
  }
  function open(id) {
    say('');
    diff.hidden = true;
    return api('GET', '/admin/net/api/file?id=' + encodeURIComponent(id)).then(function (f) {
      current = { id: id, etag: f.exists ? f.etag : '', text: f.text || '' };
      editor.hidden = false;
      $('[data-editing]').textContent = id + (f.exists ? '' : ' (new)');
      yaml.value = f.text || '';
      showProblems(f.problems);
      historyList.textContent = '';
      (f.history || []).forEach(function (h) {
        var li = el('li');
        var b = el('button', { type: 'button', 'class': 'btn-pill', 'data-rev': h.id }, when(h.at));
        li.appendChild(b);
        li.appendChild(el('span', { 'class': 'text-muted-d' }, ' ' + h.bytes + ' bytes'));
        historyList.appendChild(li);
      });
      if (!(f.history || []).length) { historyList.appendChild(el('li', { 'class': 'text-muted-d' }, 'No earlier versions.')); }
      $$('[data-file]').forEach(function (b) { b.setAttribute('aria-current', b.getAttribute('data-file') === id ? 'true' : 'false'); });
    }).catch(function (err) { say(err.message, true); });
  }
  $$('[data-file]').forEach(function (b) { b.addEventListener('click', function () { open(b.getAttribute('data-file')); }); });
  var newPeer = $('[data-new-peer]');
  if (newPeer) {
    newPeer.addEventListener('click', function () {
      var name = window.prompt('The peer\'s name (lower-case letters, digits and dashes): it becomes peers/<name>.yaml');
      if (!name) { return; }
      open('peers/' + name.trim() + '.yaml').then(function () { if (!yaml.value) { yaml.value = 'url: http://host:port\n'; } });
    });
  }
  historyList.addEventListener('click', function (e) {
    var b = e.target.closest('[data-rev]');
    if (!b || !current.id) { return; }
    api('GET', '/admin/net/api/file/revision?id=' + encodeURIComponent(current.id) + '&rev=' + encodeURIComponent(b.getAttribute('data-rev')))
      .then(function (r) { diff.hidden = false; diff.textContent = 'Changes from that version to the file now:\n\n' + lineDiff(r.text, current.text); })
      .catch(function (err) { say(err.message, true); });
  });
  $('[data-check]').addEventListener('click', function () {
    api('POST', '/admin/net/api/file/check?id=' + encodeURIComponent(current.id), { text: yaml.value }).then(function (r) {
      showProblems(r.problems);
      say(r.ok ? current.id + ' would be taken' + ((r.problems || []).length ? ', with warnings.' : '.') : current.id + ' would be refused: see the problems.', !r.ok);
    }).catch(function (err) { say(err.message, true); });
  });
  $('[data-save]').addEventListener('click', function () {
    api('PUT', '/admin/net/api/file?id=' + encodeURIComponent(current.id), { text: yaml.value }, { 'If-Match': current.etag }).then(function (r) {
      say('Saved ' + current.id + ' (version ' + r.etag + '). The server applied it.');
      showProblems(r.problems);
      return open(current.id);
    }).catch(function (err) { showProblems(err.body && err.body.problems); say(err.message, true); });
  });
  $('[data-delete]').addEventListener('click', function () {
    if (!current.id || !window.confirm('Delete ' + current.id + '? Its text is kept in the history; its settings go back to their defaults.')) { return; }
    api('DELETE', '/admin/net/api/file?id=' + encodeURIComponent(current.id), null, { 'If-Match': current.etag }).then(function () {
      say('Deleted ' + current.id + '.');
      window.location.assign('/admin/net?tab=files');
    }).catch(function (err) { say(err.message, true); });
  });
})();
