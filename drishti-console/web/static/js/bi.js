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
/* Rupaka phase 1 (docs/architecture/RUPAKA.md section 5): the BI pages. The pages are a shell; this script draws them from the same-origin JSON
   (/bi/api/..., passed on to the server under the signed-in user's identity). Everything is built with DOM calls and textContent, never innerHTML,
   so a title or a description can never become markup. Keys: / search, g h, g c, g w, ?. */
(function () {
  'use strict';

  var canvas = document.getElementById('biCanvas');
  var settingsRoot = document.querySelector('[data-bi-settings]');
  if (!canvas && !settingsRoot) { return; }
  var page = canvas ? canvas.getAttribute('data-bi-page') : 'settings';
  var root = canvas || settingsRoot;
  var me = null;                                           // GET /bi/api/me: powers and policy

  // -- small helpers ------------------------------------------------------------------------------------------------------
  function h(tag, attrs) {
    var el = document.createElement(tag);
    Object.keys(attrs || {}).forEach(function (k) {
      if (k === 'text') { el.textContent = attrs[k]; } else if (k === 'class') { el.className = attrs[k]; } else if (attrs[k] === true) { el.setAttribute(k, ''); } else if (attrs[k] !== false && attrs[k] != null) { el.setAttribute(k, attrs[k]); }
    });
    (function add(list) {
      list.forEach(function (c) {
        if (c == null || c === false) { return; }
        if (Array.isArray(c)) { add(c); } else { el.appendChild(typeof c === 'string' ? document.createTextNode(c) : c); }
      });
    })(Array.prototype.slice.call(arguments, 2));
    return el;
  }
  function icon(name) { return h('i', { class: 'bi bi-' + name, 'aria-hidden': 'true' }); }
  function q(sel, scope) { return (scope || root).querySelector(sel); }
  function qa(sel, scope) { return Array.prototype.slice.call((scope || root).querySelectorAll(sel)); }
  function clear(el) { while (el.firstChild) { el.removeChild(el.firstChild); } }

  function say(text, bad) {
    var m = q('[data-msg]');
    if (!m) { return; }
    m.textContent = text || '';
    m.className = (m.className.replace(/\s*bi-bad/, '')) + (bad ? ' bi-bad' : '');
  }

  function api(method, path, body) {
    var opt = { method: method, headers: { 'Accept': 'application/json' }, credentials: 'same-origin' };
    if (body !== undefined && method !== 'GET' && method !== 'DELETE') {
      opt.headers['Content-Type'] = 'application/json';
      opt.body = JSON.stringify(body);
    }
    return fetch(path.indexOf('/bi/') === 0 ? path : '/bi/api' + path, opt).then(function (r) {
      if (r.status === 204) { return null; }
      return r.json().catch(function () { return {}; }).then(function (data) {
        if (!r.ok) { var e = new Error((data.code ? data.code + ' ' : '') + (data.detail || r.statusText)); e.code = data.code; e.status = r.status; throw e; }
        return data;
      });
    });
  }
  function fail(e) { say(e.message, true); }

  function ago(iso) {
    if (!iso) { return ''; }
    var s = Math.max(0, (Date.now() - new Date(iso).getTime()) / 1000);
    if (s < 90) { return 'just now'; }
    if (s < 5400) { return Math.round(s / 60) + ' min ago'; }
    if (s < 129600) { return Math.round(s / 3600) + ' h ago'; }
    return Math.round(s / 86400) + ' days ago';
  }
  function stamp(iso) { return iso ? iso.replace('T', ' ').replace(/\.\d+Z?$|Z$/, '') : ''; }
  var KIND_ICON = { report: 'bar-chart-line', dataset: 'hdd-stack', board: 'speedometer2', notebook: 'journal-code', python: 'filetype-py' };
  var RANK = { viewer: 0, contributor: 1, member: 2, admin: 3 };

  function badge(text, cls, ic) { return h('span', { class: 'bi-badge ' + (cls || '') }, ic ? icon(ic) : null, ' ' + text); }
  function endorsement(it) {
    if (it.endorsement === 'certified') { return badge('certified', 'bi-certified', 'patch-check'); }
    if (it.endorsement === 'promoted') { return badge('promoted', 'bi-promoted', 'bookmark-star'); }
    return null;
  }

  // -- cards ------------------------------------------------------------------------------------------------------------
  function card(it, onChange) {
    var fav = h('button', { type: 'button', class: 'bi-fav', 'aria-pressed': it.favourite ? 'true' : 'false', 'aria-label': (it.favourite ? 'Remove favourite: ' : 'Favourite: ') + it.title, title: 'Favourite' }, icon(it.favourite ? 'star-fill' : 'star'));
    fav.addEventListener('click', function () {
      api('PUT', '/items/' + encodeURIComponent(it.id) + '/favourite', { on: !it.favourite }).then(function (n) { it.favourite = n.favourite; if (onChange) { onChange(); } else { fav.setAttribute('aria-pressed', it.favourite ? 'true' : 'false'); clear(fav); fav.appendChild(icon(it.favourite ? 'star-fill' : 'star')); } }).catch(fail);
    });
    var meta = [it.owner, it.workspaceName];
    if (it.publishedVersion) { meta.push('v' + it.publishedVersion); }
    var fresh = it.publishedAt ? 'updated ' + ago(it.publishedAt) : 'not published';
    return h('article', { class: 'bi-card', 'data-id': it.id, 'data-kind': it.kind },
      h('header', {}, h('span', { class: 'bi-kind', title: it.kind }, icon(KIND_ICON[it.kind] || 'file-earmark'), ' ' + it.kind), fav),
      h('h3', {}, h('a', { href: '/bi/i/' + encodeURIComponent(it.id).replace(/%3A/g, ':') , text: it.title })),
      it.description ? h('p', { class: 'bi-card-desc', text: it.description }) : null,
      h('p', { class: 'bi-card-badges' }, endorsement(it), it.shipped ? badge('shipped', '', 'folder') : null, it.status && it.status !== 'published' ? badge(it.status, 'bi-status') : null,
        (it.tags || []).slice(0, 4).map(function (t) { return badge(t, 'bi-tagchip', 'tag'); })),
      h('p', { class: 'bi-card-meta', text: meta.join(' · ') + ' · ' + fresh + (it.views ? ' · ' + it.views + ' views' : '') }));
  }
  function fillCards(box, items, onChange) {
    clear(box);
    items.forEach(function (it) { box.appendChild(card(it, onChange)); });
  }

  // -- Home -------------------------------------------------------------------------------------------------------------
  function home() {
    function load() {
      api('GET', '/home').then(function (d) {
        qa('[data-sec]').forEach(function (sec) {
          var items = d[sec.getAttribute('data-sec')] || [];
          fillCards(q('[data-cards]', sec), items, load);
          q('[data-empty]', sec).hidden = items.length > 0;
        });
      }).catch(fail);
    }
    load();
  }

  // -- Catalogue --------------------------------------------------------------------------------------------------------
  function catalogue() {
    var form = q('[data-catalogue-form]');
    var params = new URLSearchParams(location.search);
    var cards = q('[data-cards]'), empty = q('[data-empty]'), count = q('[data-count]');
    var timer = null, seq = 0;
    var FILTERS = ['kind', 'workspace', 'owner', 'endorsement', 'tag'];
    ['q', 'fresh', 'sort'].forEach(function (k) { if (params.get(k)) { form.elements[k].value = params.get(k); } });

    function state() {
      var s = new URLSearchParams();
      ['q', 'kind', 'workspace', 'owner', 'endorsement', 'tag', 'fresh', 'sort'].forEach(function (k) { if (form.elements[k].value) { s.set(k, form.elements[k].value); } });
      return s;
    }
    function facets(f, names) {
      FILTERS.forEach(function (k) {
        var sel = form.elements[k], keep = sel.value || params.get(k) || '';
        clear(sel);
        sel.appendChild(h('option', { value: '', text: 'any' }));
        var seen = f[k] || {};
        Object.keys(seen).sort().forEach(function (v) {
          var label = k === 'workspace' && names[v] ? names[v] : v;
          sel.appendChild(h('option', { value: v, text: label + ' (' + seen[v] + ')' }));
        });
        if (keep && !seen[keep]) { sel.appendChild(h('option', { value: keep, text: keep })); }
        sel.value = keep;
      });
    }
    function run() {
      var mine = ++seq, s = state();
      history.replaceState(null, '', location.pathname + (s.toString() ? '?' + s.toString() : ''));
      api('GET', '/catalogue?' + s.toString()).then(function (d) {
        if (mine !== seq) { return; }
        facets(d.facets, d.workspaceNames || {});
        fillCards(cards, d.items, run);
        count.textContent = d.total === 1 ? '1 item' : d.total + ' items' + (d.total > d.items.length ? ', showing ' + d.items.length : '');
        empty.hidden = d.items.length > 0;
        say('');
      }).catch(fail);
    }
    form.addEventListener('submit', function (e) { e.preventDefault(); run(); });
    form.addEventListener('input', function (e) { if (e.target.name === 'q') { clearTimeout(timer); timer = setTimeout(run, 180); } });
    form.addEventListener('change', function (e) { if (e.target.name !== 'q') { run(); } });
    q('[data-clear]').addEventListener('click', function () { qa('input, select', form).forEach(function (el) { el.value = el.name === 'sort' ? 'use' : ''; }); params = new URLSearchParams(); run(); });
    run();
    if (params.get('focus')) { q('[data-search]').focus(); }
  }

  // -- Workspaces -------------------------------------------------------------------------------------------------------
  function wsCard(w) {
    return h('article', { class: 'bi-card bi-ws', 'data-ws': w.id },
      h('header', {}, h('span', { class: 'bi-kind' }, icon(w.personal ? 'person-workspace' : w.shipped ? 'folder' : 'diagram-3'), ' ' + (w.personal ? 'personal' : w.shipped ? 'shipped' : 'shared')), badge(w.role, 'bi-role')),
      h('h3', {}, h('a', { href: '/bi/w/' + (w.personal ? 'me' : encodeURIComponent(w.id)), text: w.name })),
      w.description ? h('p', { class: 'bi-card-desc', text: w.description }) : null,
      h('p', { class: 'bi-card-meta', text: w.items + (w.items === 1 ? ' item' : ' items') + (w.reviewRequired ? ' · review before publishing' : '') }));
  }
  function workspaces() {
    var box = q('[data-cards]'), form = q('[data-new-workspace]');
    function load() { api('GET', '/workspaces').then(function (list) { clear(box); list.forEach(function (w) { box.appendChild(wsCard(w)); }); }).catch(fail); }
    form.hidden = !me.mayCreateWorkspace;
    form.addEventListener('submit', function (e) {
      e.preventDefault();
      api('POST', '/workspaces', { name: form.elements.name.value, description: form.elements.description.value, reviewRequired: form.elements.reviewRequired.checked })
        .then(function (w) { say('Created ' + w.name + '.'); form.reset(); load(); }).catch(fail);
    });
    load();
  }

  function workspace() {
    var id = canvas.getAttribute('data-workspace'), ws = null;
    var tbody = q('[data-items] tbody');
    function load() {
      Promise.all([api('GET', '/workspaces/' + encodeURIComponent(id)), api('GET', '/workspaces/' + encodeURIComponent(id) + '/items')]).then(function (r) {
        ws = r[0];
        q('[data-ws-name]').textContent = ws.name;
        q('[data-ws-desc]').textContent = ws.description || '';
        q('[data-ws-meta]').textContent = (ws.personal ? 'Personal workspace, private to you' : ws.shipped ? 'Read-only: the BI folder' : 'Your role: ' + ws.role) + (ws.reviewRequired ? ' · review before publishing' : '');
        document.title = ws.name + ' · BI';
        var rank = RANK[ws.role], authors = (me.powers || []).indexOf('bi-author') >= 0;
        q('[data-new-item]').hidden = !(authors && rank >= RANK.contributor && !ws.shipped);
        clear(tbody);
        r[1].forEach(function (it) {
          tbody.appendChild(h('tr', { 'data-id': it.id }, h('th', { scope: 'row' }, h('a', { href: '/bi/i/' + encodeURIComponent(it.id).replace(/%3A/g, ':'), text: it.title })),
            h('td', { text: it.kind }), h('td', { text: it.status }), h('td', { text: it.publishedVersion ? 'v' + it.publishedVersion : '' }),
            h('td', {}, endorsement(it) || ''), h('td', { text: ago(it.updatedAt) })));
        });
        q('[data-empty]').hidden = r[1].length > 0;
        members();
      }).catch(fail);
    }
    function members() {
      var sec = q('[data-members-section]'), set = q('[data-settings-section]');
      var shared = !ws.personal && !ws.shipped;
      sec.hidden = !shared; set.hidden = !(shared && ws.role === 'admin');
      if (!shared) { return; }
      var tb = q('[data-members] tbody'), admin = ws.role === 'admin';
      clear(tb);
      (ws.members || []).forEach(function (m) {
        var del = h('button', { type: 'button', class: 'btn-pill btn-ghost', 'aria-label': 'Remove ' + m.username }, 'Remove');
        del.addEventListener('click', function () { api('DELETE', '/workspaces/' + encodeURIComponent(ws.id) + '/members/' + encodeURIComponent(m.username)).then(function () { say('Removed ' + m.username + '.'); load(); }).catch(fail); });
        tb.appendChild(h('tr', { 'data-user': m.username }, h('th', { scope: 'row', text: m.username }), h('td', { text: m.role }), h('td', { text: m.addedBy }), h('td', {}, admin ? del : '')));
      });
      q('[data-add-member]').hidden = !admin;
      q('[data-review]').checked = !!ws.reviewRequired;
    }
    q('[data-new-item]').addEventListener('submit', function (e) {
      e.preventDefault();
      var f = e.target;
      api('POST', '/workspaces/' + encodeURIComponent(id) + '/items', { kind: f.elements.kind.value, title: f.elements.title.value, description: f.elements.description.value })
        .then(function (it) { window.location.href = '/bi/i/' + encodeURIComponent(it.id); }).catch(fail);
    });
    q('[data-add-member]').addEventListener('submit', function (e) {
      e.preventDefault();
      var f = e.target;
      api('PUT', '/workspaces/' + encodeURIComponent(ws.id) + '/members/' + encodeURIComponent(f.elements.user.value.trim()), { role: f.elements.role.value })
        .then(function () { say('Saved ' + f.elements.user.value + ' as ' + f.elements.role.value + '.'); f.reset(); load(); }).catch(fail);
    });
    q('[data-review]').addEventListener('change', function (e) {
      api('PUT', '/workspaces/' + encodeURIComponent(ws.id), { reviewRequired: e.target.checked }).then(function () { say('Review before publishing is ' + (e.target.checked ? 'on' : 'off') + '.'); load(); }).catch(fail);
    });
    q('[data-delete-workspace]').addEventListener('click', function (e) {
      var b = e.target;
      if (!b.getAttribute('data-sure')) { b.setAttribute('data-sure', '1'); b.textContent = 'Confirm: delete this workspace'; return; }
      api('DELETE', '/workspaces/' + encodeURIComponent(ws.id)).then(function () { window.location.href = '/bi/workspaces'; }).catch(function (err) { b.removeAttribute('data-sure'); b.textContent = 'Delete workspace'; fail(err); });
    });
    load();
  }

  // -- One item ---------------------------------------------------------------------------------------------------------
  function item() {
    var id = canvas.getAttribute('data-item'), it = null, note = null;
    var path = '/items/' + encodeURIComponent(id).replace(/%3A/g, ':');
    function show(x) {
      it = x;
      document.title = it.title + ' · BI';
      q('[data-title]').textContent = it.title;
      q('[data-desc]').textContent = it.description || '';
      q('[data-meta]').textContent = [it.kind, 'in ' + it.workspaceName, 'owner ' + it.owner, it.publishedVersion ? 'published v' + it.publishedVersion + ' ' + ago(it.publishedAt) : 'not published', it.views + ' views in ' + it.viewsDays + ' days'].join(' · ');
      var badges = q('[data-badges]'); clear(badges);
      [endorsement(it), it.shipped ? badge('shipped, read-only', '', 'folder') : null, badge(it.status, 'bi-status'), it.draftChanges ? badge('draft changes not published', 'bi-status') : null,
        it.status === 'proposed' ? badge('proposed by ' + it.proposedBy, 'bi-status') : null].concat((it.tags || []).map(function (t) { return badge(t, 'bi-tagchip', 'tag'); }))
        .forEach(function (b) { if (b) { badges.appendChild(b); badges.appendChild(document.createTextNode(' ')); } });
      var star = q('[data-favourite]');
      star.setAttribute('aria-pressed', it.favourite ? 'true' : 'false'); clear(star); star.appendChild(icon(it.favourite ? 'star-fill' : 'star'));
      q('[data-body]').textContent = it.body || '(empty)';
      q('[data-body-is]').textContent = it.bodyIs === 'draft' ? 'draft' : 'published version';
      actions(); form(); versions();
      var open = q('[data-open-dataset]');
      if (open) { open.hidden = it.kind !== 'dataset'; open.href = '/bi/d/' + encodeURIComponent(it.id).replace(/%3A/g, ':'); }
      var enc = encodeURIComponent(it.id).replace(/%3A/g, ':');
      var rep = q('[data-open-report]'), des = q('[data-open-design]'), mod = q('[data-open-model]');
      if (rep) { rep.hidden = it.kind !== 'report'; rep.href = '/bi/r/' + enc; }
      var ana = q('[data-open-analyse]');
      if (ana) { ana.hidden = it.kind !== 'notebook'; ana.href = '/bi/explore?item=' + encodeURIComponent(it.id); }
      var brd = q('[data-open-board]');
      if (brd) { brd.hidden = it.kind !== 'board'; brd.href = '/bi/b/' + enc; }
      if (des) { const designable = it.kind === 'report' || it.kind === 'board'; des.hidden = !(designable && it.can && it.can.edit) && !(designable && it.shipped); des.href = (it.kind === 'board' ? '/bi/design/board/' : '/bi/design/') + enc; des.lastChild.textContent = it.shipped ? ' Open a copy in the designer' : ' Open in the designer'; }
      if (mod) { mod.hidden = !(it.kind === 'dataset' && ((it.can && it.can.edit) || it.shipped)); mod.href = '/bi/m/' + enc; }
      document.dispatchEvent(new CustomEvent('bi:item', { detail: x }));          // bi-govern.js draws history, review, impact and lineage
    }
    function act(label, run, cls) {
      var b = h('button', { type: 'button', class: 'btn-pill ' + (cls || 'btn-ghost') }, label);
      b.addEventListener('click', function () { run(b); });
      return b;
    }
    function post(sub, body, done) {
      return api('POST', path + '/' + sub, body || {}).then(function (n) { say(done || 'Done.'); show(n); return n; })
        .catch(function (e) { fail(e); document.dispatchEvent(new CustomEvent('bi:refused', { detail: { code: e.code, message: e.message, sub: sub } })); });
    }
    /** A Python item's manifest checks (the server's) and source checks (the console's): every problem, in the warnings box. */
    function checkPython() {
      fetch('/bi/python/check', { method: 'POST', headers: { 'Content-Type': 'application/json', Accept: 'application/json' }, credentials: 'same-origin', body: JSON.stringify({ yaml: it.body || '' }) })
        .then(function (r) { return r.json(); }).then(function (d) {
          var w = q('[data-warnings]'); clear(w); w.hidden = false;
          var list = d.problems || (d.code ? [{ level: 'error', code: d.code, path: '', message: d.detail }] : []);
          if (!list.length) { w.appendChild(h('p', { 'data-py-check': 'ok', text: 'The Python item checks out: the manifest is valid and the source parses.' })); }
          list.forEach(function (p) { w.appendChild(h('p', { 'data-py-check': p.level, text: p.level + ' ' + p.code + (p.path ? ' ' + p.path : '') + ': ' + p.message })); });
          if (d.tests) {                                                  // the tests the item ships with, run in the sandbox
            w.appendChild(h('p', { 'data-py-tests': d.tests.ok ? 'ok' : 'failed', text: 'Tests: ' + d.tests.passed + ' of ' + d.tests.total + ' passed' + (d.tests.failed ? ', ' + d.tests.failed + ' failed' : '') + (d.tests.errors ? ', ' + d.tests.errors + ' could not run' : '') + '.' }));
            d.tests.results.forEach(function (r) { w.appendChild(h('p', { 'data-py-test': r.status, text: (r.status === 'pass' ? 'passed' : r.status === 'fail' ? 'FAILED' : 'could not run') + ' · ' + r.name + (r.message ? ' · ' + r.message : '') })); });
          }
        }).catch(fail);
    }
    function actions() {
      var box = q('[data-actions]'), c = it.can; clear(box);
      note = h('input', { class: 'studio-in bi-note', type: 'text', placeholder: 'Note or reason (optional)', 'aria-label': 'Note or reason', maxlength: '500' });
      var any = c.propose || c.publish || c.approve || c.withdraw;
      if (any) { box.appendChild(note); }
      if (it.kind === 'python') { box.appendChild(act('Check the Python', checkPython)); }
      if (c.edit) { box.appendChild(act('Edit', function () { var f = q('[data-edit]'); f.hidden = !f.hidden; if (!f.hidden) { f.elements.title.focus(); } })); }
      if (c.propose) { box.appendChild(act('Propose for review', function () { post('propose', { note: note.value }, 'Proposed. A second person approves it.'); })); }
      if (c.publish) { box.appendChild(act('Publish', function () { post('publish', { note: note.value }, 'Published.'); }, 'btn-accent')); }
      if (c.withdraw) { box.appendChild(act('Withdraw proposal', function () { post('withdraw', null, 'Withdrawn: a draft again.'); })); }
      if (c.approve) {
        box.appendChild(act('Approve and publish', function () { post('approve', { note: note.value }, 'Approved and published.'); }, 'btn-accent'));
        box.appendChild(act('Reject', function () { post('reject', { reason: note.value }, 'Rejected: a draft again.'); }));
      }
      if (c.promote) { box.appendChild(act(it.endorsement === 'promoted' ? 'Remove promotion' : 'Promote', function () { post('endorse', { level: it.endorsement === 'promoted' ? 'none' : 'promoted' }, 'Endorsement changed.'); })); }
      if (c.certify) { box.appendChild(act(it.endorsement === 'certified' ? 'Remove certification' : 'Certify', function () { post('endorse', { level: it.endorsement === 'certified' ? 'none' : 'certified' }, 'Endorsement changed.'); })); }
      if (c.move) { box.appendChild(act('Move to workspace…', function (b) { moveTo(b); })); }
      if (c.delete) {
        box.appendChild(act('Delete', function (b) {
          if (!b.getAttribute('data-sure')) { b.setAttribute('data-sure', '1'); b.textContent = 'Confirm: delete'; return; }
          api('DELETE', path).then(function () { window.location.href = '/bi/w/' + encodeURIComponent(it.workspace); }).catch(fail);
        }, 'btn-ghost bi-danger'));
      }
    }
    function moveTo(b) {
      api('GET', '/workspaces').then(function (list) {
        var sel = h('select', { class: 'studio-select', 'aria-label': 'Move to' }, list.filter(function (w) { return !w.shipped && RANK[w.role] >= RANK.contributor && w.id !== it.workspace; })
          .map(function (w) { return h('option', { value: w.personal ? 'me' : w.id, text: w.name }); }));
        var go = act('Move', function () { post('move', { workspace: sel.value }, 'Moved.'); });
        b.replaceWith(sel); sel.after(go);
      }).catch(fail);
    }
    function form() {
      var f = q('[data-edit]');
      if (!it.can.edit) { f.hidden = true; return; }
      f.elements.title.value = it.title; f.elements.description.value = it.description || ''; f.elements.tags.value = (it.tags || []).join(', ');
      f.elements.audienceRoles.value = (it.audience.roles || []).join(', '); f.elements.audienceUsers.value = (it.audience.users || []).join(', ');
      f.elements.audiencePacks.value = (it.audience.packs || []).join(', '); f.elements.body.value = it.body || '';
    }
    q('[data-edit]').addEventListener('submit', function (e) {
      e.preventDefault();
      var f = e.target;
      api('PUT', path, { title: f.elements.title.value, description: f.elements.description.value, tags: f.elements.tags.value, audienceRoles: f.elements.audienceRoles.value,
        audienceUsers: f.elements.audienceUsers.value, audiencePacks: f.elements.audiencePacks.value, body: f.elements.body.value, message: f.elements.message.value })
        .then(function (n) { say('Draft saved.'); f.hidden = true; f.elements.message.value = ''; return api('GET', path).then(show); }).catch(fail);
    });
    function versions() {
      api('GET', path + '/versions').then(function (list) {
        var tb = q('[data-versions] tbody'); clear(tb);
        q('[data-empty-versions]').hidden = list.length > 0;
        list.forEach(function (v) {
          var view = act('View', function () { api('GET', path + '/versions/' + v.version).then(function (d) { q('[data-body]').textContent = d.body || '(empty)'; q('[data-body-is]').textContent = 'version ' + v.version; }).catch(fail); });
          var cells = [view];
          if (it.can.edit && !it.shipped) { cells.push(act('Restore as draft', function () { post('versions/' + v.version + '/restore', null, 'Version ' + v.version + ' is the draft now.'); })); }
          tb.appendChild(h('tr', { 'data-version': v.version }, h('th', { scope: 'row', text: 'v' + v.version + (v.current ? ' (current)' : '') }), h('td', { text: stamp(v.createdAt) }), h('td', { text: v.createdBy }), h('td', { text: v.note }), h('td', {}, cells)));
        });
      }).catch(function () { /* a draft nobody published has no versions to show */ });
    }
    document.addEventListener('bi:reload', function (ev) { if (ev.detail && ev.detail.id) { show(ev.detail); } else { api('GET', path).then(show).catch(fail); } });
    q('[data-favourite]').addEventListener('click', function () { api('PUT', path + '/favourite', { on: !it.favourite }).then(show).catch(fail); });
    api('POST', path + '/open').then(show).catch(function (e) { say(e.message, true); q('[data-title]').textContent = 'Not found'; });
  }

  // -- Admin: BI settings -----------------------------------------------------------------------------------------------
  function settings() {
    var POWERS = ['bi', 'bi-author', 'bi-steward', 'bi-admin'];
    function load() {
      api('GET', '/settings').then(function (s) {
        q('[data-policy]').elements.workspaceCreation.value = s.workspaceCreation;
        q('[data-facts]').textContent = 'Four-eyes on reviews: ' + (s.fourEyes ? 'on' : 'off') + '. BI folder: ' + s.dir + ' (' + s.shippedItems + ' shipped item' + (s.shippedItems === 1 ? '' : 's') + '), watching: ' + s.watch + '.';
        var pr = q('[data-problems]'); clear(pr); pr.hidden = !s.folderProblems.length;
        s.folderProblems.forEach(function (x) { pr.appendChild(h('div', {}, h('b', { class: 'mono', text: x.file }), ': ' + x.message + (x.keptLastGood ? ' (the last good version is kept)' : ''))); });
        var tb = q('[data-roles] tbody'); clear(tb);
        s.roles.forEach(function (r) {
          var boxes = POWERS.map(function (p) { return h('td', {}, h('label', {}, h('input', { type: 'checkbox', 'data-power': p, 'aria-label': r.name + ' ' + p, checked: r.bi.indexOf(p) >= 0, disabled: r.builtIn }))); });
          var save = h('button', { type: 'button', class: 'btn-pill btn-ghost', disabled: r.builtIn }, r.builtIn ? 'built in' : 'Save');
          save.addEventListener('click', function () {
            var on = qa('[data-power]', tr).filter(function (b) { return b.checked; }).map(function (b) { return b.getAttribute('data-power'); });
            api('PUT', '/bi/api-roles/' + encodeURIComponent(r.name), { bi: on }).then(function () { say('Saved the BI powers of ' + r.name + '.'); }).catch(fail);
          });
          var tr = h('tr', { 'data-role': r.name }, h('th', { scope: 'row', text: r.name }), boxes, h('td', {}, save));
          tb.appendChild(tr);
        });
      }).catch(fail);
    }
    q('[data-policy]').addEventListener('submit', function (e) {
      e.preventDefault();
      api('PUT', '/settings', { workspaceCreation: e.target.elements.workspaceCreation.value }).then(function () { say('Saved: shared workspaces are created by ' + e.target.elements.workspaceCreation.value + '.'); }).catch(fail);
    });
    load();
  }

  // -- Keys -------------------------------------------------------------------------------------------------------------
  var pendingG = 0;
  window.addEventListener('keydown', function (e) {
    if (e.ctrlKey || e.metaKey || e.altKey) { return; }
    var t = e.target, tag = (t.tagName || '').toLowerCase();
    if (tag === 'input' || tag === 'textarea' || tag === 'select' || t.isContentEditable) { return; }
    var keys = document.getElementById('biKeys');
    if (e.key === '/') {
      e.preventDefault(); e.stopImmediatePropagation();
      var s = q('[data-search]');
      if (s) { s.focus(); } else { window.location.href = '/bi/catalogue?focus=1'; }
    } else if (e.key === '?' && keys) {
      e.preventDefault(); if (!keys.open) { keys.showModal(); }
    } else if (pendingG && Date.now() - pendingG < 1500) {
      var to = { h: '/bi', c: '/bi/catalogue', w: '/bi/w/me' }[e.key];
      pendingG = 0;
      if (to) { e.preventDefault(); window.location.href = to; }
    } else if (e.key === 'g') {
      pendingG = Date.now();
    }
  }, true);
  var keysBtn = document.querySelector('[data-bi-keys]');
  if (keysBtn) { keysBtn.addEventListener('click', function () { document.getElementById('biKeys').showModal(); }); }

  // -- start ------------------------------------------------------------------------------------------------------------
  api('GET', '/me').then(function (m) {
    me = m;
    ({ home: home, catalogue: catalogue, workspaces: workspaces, workspace: workspace, item: item, settings: settings }[page] || function () {})();
  }).catch(fail);
})();
