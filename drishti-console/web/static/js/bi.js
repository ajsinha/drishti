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
  /** A message beside the control that caused it (bi-lib.js draws it, in a live region, and scrolls it into view); the top line when that script is absent. */
  function near(anchor, text, bad) { var B = window.drishtiBi; if (B && B.near && anchor) { B.near(anchor, text, bad); } else { say(text, bad); } }
  function busy(btn, fn) { var B = window.drishtiBi; return B && B.busy ? B.busy(btn, fn) : fn(); }

  function ago(iso) {
    if (!iso) { return ''; }
    var s = Math.max(0, (Date.now() - new Date(iso).getTime()) / 1000);
    if (s < 90) { return 'just now'; }
    if (s < 5400) { return Math.round(s / 60) + ' min ago'; }
    if (s < 129600) { return Math.round(s / 3600) + ' h ago'; }
    return Math.round(s / 86400) + ' days ago';
  }
  function shortId(id) { return String(id).replace(/^bi-/, '').slice(0, 8); }
  function defaultTitle(kind) {
    var d = new Date(), z = function (n) { return (n < 10 ? '0' : '') + n; };
    return kind.charAt(0).toUpperCase() + kind.slice(1) + ' ' + d.getFullYear() + '-' + z(d.getMonth() + 1) + '-' + z(d.getDate()) + ' ' + z(d.getHours()) + ':' + z(d.getMinutes());
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
      api('PUT', '/items/' + encodeURIComponent(it.id) + '/favourite', { on: !it.favourite }).then(function (n) { it.favourite = n.favourite; if (onChange) { onChange(); } else { fav.setAttribute('aria-pressed', it.favourite ? 'true' : 'false'); clear(fav); fav.appendChild(icon(it.favourite ? 'star-fill' : 'star')); near(fav, it.favourite ? 'Added to your favourites.' : 'Removed from your favourites.', false); } }).catch(function (e) { near(fav, e.message, true); });
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
      var go = form.querySelector('[type=submit]');
      busy(go, function () {
        return api('POST', '/workspaces', { name: form.elements.name.value, description: form.elements.description.value, reviewRequired: form.elements.reviewRequired.checked })
          .then(function (w) { near(go, 'Created ' + w.name + '.', false); form.reset(); load(); }).catch(function (err) { near(go, err.message, true); });
      });
    });
    load();
  }

  /** What depends on an item, as a line for a delete confirmation: how many items and which (the server counts those the person may read). */
  function impactOf(id, alsoGoing, published) {
    var lose = published ? ' Readers lose it: it is published (version ' + published + ').' : '';
    return api('GET', '/items/' + encodeURIComponent(id).replace(/%3A/g, ':') + '/dependants').then(function (d) {
      var left = (d.dependants || []).filter(function (x) { return !alsoGoing || alsoGoing.indexOf(x.id) < 0; });          // an item deleted in the same selection does not count: it goes too
      if (!left.length) { return h('small', { class: 'text-muted-d', 'data-impact': '0', text: ' · nothing else depends on it.' + lose }); }
      var names = left.slice(0, 5).map(function (x) { return x.title + ' (' + x.kind + ')'; }).join(', ');
      return h('strong', { class: 'bi-impact-count', 'data-impact': String(left.length), text: ' · ' + left.length + (left.length === 1 ? ' item depends' : ' items depend') + ' on it: ' + names + (left.length > 5 ? ', and ' + (left.length - 5) + ' more' : '') + '.' + lose });
    }).catch(function () { return document.createTextNode(''); });
  }

  function workspace() {
    var id = canvas.getAttribute('data-workspace'), ws = null;
    var tbody = q('[data-items] tbody'), rows = {};
    function picks() { return Array.prototype.slice.call(tbody.querySelectorAll('[data-pick]')); }
    function syncBulk() {
      var all = picks(), on = all.filter(function (c) { return c.checked; }), b = q('[data-delete-selected]');
      q('[data-bulk]').hidden = all.length === 0;
      b.disabled = on.length === 0; b.textContent = 'Delete selected (' + on.length + ')';
      var sa = q('[data-select-all]'); sa.checked = all.length > 0 && on.length === all.length; sa.disabled = all.length === 0;
    }
    function askDelete(list) {
      var box = q('[data-confirm]'), ul = q('[data-confirm-list]'); clear(ul);
      q('[data-confirm-head]').textContent = list.length === 1 ? 'Delete this item?' : 'Delete these ' + list.length + ' items?';
      list.forEach(function (it) {
        var li = h('li', { 'data-confirm-item': it.id }, it.title + ' (' + it.kind + ', ' + shortId(it.id) + ')');
        ul.appendChild(li);
        impactOf(it.id, list.map(function (x) { return x.id; }), it.publishedVersion).then(function (t) { li.appendChild(t); });
      });
      box.hidden = false; box._list = list; q('[data-confirm-no]').focus();
    }
    function runDelete(list) {
      var done = [], skipped = [];
      return list.reduce(function (p, it) {
        return p.then(function () { return api('DELETE', '/items/' + encodeURIComponent(it.id)).then(function () { done.push(it.title); }, function (e) { skipped.push(it.title + ': ' + e.message); }); });
      }, Promise.resolve()).then(function () {
        var told = ((done.length ? 'Deleted ' + done.length + (done.length === 1 ? ' item' : ' items') + ': ' + done.join(', ') + '. ' : '') + (skipped.length ? 'Skipped ' + skipped.length + ' - ' + skipped.join(' | ') : ''));
        say(told, skipped.length > 0);
        near(q('[data-delete-selected]'), told, skipped.length > 0);
        load();
      });
    }
    q('[data-select-all]').addEventListener('change', function (e) { picks().forEach(function (c) { c.checked = e.target.checked; }); syncBulk(); });
    q('[data-delete-selected]').addEventListener('click', function () { askDelete(picks().filter(function (c) { return c.checked; }).map(function (c) { return rows[c.getAttribute('data-pick')]; })); });
    q('[data-confirm-no]').addEventListener('click', function () { q('[data-confirm]').hidden = true; });
    q('[data-confirm-yes]').addEventListener('click', function () { var box = q('[data-confirm]'); box.hidden = true; runDelete(box._list || []); });
    function load() {
      Promise.all([api('GET', '/workspaces/' + encodeURIComponent(id)), api('GET', '/workspaces/' + encodeURIComponent(id) + '/items')]).then(function (r) {
        ws = r[0];
        q('[data-ws-name]').textContent = ws.name;
        q('[data-ws-desc]').textContent = ws.description || '';
        q('[data-ws-meta]').textContent = (ws.personal ? 'Personal workspace, private to you' : ws.shipped ? 'Read-only: the BI folder' : 'Your role: ' + ws.role) + (ws.reviewRequired ? ' · review before publishing' : '');
        document.title = ws.name + ' · BI';
        var rank = RANK[ws.role], authors = (me.powers || []).indexOf('bi-author') >= 0;
        q('[data-new-item]').hidden = !(authors && rank >= RANK.contributor && !ws.shipped);
        q('[data-need-author]').hidden = !(!authors && rank >= RANK.contributor && !ws.shipped);
        clear(tbody);
        r[1].forEach(function (it) {
          var may = !!(it.can && it.can.delete);
          var pick = may ? h('input', { type: 'checkbox', 'data-pick': it.id, 'aria-label': 'Select ' + it.title }) : null;
          var del = may ? h('button', { type: 'button', class: 'btn-pill btn-ghost bi-danger', 'data-row-delete': it.id, 'aria-label': 'Delete ' + it.title }, 'Delete') : null;
          if (pick) { pick.addEventListener('change', syncBulk); }
          if (del) { del.addEventListener('click', function () { askDelete([it]); }); }
          tbody.appendChild(h('tr', { 'data-id': it.id }, h('td', { class: 'bi-pick' }, pick || ''), h('th', { scope: 'row' }, h('a', { href: '/bi/i/' + encodeURIComponent(it.id).replace(/%3A/g, ':'), text: it.title })),
            h('td', { class: 'bi-shortid', title: it.id, text: shortId(it.id) }), h('td', { text: it.kind }), h('td', { text: it.status }), h('td', { text: it.publishedVersion ? 'v' + it.publishedVersion : '' }),
            h('td', {}, endorsement(it) || ''), h('td', { text: stamp(it.createdAt).slice(0, 16) }), h('td', { text: ago(it.updatedAt) }), h('td', {}, del || '')));
          rows[it.id] = it;
        });
        syncBulk();
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
        var self = m.username === me.user;
        del.addEventListener('click', function () {
          if (!del.getAttribute('data-sure')) {
            del.setAttribute('data-sure', '1'); del.textContent = self ? 'Confirm: remove me' : 'Confirm: remove';
            near(del, self ? 'This removes you from ' + ws.name + ': you lose access to it' + (ws.role === 'admin' ? ' and may leave it with no administrator' : '') + '. Click again to confirm.' : 'Click again to remove ' + m.username + '.', true);
            return;
          }
          busy(del, function () { return api('DELETE', '/workspaces/' + encodeURIComponent(ws.id) + '/members/' + encodeURIComponent(m.username)).then(function () { say('Removed ' + m.username + '.'); if (self) { window.location.href = '/bi/workspaces'; } else { load(); } }).catch(function (err) { near(del, err.message, true); }); });
        });
        tb.appendChild(h('tr', { 'data-user': m.username }, h('th', { scope: 'row', text: m.username }), h('td', { text: m.role }), h('td', { text: m.addedBy }), h('td', {}, admin ? del : '')));
      });
      q('[data-add-member]').hidden = !admin;
      q('[data-review]').checked = !!ws.reviewRequired;
    }
    // What each kind needs before it is useful: a report needs a dataset (then opens in the designer), a board opens the board designer; datasets, notebooks and
    // Python items start where they are authored (the Datasets page and Analyse), so this form sends you there rather than create an empty item.
    var START = {
      report: { hint: 'A report draws one dataset: choose it, and the report opens in the designer.', go: 'Create and open in the designer' },
      board: { hint: 'A board is laid out in the board designer: it opens there.', go: 'Create and open in the board designer' },
      dataset: { hint: 'A dataset is built in the wizard: pick a source, tick columns, add measures. It opens there, and is saved as a draft in this workspace.', go: 'Open the dataset wizard', href: '/bi/datasets/new?workspace=' },
      notebook: { hint: 'A notebook is started in Analyse (SQL and Python cells); Analyse saves it here with Save as a notebook.', go: 'Go to Analyse', href: '/bi/explore' },
      python: { hint: 'Choose what the Python item makes. It is created with a valid, commented starter body and opens in its editor, ready to change and Check.', go: 'Create and open the editor' }
    };
    function kindChanged() {
      var f = q('[data-new-item]'), k = f.elements.kind.value, s = START[k];
      qa('[data-dataset-row]').forEach(function (r) { r.hidden = k !== 'report'; });
      q('[data-python-row]').hidden = k !== 'python';
      q('[data-kind-hint]').textContent = s.hint;
      q('[data-create]').textContent = s.go;
      f.elements.title.closest('label').hidden = !!s.href; f.elements.description.closest('label').hidden = !!s.href;
    }
    q('[data-new-item]').elements.kind.addEventListener('change', kindChanged);
    kindChanged();
    var fillPickers = function () { var B = window.drishtiBi; if (B && B.fillDatasetPickers) { B.fillDatasetPickers(q('[data-new-dataset]'), q('[data-new-also]')).catch(function () {}); } else { setTimeout(fillPickers, 50); } };
    fillPickers();
    q('[data-new-item]').addEventListener('submit', function (e) {
      e.preventDefault();
      var f = e.target, kind = f.elements.kind.value, st = START[kind];
      if (st.href) { window.location.href = st.href + (kind === 'dataset' ? encodeURIComponent(id) : ''); return; }
      var title = f.elements.title.value.trim() || defaultTitle(kind), body, to, also = [];
      var name = title.toLowerCase().replace(/[^a-z0-9]+/g, '-').replace(/^-+|-+$/g, '') || kind;
      if (kind === 'report') {
        if (!f.elements.dataset.value) { say('Choose a main dataset: a report draws one.', true); f.elements.dataset.focus(); return; }
        also = Array.prototype.filter.call(f.elements.also.selectedOptions, function (o) { return o.value !== f.elements.dataset.value; }).map(function (o) { return o.value.replace(/^file:dataset:/, ''); });
        body = 'rachana: 1\nreport: ' + name + '\ntitle: ' + JSON.stringify(title) + '\ndataset: ' + f.elements.dataset.value + '\npages:\n  - id: page1\n    title: Page 1\n    visuals: []\n';
        to = '/bi/design/';
      } else if (kind === 'python') {
        var py = window.drishtiBi && window.drishtiBi.pythonStarter;
        if (!py) { say('The starter is not loaded: reload the page.', true); return; }
        body = py(f.elements.pyinterface.value, name, title, f.elements.pyinputs.value);
        to = '/bi/i/';
      } else {
        body = 'rachana: 1\nboard: ' + name + '\ntitle: ' + JSON.stringify(title) + '\npages:\n  - id: page1\n    title: Page 1\n    tiles: []\n';
        to = '/bi/design/board/';
      }
      api('POST', '/workspaces/' + encodeURIComponent(id) + '/items', { kind: kind, title: title, description: f.elements.description.value, body: body })
        .then(function (it) { window.location.href = to + encodeURIComponent(it.id).replace(/%3A/g, ':') + (kind === 'python' ? '?edit=1' : also.length ? '?also=' + encodeURIComponent(also.join(',')) : ''); }).catch(fail);
    });
    q('[data-add-member]').addEventListener('submit', function (e) {
      e.preventDefault();
      var f = e.target, go = f.querySelector('[type=submit]');
      api('PUT', '/workspaces/' + encodeURIComponent(ws.id) + '/members/' + encodeURIComponent(f.elements.user.value.trim()), { role: f.elements.role.value })
        .then(function () { var told = 'Saved ' + f.elements.user.value + ' as ' + f.elements.role.value + '.'; say(told); near(go, told, false); f.reset(); load(); }).catch(function (err) { near(go, err.message, true); });
    });
    q('[data-review]').addEventListener('change', function (e) {
      api('PUT', '/workspaces/' + encodeURIComponent(ws.id), { reviewRequired: e.target.checked }).then(function () { say('Review before publishing is ' + (e.target.checked ? 'on' : 'off') + '.'); near(e.target, 'Review before publishing is ' + (e.target.checked ? 'on' : 'off') + '.', false); load(); }).catch(function (err) { e.target.checked = !e.target.checked; near(e.target, err.message, true); });
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
      q('[data-body-is]').textContent = it.bodyIs !== 'draft' ? 'published version' : it.status === 'proposed' ? 'draft, proposed for review (frozen)' : it.status === 'published' ? 'draft, the same as the published version' : it.draftChanges ? 'draft, with changes not yet published' : 'draft';
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
      var wiz = q('[data-open-wizard]');
      if (wiz) { wiz.hidden = !(it.kind === 'dataset' && it.can && it.can.edit && it.status !== 'proposed'); wiz.href = '/bi/datasets/new?edit=' + encodeURIComponent(it.id).replace(/%3A/g, ':'); }
      if (mod) { mod.hidden = !(it.kind === 'dataset' && ((it.can && it.can.edit) || it.shipped)); mod.href = '/bi/m/' + enc; }
      document.dispatchEvent(new CustomEvent('bi:item', { detail: x }));          // bi-govern.js draws history, review, impact and lineage
    }
    function act(label, run, cls) {
      var b = h('button', { type: 'button', class: 'btn-pill ' + (cls || 'btn-ghost') }, label);
      b.addEventListener('click', function () { run(b); });
      return b;
    }
    function post(sub, body, done, anchor) {
      var box = q('[data-actions]'), at = anchor ? Array.prototype.indexOf.call(qa('.btn-pill', box), anchor) : -1;
      return api('POST', path + '/' + sub, body || {}).then(function (n) {
        say(done || 'Done.'); show(n);
        var now = qa('.btn-pill', q('[data-actions]'));                              // the actions were drawn again: the message goes beside the button now in the same place
        if (now.length) { near(now[Math.max(0, Math.min(at < 0 ? 0 : at, now.length - 1))], done || 'Done.', false); }
        return n;
      }).catch(function (e) { fail(e); if (anchor && anchor.isConnected) { near(anchor, e.message, true); } document.dispatchEvent(new CustomEvent('bi:refused', { detail: { code: e.code, message: e.message, sub: sub } })); });
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
      if (c.edit && it.status !== 'proposed') { box.appendChild(act('Edit', function () { var f = q('[data-edit]'); f.hidden = !f.hidden; if (!f.hidden) { f.elements.title.focus(); } })); }
      if (c.propose) { box.appendChild(act('Propose for review', function (b) { post('propose', { note: note.value }, 'Proposed. A second person approves it.', b); })); }
      if (c.publish) { box.appendChild(act('Publish', function (b) { post('publish', { note: note.value }, 'Published.', b); }, 'btn-accent')); }
      if (it.status === 'proposed') {
        box.appendChild(h('p', { class: 'bi-approvers', 'data-approvers': '' }, h('strong', { text: 'Waiting for review. ' }), 'Frozen until decided or withdrawn. Who can approve: ' + (it.approveRule || 'a reviewer') + (it.approvers && it.approvers.length ? '. Told by notification: ' + it.approvers.join(', ') : '. Nobody is eligible yet: ask a steward, or give a member of the workspace a role with the approve power') + '.'));
      }
      if (c.withdraw) { box.appendChild(act('Withdraw proposal', function (b) { post('withdraw', null, 'Withdrawn: a draft again.', b); })); }
      if (c.approve) {
        box.appendChild(act('Approve and publish', function (b) { post('approve', { note: note.value }, 'Approved and published.', b); }, 'btn-accent'));
        box.appendChild(act('Reject', function (b) { post('reject', { reason: note.value }, 'Rejected: a draft again.', b); }));
      }
      if (c.promote && it.endorsement !== 'certified') { box.appendChild(act(it.endorsement === 'promoted' ? 'Remove promotion' : 'Promote', function (b) { post('endorse', { level: it.endorsement === 'promoted' ? 'none' : 'promoted' }, it.endorsement === 'promoted' ? 'Promotion removed.' : 'Promoted: it is listed first for others.', b); })); }
      if (c.certify) { box.appendChild(act(it.endorsement === 'certified' ? 'Remove certification' : 'Certify', function (b) { post('endorse', { level: it.endorsement === 'certified' ? 'none' : 'certified' }, it.endorsement === 'certified' ? 'Certification removed.' : 'Certified.', b); })); }
      if (c.move) { box.appendChild(act('Move to workspace…', function (b) { moveTo(b); })); }
      box.appendChild(act('Duplicate…', function (b) { duplicate(b); }));
      if (c.delete) {
        box.appendChild(act('Delete', function (b) {
          if (!b.getAttribute('data-sure')) {
            b.setAttribute('data-sure', '1'); b.textContent = 'Confirm: delete';
            impactOf(it.id, null, it.publishedVersion).then(function (t) { var w = h('span', { class: 'bi-delete-impact', 'data-delete-impact': '' }, t); b.after(w); });
            return;
          }
          api('DELETE', path).then(function () { window.location.href = '/bi/w/' + encodeURIComponent(it.workspace); }).catch(function (e) { near(b, e.message, true); });
        }, 'btn-ghost bi-danger'));
      }
    }
    /** A copy of this item in a workspace the person chooses (they need to be a contributor there): same kind, body and description, "(copy)" on the title, a draft. */
    function closePanels() { qa('[data-ask-workspace], [data-move-ui]').forEach(function (n) { n.remove(); }); }
    function duplicate(b) {
      var B = window.drishtiBi;
      closePanels();
      if (!B || !B.askWorkspace) { say('Reload the page to duplicate.', true); return; }
      B.askWorkspace(b, 'Duplicate here', 'Duplicate into').then(function (to) {
        return api('POST', '/workspaces/' + encodeURIComponent(to) + '/items', { kind: it.kind, title: it.title + ' (copy)', description: it.description || '', tags: (it.tags || []).join(', '), body: it.shipped && B.copyBody ? B.copyBody(it.body || '') : it.body || '' });
      }).then(function (n) { window.location.href = '/bi/i/' + encodeURIComponent(n.id).replace(/%3A/g, ':'); }).catch(function (e) { if (!e.cancelled) { near(b, e.message, true); } });
    }
    function moveTo(b) {
      closePanels();
      api('GET', '/workspaces').then(function (list) {
        var targets = list.filter(function (w) { return !w.shipped && RANK[w.role] >= RANK.contributor && w.id !== it.workspace; });
        var sel = h('select', { class: 'studio-select', 'aria-label': 'Move to' }, targets.map(function (w) { return h('option', { value: w.personal ? 'me' : w.id, text: w.name + (w.personal ? ' (private)' : '') }); }));
        var go = act('Move', function (g) {
          busy(g, function () {
            return api('POST', path + '/move', { workspace: sel.value }).then(function (n) { say('Moved to ' + n.workspaceName + '.'); show(n); near(q('[data-actions] .btn-pill'), 'Moved to ' + n.workspaceName + '.', false); }).catch(function (err) { near(g, err.message, true); });
          });
        });
        var no = act('Cancel', function () { closePanels(); });
        var ui = h('span', { class: 'bi-ask', 'data-move-ui': '', role: 'group', 'aria-label': 'Move to a workspace' }, targets.length ? [sel, go, no] : [h('small', { text: 'You are a contributor in no other workspace. ' }), no]);
        b.after(ui);
        if (targets.length) { sel.focus(); }
      }).catch(fail);
    }
    var opened = false;
    function form() {
      var f = q('[data-edit]');
      if (!it.can.edit) { f.hidden = true; return; }
      f.elements.title.value = it.title; f.elements.description.value = it.description || ''; f.elements.tags.value = (it.tags || []).join(', ');
      f.elements.audienceRoles.value = (it.audience.roles || []).join(', '); f.elements.audienceUsers.value = (it.audience.users || []).join(', ');
      f.elements.audiencePacks.value = (it.audience.packs || []).join(', '); f.elements.body.value = it.body || '';
      if (!opened && /[?&]edit=1/.test(window.location.search)) { opened = true; f.hidden = false; f.elements.body.focus(); }
    }
    q('[data-edit]').addEventListener('submit', function (e) {
      e.preventDefault();
      var f = e.target;
      var B = window.drishtiBi, go = f.querySelector('[type=submit]');
      var payload = { title: f.elements.title.value, description: f.elements.description.value, tags: f.elements.tags.value, audienceRoles: f.elements.audienceRoles.value,
        audienceUsers: f.elements.audienceUsers.value, audiencePacks: f.elements.audiencePacks.value, body: f.elements.body.value, message: f.elements.message.value };
      var copy = function () { return B.askWorkspace(go, 'Save mine as a copy', 'Save a copy into').then(function (to) { return api('POST', '/workspaces/' + encodeURIComponent(to) + '/items', { kind: it.kind, title: payload.title + ' (copy)', description: payload.description, tags: payload.tags, body: payload.body }); }).then(function (n) { window.location.href = '/bi/i/' + encodeURIComponent(n.id).replace(/%3A/g, ':'); }); };
      var save = B && B.saveItem ? function () { return B.saveItem(id, payload, it.etag, go, copy); } : function () { return api('PUT', path, payload); };
      busy(go, function () {
        return save().then(function () { say('Draft saved.'); f.hidden = true; f.elements.message.value = ''; return api('GET', path).then(show); }).catch(function (err) { if (!err.cancelled) { near(go, err.message, true); } });
      });
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
