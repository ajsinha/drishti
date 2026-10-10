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
   Data source (the connectors it uses, with links to Admin → Connectors). Everything goes through same-origin /admin/api/packs/...; the server enforces the admin role,
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
        var t = el('table', { class: 'tbl adm-tbl', 'data-plain': true }, [el('thead', {}, [el('tr', {}, ['What', 'Name', 'Detail'].map(function (h) { return el('th', { text: h }); }))])]);
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
        if (!window.confirm('Deploy ' + rep.pack + ' ' + rep.version + '? It takes effect now, with no restart. If the pack cannot be used, the old files are put back.')) { return; }
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
      if (!window.confirm('Roll ' + name + ' back to ' + (version === 'shipped' ? 'the version that ships with the server' : version) + '? It takes effect now, with no restart.')) { return; }
      var m = root.querySelector('[data-msg]');
      say(m, 'Rolling ' + name + ' back…');
      call('POST', '/admin/api/packs/' + q(name) + '/rollback', { version: version }).then(function (r) {
        if (!r.ok) { say(m, why(r), true); return; }
        afterChange(m, r.body);
      });
    });
  });

  // ---------------------------------------------------------------------------------------------------------------- sample packs
  // Who sees the sample packs: saved on the server (it overrides drishti.packs.samples), audited, applied at once.
  root.querySelectorAll('[data-samples-set]').forEach(function (b) {
    b.addEventListener('click', function () {
      var mode = b.getAttribute('data-samples-set'), m = root.querySelector('[data-msg]');
      if (mode === 'hidden' && !window.confirm('Hide the sample packs from everyone, developers too? They are unloaded and their connectors stop.')) { return; }
      say(m, 'Saving\u2026');
      call('POST', '/admin/api/pack-samples', { mode: mode }).then(function (r) {
        if (!r.ok) { say(m, why(r), true); return; }
        say(m, 'Saved: sample packs are now ' + r.body.mode + '.');
        setTimeout(function () { location.reload(); }, 700);
      });
    });
  });

  // ---------------------------------------------------------------------------------------------------------------- data source
  // A pack names its connectors; they are defined and changed in Admin -> Connectors. This panel is a view with links.
  var dlg = root.querySelector('[data-ds-dialog]');
  if (!dlg) { return; }
  var dsBody = dlg.querySelector('[data-ds-body]'), dsMsg = dlg.querySelector('[data-ds-msg]'), dsTitle = dlg.querySelector('[data-ds-title]'), dsIntro = dlg.querySelector('[data-ds-intro]');
  var opener = null;

  var STATE = { RUNNING: ['st st-ok', 'running'], DISABLED: ['st', 'disabled'], FAILED: ['st st-bad', 'failed'], IDLE: ['st st-warn', 'not configured by its plugin'],
    NOT_LOADED: ['st st-warn', 'not started'], NOT_CONFIGURED: ['st st-bad', 'not configured'] };

  function build(ds) {
    dsBody.textContent = '';
    dsIntro.textContent = '';
    dsIntro.appendChild(document.createTextNode('A pack names the connectors it reads through; each one is a file in '));
    dsIntro.appendChild(el('span', { class: 'mono', text: ds.directory }));
    dsIntro.appendChild(document.createTextNode(' and is created, tested and changed in '));
    dsIntro.appendChild(el('a', { href: '/admin/connectors', text: 'Admin \u2192 Connectors' }));
    dsIntro.appendChild(document.createTextNode('. Several packs may use the same connector.'));
    if (!ds.connectors.length) { dsBody.appendChild(el('p', { class: 'text-muted-d', text: 'This pack names no connector.' })); return; }
    var tb = el('tbody');
    ds.connectors.forEach(function (c) {
      var st = STATE[c.state] || ['st', String(c.state || '').toLowerCase()];
      var link = c.defined
        ? el('a', { class: 'fk', href: c.editUrl, text: 'Edit', 'aria-label': 'Edit connector ' + c.name })
        : el('a', { class: 'fk', href: c.createUrl, text: 'Create', 'aria-label': 'Create connector ' + c.name });
      var problems = (c.problems || []).map(function (x) { return el('div', { class: 't-bad', text: x }); });
      tb.appendChild(el('tr', { 'data-connector': c.name }, [
        el('th', { scope: 'row', class: 'mono', text: c.name }),
        el('td', {}, [el('span', { class: st[0], text: st[1] })].concat(c.health ? [el('span', { class: 'text-muted-d', text: ' ' + c.health })] : [])),
        el('td', { class: 'mono', text: c.plugin || (c.hasTemplate ? 'from the pack\'s template' : '\u2014') }),
        el('td', { class: 'mono', text: (c.kinds || []).join(', ') || '\u2014' }),
        el('td', { text: c.defined ? c.origin : 'nothing defines it' }),
        el('td', {}, problems.concat([link]))]));
    });
    dsBody.appendChild(el('div', { class: 'tbl-wrap' }, [el('table', { class: 'tbl adm-tbl ds-tbl', 'data-plain': true }, [
      el('caption', { class: 'visually-hidden', text: 'Connectors this pack uses' }),
      el('thead', {}, [el('tr', {}, ['Connector', 'Status', 'Plugin', 'Kinds from this pack', 'Defined by', ''].map(function (h) { return el('th', { scope: 'col', text: h }); }))]), tb])]));
    if (ds.missing.length) {
      say(dsMsg, ds.missing.length + ' connector' + (ds.missing.length === 1 ? ' is' : 's are') + ' not configured: the pack loads, and the kinds routed to ' + (ds.missing.length === 1 ? 'it show' : 'them show') + ' that until the connector is created.', true);
    }
  }
  function open(name, from) {
    opener = from;
    dsTitle.textContent = 'Connectors of ' + name;
    dsBody.textContent = 'Reading the connectors\u2026';
    say(dsMsg, '');
    if (!dlg.open) { dlg.showModal(); }
    call('GET', '/admin/api/packs/' + q(name) + '/datasource').then(function (r) {
      if (!r.ok) { dsBody.textContent = ''; say(dsMsg, why(r), true); return; }
      build(r.body);
      var first = dsBody.querySelector('a') || dlg.querySelector('[data-ds-close]');
      first.focus();
      requestAnimationFrame(function () { if (document.activeElement !== first) { first.focus(); } });   // Chromium may move focus to the dialog once, after the first paint
    });
  }
  root.querySelectorAll('[data-datasource]').forEach(function (b) { b.addEventListener('click', function () { open(b.closest('tr').getAttribute('data-pack'), b); }); });
  dlg.querySelector('[data-ds-close]').addEventListener('click', function () { dlg.close(); });
  dlg.addEventListener('close', function () { if (opener) { opener.focus(); } });
})();
