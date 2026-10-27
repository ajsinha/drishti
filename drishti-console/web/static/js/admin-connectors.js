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
/* Admin → Connectors. The list is server-rendered; this filters it, and runs the editor: a form generated from the chosen plugin's settings
   (secrets accept ${ENV_VAR} or file: references only), a YAML tab, a Test tab (a throwaway instance on the draft), and History (diff and restore).
   The form and the YAML tab edit one draft: leaving a tab converts it through the server (render / parse), so what you see is what is saved.
   Every call is same-origin JSON to /admin/connectors/api/...; a connector's version travels as If-Match; text from the server is only ever put
   in the page as text. Keys: "n" new connector, "/" the filter, arrow keys move between the tabs, Esc closes a dialog. */
(function () {
  'use strict';
  var root = document.querySelector('[data-connectors]');
  if (!root) { return; }
  function $(s, from) { return (from || root).querySelector(s); }
  function $$(s, from) { return Array.prototype.slice.call((from || root).querySelectorAll(s)); }
  function el(tag, attrs, kids) {
    var e = document.createElement(tag);
    Object.keys(attrs || {}).forEach(function (k) {
      if (k === 'text') { e.textContent = attrs[k]; } else if (k === 'class') { e.className = attrs[k]; } else if (attrs[k] !== false && attrs[k] != null) { e.setAttribute(k, attrs[k] === true ? '' : attrs[k]); }
    });
    (kids || []).forEach(function (c) { if (c != null) { e.appendChild(typeof c === 'string' ? document.createTextNode(c) : c); } });
    return e;
  }
  function show(d) { if (d.showModal) { d.showModal(); } else { d.setAttribute('open', ''); } }
  function hide(d) { if (d.close) { d.close(); } else { d.removeAttribute('open'); } }
  function say(node, text, bad) { node.textContent = text; node.classList.toggle('t-bad', !!bad); node.classList.toggle('t-ok', !bad && !!text); }
  function q(s) { return encodeURIComponent(s); }
  function call(method, path, body, headers, query) {
    var o = { method: method, headers: Object.assign({}, headers || {}) };
    if (body !== undefined && body !== null) { o.headers['Content-Type'] = 'application/json'; o.body = JSON.stringify(body); }
    return fetch('/admin/connectors/api' + path + (query || ''), o).then(function (r) {
      return r.json().catch(function () { return {}; }).then(function (b) { return { ok: r.ok, status: r.status, body: b }; });
    });
  }
  function why(r) { return window.drsMessage ? window.drsMessage({ code: r.body.code || ('HTTP ' + r.status), detail: r.body.detail }, 'The request failed') : ((r.body.code ? r.body.code + ' ' : '') + (r.body.detail || 'The request failed')); }

  var msg = $('[data-msg]');
  var known = (root.getAttribute('data-kinds') || '').split(',').filter(Boolean);
  var SECRET_OK = /^(\$\{[A-Za-z_][A-Za-z0-9_]*\}|file:.+)$/;
  var SECRET_KEY = /password|passwd|secret|token|api[-_.]?key|access[-_.]?key|private[-_.]?key|credential/i;
  var GROUPS = [['connection', 'Connection'], ['tls', 'TLS'], ['proxy', 'Proxy'], ['mapping', 'What it reads'], ['tuning', 'Tuning'], ['pool', 'Connection pool'], ['s3', 'S3 storage'], ['security', 'Security'], ['general', 'General']];

  // ---- the list: filter ---------------------------------------------------------------------------------------------------
  var filter = $('[data-filter]'), none = $('[data-filter-none]'), stateSel = $('[data-state-filter]'), originSel = $('[data-origin-filter]');
  function applyFilter() {
    if (!filter) { return; }               // the detail page has the editor but no list
    var text = filter.value.trim().toLowerCase(), st = stateSel.value, org = originSel.value, shown = 0;
    $$('[data-list] tbody tr[data-conn]').forEach(function (tr) {
      var hit = (!text || tr.textContent.toLowerCase().indexOf(text) >= 0)
        && (!st || (st === 'problem' ? tr.getAttribute('data-has-problem') === 'yes' : tr.getAttribute('data-state') === st))
        && (!org || tr.getAttribute('data-origin') === org);
      tr.hidden = !hit; if (hit) { shown++; }
    });
    none.hidden = shown > 0 || !$('tr[data-conn]');
  }
  [filter, stateSel, originSel].filter(Boolean).forEach(function (n) { n.addEventListener('input', applyFilter); n.addEventListener('change', applyFilter); });

  // ---- confirm ------------------------------------------------------------------------------------------------------------
  var confirmDlg = $('[data-confirm-dialog]');
  function ask(title, text, uses, yesLabel) {
    return new Promise(function (resolve) {
      $('[data-confirm-title]').textContent = title;
      $('[data-confirm-text]').textContent = text;
      var ul = $('[data-confirm-uses]');
      ul.textContent = '';
      (uses || []).forEach(function (u) { ul.appendChild(el('li', { text: u })); });
      $('[data-confirm-yes]').textContent = yesLabel || 'Go ahead';
      var done = false;
      function finish(v) { if (done) { return; } done = true; hide(confirmDlg); resolve(v); }
      $('[data-confirm-yes]').onclick = function () { finish(true); };
      $('[data-confirm-no]').onclick = function () { finish(false); };
      confirmDlg.oncancel = function () { finish(false); };
      show(confirmDlg);
      $('[data-confirm-no]').focus();
    });
  }
  function usesOf(detail) { return (detail.usedBy || []).map(function (u) { return 'pack ' + u.pack + (u.kinds && u.kinds.length ? ' (' + u.kinds.join(', ') + ')' : ''); }); }

  // ---- row actions --------------------------------------------------------------------------------------------------------
  var onDetail = root.hasAttribute('data-detail');
  function reloadSoon(gone) { setTimeout(function () { if (gone && onDetail) { location.href = '/admin/connectors'; } else { location.reload(); } }, 600); }
  function fetchDetail(name) { return call('GET', '/' + q(name)); }

  // Feedback on the button itself: stopping a connector takes a few seconds, and the page message may be scrolled out of view.
  function busy(btn, text) {
    if (!btn) { return function () {}; }
    var was = btn.textContent;
    btn.disabled = true; btn.setAttribute('aria-busy', 'true'); btn.textContent = text;
    return function (after) { btn.disabled = false; btn.removeAttribute('aria-busy'); btn.textContent = after || was; };
  }
  function toggle(name, on, btn) {
    fetchDetail(name).then(function (d) {
      if (!d.ok) { say(msg, why(d), true); return; }
      var uses = usesOf(d.body);
      var go = (!on && uses.length) ? ask('Disable ' + name + '?', 'These packs read through it and will show it as unavailable until it is switched on again:', uses, 'Disable')
                                    : Promise.resolve(true);
      go.then(function (yes) {
        if (!yes) { return; }
        say(msg, (on ? 'Enabling ' : 'Disabling ') + name + '…');
        var done = busy(btn, on ? 'Enabling…' : 'Disabling…');
        call('POST', '/' + q(name) + '/enabled', { enabled: on }, d.body.etag ? { 'If-Match': d.body.etag } : {}, uses.length && !on ? '?confirm=true' : '').then(function (r) {
          if (!r.ok) { done(); say(msg, why(r), true); if (btn) { btn.title = why(r); } return; }
          done(on ? 'Disable' : 'Enable');
          if (btn && btn.hasAttribute('data-toggle')) { btn.setAttribute('data-toggle', on ? 'disable' : 'enable'); }
          if (btn && btn.hasAttribute('data-act')) { btn.setAttribute('data-act', on ? 'disable' : 'enable'); }
          say(msg, name + (on ? ' is on.' : ' is off.'));
          reloadSoon();
        });
      });
    });
  }
  function reset(name) {
    fetchDetail(name).then(function (d) {
      if (!d.ok) { say(msg, why(d), true); return; }
      var shared = d.body.source === 'shared' || d.body.source === 'shared-hidden';
      ask(shared ? 'Remove the shared edit of ' + name + '?' : 'Reset ' + name + ' to the default of pack ' + d.body.template + '?',
        shared ? 'Every node goes back to ' + (d.body.file || name + '.yaml') + (d.body.template ? ' (or the pack\'s default when there is no file)' : '') + '; the edit is kept in the history and can be restored.'
          : 'The file is replaced by the pack\'s suggestion; your current text is kept in the history and can be restored.', usesOf(d.body), 'Reset').then(function (yes) {
        if (!yes) { return; }
        call('POST', '/' + q(name) + '/reset', null, d.body.etag ? { 'If-Match': d.body.etag } : {}).then(function (r) {
          if (!r.ok) { say(msg, why(r), true); return; }
          say(msg, shared ? name + ': the shared edit is removed.' : name + ' is back to the pack\'s default.');
          reloadSoon();
        });
      });
    });
  }
  root.addEventListener('click', function (e) {
    var b = e.target.closest('button');
    var tr = b && b.closest('tr[data-conn]');
    if (!b || !tr) { return; }
    var name = tr.getAttribute('data-conn');
    if (b.hasAttribute('data-edit')) { openExisting(name, b); }
    else if (b.hasAttribute('data-toggle')) { toggle(name, b.getAttribute('data-toggle') === 'enable', b); }
    else if (b.hasAttribute('data-reset')) { reset(name); }
  });

  // ---- the editor ---------------------------------------------------------------------------------------------------------
  var dlg = $('[data-edit-dialog]'), tabs = $$('[data-tab]', dlg), panes = $$('[data-pane]', dlg);
  var f = { name: $('[data-f-name]'), plugin: $('[data-f-plugin]'), enabled: $('[data-f-enabled]'), description: $('[data-f-description]'), yaml: $('[data-yaml]') };
  var editMsg = $('[data-edit-msg]'), problemsList = $('[data-problems]'), groupsHost = $('[data-groups]'), chipsHost = $('[data-chips]');
  var plugins = null;                       // name -> {tls, settings:[...]}
  var draft = null;                         // {isNew, name, plugin, enabled, kinds, description, settings{}, etag, template, text}
  var tab = 'form', yamlDirty = false, opener = null;

  function loadPlugins() {
    if (plugins) { return Promise.resolve(plugins); }
    return call('GET', '/plugins').then(function (r) {
      plugins = {};
      ((r.body && r.body.plugins) || []).forEach(function (p) { plugins[p.name] = p; });
      f.plugin.textContent = '';
      Object.keys(plugins).sort().forEach(function (n) { f.plugin.appendChild(el('option', { value: n, text: n })); });
      return plugins;
    });
  }
  $('#connKinds').appendChild(document.createDocumentFragment());
  known.forEach(function (k) { $('#connKinds').appendChild(el('option', { value: k })); });

  function setTab(name, focus) {
    tab = name;
    tabs.forEach(function (t) {
      var on = t.getAttribute('data-tab') === name;
      t.setAttribute('aria-selected', on ? 'true' : 'false');
      t.tabIndex = on ? 0 : -1;
      if (on && focus) { t.focus(); }
    });
    panes.forEach(function (p) { p.hidden = p.getAttribute('data-pane') !== name; });
    // History restores a kept file by itself: there is nothing in the draft to test or save on that tab
    $('[data-test]').hidden = name === 'history';
    $('[data-save]').hidden = name === 'history';
  }
  function fieldOf(field) {
    var m = /^settings\.(.+)$/.exec(field || '');
    return m ? $$('[data-setting]', groupsHost).filter(function (n) { return n.getAttribute('data-setting') === m[1]; })[0] : null;
  }
  // the first failing field is shown, focused and scrolled into view: the form is long and the Save bar is sticky
  function focusField(n) {
    if (!n || tab !== 'form') { return; }
    n.scrollIntoView({ block: 'center' });
    n.focus({ preventScroll: true });
  }
  function showProblems(list, focusFirst) {
    problemsList.textContent = '';
    $$('[data-field-err]', groupsHost).forEach(function (e) { if (!e.getAttribute('data-secret-err')) { e.textContent = ''; } });
    $$('[aria-invalid="true"]', groupsHost).forEach(function (n) { if (!n.hasAttribute('data-secret')) { n.removeAttribute('aria-invalid'); } });
    var first = null;
    (list || []).forEach(function (p) {
      problemsList.appendChild(el('li', { class: p.level === 'error' ? 't-bad' : 't-warn' }, [el('b', { text: (p.level === 'error' ? 'Error' : 'Note') + ' ' }), el('span', { class: 'mono', text: p.field + ': ' }), p.message]));
      var n = fieldOf(p.field);
      if (n) {
        var err = $('[data-field-err]', n.closest('.conn-field'));
        if (err && !err.textContent) { err.textContent = p.message; }
        if (p.level === 'error') { n.setAttribute('aria-invalid', 'true'); if (!first) { first = n; } }
      }
    });
    if (focusFirst && first) { focusField(first); }
  }

  // draft <-> form
  function readForm() {
    var settings = {};
    $$('[data-setting]', groupsHost).forEach(function (n) {
      var v = n.value.trim();
      if (v !== '') { settings[n.getAttribute('data-setting')] = v; }
    });
    $$('[data-extra-row]', groupsHost).forEach(function (r) {
      var k = $('[data-extra-key]', r).value.trim(), v = $('[data-extra-val]', r).value.trim();
      if (k && v !== '') { settings[k] = v; }
    });
    draft.name = f.name.value.trim();
    draft.plugin = f.plugin.value;
    draft.enabled = f.enabled.checked;
    draft.description = f.description.value.trim();
    draft.settings = settings;
    return draft;
  }
  function fieldFor(spec, value) {
    var id = 'connF-' + spec.name.replace(/[^a-z0-9]/gi, '-');
    var t = spec.type || 'string', input;
    if (t === 'boolean') {
      input = el('select', { class: 'studio-in', id: id, 'data-setting': spec.name }, [el('option', { value: '', text: spec.default != null ? 'default (' + spec.default + ')' : 'default' }), el('option', { value: 'true', text: 'true' }), el('option', { value: 'false', text: 'false' })]);
    } else if (t.indexOf('enum:') === 0) {
      input = el('select', { class: 'studio-in', id: id, 'data-setting': spec.name }, [el('option', { value: '', text: spec.default != null ? 'default (' + spec.default + ')' : 'default' })].concat(t.slice(5).split('|').map(function (o) { return el('option', { value: o, text: o }); })));
    } else {
      input = el('input', { class: 'studio-in' + (t === 'path' || spec.secret ? ' mono' : ''), id: id, 'data-setting': spec.name, autocomplete: 'off', spellcheck: 'false',
        placeholder: spec.secret ? '${ENV_VAR} or file:/run/secrets/name' : (spec.default != null ? spec.default : '') });
    }
    if (spec.secret) { input.setAttribute('data-secret', ''); input.setAttribute('type', 'password'); input.setAttribute('autocomplete', 'new-password'); }
    if (spec.required) { input.setAttribute('aria-required', 'true'); }
    if (value != null) {
      if (input.tagName === 'SELECT' && !Array.prototype.some.call(input.options, function (o) { return o.value === value; })) { input.appendChild(el('option', { value: value, text: value })); }
      input.value = value;
    }
    var hint = el('span', { class: 'text-muted-d conn-hint', id: id + '-hint', text: spec.description + (spec.secret ? ' A credential is never written into the file: use ${ENV_VAR} or file:/path.' : '') });
    input.setAttribute('aria-describedby', id + '-hint');
    var err = el('span', { class: 't-bad conn-field-err', 'data-field-err': true, role: 'alert' });
    return el('div', { class: 'conn-field' + (spec.required ? ' conn-required' : '') }, [
      el('label', { for: id }, [el('span', { class: 'mono', text: spec.name }), spec.required ? el('span', { class: 'conn-req', text: ' (required)' }) : null]), input, hint, err]);
  }
  // the proxy the connector uses now (masked by the server) and where it came from, for the Proxy section and the test
  function proxyLine(px) {
    if (px === undefined || px === null) { return 'The site default (drishti.http.proxy.*) applies unless you set proxy.url here, or write proxy: none to go direct.'; }
    if (px.error) { return 'Problem with the proxy settings: ' + px.error; }
    if (!px.enabled) { return 'No proxy: this connector goes direct' + (px.source === 'disabled' ? ' (proxy: none).' : ' (no proxy is configured here or by the site).'); }
    var t = 'Effective proxy: ' + px.url + ', from the ' + px.sourceLabel + (px.username ? '; user ' + px.username + ', password ' + (px.password || 'none') : '') +
      ((px.noProxy || []).length ? '; direct for ' + px.noProxy.join(', ') : '') + '.';
    return px.note ? t + ' ' + px.note + '.' : t;
  }
  function buildForm() {
    groupsHost.textContent = '';
    var spec = plugins[draft.plugin];
    var settings = Object.assign({}, draft.settings), used = {};
    var by = {};
    ((spec && spec.settings) || []).forEach(function (s) { if (s.name.slice(-2) !== '.*') { (by[s.group] = by[s.group] || []).push(s); } });
    GROUPS.concat(Object.keys(by).filter(function (g) { return !GROUPS.some(function (x) { return x[0] === g; }); }).map(function (g) { return [g, g]; })).forEach(function (g) {
      if (!by[g[0]]) { return; }
      var fs = el('fieldset', { class: 'conn-set', 'data-group': g[0] }, [el('legend', { text: g[1] })]);
      var grid = el('div', { class: 'conn-fields' });
      if (g[0] === 'proxy') { fs.appendChild(el('p', { class: 'text-muted-d', 'data-proxy-now': true, text: proxyLine(draft.proxy) })); }
      by[g[0]].forEach(function (s) { used[s.name] = true; grid.appendChild(fieldFor(s, settings[s.name])); });
      fs.appendChild(grid);
      groupsHost.appendChild(fs);
    });
    var extra = el('fieldset', { class: 'conn-set', 'data-group': 'other' }, [el('legend', { text: 'Other settings' })]);
    var rows = el('div', { 'data-extras': true });
    Object.keys(settings).filter(function (k) { return !used[k]; }).forEach(function (k) { rows.appendChild(extraRow(k, settings[k])); });
    var add = el('button', { type: 'button', class: 'fk', text: 'Add setting' });
    add.addEventListener('click', function () { var r = extraRow('', ''); rows.appendChild(r); $('[data-extra-key]', r).focus(); });
    extra.appendChild(el('p', { class: 'text-muted-d', text: 'Settings the plugin reads that have no field above, such as layout.trade.columns or mode.trade.' }));
    extra.appendChild(rows);
    extra.appendChild(add);
    groupsHost.appendChild(extra);
  }
  function extraRow(k, v) {
    var key = el('input', { class: 'studio-in mono', 'data-extra-key': true, value: k, 'aria-label': 'Setting name', placeholder: 'setting.name', autocomplete: 'off', spellcheck: 'false' });
    var val = el('input', { class: 'studio-in mono', 'data-extra-val': true, value: v, 'aria-label': 'Setting value', autocomplete: 'off', spellcheck: 'false' });
    var rm = el('button', { type: 'button', class: 'fk', text: 'Remove', 'aria-label': 'Remove this setting' });
    var row = el('div', { class: 'conn-extra', 'data-extra-row': true }, [key, val, rm]);
    rm.addEventListener('click', function () { row.remove(); });
    return row;
  }
  function chips() {
    chipsHost.textContent = '';
    draft.kinds.forEach(function (k) {
      var rm = el('button', { type: 'button', class: 'conn-chip-x', 'aria-label': 'Remove kind ' + k, text: '×' });
      rm.addEventListener('click', function () { draft.kinds = draft.kinds.filter(function (x) { return x !== k; }); chips(); });
      var odd = known.length > 0 && known.indexOf(k) < 0;
      chipsHost.appendChild(el('span', { class: 'conn-chip mono' + (odd ? ' conn-chip-odd' : ''), title: odd ? 'No loaded pack owns this kind: it is kept, and reads nothing until a pack does' : null }, [k, odd ? el('span', { class: 't-warn', text: ' (no pack owns it)' }) : null, rm]));
    });
  }
  function addKind() {
    var inp = $('[data-kind-in]'), k = inp.value.trim();
    if (k && draft.kinds.indexOf(k) < 0) {
      draft.kinds.push(k); chips();
      if (known.length && known.indexOf(k) < 0) { say(editMsg, 'Note: no loaded pack owns the kind ' + k + '. It is kept, and reads nothing until a pack does.', false); }
    }
    inp.value = ''; inp.focus();
  }
  $('[data-kind-add]').addEventListener('click', addKind);
  $('[data-kind-in]').addEventListener('keydown', function (e) { if (e.key === 'Enter') { e.preventDefault(); addKind(); } });

  function fillForm() {
    f.name.value = draft.name; f.name.readOnly = !draft.isNew;
    f.plugin.value = draft.plugin; f.plugin.disabled = !draft.isNew;
    f.enabled.checked = draft.enabled; f.description.value = draft.description || '';
    $('[data-file-name]').textContent = (draft.name || 'name') + '.yaml';
    chips();
    buildForm();
  }
  f.name.addEventListener('input', function () { $('[data-file-name]').textContent = (f.name.value.trim() || 'name') + '.yaml'; });
  f.plugin.addEventListener('change', function () { readForm(); buildForm(); });

  // client-side guard: a credential is only a reference (the server checks too)
  function checkSecrets() {
    var bad = 0;
    $$('[data-secret]', groupsHost).forEach(function (n) {
      var holder = n.closest('.conn-field'), err = $('[data-field-err]', holder), v = n.value.trim();
      var wrong = v !== '' && !SECRET_OK.test(v);
      if (wrong) { err.setAttribute('data-secret-err', '1'); } else { err.removeAttribute('data-secret-err'); }
      err.textContent = wrong ? 'A credential is never written into a connector file. Use an environment reference such as ${' + n.getAttribute('data-setting').replace(/[^A-Za-z0-9]+/g, '_').toUpperCase() + '} or file:/run/secrets/name.' : '';
      n.setAttribute('aria-invalid', wrong ? 'true' : 'false');
      if (wrong) { bad++; if (bad === 1) { focusField(n); } }
    });
    $$('[data-extra-row]', groupsHost).forEach(function (r) {
      var k = $('[data-extra-key]', r), v = $('[data-extra-val]', r);
      if (SECRET_KEY.test(k.value) && v.value.trim() !== '' && !SECRET_OK.test(v.value.trim())) { bad++; v.setAttribute('aria-invalid', 'true'); say(editMsg, k.value + ' is a credential: use ${ENV_VAR} or file:/path, never the value itself.', true); }
    });
    return bad === 0;
  }

  // tab switching converts the draft through the server
  function toYaml() {
    readForm();
    return call('POST', '/' + q(draft.name || 'draft') + '/render', { plugin: draft.plugin, enabled: draft.enabled, kinds: draft.kinds, description: draft.description, settings: draft.settings }).then(function (r) {
      if (!r.ok) { say(editMsg, why(r), true); return false; }
      f.yaml.value = r.body.text; yamlDirty = false; return true;
    });
  }
  function fromYaml() {
    return call('POST', '/' + q(draft.name || 'draft') + '/parse', { text: f.yaml.value }).then(function (r) {
      if (!r.ok) { say(editMsg, why(r), true); return false; }
      if (!r.body.plugin) { showProblems(r.body.problems); say(editMsg, 'The YAML cannot be read yet; fix it before leaving this tab.', true); return false; }
      draft.plugin = r.body.plugin; draft.enabled = r.body.enabled; draft.kinds = r.body.kinds || []; draft.description = r.body.description || ''; draft.settings = r.body.settings || {};
      if (!plugins[draft.plugin]) { say(editMsg, 'No plugin named ' + draft.plugin + '.', true); }
      fillForm(); showProblems(r.body.problems); yamlDirty = false; return true;
    });
  }
  function go(name, focus) {
    if (name === tab) { return Promise.resolve(); }
    say(editMsg, '');
    var leaving = tab;
    var step = Promise.resolve(true);
    if (leaving === 'form' && (name === 'yaml')) { step = toYaml(); }
    else if (leaving === 'yaml' && yamlDirty && name !== 'yaml') { step = fromYaml(); }
    return step.then(function (ok) {
      if (!ok) { return; }
      setTab(name, focus);
      if (name === 'history') { loadHistory(); }
      if (name === 'yaml' && !focus) { f.yaml.focus(); }
    });
  }
  tabs.forEach(function (t) { t.addEventListener('click', function () { go(t.getAttribute('data-tab')); }); });
  $('[data-tabs]').addEventListener('keydown', function (e) {
    var order = tabs.filter(function (t) { return !t.hidden; }), i = order.indexOf(document.activeElement);
    if (i < 0) { return; }
    var next = e.key === 'ArrowRight' ? (i + 1) % order.length : e.key === 'ArrowLeft' ? (i - 1 + order.length) % order.length : e.key === 'Home' ? 0 : e.key === 'End' ? order.length - 1 : -1;
    if (next >= 0) { e.preventDefault(); go(order[next].getAttribute('data-tab'), true); }
  });
  f.yaml.addEventListener('input', function () { yamlDirty = true; });

  // a draft as the request body: the YAML as typed when that is what was edited, else the form's fields
  function bodyNow() {
    if (tab === 'yaml' || (yamlDirty && tab !== 'form')) { return { text: f.yaml.value }; }
    readForm();
    return { plugin: draft.plugin, enabled: draft.enabled, kinds: draft.kinds, description: draft.description, settings: draft.settings };
  }
  function nameNow() { return tab === 'yaml' ? (draft.name || f.name.value.trim()) : (f.name.value.trim() || draft.name); }

  function open(opts) {
    opener = opts.from || null;
    return loadPlugins().then(function () {
      draft = opts.draft;
      yamlDirty = false;
      $('[data-title]').textContent = draft.isNew ? 'New connector' : 'Connector ' + draft.name;
      $('[data-origin-note]').textContent = opts.note || '';
      tabs.filter(function (t) { return t.getAttribute('data-tab') === 'history'; }).forEach(function (t) { t.hidden = draft.isNew || !draft.hasFile; });
      showProblems(opts.problems || []);
      say(editMsg, '');
      $('[data-test-out]').textContent = '';
      $('[data-history]').textContent = '';
      $('[data-diff]').hidden = true;
      fillForm();
      if (opts.yaml != null) { f.yaml.value = opts.yaml; }
      setTab('form');
      if (!dlg.open) { show(dlg); }
      (draft.isNew ? f.name : f.description).focus();
    });
  }
  function newDraft(name) { return { isNew: true, hasFile: false, name: name || '', plugin: 'file', enabled: true, kinds: [], description: '', settings: {} }; }

  function openNew(name, from) {
    var d = newDraft(name);
    if (!name) { open({ from: from, draft: d }); return; }
    call('GET', '/' + q(name) + '/suggestion').then(function (s) {
      if (!s.ok) { open({ from: from, draft: d, note: 'Nothing defines ' + name + ' yet. Choose a plugin and fill the form.' }); return; }
      call('POST', '/' + q(name) + '/parse', { text: s.body.text }).then(function (p) {
        if (p.ok && p.body.plugin) {
          d.plugin = p.body.plugin; d.enabled = p.body.enabled; d.kinds = p.body.kinds || []; d.description = p.body.description || ''; d.settings = p.body.settings || {};
        }
        open({ from: from, draft: d, note: 'Pre-filled from the suggestion of pack ' + s.body.pack + '. Check it, then Save to create ' + name + '.yaml.' });
      });
    });
  }
  function openExisting(name, from) {
    return fetchDetail(name).then(function (r) {
      if (!r.ok) { say(msg, why(r), true); return; }
      var c = r.body;
      var d = { isNew: false, hasFile: !!c.text, name: name, plugin: c.plugin, enabled: c.enabled, kinds: c.kinds || [], description: c.description || '', settings: c.settings || {}, etag: c.etag, template: c.template, text: c.text, proxy: c.proxy };
      var note = c.origin === 'file' ? 'Defined by ' + (c.file || name + '.yaml') + '.' + (c.template ? ' Pack ' + c.template + ' suggests a default; Reset in the list puts it back.' : '')
        : c.origin === 'pack' ? 'Defined by pack ' + c.template + ' and not yet a file: saving writes ' + name + '.yaml.'
        : 'Defined in the server\'s own configuration (deprecated): saving writes ' + name + '.yaml. Credentials show as *** and must be re-entered as ${ENV_VAR} references.';
      var go2 = c.text ? call('POST', '/' + q(name) + '/parse', { text: c.text }) : Promise.resolve(null);
      return go2.then(function (p) {
        if (p && p.ok && p.body.plugin) {
          d.plugin = p.body.plugin; d.enabled = p.body.enabled; d.kinds = p.body.kinds || []; d.description = p.body.description || ''; d.settings = p.body.settings || {};
        }
        return open({ from: from, draft: d, note: note, problems: (c.problems || []).map(function (x) { return { level: 'warning', field: name, message: x }; }), yaml: c.text });
      });
    });
  }
  var newBtn = $('[data-new]');
  if (newBtn) { newBtn.addEventListener('click', function () { openNew('', this); }); }
  dlg.addEventListener('close', function () { if (opener && document.body.contains(opener)) { opener.focus(); } });
  $('[data-close]', dlg).addEventListener('click', function () { hide(dlg); });

  // ---- test ---------------------------------------------------------------------------------------------------------------
  function renderTest(t) {
    var host = $('[data-test-out]');
    host.textContent = '';
    host.appendChild(el('p', { class: t.ok ? 't-ok' : 't-bad', role: t.ok ? 'status' : 'alert',
      text: t.ok ? 'Reachable: health ' + t.health + ', ' + t.ms + ' ms.' : 'Problem: ' + (t.error || 'not reachable') }));
    if (t.hint) { host.appendChild(el('p', { class: 'conn-hint-box', text: t.hint })); }
    if (t.via) { host.appendChild(el('p', { class: 'text-muted-d', 'data-test-via': true, text: 'The test went ' + t.via + '.' })); }
    else if (t.proxy && t.proxy.enabled) { host.appendChild(el('p', { class: 'text-muted-d', 'data-test-via': true, text: 'The test went direct: ' + (t.proxy.note || 'the target is on the no-proxy list.') })); }
    (t.warnings || []).forEach(function (w) { host.appendChild(el('p', { class: 't-warn', text: 'Note: ' + w })); });
    if (!t.ok || !(t.kinds || []).length) { if (t.ok) { host.appendChild(el('p', { class: 'text-muted-d', text: 'It started and is healthy. It names no kinds to ask about, so nothing was read.' })); } return; }
    var tb = el('tbody');
    t.kinds.forEach(function (k) {
      if (!k.dates.length) { tb.appendChild(el('tr', {}, [el('td', { class: 'mono', text: k.kind }), el('td', { text: '—' }), el('td', { class: 'mono', text: '0' }), el('td', { text: k.note || '' })])); }
      k.dates.forEach(function (d) {
        tb.appendChild(el('tr', {}, [el('td', { class: 'mono', text: k.kind }), el('td', { class: 'mono', text: d.date || '(undated)' }),
          el('td', { class: 'mono ds-num', text: (k.exact ? '' : '≥ ') + Number(d.rows).toLocaleString('en-US') }), el('td', { text: k.note || '' })]));
      });
    });
    host.appendChild(el('div', { class: 'tbl-wrap' }, [el('table', { class: 'tbl adm-tbl', 'data-plain': true }, [el('caption', { class: 'visually-hidden', text: 'What the connector holds' }),
      el('thead', {}, [el('tr', {}, ['Kind', 'Business date', 'Rows', ''].map(function (h) { return el('th', { scope: 'col', text: h }); }))]), tb])]));
  }
  $('[data-test]').addEventListener('click', function () {
    if (tab === 'form' && !checkSecrets()) {
      say(editMsg, 'Not tried: a credential holds the value itself. Use a ${ENV_VAR} or file: reference, then test again.', true);
      return;
    }
    var name = nameNow() || 'draft';
    say(editMsg, 'Trying the settings on the real source…');
    go('test').then(function () {
      $('[data-test-out]').textContent = '';
      call('POST', '/' + q(name) + '/test', bodyNow()).then(function (r) {
        if (!r.ok) { say(editMsg, why(r), true); return; }
        say(editMsg, r.body.ok ? 'The connection works.' : 'There is a problem.', !r.body.ok);
        renderTest(r.body);
      });
    });
  });

  // ---- save ---------------------------------------------------------------------------------------------------------------
  function save(confirmed) {
    if (tab === 'form' && !checkSecrets()) { say(editMsg, 'Not saved: a credential holds the value itself. Use a ${ENV_VAR} or file: reference.', true); return; }
    var name = nameNow();
    if (!name) { say(editMsg, 'Give the connector a name.', true); f.name.setAttribute('aria-invalid', 'true'); setTab('form'); f.name.focus(); return; }
    f.name.removeAttribute('aria-invalid');
    var idle = busy($('[data-save]'), 'Saving…');
    say(editMsg, 'Saving…');
    var headers = draft.etag ? { 'If-Match': draft.etag } : {};
    call('PUT', '/' + q(name), bodyNow(), headers, confirmed ? '?confirm=true' : '').then(function (r) {
      if (!r.ok) {
        idle('Save');
        say(editMsg, 'Not saved: ' + why(r), true);
        if (r.status === 409 && /changed since you read it|already exists/.test(r.body.detail || '')) {
          var reload = el('button', { type: 'button', class: 'fk', text: 'Load the current version' });
          reload.addEventListener('click', function () { hide(dlg); openExisting(name, opener); });
          editMsg.appendChild(document.createTextNode(' '));
          editMsg.appendChild(reload);
        }
        if (r.status === 422) { call('POST', '/' + q(name) + '/validate', bodyNow()).then(function (v) { if (v.ok) { showProblems(v.body.problems, true); } }); }
        return;
      }
      var c = r.body, warn = (c.warnings || []);
      showProblems(warn);
      say(editMsg, name + ' is saved' + (c.state ? ' and ' + String(c.state).toLowerCase().replace('not_loaded', 'not started') : '') + (c.problems && c.problems.length ? '. ' + c.problems[0] : '.'), !!(c.problems && c.problems.length && c.state === 'FAILED'));
      draft.etag = c.etag; draft.isNew = false; draft.hasFile = true; draft.name = name;
      reloadSoon();
    });
  }
  $('[data-save]').addEventListener('click', function () { save(false); });

  // ---- history: diff and restore -------------------------------------------------------------------------------------------
  function diffLines(a, b) {
    var x = a.split('\n'), y = b.split('\n'), n = x.length, m = y.length, t = [], i, j;
    for (i = 0; i <= n; i++) { t.push(new Array(m + 1).fill(0)); }
    for (i = n - 1; i >= 0; i--) { for (j = m - 1; j >= 0; j--) { t[i][j] = x[i] === y[j] ? t[i + 1][j + 1] + 1 : Math.max(t[i + 1][j], t[i][j + 1]); } }
    var out = []; i = 0; j = 0;
    while (i < n && j < m) {
      if (x[i] === y[j]) { out.push(['  ', x[i]]); i++; j++; }
      else if (t[i + 1][j] >= t[i][j + 1]) { out.push(['- ', x[i]]); i++; }
      else { out.push(['+ ', y[j]]); j++; }
    }
    while (i < n) { out.push(['- ', x[i++]]); }
    while (j < m) { out.push(['+ ', y[j++]]); }
    return out;
  }
  function showDiff(oldText, label) {
    var pre = $('[data-diff]');
    pre.textContent = '';
    var cur = draft.text || '';
    pre.appendChild(el('span', { class: 'conn-diff-h', text: '--- ' + label + '\n+++ current file\n' }));
    diffLines(oldText, cur).forEach(function (l) {
      pre.appendChild(el('span', { class: l[0] === '+ ' ? 'conn-add' : l[0] === '- ' ? 'conn-del' : 'conn-same', text: l[0] + l[1] + '\n' }));
    });
    pre.hidden = false;
  }
  // when a version was kept, in the zone the page shows (the top bar's "known at" zone), with the zone named: 2026-10-06 05:00:00 EDT
  var zone = root.getAttribute('data-zone') || '';
  function savedAt(h) {
    var m = /^(\d{4})(\d{2})(\d{2})T(\d{2})(\d{2})(\d{2})/.exec(h.id), when = h.at ? new Date(h.at) : (m ? new Date(Date.UTC(+m[1], m[2] - 1, +m[3], +m[4], +m[5], +m[6])) : null);
    if (!when || isNaN(when.getTime())) { return h.id; }
    try {
      var o = { year: 'numeric', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', second: '2-digit', hour12: false, timeZoneName: 'short' };
      if (zone) { o.timeZone = zone; }
      return new Intl.DateTimeFormat('sv-SE', o).format(when);
    } catch (e) { return when.toISOString().replace('T', ' ').slice(0, 19) + ' UTC'; }
  }
  function loadHistory() {
    var host = $('[data-history]');
    host.textContent = 'Reading the history…';
    call('GET', '/' + q(draft.name)).then(function (r) {
      host.textContent = '';
      if (!r.ok) { say(editMsg, why(r), true); return; }
      draft.text = r.body.text || ''; draft.etag = r.body.etag;
      var hist = r.body.history || [];
      if (!hist.length) { host.appendChild(el('p', { class: 'text-muted-d', text: 'No earlier version yet: the first change keeps the text it replaces.' })); return; }
      var tb = el('tbody');
      hist.forEach(function (h) {
        var view = el('button', { type: 'button', class: 'fk', text: 'Show changes', 'aria-label': 'Show what changed since version ' + h.id });
        var put = el('button', { type: 'button', class: 'fk', text: 'Restore', 'aria-label': 'Restore version ' + h.id });
        view.addEventListener('click', function () {
          call('GET', '/' + q(draft.name) + '/history/' + q(h.id)).then(function (v) { if (v.ok) { showDiff(v.body.text, h.id); } else { say(editMsg, why(v), true); } });
        });
        put.addEventListener('click', function () {
          ask('Restore version ' + h.id + '?', 'It becomes the connector\'s file and is applied at once; the current text is kept in the history.', [], 'Restore').then(function (yes) {
            if (!yes) { return; }
            call('POST', '/' + q(draft.name) + '/restore/' + q(h.id), null, draft.etag ? { 'If-Match': draft.etag } : {}, '?confirm=true').then(function (x) {
              if (!x.ok) { say(editMsg, why(x), true); return; }
              say(editMsg, 'Version ' + h.id + ' is back.');
              reloadSoon();
            });
          });
        });
        tb.appendChild(el('tr', {}, [el('td', { class: 'mono', text: savedAt(h) }), el('td', { class: 'mono', text: h.bytes + ' bytes' }), el('td', {}, [view, put])]));
      });
      host.appendChild(el('div', { class: 'tbl-wrap' }, [el('table', { class: 'tbl adm-tbl', 'data-plain': true }, [el('caption', { class: 'visually-hidden', text: 'Kept earlier versions, newest first' }),
        el('thead', {}, [el('tr', {}, ['Saved', 'Size', ''].map(function (h) { return el('th', { scope: 'col', text: h }); }))]), tb])]));
    });
  }

  // ---- keys ---------------------------------------------------------------------------------------------------------------
  document.addEventListener('keydown', function (e) {
    var t = e.target, typing = t && (t.tagName === 'INPUT' || t.tagName === 'TEXTAREA' || t.tagName === 'SELECT' || t.isContentEditable);
    if (typing || e.ctrlKey || e.metaKey || e.altKey || dlg.open || confirmDlg.open) { return; }
    if (e.key === 'n' && newBtn) { e.preventDefault(); openNew('', newBtn); }
    else if (e.key === '/' && filter) { e.preventDefault(); filter.focus(); }
  });

  // links from a pack: ?name=x opens it, ?new=x opens New connector pre-filled from the pack's suggestion
  var params = new URLSearchParams(location.search);
  if (params.get('name')) {
    openExisting(params.get('name'), null).then(function () {
      if (params.get('tab') === 'history') { go('history'); }
      else if (params.get('tab') === 'test') { $('[data-test]').click(); }
    });
  }
  else if (params.get('new')) { openNew(params.get('new'), null); }

  // the connector's own page (/admin/connectors/<name>): its buttons reuse the list's actions and the editor
  if (onDetail) {
    var me = root.getAttribute('data-detail');
    root.addEventListener('click', function (e) {
      var b = e.target.closest('button[data-act]');
      if (!b) { return; }
      var act = b.getAttribute('data-act');
      if (act === 'edit') { openExisting(me, b); }
      else if (act === 'history') { openExisting(me, b).then(function () { go('history'); }); }
      else if (act === 'test') { openExisting(me, b).then(function () { $('[data-test]').click(); }); }
      else if (act === 'enable' || act === 'disable') { toggle(me, act === 'enable', b); }
      else if (act === 'reset') { reset(me); }
      else if (act === 'var-apply-now') {               // a var-pv-stream connector: its waiting window applied now (VAR_GUIDE.md 8.4)
        b.disabled = true;
        fetch('/api/var/intraday/streams/' + q(me) + '/apply', { method: 'POST' }).then(function (r) {
          return r.json().catch(function () { return {}; }).then(function (x) { return { ok: r.ok, status: r.status, body: x }; });
        }).then(function (r) {
          b.disabled = false;
          if (!r.ok) { say(msg, why(r), true); return; }
          say(msg, r.body.status === 'applied' ? 'Applied ' + r.body.rows + ' intraday update' + (r.body.rows === 1 ? '' : 's') + ' as batch ' + r.body.batchId
            : 'Nothing to apply: ' + (r.body.status || 'no window waiting'), false);
        });
      }
    });
  }
})();
