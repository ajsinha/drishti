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
/* Admin → Packs: Deploy archive (upload, the server's checks, the preview of what changes, confirm), History and roll back, and a pack's
   Data source (view, edit, test, reset). Everything goes through same-origin /admin/api/packs/...; the server enforces the admin role,
   validates and audits. Text from the server is only ever put in the page as text, never as HTML. */
(function () {
  'use strict';
  var root = document.querySelector('[data-pack-admin]');
  if (!root) { return; }

  function el(tag, attrs, kids) {
    var e = document.createElement(tag);
    Object.keys(attrs || {}).forEach(function (k) {
      if (k === 'text') { e.textContent = attrs[k]; } else if (k === 'class') { e.className = attrs[k]; } else if (attrs[k] !== false && attrs[k] != null) { e.setAttribute(k, attrs[k] === true ? '' : attrs[k]); }
    });
    (kids || []).forEach(function (c) { if (c != null) { e.appendChild(typeof c === 'string' ? document.createTextNode(c) : c); } });
    return e;
  }
  function call(method, url, body, init) {
    var o = { method: method, headers: {} };
    if (body !== undefined && body !== null) { o.headers['Content-Type'] = 'application/json'; o.body = JSON.stringify(body); }
    if (init) { Object.keys(init.headers || {}).forEach(function (k) { o.headers[k] = init.headers[k]; }); if (init.rawBody) { o.body = init.rawBody; } }
    return fetch(url, o).then(function (r) { return r.json().catch(function () { return {}; }).then(function (b) { return { ok: r.ok, status: r.status, body: b }; }); });
  }
  function why(r) { return window.drsMessage ? window.drsMessage({ code: r.body.code || ('HTTP ' + r.status), detail: r.body.detail }, 'The request failed') : (r.body.detail || 'The request failed'); }
  function say(node, text, bad) { node.textContent = text; node.classList.toggle('t-bad', !!bad); node.classList.toggle('t-ok', !bad && !!text); }
  function q(s) { return encodeURIComponent(s); }

  // After a change that restarts the server in place: wait for it to go and come back, then show the new state.
  function afterChange(msgNode, body) {
    say(msgNode, body.note || 'Done.', false);
    if (!body.restarting) { setTimeout(function () { location.reload(); }, 700); return; }
    var seenDown = false, started = Date.now();
    (function poll() {
      fetch('/readyz', { cache: 'no-store' }).then(function (r) { return r.json(); }).then(function (s) {
        if (s.status !== 'UP') { seenDown = true; }
        if (s.status === 'UP' && (seenDown || Date.now() - started > 15000)) { location.reload(); return; }
        msgNode.textContent = 'Restarting the server… ' + Math.round((Date.now() - started) / 1000) + ' s';
        setTimeout(poll, 1000);
      }).catch(function () { seenDown = true; setTimeout(poll, 1000); });
    })();
  }

  // ---------------------------------------------------------------------------------------------------------------- deploy
  var dep = root.querySelector('[data-deploy]');
  if (dep) {
    var file = dep.querySelector('[data-file]'), up = dep.querySelector('[data-upload]'), msg = dep.querySelector('[data-dep-msg]');
    var out = dep.querySelector('[data-result]'), drop = dep.querySelector('[data-drop]');
    var maxMb = parseFloat(dep.getAttribute('data-max-mb')) || 50;
    function chosen() { return file.files && file.files[0]; }
    file.addEventListener('change', function () { up.disabled = !chosen(); out.hidden = true; say(msg, ''); });
    ['dragenter', 'dragover'].forEach(function (ev) { drop.addEventListener(ev, function (e) { e.preventDefault(); drop.classList.add('dep-over'); }); });
    ['dragleave', 'drop'].forEach(function (ev) { drop.addEventListener(ev, function (e) { e.preventDefault(); drop.classList.remove('dep-over'); }); });
    drop.addEventListener('drop', function (e) {
      var f = e.dataTransfer && e.dataTransfer.files && e.dataTransfer.files[0];
      if (!f) { return; }
      try { var dt = new DataTransfer(); dt.items.add(f); file.files = dt.files; } catch (x) { /* an old browser: choose it with the button */ }
      file.dispatchEvent(new Event('change'));
    });
    up.addEventListener('click', function () {
      var f = chosen();
      if (!f) { return; }
      if (f.size > maxMb * 1048576) { say(msg, f.name + ' is ' + (f.size / 1048576).toFixed(1) + ' MB; the limit is ' + maxMb + ' MB.', true); return; }
      var headers = { 'Content-Type': 'application/octet-stream', 'X-Drishti-Filename': f.name.replace(/[^\x20-\x7e]/g, '_') };
      var pub = dep.querySelector('[data-publisher]').value.trim(), sig = dep.querySelector('[data-signature]').value.trim();
      if (sig) { headers['X-Drishti-Signature'] = sig; headers['X-Drishti-Publisher'] = pub; }
      up.disabled = true;
      say(msg, 'Uploading and checking ' + f.name + '…');
      out.hidden = true;
      call('POST', '/admin/api/packs/deploy', null, { headers: headers, rawBody: f }).then(function (r) {
        up.disabled = false;
        if (!r.ok) { say(msg, why(r), true); return; }
        say(msg, r.body.ok ? 'Checked: ' + r.body.pack + ' ' + r.body.version + '. Nothing has changed yet.' : 'Refused: nothing was changed.', !r.body.ok);
        render(r.body);
      });
    });

    function checkList(checks) {
      var ul = el('ul', { class: 'dep-checks' });
      checks.forEach(function (c) {
        var cls = !c.ok ? 'fail' : (c.warn ? 'warn' : 'ok'), mark = !c.ok ? 'FAIL' : (c.warn ? 'NOTE' : 'OK');
        ul.appendChild(el('li', { class: 'dep-' + cls }, [el('span', { class: 'dep-mark', text: mark }), ' ', el('b', { text: c.name }), ' ', el('span', { text: c.detail })]));
      });
      return ul;
    }
    function findings(pv) {
      var wrap = el('div', { class: 'dep-find' });
      var labels = { breaking: 'Breaking: monitors, workspaces, alerts or saved links may stop working', selection: 'Screen selection: some documents get another screen',
        layout: 'Data layout changed', change: 'Changes' };
      ['breaking', 'selection', 'layout', 'change'].forEach(function (lv) {
        var rows = (pv.findings || []).filter(function (f) { return f.level === lv; });
        if (!rows.length) { return; }
        var box = el('div', { class: 'dep-lv dep-lv-' + lv }, [el('h4', { text: labels[lv] + ' (' + rows.length + ')' })]);
        if (lv === 'breaking') { box.setAttribute('role', 'alert'); }
        var t = el('table', { class: 'tbl adm-tbl' }, [el('thead', {}, [el('tr', {}, ['What', 'Name', 'Detail'].map(function (h) { return el('th', { text: h }); }))])]);
        var tb = el('tbody');
        rows.forEach(function (f) { tb.appendChild(el('tr', {}, [el('td', { text: f.what }), el('td', { class: 'mono', text: f.name }), el('td', { class: 'cmp-path', text: f.detail || '' })])); });
        t.appendChild(tb);
        box.appendChild(el('div', { class: 'tbl-wrap' }, [t]));
        wrap.appendChild(box);
      });
      return wrap;
    }
    function render(rep) {
      out.textContent = '';
      out.hidden = false;
      out.appendChild(el('h3', { text: 'Checks' }));
      out.appendChild(checkList(rep.checks || []));
      if (!rep.ok) { out.appendChild(el('p', { class: 'dep-refused t-bad', role: 'alert', text: 'Refused: nothing was changed. Fix the failed checks and upload again.' })); return; }
      var pv = rep.preview || {}, run = pv.running, counts = pv.counts || {}, breaking = counts.breaking || 0;
      out.appendChild(el('h3', { text: 'What it changes' }));
      out.appendChild(el('p', { class: 'dep-ver', text: run ? rep.pack + ': ' + run.version + ' (' + run.where + ') becomes ' + rep.version + ' (' + pv.versionOrder + ' than the running one)'
        : rep.pack + ' ' + rep.version + ' is a new pack: it is loaded when you deploy it.' }));
      if (pv.versionOrder === 'same' || pv.versionOrder === 'older') {
        out.appendChild(el('p', { class: 't-warn', text: 'This version is ' + (pv.versionOrder === 'same' ? 'the same as' : 'older than') + ' the running one. You can still deploy it.' }));
      }
      var chips = el('p', { class: 'dep-counts' });
      ['breaking', 'selection', 'layout', 'change'].forEach(function (lv) { chips.appendChild(el('span', { class: 'st ' + (lv === 'breaking' && counts[lv] ? 'st-bad' : ''), text: counts[lv] + ' ' + lv })); chips.appendChild(document.createTextNode(' ')); });
      out.appendChild(chips);
      out.appendChild(findings(pv));
      var ack = null;
      if (breaking) {
        ack = el('input', { type: 'checkbox', id: 'depAck', 'data-ack': true });
        out.appendChild(el('p', {}, [el('label', { class: 'chk', for: 'depAck' }, [ack, 'I have read the ' + breaking + ' breaking change(s) and want to deploy anyway'])]));
      }
      var go = el('button', { type: 'button', class: 'btn-pill btn-accent', 'data-deploy-go': true, text: 'Deploy ' + rep.pack + ' ' + rep.version, disabled: !!breaking });
      var no = el('button', { type: 'button', class: 'btn-pill btn-ghost', 'data-deploy-discard': true, text: 'Discard' });
      if (ack) { ack.addEventListener('change', function () { go.disabled = !ack.checked; }); }
      out.appendChild(el('div', { class: 'adm-dlg-actions dep-actions' }, [no, go]));
      no.addEventListener('click', function () {
        call('DELETE', '/admin/api/packs/deploy/' + q(rep.uploadId)).then(function () { out.hidden = true; say(msg, 'Discarded. Nothing was changed.'); file.value = ''; up.disabled = true; });
      });
      go.addEventListener('click', function () {
        if (!window.confirm('Deploy ' + rep.pack + ' ' + rep.version + '? The server restarts in place: a few seconds without data. If it cannot start with the new files, the old ones are put back.')) { return; }
        go.disabled = no.disabled = true;
        say(msg, 'Deploying ' + rep.pack + ' ' + rep.version + '…');
        call('POST', '/admin/api/packs/deploy/' + q(rep.uploadId), { acceptBreaking: !!(ack && ack.checked) }).then(function (r) {
          if (!r.ok) { say(msg, why(r), true); go.disabled = no.disabled = false; return; }
          afterChange(msg, r.body);
        });
      });
    }
  }

  // ---------------------------------------------------------------------------------------------------------------- roll back
  root.querySelectorAll('[data-rollback]').forEach(function (b) {
    b.addEventListener('click', function () {
      var row = b.closest('tr'), name = row.getAttribute('data-pack'), version = row.getAttribute('data-version');
      if (!window.confirm('Roll ' + name + ' back to ' + (version === 'shipped' ? 'the version that ships with the server' : version) + '? The server restarts in place: a few seconds without data.')) { return; }
      var m = root.querySelector('[data-msg]');
      say(m, 'Rolling ' + name + ' back…');
      call('POST', '/admin/api/packs/' + q(name) + '/rollback', { version: version }).then(function (r) {
        if (!r.ok) { say(m, why(r), true); return; }
        afterChange(m, r.body);
      });
    });
  });

  // ---------------------------------------------------------------------------------------------------------------- data source
  var dlg = root.querySelector('[data-ds-dialog]');
  if (!dlg) { return; }
  var dsBody = dlg.querySelector('[data-ds-body]'), dsMsg = dlg.querySelector('[data-ds-msg]'), dsTitle = dlg.querySelector('[data-ds-title]'), dsIntro = dlg.querySelector('[data-ds-intro]');
  var pack = null, model = null, opener = null;

  var SOURCE = { pack: 'pack default', override: 'overridden here', site: 'set by the site: wins over this file' };

  function build(ds) {
    model = ds;
    dsBody.textContent = '';
    dsIntro.textContent = '';
    dsIntro.appendChild(document.createTextNode('Precedence, highest first: the site (environment variables and the server\'s own configuration), then what you save here ('));
    dsIntro.appendChild(el('span', { class: 'mono', text: ds.file }));
    dsIntro.appendChild(document.createTextNode('), then the pack\'s own settings. The pack\'s files are never changed, so redeploying the pack keeps your overrides. '
      + 'A credential is never stored: write an environment variable reference such as ${LAKE_PASSWORD}.'));
    ds.connectors.forEach(function (c) { dsBody.appendChild(connector(c)); });
  }
  function connector(c) {
    var sec = el('section', { class: 'ds-conn', 'data-connector': c.name });
    var en = el('input', { type: 'checkbox', id: 'dsEn-' + c.name, 'data-enabled': true });
    en.checked = !!c.enabled.on;
    en.setAttribute('data-initial', c.enabled.on ? '1' : '0');
    if (c.enabled.site != null) { en.disabled = true; }
    sec.appendChild(el('h3', {}, [c.name, ' ', el('span', { class: 'st', text: c.plugin }), ' ', el('span', { class: 'text-muted-d', text: 'kinds: ' + (c.kinds || []).join(', ') })]));
    sec.appendChild(el('p', {}, [el('label', { class: 'chk', for: 'dsEn-' + c.name }, [en, 'Connector is on']),
      el('span', { class: 'st ' + (c.enabled.source === 'pack' ? '' : 'st-warn'), text: SOURCE[c.enabled.source] }),
      c.enabled.pack != null ? el('span', { class: 'text-muted-d mono', text: ' pack: ' + c.enabled.pack }) : null]));
    var tb = el('tbody', { 'data-rows': true });
    c.settings.forEach(function (s) { tb.appendChild(row(c, s)); });
    var t = el('table', { class: 'tbl adm-tbl ds-tbl' }, [el('caption', { class: 'sr-only', text: 'Settings of ' + c.name }),
      el('thead', {}, [el('tr', {}, ['Setting', 'Value', 'Where it comes from', ''].map(function (h) { return el('th', { text: h }); }))]), tb]);
    sec.appendChild(el('div', { class: 'tbl-wrap' }, [t]));
    var k = el('input', { class: 'studio-in', placeholder: 'setting name', 'aria-label': 'New setting name for ' + c.name, autocomplete: 'off' });
    var v = el('input', { class: 'studio-in', placeholder: 'value (a secret: ${ENV_NAME})', 'aria-label': 'New setting value for ' + c.name, autocomplete: 'off' });
    var add = el('button', { type: 'button', class: 'fk', text: 'Add setting' });
    add.addEventListener('click', function () {
      if (!k.value.trim()) { k.focus(); return; }
      var existing = tb.querySelector('tr[data-key="' + k.value.trim().replace(/"/g, '') + '"]');
      if (existing) { existing.querySelector('input').value = v.value; existing.querySelector('input').focus(); return; }
      var r = row(c, { key: k.value.trim(), pack: null, override: v.value, site: null, effective: v.value, secret: /password|passwd|secret|token|api[-_.]?key|access[-_.]?key|private[-_.]?key|credential/i.test(k.value), source: 'override', overridden: true, resolvable: true });
      tb.appendChild(r);
      k.value = ''; v.value = '';
      r.querySelector('input').focus();
    });
    sec.appendChild(el('div', { class: 'ds-add' }, [k, v, add]));
    sec.appendChild(el('div', { class: 'ds-test', 'data-test-out': true, 'aria-live': 'polite' }));
    return sec;
  }
  function row(c, s) {
    var site = s.source === 'site';
    var input = el('input', { class: 'studio-in mono', value: site ? s.effective : (s.override != null ? s.override : (s.pack != null ? s.pack : '')), 'aria-label': c.name + ' ' + s.key,
      autocomplete: 'off', spellcheck: 'false', placeholder: s.secret ? '${ENV_NAME}' : '' });
    input.disabled = site;
    var badge = el('span', { class: 'st ' + (s.source === 'pack' ? '' : (site ? 'st-bad' : 'st-warn')), 'data-badge': true });
    var note = el('span', { class: 'text-muted-d mono' });
    function refresh() {
      var now = input.value, base = s.pack == null ? '' : s.pack;
      var changed = !site && now !== base;
      badge.textContent = site ? SOURCE.site : (changed ? SOURCE.override : SOURCE.pack);
      badge.className = 'st ' + (site ? 'st-bad' : (changed ? 'st-warn' : ''));
      note.textContent = changed && s.pack != null ? ' pack default: ' + s.pack : (s.resolvable === false && !changed ? ' an environment variable it refers to is not set' : (s.secret && !changed ? ' a credential: an environment reference' : ''));
      note.classList.toggle('t-warn', s.resolvable === false && !changed);
      reset.hidden = site || !changed;
    }
    var reset = el('button', { type: 'button', class: 'fk', text: s.pack == null ? 'Remove' : 'Use pack default', 'aria-label': (s.pack == null ? 'Remove ' : 'Use the pack default for ') + c.name + ' ' + s.key });
    reset.addEventListener('click', function () {
      if (s.pack == null) { tr.remove(); return; }
      input.value = s.pack; refresh(); input.focus();
    });
    input.addEventListener('input', refresh);
    var tr = el('tr', { 'data-key': s.key, 'data-pack-value': s.pack == null ? '' : s.pack, 'data-has-pack': s.pack != null ? '1' : '0', 'data-site': site ? '1' : '0' },
      [el('td', { class: 'mono', text: s.key }), el('td', {}, [input]), el('td', {}, [badge, note]), el('td', {}, [reset])]);
    refresh();
    return tr;
  }

  // The whole override the form describes: only what differs from the pack stays (the server drops the rest too).
  function desired() {
    var conns = {};
    dsBody.querySelectorAll('[data-connector]').forEach(function (sec) {
      var name = sec.getAttribute('data-connector'), entry = {}, st = {};
      sec.querySelectorAll('tr[data-key]').forEach(function (tr) {
        if (tr.getAttribute('data-site') === '1') { return; }
        var key = tr.getAttribute('data-key'), val = tr.querySelector('input').value, base = tr.getAttribute('data-pack-value'), has = tr.getAttribute('data-has-pack') === '1';
        if (has ? val !== base : val !== '') { st[key] = val; }
      });
      if (Object.keys(st).length) { entry.settings = st; }
      var en = sec.querySelector('[data-enabled]');
      if (en && !en.disabled && (en.checked ? '1' : '0') !== en.getAttribute('data-initial')) { entry.enabled = en.checked; }
      var c = model.connectors.filter(function (x) { return x.name === name; })[0];
      if (!('enabled' in entry) && c && c.enabled.override != null && !en.disabled) { entry.enabled = en.checked; }   // an override already in force stays unless changed
      if (Object.keys(entry).length) { conns[name] = entry; }
    });
    return conns;
  }
  function open(name, from) {
    pack = name; opener = from;
    dsTitle.textContent = 'Data source of ' + name;
    dsBody.textContent = 'Reading the settings…';
    say(dsMsg, '');
    if (!dlg.open) { dlg.showModal(); }
    call('GET', '/admin/api/packs/' + q(name) + '/datasource').then(function (r) {
      if (!r.ok) { dsBody.textContent = ''; say(dsMsg, why(r), true); return; }
      build(r.body);
      var first = dsBody.querySelector('.ds-tbl input:not([disabled])') || dsBody.querySelector('input:not([disabled])');
      if (first) {
        first.focus();
        // Chromium may move focus back to the dialog itself once, right after the first paint
        requestAnimationFrame(function () { if (document.activeElement !== first) { first.focus(); } });
      }
    });
  }
  root.querySelectorAll('[data-datasource]').forEach(function (b) { b.addEventListener('click', function () { open(b.closest('tr').getAttribute('data-pack'), b); }); });
  dlg.querySelector('[data-ds-close]').addEventListener('click', function () { dlg.close(); });
  dlg.addEventListener('close', function () { if (opener) { opener.focus(); } });

  function renderTest(t) {
    t.connectors.forEach(function (c) {
      var host = dsBody.querySelector('[data-connector="' + c.connector + '"] [data-test-out]');
      if (!host) { return; }
      host.textContent = '';
      if (c.skipped) { host.appendChild(el('p', { class: 'text-muted-d', text: c.error })); return; }
      host.appendChild(el('p', { class: c.ok ? 't-ok' : 't-bad', role: c.ok ? 'status' : 'alert',
        text: c.ok ? 'Reachable: health ' + c.health + ', ' + c.ms + ' ms. What it holds:' : 'Problem: ' + (c.error || 'not reachable') }));
      if (!c.ok || !(c.kinds || []).length) { return; }
      var tb = el('tbody');
      c.kinds.forEach(function (k) {
        if (!k.dates.length) { tb.appendChild(el('tr', {}, [el('td', { class: 'mono', text: k.kind }), el('td', { text: '—' }), el('td', { class: 'mono', text: '0' }), el('td', { class: 'cmp-path', text: k.note || '' })])); }
        k.dates.forEach(function (d) {
          tb.appendChild(el('tr', {}, [el('td', { class: 'mono', text: k.kind }), el('td', { class: 'mono', text: d.date || '(undated)' }),
            el('td', { class: 'mono ds-num', text: (k.exact ? '' : '≥ ') + d.rows.toLocaleString('en-US') }), el('td', { class: 'cmp-path', text: k.note || '' })]));
        });
      });
      host.appendChild(el('div', { class: 'tbl-wrap' }, [el('table', { class: 'tbl adm-tbl' }, [el('caption', { class: 'sr-only', text: 'What ' + c.connector + ' holds' }),
        el('thead', {}, [el('tr', {}, ['Kind', 'Business date', 'Rows', ''].map(function (h) { return el('th', { text: h }); }))]), tb])]));
    });
  }
  dlg.querySelector('[data-ds-test]').addEventListener('click', function () {
    say(dsMsg, 'Trying the settings on the real source…');
    dsBody.querySelectorAll('[data-test-out]').forEach(function (n) { n.textContent = ''; });
    call('POST', '/admin/api/packs/' + q(pack) + '/datasource/test', { connectors: desired() }).then(function (r) {
      if (!r.ok) { say(dsMsg, why(r), true); return; }
      say(dsMsg, r.body.ok ? 'The connection works (tested: ' + r.body.tested + ').' : 'There is a problem (tested: ' + r.body.tested + ').', !r.body.ok);
      renderTest(r.body);
    });
  });
  dlg.querySelector('[data-ds-save]').addEventListener('click', function () {
    if (!window.confirm('Save these settings for ' + pack + ' and apply them? The server restarts in place: a few seconds without data. If it cannot start, the previous settings are put back.')) { return; }
    say(dsMsg, 'Saving…');
    call('PUT', '/admin/api/packs/' + q(pack) + '/datasource', { connectors: desired() }).then(function (r) {
      if (!r.ok) { say(dsMsg, why(r), true); return; }
      afterChange(dsMsg, r.body);
    });
  });
  dlg.querySelector('[data-ds-reset]').addEventListener('click', function () {
    if (!window.confirm('Remove every override of ' + pack + ' and use the pack\'s own settings again? The server restarts in place.')) { return; }
    say(dsMsg, 'Resetting…');
    call('DELETE', '/admin/api/packs/' + q(pack) + '/datasource').then(function (r) {
      if (!r.ok) { say(dsMsg, why(r), true); return; }
      afterChange(dsMsg, r.body);
    });
  });
})();
