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
/* Write-back from a form (docs/architecture/WRITE_BACK.md section 18; the forms guide: docs/guides/SUTRA_FORMS_GUIDE.md): Submit on a form
   whose Sutra says where it goes (form.save) opens the review and confirm sheet: per record, three columns per field (what you saw, what is
   there now, your change; a field that moved underneath is flagged before you submit), where it goes (a correction kept beside the source, an
   owned record, or an amendment through a connector), who must approve (never you), what it will recompute, and the reason box when the form
   asks for one. Confirm turns the draft into change requests (/api/changes); the sheet then follows each change on the page's live
   connection (the "edit" events): waiting for approval, queued, applied (the draft is closed), refused or conflicted (the draft is kept,
   marked, to edit again), and the recomputes it triggered. Nothing is sent before Confirm; the server checks everything again.
   window.drishtiWriteBack: settings() (whether this person may submit here), submit({draft, title, onDone}). */
(function () {
  'use strict';
  var settings = null;
  var open = {};                                               // change id -> the sheet line that follows it

  function el(tag, cls, text) { var n = document.createElement(tag); if (cls) { n.className = cls; } if (text !== undefined) { n.textContent = text; } return n; }
  function btn(cls, text, on) { var b = el('button', cls, text); b.type = 'button'; if (on) { b.addEventListener('click', on); } return b; }
  function show(v) { return v === null || v === undefined || v === '' ? '—' : String(v); }
  function call(method, path, body) {
    return fetch('/api/changes' + (path || ''), { method: method, credentials: 'same-origin',
      headers: body ? { 'Content-Type': 'application/json', Accept: 'application/json' } : { Accept: 'application/json' }, body: body ? JSON.stringify(body) : undefined })
      .then(function (r) {
        return r.json().catch(function () { return {}; }).then(function (j) {
          if (!r.ok) { var e = new Error(j.detail || ('HTTP ' + r.status)); e.code = j.code; e.status = r.status; throw e; }
          return j;
        });
      });
  }

  /* {enabled, mayEdit, approver}: asked once per page. */
  function load() {
    if (!settings) {
      settings = call('GET', '/settings').catch(function () { return { enabled: false, mayEdit: false }; });
    }
    return settings;
  }

  function words(state, c) {
    switch (state) {
      case 'awaiting': return 'Waiting for ' + (c && c.needed > 1 ? c.needed + ' approvers' : 'an approver');
      case 'queued': return 'Queued: being applied';
      case 'approved': return 'Approved: queued, being applied';
      case 'applied': return 'Applied';
      case 'conflicted': return 'Conflicted: the record moved since you opened it';
      case 'refused': return 'Refused';
      case 'rejected': return 'Rejected';
      case 'confirmed': return 'Confirmed by the source';
      case 'refused-at-source': return 'Refused by the source';
      default: return state;
    }
  }

  function sheetLine(c) {
    var box = el('section', 'wb-change'); box.setAttribute('data-wb-change', '');
    box.appendChild(el('h3', 'wb-rec', c.kind + ' ' + c.entity));
    var t = el('table', 'tbl wb-diff'), head = el('tr');
    ['Field', 'You saw', 'Now', 'Your change'].forEach(function (h) { var th = el('th', '', h); th.scope = 'col'; head.appendChild(th); });
    var thead = el('thead'); thead.appendChild(head); t.appendChild(thead);
    var tb = el('tbody');
    (c.diff || []).forEach(function (d) {
      var tr = el('tr', d.moved ? 'wb-moved' : '');
      tr.appendChild(el('td', 'mono', d.field));
      tr.appendChild(el('td', 'mono', show(d.saw)));
      var now = el('td', 'mono', show(d.now));
      if (d.moved) { now.appendChild(el('span', 'wb-flag', ' changed since you opened it')); }
      tr.appendChild(now);
      tr.appendChild(el('td', 'mono wb-new', show(d.wanted)));
      tb.appendChild(tr);
    });
    t.appendChild(tb); box.appendChild(t);
    box.appendChild(el('p', 'wb-where', c.where));
    if (c.approvals > 0) {
      box.appendChild(el('p', 'wb-who', (c.approvals === 1 ? 'One approver' : c.approvals + ' approvers') + ' (not you)'
        + (c.approvers && c.approvers.length ? ': ' + c.approvers.join(' or ') : '')));
    } else {
      box.appendChild(el('p', 'wb-who', 'No approval needed: it is applied at once'));
    }
    var rc = c.recompute || [];
    box.appendChild(el('p', 'wb-recompute', rc.length ? 'Recomputes: ' + rc.map(function (r) { return r.connector + (r.available && r.available !== 'ready' ? ' (' + r.available + ')' : ''); }).join(', ')
      : 'Recomputes nothing: no computed connector is triggered by a change to ' + c.kind));
    var st = el('p', 'wb-state'); st.setAttribute('data-wb-state', ''); st.setAttribute('role', 'status');
    box.appendChild(st);
    return { box: box, state: st };
  }

  /* The review and confirm sheet for a saved draft. */
  function submit(opts) {
    var dlg = el('dialog', 'fm-dialog wb-sheet'); dlg.setAttribute('aria-label', 'Review and confirm'); dlg.setAttribute('data-wb-sheet', '');
    dlg.appendChild(el('h2', 'wb-h', 'Review and confirm' + (opts.title ? ': ' + opts.title : '')));
    var body = el('div', 'wb-body', 'Reading what it would do…');
    dlg.appendChild(body);
    var why = el('div', 'wb-why'), lbl = el('label', '', 'Reason'), ta = el('textarea', 'fm-in wb-reason');
    ta.id = 'wb-reason-' + Date.now(); lbl.htmlFor = ta.id; ta.rows = 2; ta.maxLength = 500; ta.placeholder = 'Why this change (the approver reads it)';
    why.appendChild(lbl); why.appendChild(ta);
    var msg = el('p', 'wb-msg'); msg.setAttribute('role', 'alert');
    var foot = el('footer', 'fm-foot');
    var confirmB = btn('fm-btn fm-primary wb-confirm', 'Confirm and submit');
    var closeB = btn('fm-btn', 'Cancel', function () { dlg.close(); dlg.remove(); });
    foot.appendChild(confirmB); foot.appendChild(closeB);
    dlg.appendChild(why); dlg.appendChild(msg); dlg.appendChild(foot);
    document.body.appendChild(dlg);
    if (dlg.showModal) { dlg.showModal(); } else { dlg.setAttribute('open', ''); }
    var lines = [];
    call('POST', '/preview', { draft: opts.draft }).then(function (p) {
      body.textContent = '';
      if (!p.ok) { body.appendChild(el('p', 'wb-problem', p.problem || 'This draft cannot be submitted')); confirmB.disabled = true; return; }
      var needs = false;
      (p.changes || []).forEach(function (c) { var l = sheetLine(c); lines.push(l); body.appendChild(l.box); needs = needs || c.reasonRequired; });
      ta.required = needs;
      lbl.textContent = needs ? 'Reason (required)' : 'Reason';
    }).catch(function (e) { body.textContent = e.message; confirmB.disabled = true; });
    confirmB.addEventListener('click', function () {
      if (ta.required && !ta.value.trim()) { msg.textContent = 'Say why: this change needs a reason'; ta.focus(); return; }
      confirmB.disabled = true; msg.textContent = '';
      call('POST', '', { draft: opts.draft, reason: ta.value.trim() }).then(function (r) {
        (r.changes || []).forEach(function (c, i) {
          var l = lines[i] || sheetLine({ kind: c.kind, entity: c.entity, diff: [], where: '' });
          if (!lines[i]) { body.appendChild(l.box); }
          l.state.textContent = words(c.state, c) + ' · ' + c.id;
          l.box.setAttribute('data-wb-id', c.id);
          open[c.id] = l;
        });
        why.hidden = true;
        confirmB.remove();
        closeB.textContent = 'Close';
        if (opts.onDone) { opts.onDone(r.changes || []); }
      }).catch(function (e) { msg.textContent = e.message; confirmB.disabled = false; });
    });
    return dlg;
  }

  /* A change's steps on the live connection: the sheet line that follows it, and the page told (drishti:edit from alerts.js). */
  document.addEventListener('drishti:edit', function (ev) {
    var d = ev.detail || {}, l = open[d.id];
    if (!l || !d.mine) { return; }
    if (d.type === 'recompute') {
      var rc = d.recompute || {}, names = Object.keys(rc);
      l.box.appendChild(el('p', 'wb-recompute-done', names.length ? 'Recompute (' + d.watermark + '): ' + names.map(function (n) { return n + ' ' + JSON.stringify(rc[n]); }).join('; ')
        : 'Recompute: no computed connector was triggered'));
      return;
    }
    l.state.textContent = words(d.type === 'approval' ? d.state : d.type, d) + (d.reason ? ': ' + d.reason : '') + ' · ' + d.id;
    l.box.setAttribute('data-wb-state-now', d.type);
  });

  window.drishtiWriteBack = { settings: load, submit: submit, words: words };
})();
