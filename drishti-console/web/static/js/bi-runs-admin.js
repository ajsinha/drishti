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
/* Admin → BI → Runs (docs/architecture/BI_DESIGN.md › BI runs: every BI query as a bounded, visible job (built in 2.6.7), section 17.2), drawn from GET /bi/api/admin/runs every 5 seconds while the page is shown: the gate (slots
   per work class), the queue, what runs (with Cancel), the result store, the estimates' accuracy, the inline rate, the settings, and the repeated heavy
   questions a rollup would answer, each accepted (the rollup added to the model's draft for review) or dismissed. Every part is optional: what the server does not report is left out, never shown as zero. */
(function () {
  'use strict';
  const B = window.drishtiBi, R = B && B.runs;
  const root = document.querySelector('[data-bi-runs-admin]');
  if (!B || !R || !root) { return; }
  const h = B.h, q = B.q;
  const facts = q('[data-admin-facts]'), running = q('[data-admin-running] tbody'), suggest = q('[data-admin-suggest] tbody'), msg = q('[data-admin-msg]');
  const nodeSel = q('[data-admin-node]');
  const slot = (x) => (x && x.used != null ? x.used + '/' + (x.of != null ? x.of : x.cap != null ? x.cap : '?') + (x.cap != null && x.used >= x.cap ? ' (cap)' : '') : '');
  const fact = (k, v, attrs) => (v ? [h('dt', { text: k }), h('dd', Object.assign({ text: v }, attrs || {}))] : []);

  function draw(d) {
    B.clear(facts);
    const now = d.now || {}, sl = now.slots || {}, qd = d.queue || {}, st = d.storage || {}, acc = d.accuracy || {}, inl = d.inline || {}, set = d.settings || {};
    const slots = ['interactive', 'standing', 'background'].filter((k) => sl[k]).map((k) => k + ' ' + slot(sl[k])).join(' · ');
    const byClass = qd.byClass || {}, byUser = qd.byUser || {};
    const queued = Object.keys(byClass).reduce((n, k) => n + (Number(byClass[k]) || 0), 0);
    const people = Object.keys(byUser).length;
    const wait = now.admissionWaitP95Ms != null ? 'interactive admission wait p95 (5 min) ' + Math.round(now.admissionWaitP95Ms) + ' ms' + (now.starving === false || now.admissionWaitP95Ms < 100 ? ' · ✓ background is not slowing it' : ' · ⚠ interactive work is waiting') : '';
    [fact('Now', [slots, queued || people ? 'queue ' + queued + (people ? ' (' + people + (people === 1 ? ' person' : ' people') + ')' : '') : 'queue empty'].filter(Boolean).join(' · '), { 'data-admin-now': '' }),
      fact('', wait),
      fact('Queued', queued ? queued + ' runs' + (qd.oldestSeconds != null ? ' · oldest ' + R.duration(qd.oldestSeconds) + (qd.oldestUser ? ' (' + qd.oldestUser + ')' : '') : '') + (people ? ' · per person: ' + Object.keys(byUser).map((u) => u + ' ' + byUser[u]).join(', ') : '') : 'none'),
      fact('Storage', st.resultsBytes != null ? 'results ' + R.bytes(st.resultsBytes) + (st.maxBytes ? ' of ' + R.bytes(st.maxBytes) : '') + (st.results != null ? ' (' + st.results.toLocaleString('en-US') + ' results' + (st.oldest ? ', oldest ' + String(st.oldest).slice(0, 10) : '') + ')' : '')
        + (st.snapshotsBytes != null ? ' · snapshots ' + R.bytes(st.snapshotsBytes) + (st.snapshots != null ? ' (' + st.snapshots + ')' : '') : '')
        + (st.topUsers && st.topUsers.length ? ' · top users: ' + st.topUsers.map((u) => u.user + ' ' + R.bytes(u.bytes)).join(' · ') : '') : '', { 'data-admin-storage': '' }),
      fact('Estimates', acc.medianRatio != null ? 'last 7 days: median actual/estimate ' + acc.medianRatio + (acc.p10 != null ? ', 90 % within ' + acc.p10 + '–' + acc.p90 : '') + (acc.basis ? ' (' + R.basis(acc.basis) + ')' : '') + (acc.unknownShare != null ? '; ' + Math.round(acc.unknownShare * 100) + ' % with no size known' : '') : ''),
      fact('Inline', inl.inlineShare != null ? Math.round(inl.inlineShare * 100) + ' % of questions answered inline; ' + (100 - Math.round(inl.inlineShare * 100)) + ' % became runs' + (inl.jobMedianSeconds != null ? ' (median ' + R.duration(inl.jobMedianSeconds) + ')' : '') : ''),
      fact('Settings', [set.file, set.inlineWait ? 'inline ' + R.duration(R.seconds(set.inlineWait)) : '', set.confirmAboveSeconds ? 'confirm above ' + R.duration(set.confirmAboveSeconds) : '', set.retentionDays ? 'retention ' + set.retentionDays + ' days' : ''].filter(Boolean).join(' · ') + (set.file ? ' (read-only here)' : ''))
    ].forEach((pair) => pair.forEach((n) => facts.appendChild(n)));

    B.clear(running);
    (d.running || []).forEach((r) => {
      const cancel = h('button', { type: 'button', class: 'btn-pill btn-ghost', 'data-admin-cancel': r.id, 'aria-label': 'Cancel the run of ' + r.runner + ': ' + (r.title || r.id), text: 'Cancel' });
      cancel.addEventListener('click', () => B.confirm(cancel, 'Cancel ' + r.runner + '\'s run? They are told it was cancelled by an administrator.', 'Cancel the run').then((yes) => {
        if (!yes) { return; }
        fetch('/bi/api/admin/runs/' + encodeURIComponent(r.id) + '/cancel', { method: 'POST', credentials: 'same-origin', headers: { Accept: 'application/json' } })
          .then((res) => res.json().catch(() => ({})).then((j) => { if (!res.ok) { throw j; } B.near(cancel, 'Cancelled. ' + r.runner + ' is told.', false); load(); }))
          .catch((e) => B.near(cancel, R.words(e.code, e.detail), true));
      }));
      const took = R.took(r);
      running.appendChild(h('tr', { 'data-admin-run': r.id }, h('th', { scope: 'row', class: 'mono', title: r.id, text: r.id.length > 18 ? r.id.slice(0, 16) + '…' : r.id }), h('td', { text: r.runner }), h('td', { text: r.title || '' }),
        h('td', { text: r.node || '' }), h('td', { text: (r.phase || R.stateOf(r.state).label) + (r.progress != null ? ' ' + Math.round(r.progress * 100) + ' %' : '') }), h('td', { text: took != null ? R.duration(took) : '' }), h('td', {}, cancel)));
    });
    if (!running.children.length) { running.appendChild(h('tr', {}, h('td', { colspan: '7', class: 'text-muted-d', text: 'Nothing runs in the background now.' }))); }

    drawSuggestions(d.suggestions || []);

    const nodes = (d.nodes || []).map((n) => (typeof n === 'string' ? { node: n } : n));
    if (nodes.length && nodeSel.options.length === 1) { nodes.forEach((n) => nodeSel.appendChild(h('option', { value: n.node, text: n.node + (n.alive === false ? ' (not reachable)' : '') }))); }
  }

  // the rollup suggestions (section 8.3a): redrawn only when they change, so an open YAML or the focus survives the 5-second refresh
  const STATUS = { suggested: 'Suggested', accepted: 'Accepted: in the model\'s draft for review', dismissed: 'Dismissed', built: 'Built: these questions are instant now' };
  const sMsg = q('[data-admin-suggest-msg]');
  const onlyModel = new URLSearchParams(window.location.search).get('model');
  let shownSig = null;
  function decide(s, verb, b) {
    B.busy(b, () => fetch('/bi/api/admin/runs/suggestions/' + encodeURIComponent(s.id) + '/' + verb, { method: 'POST', credentials: 'same-origin', headers: { Accept: 'application/json', 'Content-Type': 'application/json' }, body: '{}' })
      .then((res) => res.json().catch(() => ({})).then((j) => {
        if (!res.ok) { throw j; }
        B.clear(sMsg);
        if (verb === 'dismiss') { sMsg.textContent = 'Dismissed: the rollup for ' + s.model + ' at ' + [].concat(s.grain || []).join(' × ') + ' is no longer suggested.'; } else if (j.item) {
          sMsg.append('Accepted: the rollup ' + ((j.rollup || s.rollup || {}).name || '') + ' is in the draft of ' + s.model + '. Review it and publish it as any change. ',
            h('a', { href: '/bi/i/' + B.id2path(j.item), 'data-sugg-open': j.item, text: 'Open the model' }));
        } else { sMsg.textContent = 'Accepted. ' + s.model + ' is a pack\'s model: add the YAML shown under the suggestion to it by hand.'; }
        shownSig = null;
        return load();
      })).catch((e) => { sMsg.textContent = R.words(e.code, e.detail); }));
  }
  function drawSuggestions(list) {
    list = onlyModel ? list.filter((s) => s.model === onlyModel) : list;
    const sig = JSON.stringify(list);
    if (sig === shownSig) { return; }
    shownSig = sig;
    B.clear(suggest);
    list.forEach((s) => {
      const st = s.status || 'suggested';
      const grain = [].concat(s.grain || []).join(' × ') + ((s.filters || []).length ? ' · filters: ' + [].concat(s.filters).join(', ') : '');
      const acts = h('div', { class: 'bi-sugg-acts' });
      if (st === 'suggested') {
        const acc = h('button', { type: 'button', class: 'btn-pill btn-accent', 'data-sugg-accept': s.id, 'aria-label': 'Accept: add the rollup to the draft of ' + s.model, text: 'Accept' });
        const dis = h('button', { type: 'button', class: 'btn-pill btn-ghost', 'data-sugg-dismiss': s.id, 'aria-label': 'Dismiss the suggestion for ' + s.model, text: 'Dismiss' });
        acc.addEventListener('click', () => decide(s, 'accept', acc));
        dis.addEventListener('click', () => decide(s, 'dismiss', dis));
        acts.append(acc, dis);
      } else if (s.item) { acts.appendChild(h('a', { class: 'btn-pill btn-ghost', href: '/bi/i/' + B.id2path(s.item), text: 'Open the model' })); }
      const yaml = s.yaml ? h('details', {}, h('summary', { text: 'The rollup' + (s.rollup && s.rollup.name ? ' ' + s.rollup.name : '') }), h('pre', { class: 'bi-sugg-yaml', text: s.yaml })) : null;
      suggest.appendChild(h('tr', { 'data-sugg': s.id, 'data-sugg-status': st }, h('th', { scope: 'row', class: 'mono', text: s.model || '' }),
        h('td', {}, h('span', { text: grain }), s.words ? h('small', { class: 'text-muted-d', text: ' ' + s.words }) : null, yaml),
        h('td', { class: 'num', text: String(s.runs || 0) }), h('td', { text: s.medianSeconds != null ? R.duration(s.medianSeconds) : '' }),
        h('td', { class: 'bi-sugg-status bi-sugg-status-' + st, text: (STATUS[st] || st) + (s.decidedBy && st !== 'suggested' ? ' (' + s.decidedBy + ')' : '') }), h('td', {}, acts)));
    });
    if (!suggest.children.length) { suggest.appendChild(h('tr', {}, h('td', { colspan: '6', class: 'text-muted-d', text: 'No heavy question was asked often enough to suggest a rollup.' }))); }
  }

  let timer = null;
  function load() {
    window.clearTimeout(timer);
    const p = nodeSel.value ? '?node=' + encodeURIComponent(nodeSel.value) : '';
    return fetch('/bi/api/admin/runs' + p, { credentials: 'same-origin', headers: { Accept: 'application/json' } })
      .then((r) => r.json().catch(() => ({})).then((d) => { if (!r.ok) { throw d; } msg.textContent = ''; draw(d); }))
      .catch((e) => { msg.textContent = 'The runs could not be read: ' + R.words(e.code, e.detail); })
      .then(() => { if (!document.hidden) { timer = window.setTimeout(load, 5000); } });
  }
  nodeSel.addEventListener('change', load);
  document.addEventListener('visibilitychange', () => { if (!document.hidden) { load(); } });
  load();
})();
