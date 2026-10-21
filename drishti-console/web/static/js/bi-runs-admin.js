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
/* Admin → BI → Runs (docs/architecture/BI_RUNS.md section 17.2), drawn from GET /bi/api/admin/runs every 5 seconds while the page is shown: the gate (slots
   per work class), the queue, what runs (with Cancel), the result store, the estimates' accuracy, the inline rate, the settings, and the repeated heavy
   questions a rollup would answer. Every part is optional: what the server does not report is left out, never shown as zero. */
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

    B.clear(suggest);
    (d.suggestions || []).forEach((s) => suggest.appendChild(h('tr', {}, h('th', { scope: 'row', class: 'mono', text: s.model || '' }), h('td', { text: [].concat(s.grain || []).join(' × ') }), h('td', { class: 'num', text: String(s.runs || 0) }),
      h('td', { text: s.medianSeconds != null ? R.duration(s.medianSeconds) : '' }), h('td', { text: s.status || 'a rollup at this grain would answer it at once' }))));
    if (!suggest.children.length) { suggest.appendChild(h('tr', {}, h('td', { colspan: '5', class: 'text-muted-d', text: 'No heavy question was asked often enough to suggest a rollup.' }))); }

    const nodes = (d.nodes || []).map((n) => (typeof n === 'string' ? { node: n } : n));
    if (nodes.length && nodeSel.options.length === 1) { nodes.forEach((n) => nodeSel.appendChild(h('option', { value: n.node, text: n.node + (n.alive === false ? ' (not reachable)' : '') }))); }
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
