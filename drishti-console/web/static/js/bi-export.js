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
/* Export and subscribe on a BI report page (RUPAKA.md section 15). The controls sit in the report bar (report.html); bi-report.js says when the report is
   ready (drishti:report-ready) and keeps `drishtiBi.reportView`: the report, the page shown and the state of the screen (slicers, selections, drill path,
   carried values, dates). A download is a GET of /dist/bi/<report>.<format> with that state in the address; the server makes the file as the signed-in
   reader (their rows, their masks) and the browser saves it. Subscribe opens the form with the same target and state. No data passes through this file. */
(function () {
  'use strict';
  const canvas = document.getElementById('biCanvas');
  if (!canvas || canvas.getAttribute('data-bi-page') !== 'report') { return; }
  const B = window.drishtiBi;
  const box = canvas.querySelector('[data-export]');
  if (!box) { return; }
  const scope = box.querySelector('[data-export-scope]');
  const LIMIT = 15000;                                           // characters of state an address may carry (the console refuses more)

  function fillScope() {
    const rv = B.reportView;
    const keep = scope.value;
    while (scope.options.length > 1) { scope.remove(1); }
    rv.visuals().forEach((v) => {
      const o = document.createElement('option');
      o.value = v.id;
      o.textContent = 'visual: ' + (v.title || v.id);
      scope.appendChild(o);
    });
    scope.value = Array.prototype.some.call(scope.options, (o) => o.value === keep) ? keep : '';
  }

  function query() {
    const rv = B.reportView;
    const p = new URLSearchParams();
    p.set('page', rv.page());
    if (scope.value) { p.set('visual', scope.value); }
    const st = JSON.stringify(rv.state());
    if (st.length <= LIMIT) { p.set('state', st); }
    return p;
  }

  box.querySelectorAll('[data-export-fmt]').forEach((a) => {
    a.addEventListener('click', (e) => {
      e.preventDefault();
      window.location.assign('/dist/bi/' + B.id2path(B.reportView.id) + '.' + a.getAttribute('data-export-fmt') + '?' + query().toString());
    });
  });
  const sub = box.querySelector('[data-subscribe]');
  sub.addEventListener('click', (e) => {
    e.preventDefault();
    const p = query();
    p.set('type', 'report');
    p.set('id', B.reportView.id);
    p.set('title', B.reportView.title);
    window.location.assign('/reports/subscribe?' + p.toString());
  });

  /** Export and Subscribe appear only when they work for this person; when they do not, a line says why (the administrator's switches, or the person's own limit). */
  function entitlements() {
    const why = box.querySelector('[data-export-why]'), says = [];
    return fetch('/dist/status', { credentials: 'same-origin', headers: { Accept: 'application/json' } }).then((r) => (r.ok ? r.json() : null)).then((st) => {
      if (!st) { return; }
      const fmts = box.querySelectorAll('[data-export-fmt]');
      if (!st.export.enabled) {
        fmts.forEach((a) => { a.hidden = true; });
        scope.closest('label').hidden = true;
        says.push('Export is switched off on this server (drishti.distribution.export.enabled).');
      }
      if (!st.subscriptions.enabled) {
        sub.hidden = true;
        says.push('Subscriptions are switched off on this server (drishti.distribution.subscriptions.enabled).');
      } else if (st.subscriptions.mine >= st.subscriptions.perUser) {
        sub.hidden = true;
        says.push('You own ' + st.subscriptions.mine + ' subscriptions, the most allowed (' + st.subscriptions.perUser + '): delete one under My subscriptions to add another.');
      }
      why.textContent = says.join(' ');
      why.hidden = !says.length;
    }).catch(() => {});
  }

  document.addEventListener('drishti:report-ready', () => { box.hidden = false; fillScope(); entitlements(); });
  document.addEventListener('drishti:report-page', () => { if (B.reportView) { fillScope(); } });
})();
