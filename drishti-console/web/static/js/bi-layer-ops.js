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
/* What an author does to a dataset of the model layer (an item: a pack's dataset is the pack's), on its page /bi/ds/<id>: its Refresh (the state of the
   schedule, the recent runs, Refresh now, and Set a schedule, which writes `refresh:` into a new saved state of the draft, comments and order kept) and its
   Expected values (`tests:`, run as yourself or a role you hold). Same-origin JSON only; the server applies who may do what. */
(function () {
  'use strict';
  const B = (window.drishtiBi = window.drishtiBi || {});
  const h = (tag, attrs, ...kids) => B.h(tag, attrs, ...kids);
  const num = (v) => (typeof v === 'number' ? v.toLocaleString('en-US', { maximumFractionDigits: 2 }) : String(v));
  const when = (x) => (x ? new Date(x).toLocaleString() : '–');
  const badge = (text, cls, ic) => h('span', { class: 'bi-badge ' + (cls || '') }, ic ? B.icon(ic) : null, ' ' + text);

  /**
   * One state for a dataset of one table: the server keeps the schedule on the table and a run someone asked for on the dataset; the page shows them as
   * one line (the schedule, and the last run whichever started it).
   */
  const own = (r) => {
    const t = (r.tables || [])[0] || {}, d = r.dataset || {};
    if ((r.tables || []).length > 1 || !r.dataset) { return r.dataset || t; }
    const later = !t.lastRun || (d.lastRun && d.lastRun > t.lastRun) ? d : t;
    return Object.assign({}, later, { schedule: t.schedule || d.schedule, nextRun: t.nextRun || d.nextRun, history: d.history || t.history || [],
      status: ['failed', 'retrying', 'running', 'invalid'].indexOf(t.status) >= 0 ? t.status : later.status });   // a scheduled run that went wrong is the news
  };

  /** The Refresh and Expected values sections of a dataset item's page (`info` is the console's dataset info: item, readOnly, refresh). */
  B.layerOps = function (root, info) {
    const q = (sel) => root.querySelector(sel);
    const path = '/datasets/' + B.id2path(info.item);
    const sec = q('[data-refresh-sec]'), tsec = q('[data-tests-sec]');
    if (!sec || !info.item) { return; }
    let me = null;
    const mePromise = B.api('GET', '/me').then((m) => { me = m; return m; }).catch(() => ({ powers: [], roles: [] }));

    function history(list) {
      const tb = B.clear(q('[data-refresh-history] tbody'));
      list.forEach((x) => {
        const bad = x.status !== 'ok' && x.status !== 'warning';
        tb.appendChild(h('tr', { 'data-history-row': x.status }, h('th', { scope: 'row', text: when(x.at) }), h('td', { text: x.trigger || '' }),
          h('td', {}, badge(x.status, bad ? 'bi-status' : 'bi-promoted', bad ? 'exclamation-triangle' : 'check-circle')), h('td', { text: x.date || '' }),
          h('td', { text: bad ? '–' : num(x.rows) }), h('td', { text: bad ? '–' : num(x.durationMs) + ' ms' }), h('td', { text: x.error || '' })));
      });
      q('[data-refresh-history]').hidden = list.length === 0;
      q('[data-refresh-history-head]').hidden = list.length === 0;
    }

    function draw(r) {
      const tb = B.clear(q('[data-refresh] tbody'));
      const line = (what, st) => {
        const bad = st.status === 'failed' || st.status === 'retrying' || st.status === 'invalid';
        tb.appendChild(h('tr', { 'data-refresh-row': what, 'data-status': st.status || '' }, h('th', { scope: 'row', text: what }), h('td', { class: 'mono', text: st.schedule || (info.refresh && info.refresh['on-load'] ? 'when data loads' : '') }),
          h('td', {}, badge(st.status || 'waiting', bad ? 'bi-status' : 'bi-promoted', bad ? 'exclamation-triangle' : 'arrow-repeat'), st.error ? h('small', { class: 'text-muted-d', text: ' ' + st.error }) : null),
          h('td', { text: when(st.lastRun) }), h('td', { text: st.retryAt ? 'retry ' + when(st.retryAt) : /^after-load/.test(st.schedule || '') ? 'at the next data load' : when(st.nextRun) }),
          h('td', { text: st.lastRows == null ? '–' : num(st.lastRows) }), h('td', { text: st.lastDurationMs == null ? '–' : num(st.lastDurationMs) + ' ms' }), h('td', { text: String(st.failures || 0) })));
      };
      const one = own(r);
      if ((r.tables || []).length <= 1) { line('dataset', one); } else { if (r.dataset) { line('dataset', r.dataset); } r.tables.forEach((t) => line('table ' + t.table, t)); }
      q('[data-refresh-when]').textContent = 'Last refresh: ' + when(one.lastRun) + ' · Next: ' + (one.retryAt ? 'retry ' + when(one.retryAt) : one.nextRun ? when(one.nextRun) : r.scheduled ? 'worked out at the next scheduler look' : 'no schedule');
      history(one.history || []);
      q('[data-refresh-note]').textContent = r.scheduled
        ? 'Layers are rebuilt on the schedule (' + r.zone + ' time, ' + r.calendar + ' calendar for business days); a failed run is tried again with the shared retry policy.'
        : 'No refresh schedule: the layers are rebuilt when a data load for the kind this dataset reads arrives, or when an author asks (Refresh now).';
      q('[data-refresh]').hidden = tb.children.length === 0;
      q('[data-set-schedule]').hidden = info.readOnly || !!r.scheduled || !(me && me.powers.indexOf('bi-author') >= 0);
    }

    const load = () => B.api('GET', path + '/refresh').then(draw).catch((e) => { q('[data-refresh-note]').textContent = e.message; });
    sec.hidden = false;
    mePromise.then((m) => {
      load();
      const author = (m.powers || []).indexOf('bi-author') >= 0;
      const btn = q('[data-refresh-now]'), msg = q('[data-refresh-msg]');
      btn.hidden = !author;
      btn.addEventListener('click', () => {
        btn.disabled = true; msg.classList.remove('bi-bad'); msg.textContent = 'Refreshing…';
        B.api('POST', path + '/refresh', {}).then(() => {
          let tries = 0;
          (function poll() {
            B.api('GET', path + '/refresh').then((st) => {
              const d = st.dataset || {};
              if ((!st.dataset || d.status === 'running') && tries++ < 120) { setTimeout(poll, 1000); return; }   // the run appears, then ends
              btn.disabled = false;
              const ok = d.status === 'ok' || d.status === 'warning';
              msg.textContent = ok ? 'Refreshed ' + num(d.lastRows) + ' rows in ' + num(d.lastDurationMs) + ' ms.' : 'Refresh ' + (d.status || 'did not finish') + (d.error ? ': ' + d.error : '') + '.';
              msg.classList.toggle('bi-bad', !ok);
              draw(st);
            }).catch((e) => { btn.disabled = false; msg.textContent = e.message; msg.classList.add('bi-bad'); });
          })();
        }).catch((e) => { btn.disabled = false; msg.textContent = e.message; msg.classList.add('bi-bad'); });
      });
      const f = q('[data-set-schedule]');
      f.addEventListener('submit', (e) => {
        e.preventDefault();
        const go = f.querySelector('[type=submit]');
        const k = f.elements.kind.value, text = (k === 'hourly' || k === 'after-load') ? k : k + ' ' + f.elements.time.value;
        B.layer.post('dataset-refresh', { item: info.item, refresh: text }).then(() => {
          B.near(go, 'Saved refresh: ' + text + ' in the draft. Publish the dataset to start the schedule.', false);
          f.hidden = true;
        }).catch((err) => B.near(go, err.message, true));
      });
      if (!tsec || !author || !info.hasTests) { return; }                                          // a dataset that declares no tests has nothing to run
      tsec.hidden = false;
      const sel = q('[data-tests-role]'), run = q('[data-tests-run]'), sum = q('[data-tests-sum]');
      sel.appendChild(h('option', { value: '', text: 'yourself' }));
      (m.roles || []).forEach((r) => sel.appendChild(h('option', { value: r, text: r })));
      run.addEventListener('click', () => {
        run.disabled = true; sum.textContent = 'Running…';
        const tb = B.clear(q('[data-tests-table] tbody'));
        B.api('POST', '/items/' + B.id2path(info.item) + '/tests', { role: sel.value || undefined }).then((r) => {
          run.disabled = false;
          q('[data-tests-table]').hidden = r.total === 0;
          sum.textContent = r.total === 0 ? 'This dataset declares no tests.' : r.passed + ' passed, ' + r.failed + ' failed' + (r.errors ? ', ' + r.errors + ' could not run' : '') + '.';
          r.results.forEach((x) => {
            const bad = x.status !== 'pass';
            tb.appendChild(h('tr', { 'data-test': x.status }, h('th', { scope: 'row', text: x.name }), h('td', { class: 'mono', text: x.target }), h('td', { text: x.expect }),
              h('td', { text: x.actual == null ? '–' : num(x.actual) }), h('td', {}, badge(x.status, bad ? 'bi-status' : 'bi-promoted', bad ? 'exclamation-triangle' : 'check-circle'), x.message ? h('small', { class: 'text-muted-d', text: ' ' + x.message }) : null)));
          });
        }).catch((e) => { run.disabled = false; sum.textContent = e.message; });
      });
    });
  };
  B.layerOps.own = own;
})();
