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
/* Export and subscribe on a BI report page (BI_DESIGN.md › Rūpaka: the original design and every phase as built, section 15). The controls sit in the report bar (report.html); bi-report.js says when the report is
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

  /** The address query of the screen's state; `dropped` is set when the state was too long to carry, so the file or the subscription starts without the filters. */
  function query() {
    const rv = B.reportView;
    const p = new URLSearchParams();
    p.set('page', rv.page());
    if (scope.value) { p.set('visual', scope.value); }
    const st = JSON.stringify(rv.state());
    if (st.length <= LIMIT) { p.set('state', st); } else { p.dropped = true; }
    return p;
  }
  /** The file name of a Content-Disposition: `filename*=UTF-8''..` or a plain or RFC 2047 (`=?UTF-8?Q?..?=`) `filename=`; empty when none. */
  function fileName(cd) {
    let m = /filename\*=(?:UTF-8'')?([^;]+)/i.exec(cd);
    if (m) { try { return decodeURIComponent(m[1].trim()); } catch (e) { return m[1].trim(); } }
    m = /filename="?([^";]+)"?/i.exec(cd);
    if (!m) { return ''; }
    const q = /^=\?utf-8\?q\?(.*)\?=$/i.exec(m[1]);
    if (!q) { return m[1]; }
    const bytes = q[1].replace(/_/g, ' ').replace(/=([0-9A-F]{2})/gi, (all, hex) => '%' + hex);
    try { return decodeURIComponent(bytes); } catch (e) { return bytes; }
  }
  /** One status line for the whole export bar: saying something beside one control clears what was said beside the others. */
  function tell(anchor, text, bad) {
    box.querySelectorAll('.bi-near').forEach((n) => { if (n.previousElementSibling !== anchor) { n.textContent = ''; } });
    return B.near(anchor, text, bad);
  }
  const DROPPED = ' The filters on the screen were too long to carry, so this does not include them.';

  box.querySelectorAll('[data-export-fmt]').forEach((a) => {
    a.addEventListener('click', (e) => {
      e.preventDefault();
      const fmt = a.getAttribute('data-export-fmt'), p = query();
      tell(a, 'Preparing the ' + fmt.toUpperCase() + '...', false);
      // fetched, not navigated to, so a refusal (switched off, over a limit, a visual that cannot be drawn) is said beside the button instead of replacing the report
      fetch('/dist/bi/' + B.id2path(B.reportView.id) + '.' + fmt + '?' + p.toString(), { credentials: 'same-origin' }).then((r) => {
        if (!r.ok) { return r.json().catch(() => ({})).then((d) => { throw new Error((d.code ? d.code + ' ' : '') + (d.detail || d.title || r.statusText)); }); }
        return r.blob().then((blob) => ({ blob, name: fileName(r.headers.get('Content-Disposition') || '') || (B.reportView.id.replace(/[^\w.-]+/g, '-') + '.' + fmt) }));
      }).then(({ blob, name }) => {
        const url = URL.createObjectURL(blob), dl = document.createElement('a');
        dl.href = url; dl.download = name; document.body.appendChild(dl); dl.click(); dl.remove();
        window.setTimeout(() => URL.revokeObjectURL(url), 10000);
        tell(a, 'Downloaded ' + name + '.' + (p.dropped ? DROPPED : ''), false);
      }).catch((err) => tell(a, err.message, true));
    });
  });
  const sub = box.querySelector('[data-subscribe]');
  sub.addEventListener('click', (e) => {
    e.preventDefault();
    const p = query();
    p.set('type', 'report');
    p.set('id', B.reportView.id);
    p.set('title', B.reportView.title);
    tell(sub, 'Opening the subscription form...' + (p.dropped ? DROPPED : ''), false);
    window.setTimeout(() => window.location.assign('/reports/subscribe?' + p.toString()), p.dropped ? 1800 : 0);
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
