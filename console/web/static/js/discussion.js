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
/* Discussion (COLLABORATION.md, Discussion): the second tab of the side drawer (the first is About, about.js). Threads on the whole view, on a
   panel (the comment badge in the panel header, beside its ?) or on a field; reply, edit within the window, retract, resolve, follow and
   mute, and for administrators hide and lock. A comment's pin ("gen 1702 · 2 Oct") and *Open as it was* come from the server's thread
   answer (the console adds the link). New comments for people who follow a thread arrive on the bell's live connection (channel.js) and
   are announced in a polite live region. The server decides every right; this file draws what it says and puts a refusal in words.
   Alt+N opens the tab and puts the focus in the comment box; ? and F1 still open About. Drawing is discussion-view.js, the @ and { pickers discussion-compose.js. */
(function () {
  'use strict';
  var drawer = document.getElementById('aboutDrawer'), view = document.querySelector('[data-view]'), host = window.drishtiAbout;
  if (!drawer || !view || !host || !host.setTab) { return; }
  var V = window.DrishtiThreadView, panel = drawer.querySelector('[data-disc-panel]');
  var kind = view.getAttribute('data-kind'), id = view.getAttribute('data-id'), embed = view.hasAttribute('data-embed');
  var list = panel.querySelector('[data-disc-list]'), filterEl = panel.querySelector('[data-disc-filter]'), live = panel.querySelector('[data-disc-live]');
  var form = panel.querySelector('[data-disc-form]'), anchor = form.querySelector('[data-disc-anchor]'), box = form.querySelector('[data-disc-body]');
  var msg = form.querySelector('[data-disc-msg]'), scope = panel.querySelector('[data-disc-scope]'), tabCount = drawer.querySelector('[data-disc-tabcount]');
  var conf = { enabled: true, collaborate: true, maxText: 2000, minQuery: 2 }, threads = [], only = { panel: '', path: '' }, counts = { entity: 0, panels: {}, fields: {} };
  var base = '/api/threads/' + encodeURIComponent(kind) + '/' + encodeURIComponent(id);

  function ctx() {
    return { me: view.getAttribute('data-me'), admin: view.hasAttribute('data-admin'), pageGen: view.getAttribute('data-generation'),
      pageAsOf: view.getAttribute('data-page-asof') || 'live', canWrite: conf.collaborate, maxText: conf.maxText };
  }
  function api(method, url, body) {
    var o = { method: method, credentials: 'same-origin', headers: { Accept: 'application/json' } };
    if (body !== undefined) { o.headers['Content-Type'] = 'application/json'; o.body = JSON.stringify(body); }
    return fetch(url, o).then(function (r) { return r.json().catch(function () { return {}; }).then(function (b) { return { ok: r.ok, status: r.status, b: b }; }); });
  }
  function say(node, text, bad) { node.textContent = text; node.classList.toggle('bad', !!bad); }
  function problem(r) { return (r.b && (r.b.detail || r.b.code)) || ('The server answered ' + r.status); }
  function page() { return { asOf: view.getAttribute('data-page-asof') || 'live', knownAt: view.getAttribute('data-known') || null, generation: Number(view.getAttribute('data-generation')) || 0 }; }
  function announce(t) { live.textContent = ''; setTimeout(function () { live.textContent = t; }, 50); }

  // the places one can write about: the view, each panel, each labelled field (the strip and key-value panels)
  function fields() {
    var out = [], seen = {};
    view.querySelectorAll('[data-path]').forEach(function (e) {
      var p = e.getAttribute('data-path'), dt = e.previousElementSibling, label = dt && dt.tagName === 'DT' ? dt.textContent.trim() : '';
      if (p && label && !seen[p]) { seen[p] = 1; out.push({ path: p, label: label }); }
    });
    return out;
  }
  function panels() {
    return Array.prototype.map.call(view.querySelectorAll('.pnl[data-panel]'), function (p) { return { id: p.getAttribute('data-panel'), title: ((p.querySelector('h3') || {}).textContent || p.getAttribute('data-panel')).trim() }; });
  }
  function fillAnchors() {
    anchor.textContent = '';
    anchor.appendChild(new Option('The whole view (' + id + ')', 'entity'));
    var g = V.el('optgroup'); g.label = 'A panel';
    panels().forEach(function (p) { g.appendChild(new Option(p.title, 'panel:' + p.id)); });
    if (g.children.length) { anchor.appendChild(g); }
    var f = V.el('optgroup'); f.label = 'A field';
    fields().forEach(function (x) { f.appendChild(new Option(x.label, 'field:' + x.path)); });
    if (f.children.length) { anchor.appendChild(f); }
  }
  function aim(value) { if (value && Array.prototype.some.call(anchor.options, function (o) { return o.value === value; })) { anchor.value = value; } }

  function total() { return counts.entity + Object.keys(counts.panels).reduce(function (n, k) { return n + counts.panels[k]; }, 0) + Object.keys(counts.fields).reduce(function (n, k) { return n + counts.fields[k]; }, 0); }
  function badges() {
    var n = total();
    document.querySelectorAll('[data-disc-count]').forEach(function (e) { e.textContent = String(n); e.hidden = !n; });
    if (tabCount) { tabCount.textContent = n ? ' (' + n + ')' : ''; }
    document.querySelectorAll('.pnl[data-panel] [data-disc-panel-open]').forEach(function (b) {
      var c = counts.panels[b.getAttribute('data-disc-panel-open')] || 0, s = b.querySelector('.disc-n'), name = b.getAttribute('data-name');
      s.textContent = c ? String(c) : ''; b.classList.toggle('has', c > 0);
      b.setAttribute('aria-label', c ? 'Discussion on ' + name + ': ' + c + (c === 1 ? ' comment' : ' comments') : 'Start a discussion on ' + name);
    });
    view.querySelectorAll('.has-note').forEach(function (e) { e.classList.remove('has-note'); e.removeAttribute('data-note'); });
    Object.keys(counts.fields).forEach(function (p) {
      view.querySelectorAll('[data-path="' + CSS.escape(p) + '"]').forEach(function (e) { e.classList.add('has-note'); e.setAttribute('data-note', counts.fields[p] + ' comment(s)'); e.title = counts.fields[p] + (counts.fields[p] === 1 ? ' comment' : ' comments') + ' on this field (Alt+N)'; });
    });
  }
  function loadCounts() { return api('GET', '/api/thread-counts/' + encodeURIComponent(kind) + '/' + encodeURIComponent(id)).then(function (r) { if (r.ok) { counts = r.b; badges(); } }); }

  function draw() {
    list.textContent = '';
    if (!threads.length) { list.appendChild(V.el('p', 'disc-empty', only.panel || only.path ? 'No comments here yet.' : 'No comments yet. Start the discussion below.')); }
    var c = ctx();
    threads.forEach(function (t) { list.appendChild(V.thread(t, c)); });
    list.querySelectorAll('form[data-reply]').forEach(function (f) { var ta = f.querySelector('textarea'); window.DrishtiCompose.attach(ta, f.querySelector('ul'), { kind: kind, minQuery: function () { return conf.minQuery; }, fields: fields }); });
    scope.hidden = !(only.panel || only.path);
    var label = only.panel ? (panels().filter(function (p) { return p.id === only.panel; })[0] || {}).title || only.panel : only.path;
    scope.querySelector('[data-disc-scope-name]').textContent = label || '';
  }
  function load() {
    var q = [filterEl.value ? 'state=' + filterEl.value : '', only.panel ? 'panel=' + encodeURIComponent(only.panel) : '', only.path ? 'path=' + encodeURIComponent(only.path) : ''].filter(Boolean).join('&');
    return api('GET', base + (q ? '?' + q : '')).then(function (r) {
      if (!r.ok) { list.textContent = ''; list.appendChild(V.el('p', 'disc-empty', 'Comments could not be loaded: ' + problem(r))); return; }
      threads = r.b; draw();
    }).then(loadCounts);
  }

  function open(opts) {
    opts = opts || {};
    only = { panel: opts.panel || '', path: opts.path || '' };
    host.open('discussion');
    aim(opts.panel ? 'panel:' + opts.panel : opts.path ? 'field:' + opts.path : 'entity');
    return load().then(function () { box.focus(); });
  }

  // one comment box submits a thread, another a reply: the answer's warnings and who was not told are shown to the writer
  function posted(r, target, ta) {
    if (!r.ok) { say(target, problem(r), true); return false; }
    var out = ['Posted.'], b = r.b;
    if (b.notified) { out.push(b.notified + (b.notified === 1 ? ' person' : ' people') + ' notified.'); }
    (b.skipped || []).forEach(function (s) { out.push('Not told: ' + s.name + ' (' + s.reason + ').'); });
    (b.warnings || []).forEach(function (w) { out.push(w); });
    say(target, out.join(' '), false);
    ta.value = ''; load();
    return true;
  }
  form.addEventListener('submit', function (e) {
    e.preventDefault();
    if (!box.value.trim()) { say(msg, 'Write something first.', true); return; }
    var a = anchor.value.split(':'), pg = page(), opt = anchor.options[anchor.selectedIndex];
    var body = { anchor: a[0], generation: pg.generation, asOf: pg.asOf, knownAt: pg.knownAt, body: box.value, label: opt.textContent };
    if (a[0] === 'panel') { body.panel = a[1]; var p = document.getElementById('p-' + a[1]); if (p && p.getAttribute('data-gate-kind')) { body.gateKind = p.getAttribute('data-gate-kind'); } }
    if (a[0] === 'field') { body.path = a.slice(1).join(':'); }
    api('POST', base, body).then(function (r) { posted(r, msg, box); });
  });
  form.querySelector('[data-disc-quote]').addEventListener('click', function () {   // the keyboard-free way to the { picker
    var at = box.selectionStart; box.value = box.value.slice(0, at) + '{' + box.value.slice(box.selectionEnd);
    box.setSelectionRange(at + 1, at + 1); box.focus(); box.dispatchEvent(new Event('input', { bubbles: true }));
  });
  box.addEventListener('keydown', function (e) { if (e.key === 'Enter' && (e.ctrlKey || e.metaKey)) { e.preventDefault(); form.requestSubmit(); } });
  list.addEventListener('submit', function (e) {
    var f = e.target.closest('form[data-reply]');
    if (!f) { return; }
    e.preventDefault();
    var ta = f.querySelector('textarea'), pg = page();
    if (!ta.value.trim()) { return; }
    api('POST', '/api/thread/' + f.getAttribute('data-reply') + '/comments', { generation: pg.generation, asOf: pg.asOf, knownAt: pg.knownAt, body: ta.value }).then(function (r) {
      var note = f.parentNode.querySelector('.disc-note') || f.parentNode.appendChild(V.el('p', 'disc-note'));
      note.setAttribute('role', 'status'); posted(r, note, ta);
    });
  });
  list.addEventListener('keydown', function (e) { if (e.key === 'Enter' && (e.ctrlKey || e.metaKey) && e.target.closest('form')) { e.preventDefault(); e.target.closest('form').requestSubmit(); } });

  // inline forms: edit a comment in place; give a reason to hide one
  function inline(li, text, label, run) {
    var f = V.el('form', 'disc-inline'), ta = V.el('textarea', 'studio-in'), note = V.el('p', 'disc-note');
    ta.rows = 2; ta.value = text; ta.maxLength = conf.maxText; ta.setAttribute('aria-label', label); note.setAttribute('role', 'status');
    var ok = V.el('button', 'btn-pill btn-accent btn-sm-pill', 'Save'), no = V.btn('Cancel', 'cancel');
    ok.type = 'submit'; [ta, ok, no, note].forEach(function (n) { f.appendChild(n); });
    f.addEventListener('submit', function (e) { e.preventDefault(); run(ta.value, note); });
    f.addEventListener('keydown', function (e) { if (e.key === 'Enter' && e.ctrlKey) { e.preventDefault(); f.requestSubmit(); } });
    li.appendChild(f); ta.focus();
    return ta;
  }
  function act(btn) {
    var li = btn.closest('[data-comment]'), cid = li && li.getAttribute('data-comment'), th = btn.closest('[data-thread]'), tid = th && th.getAttribute('data-thread');
    var go = function (r, note) { if (r.ok) { load(); } else if (note) { say(note, problem(r), true); load(); } else { announce(problem(r)); load(); } };
    switch (btn.getAttribute('data-act')) {
      case 'goto': {
        var t = view.querySelector('[data-path="' + CSS.escape(btn.getAttribute('data-path')) + '"]');
        if (t) { t.scrollIntoView({ block: 'center' }); t.setAttribute('tabindex', '-1'); t.focus({ preventScroll: true }); t.classList.add('disc-flash'); setTimeout(function () { t.classList.remove('disc-flash'); }, 2500); }
        else { announce('That field is not on this page.'); }
        break;
      }
      case 'cancel': btn.closest('form').remove(); break;
      case 'edit': {
        var cur = (threads.reduce(function (a, t2) { return a.concat(t2.items || []); }, []).filter(function (c) { return c.id === cid; })[0] || {}), tools = li.querySelector('.disc-tools');
        var ta = inline(li, cur.body || '', 'Edit your comment', function (text, note) {
          api('PATCH', '/api/comment/' + cid, { body: text, revision: Number(li.getAttribute('data-revision')) }).then(function (r) { go(r, note); });
        });
        var opts = V.el('ul', 'shr-list disc-opts'); opts.id = 'discEditList-' + cid; opts.hidden = true; opts.setAttribute('role', 'listbox'); opts.setAttribute('aria-label', 'Suggestions');
        ta.parentNode.insertBefore(opts, ta.nextSibling); tools.hidden = true;
        window.DrishtiCompose.attach(ta, opts, { kind: kind, minQuery: function () { return conf.minQuery; }, fields: fields });
        break;
      }
      case 'retract':
        if (!btn.hasAttribute('data-sure')) { btn.setAttribute('data-sure', '1'); btn.textContent = 'Retract: sure?'; break; }
        api('POST', '/api/comment/' + cid + '/retract').then(function (r) { go(r); });
        break;
      case 'hide': inline(li, '', 'Reason for hiding this comment', function (reason, note) { api('POST', '/api/comment/' + cid + '/hide', { reason: reason }).then(function (r) { go(r, note); }); }); break;
      case 'unhide': api('POST', '/api/comment/' + cid + '/unhide').then(function (r) { go(r); }); break;
      case 'history':
        api('GET', '/api/comment/' + cid + '/revisions').then(function (r) {
          var old = li.querySelector('.disc-hist'); if (old) { old.remove(); return; }
          var ul = V.el('ul', 'disc-hist');
          (r.ok ? r.b : []).forEach(function (v) { ul.appendChild(V.el('li', null, 'v' + v.revision + ' · ' + V.when(v.at, v.atLocal) + ' · ' + v.action + (v.body ? ': ' + v.body : ''))); });
          li.appendChild(ul);
        });
        break;
      case 'state': api('POST', '/api/thread/' + tid + '/state', { state: btn.getAttribute('data-to') }).then(function (r) { go(r); }); break;
      case 'follow': (btn.getAttribute('aria-pressed') === 'true' ? api('DELETE', '/api/thread/' + tid + '/follow') : api('PUT', '/api/thread/' + tid + '/follow', { muted: false })).then(function (r) { go(r); }); break;
      case 'mute': api('PUT', '/api/thread/' + tid + '/follow', { muted: btn.getAttribute('aria-pressed') !== 'true' }).then(function (r) { go(r); }); break;
      default: break;
    }
  }
  list.addEventListener('click', function (e) { var b = e.target.closest('button[data-act]'); if (b && !b.disabled) { act(b); } });
  filterEl.addEventListener('change', load);
  scope.querySelector('[data-disc-scope-clear]').addEventListener('click', function () { only = { panel: '', path: '' }; load(); });

  // the drawer's tab, the header button, the keyboard
  document.querySelectorAll('[data-discussion-open]').forEach(function (b) { b.addEventListener('click', function () { if (host.isOpen() && host.tab() === 'discussion') { host.close(); } else { open(); } }); });
  document.addEventListener('keydown', function (e) {
    if (e.altKey && !e.ctrlKey && !e.metaKey && !e.shiftKey && (e.code === 'KeyN' || e.key === 'n')) { e.preventDefault(); open(); }
  });
  document.addEventListener('drishti:drawer', function (e) {
    document.querySelectorAll('[data-discussion-open]').forEach(function (b) { b.setAttribute('aria-expanded', e.detail.open && e.detail.tab === 'discussion' ? 'true' : 'false'); });
    if (e.detail.open && e.detail.tab === 'discussion' && !threads.length) { load(); }
  });

  // live: a notice about this entity (a reply on a thread you follow, a mention) refreshes the list and is said aloud
  if (window.DrishtiChannel && !embed) {
    window.DrishtiChannel.subscribe('alerts', { notice: function (n) {
      if (n.kind !== kind || n.id !== id || (n.type !== 'mention' && n.type !== 'reply')) { return; }
      var name = n.panel ? ((panels().filter(function (p) { return p.id === n.panel; })[0] || {}).title || n.panel) : 'this view';
      announce('1 new comment on ' + name);
      if (host.isOpen() && host.tab() === 'discussion') { load(); } else { loadCounts(); }
    } });
  }

  fillAnchors();
  api('GET', '/api/collab').then(function (r) {
    if (r.ok && r.b) { conf = { enabled: r.b.enabled !== false, collaborate: !!r.b.collaborate, maxText: r.b.maxText || 2000, minQuery: r.b.minQuery || 2 }; }
    if (!conf.enabled) { document.querySelectorAll('[data-discussion-open], [data-disc-tab]').forEach(function (b) { b.hidden = true; }); return; }
    form.hidden = !conf.collaborate;
    if (!conf.collaborate) { say(msg, 'You can read this discussion; writing needs the collaborate right.', false); }
    box.maxLength = conf.maxText;
    window.DrishtiCompose.attach(box, form.querySelector('[data-disc-opts]'), { kind: kind, minQuery: function () { return conf.minQuery; }, fields: fields });
    if (!embed) {                                                      // a comment badge beside the ? of every panel
      document.querySelectorAll('.pnl[data-panel] .pnl-code').forEach(function (code) {
        var pid = code.closest('.pnl').getAttribute('data-panel'), name = ((code.closest('.pnl').querySelector('h3') || {}).textContent || pid).trim();
        var b = V.el('button', 'pnl-help disc-badge'); b.type = 'button'; b.setAttribute('data-disc-panel-open', pid); b.setAttribute('data-name', name);
        var i = V.el('i', 'bi bi-chat-left-text'); i.setAttribute('aria-hidden', 'true'); b.appendChild(i); b.appendChild(V.el('span', 'disc-n'));
        b.addEventListener('click', function () { open({ panel: pid }); });
        code.insertBefore(b, code.querySelector('a.pnl-help[href^="/help/"]') || null);
      });
    }
    badges();
    loadCounts();
  });
  window.drishtiDiscussion = { open: open };
})();
