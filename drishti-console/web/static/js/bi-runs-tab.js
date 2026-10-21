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
/* The workspace's tabs (Items · Runs · Members, ?tab=) and the Runs tab (docs/architecture/BI_RUNS.md section 7): the list with its filters, rows that change as
   the runs do (/bi/api/runs/events, no polling), and the drawer of one run with every action its `can` allows (open, cancel, re-run on the same or the newest
   data, export, share, promote, keep longer, delete the result, schedule, confirm or discard). Keyboard: the tabs take the arrow keys (bi-lib.js), the rows
   ↑ ↓ and Enter, the drawer Esc. Uses drishtiBi.runs (bi-runs.js) for the words. */
(function () {
  'use strict';
  const B = window.drishtiBi, R = B && B.runs;
  const canvas = document.getElementById('biCanvas');
  if (!R || !canvas || !document.querySelector('[data-ws-tabs]')) { return; }
  const h = B.h, q = B.q, qa = B.qa;
  const workspace = canvas.getAttribute('data-workspace') || 'me';
  const params = new URLSearchParams(window.location.search);

  // ---- the tabs ---------------------------------------------------------------------------------------------------------------------------------------
  const tabs = qa('[data-ws-tab]'), panels = qa('[data-ws-panel]');
  let runsStarted = false;
  function show(name, push) {
    const tab = tabs.find((t) => t.getAttribute('data-ws-tab') === name && !t.hidden) || tabs[0];
    name = tab.getAttribute('data-ws-tab');
    tabs.forEach((t) => { const on = t === tab; t.setAttribute('aria-selected', on ? 'true' : 'false'); t.tabIndex = on ? 0 : -1; });
    panels.forEach((p) => { p.hidden = p.getAttribute('data-ws-panel') !== name; });
    if (push) {
      const u = new URL(window.location.href);
      u.searchParams.set('tab', name);
      if (name !== 'runs') { u.searchParams.delete('run'); }
      window.history.replaceState(null, '', u.pathname + u.search);
    }
    if (name === 'runs' && !runsStarted) { runsStarted = true; startRuns(); }
  }
  tabs.forEach((t) => t.addEventListener('click', (e) => { e.preventDefault(); show(t.getAttribute('data-ws-tab'), true); }));
  // Members is a tab only where the workspace has members (bi.js shows its section for a shared workspace)
  const members = q('[data-members-section]'), membersTab = q('[data-ws-tab="members"]');
  const syncMembers = () => { membersTab.hidden = members.hidden; if (params.get('tab') === 'members' && !members.hidden && membersTab.getAttribute('aria-selected') !== 'true') { show('members'); } };
  new MutationObserver(syncMembers).observe(members, { attributes: true, attributeFilter: ['hidden'] });
  syncMembers();

  // ---- the list ---------------------------------------------------------------------------------------------------------------------------------------
  const tbody = q('[data-runs-table] tbody'), form = q('[data-runs-filters]'), sayBox = q('[data-runs-say]');
  const byId = {};
  let next = null;
  const say = (text) => { sayBox.textContent = ''; window.setTimeout(() => { sayBox.textContent = text; }, 30); };

  function query(after) {
    const f = form.elements, p = new URLSearchParams();
    const who = f.who.value;
    p.set('mine', who === 'shared' ? 'false' : 'true');
    p.set('shared', who === 'mine' ? 'false' : 'true');
    if (workspace !== 'me') { p.set('workspace', workspace); }
    ['state', 'source', 'q', 'since'].forEach((k) => { if (f[k].value) { p.set(k, f[k].value); } });
    p.set('limit', '50');
    if (after) { p.set('after', after); }
    return '/runs?' + p.toString();
  }
  function load(more) {
    return B.api('GET', query(more ? next : null)).then((d) => {
      if (!more) { B.clear(tbody); Object.keys(byId).forEach((k) => { delete byId[k]; }); }
      (d.runs || []).forEach((r) => { byId[r.id] = r; tbody.appendChild(rowsOf(r)); });
      next = d.next || null;
      q('[data-runs-more]').hidden = !next;
      q('[data-runs-empty]').hidden = !!tbody.children.length;
      const st = d.storage || {};
      const failed = (d.nodes && d.nodes.failed) || [];
      q('[data-runs-storage]').textContent = (st.bytes != null ? 'Storage: ' + R.bytes(st.bytes) + ' of your results' + (st.results != null ? ' (' + st.results + ')' : '') + ' · ' : '')
        + 'kept ' + q('[data-runs-retention]').textContent + ' days unless promoted' + (failed.length ? ' · not reachable: ' + failed.join(', ') + ' (their runs\' state is unknown until they return)' : '');
      countTab();
    }).catch((e) => { B.clear(tbody); q('[data-runs-empty]').hidden = false; q('[data-runs-empty]').textContent = 'The runs could not be listed: ' + R.words(e.code, e.detail); });
  }

  function td(text, attrs) { return h('td', Object.assign({ text: text == null ? '' : String(text) }, attrs || {})); }
  /** One run as its row (and, for a failure, the row under it with the reason in words). */
  function rowsOf(r) {
    const st = R.stateOf(r.state), est = r.estimate || {}, act = r.actual || {};
    const shared = r.shared && r.shared.from;
    const q1 = h('th', { scope: 'row' },
      h('span', { class: 'bi-run-q', text: r.title || '(untitled)' }),
      shared ? h('span', { class: 'bi-run-shared', title: 'Shared with you by ' + shared, text: ' ⇄ shared by ' + shared }) : null,
      h('small', { class: 'bi-run-src', text: R.sourceLine(r) }));
    const status = h('td', { class: 'bi-run-status bi-run-' + r.state },
      h('span', { text: st.label + (r.reuse ? ' ⇄' : '') }),
      R.active(r.state) && r.progress != null ? h('small', { text: Math.round(r.progress * 100) + ' %' }) : null);
    const data = td((r.data && r.data.words) || '–', { title: r.data && r.data.tables ? r.data.tables.map((t) => t.table + ' ' + t.versions).join('\n') : '' });
    const took = R.took(r);
    const estTip = est.seconds ? 'estimated ' + R.range(est).replace(/^about /, '') + (R.finished(r.state) && took != null ? ', took ' + R.duration(took) : '') : '';
    const open = h('button', { type: 'button', class: 'btn-pill btn-ghost bi-run-more', 'aria-label': 'Open the run ' + (r.title || r.id), 'data-run-open': r.id, text: '⋯' });
    const tr = h('tr', { 'data-run-row': r.id, 'data-run-state': r.state, tabindex: '0', class: 'bi-run-row' },
      h('td', { class: 'bi-run-glyph bi-run-' + r.state, 'aria-label': st.label, text: st.glyph }), q1, status,
      td(R.asOfShort(r) || '–', { title: R.asOf(r) }), data, td(took != null ? R.duration(took) : '–'), td(act.rowsOut != null ? R.count(act.rowsOut) : '–', { class: 'num' }),
      td(R.shortRange(est) || '–', { title: estTip }), h('td', {}, open));
    const frag = document.createDocumentFragment();
    frag.appendChild(tr);
    if (r.state === 'failed' || (r.state === 'cancelled' && r.reason)) {
      frag.appendChild(h('tr', { class: 'bi-run-whyrow', 'data-run-why-of': r.id }, h('td', {}), h('td', { colspan: '8', class: 'bi-run-bad', text: R.line(r) })));
    }
    return frag;
  }
  function replaceRow(r) {
    const old = tbody.querySelector('[data-run-row="' + CSS.escape(r.id) + '"]');
    const why = tbody.querySelector('[data-run-why-of="' + CSS.escape(r.id) + '"]');
    const focused = old && old.contains(document.activeElement);
    const frag = rowsOf(r);
    const first = frag.firstChild;
    if (old) { old.replaceWith(frag); } else { tbody.insertBefore(frag, tbody.firstChild); q('[data-runs-empty]').hidden = true; }
    if (why) { why.remove(); }
    if (focused) { first.focus(); }
  }
  function countTab() {
    const n = Object.keys(byId).filter((k) => R.active(byId[k].state)).length;
    [q('[data-runs-tab-count]'), q('[data-runs-count]')].forEach((b) => { if (b) { b.textContent = String(n); b.hidden = n === 0; } });
  }

  // live rows: an event changes its row at once; a run that ends is asked again for its whole record (its `can`, its actuals); a new run reloads the list
  let reloadT = null;
  function onEvent(d) {
    if (d.gone) { q('[data-runs-live-state]').textContent = 'not live'; q('[data-runs-live-state]').title = 'The live stream stopped: reload the page to follow the runs again.'; return; }
    if (d.hello || !d.id) { return; }
    const had = byId[d.id];
    if (!had) { if (!reloadT) { reloadT = window.setTimeout(() => { reloadT = null; load(); }, 400); } return; }
    const was = had.state;
    const r = Object.assign({}, had, d, { actual: Object.assign({}, had.actual, d.rowsOut != null ? { rowsOut: d.rowsOut } : {}) });
    byId[d.id] = r;
    replaceRow(r);
    if (was !== r.state) {
      say((r.title || r.id) + ': ' + R.line(r));
      if (!R.active(r.state)) {
        B.api('GET', '/runs/' + encodeURIComponent(r.id)).then((full) => { byId[full.id] = full; replaceRow(full); if (openId === full.id) { drawDrawer(full); } }).catch(() => {});
      }
    }
    if (openId === r.id) { drawDrawer(r); }
    countTab();
  }

  function startRuns() {
    R.settings().then((s) => { q('[data-runs-retention]').textContent = String(s.retentionDays || 30); });
    let t = null;
    form.addEventListener('input', () => { window.clearTimeout(t); t = window.setTimeout(() => load(), 250); });
    form.addEventListener('submit', (e) => { e.preventDefault(); load(); });
    q('[data-runs-more]').addEventListener('click', () => load(true));
    tbody.addEventListener('click', (e) => {
      const b = e.target.closest('[data-run-open]');
      const row = e.target.closest('[data-run-row]');
      if (b || (row && !e.target.closest('a,button'))) { openDrawer((b || row).getAttribute(b ? 'data-run-open' : 'data-run-row'), row); }
    });
    tbody.addEventListener('keydown', (e) => {
      const row = e.target.closest('[data-run-row]');
      if (!row || e.target !== row) { return; }
      const rows = qa('[data-run-row]', tbody), i = rows.indexOf(row);
      if (e.key === 'ArrowDown' && rows[i + 1]) { e.preventDefault(); rows[i + 1].focus(); } else if (e.key === 'ArrowUp' && rows[i - 1]) { e.preventDefault(); rows[i - 1].focus(); } else if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); openDrawer(row.getAttribute('data-run-row'), row); }
    });
    R.listen(onEvent);
    load().then(() => { const id = params.get('run'); if (id) { openDrawer(id, null); } });
  }

  // ---- the drawer of one run (section 7.3) ------------------------------------------------------------------------------------------------------------
  const drawer = q('[data-run-drawer]'), dBody = q('[data-run-drawer-body]');
  let openId = null, returnTo = null;
  function openDrawer(id, from) {
    returnTo = from || document.activeElement;
    openId = id;
    drawer.hidden = false;
    drawer.classList.add('bi-run-drawer-open');
    const known = byId[id];
    if (known) { drawDrawer(known); }
    B.api('GET', '/runs/' + encodeURIComponent(id)).then((r) => { if (openId === id) { byId[id] = r; drawDrawer(r); } })
      .catch((e) => { if (openId === id && !known) { q('[data-run-drawer-title]').textContent = 'Run'; B.clear(dBody).appendChild(h('p', { class: 'bi-run-bad', role: 'alert', text: R.words(e.code, e.detail) })); } });
    window.setTimeout(() => { const f = drawer.querySelector('[data-run-drawer-close]'); if (f) { f.focus(); } }, 0);
  }
  function closeDrawer() {
    drawer.hidden = true;
    drawer.classList.remove('bi-run-drawer-open');
    openId = null;
    const u = new URL(window.location.href);
    if (u.searchParams.has('run')) { u.searchParams.delete('run'); window.history.replaceState(null, '', u.pathname + u.search); }
    if (returnTo && returnTo.isConnected) { returnTo.focus(); }
  }
  q('[data-run-drawer-close]').addEventListener('click', closeDrawer);
  drawer.addEventListener('keydown', (e) => {
    if (e.key === 'Escape') { e.preventDefault(); closeDrawer(); return; }
    if (e.key === 'Tab') {                                               // the focus stays in the drawer while it is open
      const f = qa('a[href],button:not([disabled]),select,input', drawer).filter((x) => x.offsetParent !== null);
      if (!f.length) { return; }
      if (e.shiftKey && document.activeElement === f[0]) { e.preventDefault(); f[f.length - 1].focus(); } else if (!e.shiftKey && document.activeElement === f[f.length - 1]) { e.preventDefault(); f[0].focus(); }
    }
  });

  const when = (t) => (t ? String(t).replace('T', ' ').slice(0, 16) : '');
  function fact(label, value, extra) { return [h('dt', { text: label }), h('dd', {}, value || '—', extra || null)]; }
  function act(text, attrs, fn, accent) {
    const b = h('button', Object.assign({ type: 'button', class: 'btn-pill ' + (accent ? 'btn-accent' : 'btn-ghost'), text }, attrs || {}));
    b.addEventListener('click', (e) => fn(e.currentTarget));
    return b;
  }
  function menu(label, attrs, items) {
    const sel = h('select', Object.assign({ class: 'studio-select', 'aria-label': label }, attrs || {}), h('option', { value: '', text: label + ' ▾' }), items.map((i) => h('option', { value: i[0], text: i[1] })));
    return sel;
  }
  const said = (anchor, text, bad) => { B.near(anchor, text, bad); say(text); };
  const failed = (anchor) => (e) => said(anchor, R.words(e.code, e.detail), true);
  const refresh = (r) => { if (r && r.id) { byId[r.id] = r; replaceRow(r); if (openId === r.id) { drawDrawer(r); } } };

  function drawDrawer(r) {
    const can = r.can || {}, est = r.estimate || {}, act2 = r.actual || {}, s = r.source || {}, p = r.params || {};
    const enc = encodeURIComponent(r.id);
    q('[data-run-drawer-title]').textContent = r.title || r.id;
    B.clear(dBody);
    dBody.appendChild(h('p', { class: 'bi-run-dstate bi-run-' + r.state, 'data-run-drawer-state': r.state, role: 'status' }, h('span', { 'aria-hidden': 'true', text: R.stateOf(r.state).glyph + ' ' }), R.line(r)
      + (act2.bytesOut != null ? ' · ' + R.bytes(act2.bytesOut) : '')));
    if (R.active(r.state) && r.progress != null) {
      const fill = h('span', { class: 'bi-run-fill' });
      fill.style.width = Math.round(r.progress * 100) + '%';
      dBody.appendChild(h('span', { class: 'bi-run-bar', role: 'progressbar', 'aria-valuemin': '0', 'aria-valuemax': '100', 'aria-valuenow': String(Math.round(r.progress * 100)), 'aria-label': 'Progress' }, fill));
    }
    // the actions, as the run's `can` allows (the server decides)
    const acts = h('div', { class: 'bi-run-dacts', role: 'group', 'aria-label': 'What you can do with this run' });
    if (r.state === 'awaiting-confirm') {
      acts.appendChild(act('Run in background', { 'data-run-confirm': '' }, (b) => B.busy(b, () => B.api('POST', '/runs/' + enc + '/confirm', { confirmToken: r.confirmToken || null }).then((x) => { refresh(x.run || x); said(b, 'Confirmed: it is queued.'); }).catch(failed(b))), true));
      if (can.cancel) { acts.appendChild(act('Discard', { 'data-run-discard': '' }, (b) => B.busy(b, () => B.api('POST', '/runs/' + enc + '/cancel').then((x) => { refresh(x); said(b, 'Discarded. Nothing ran.'); }).catch(failed(b))))); }
    }
    if (can.open) { acts.appendChild(h('a', { class: 'btn-pill btn-accent', href: '/bi/run/' + enc, 'data-run-act': 'open', text: 'Open' })); }
    if (can.cancel && R.active(r.state) && r.state !== 'awaiting-confirm') {
      acts.appendChild(act('Cancel', { 'data-run-act': 'cancel' }, (b) => B.busy(b, () => B.api('POST', '/runs/' + enc + '/cancel').then((x) => { refresh(x); said(b, 'Cancelled. Everything it held is freed.'); }).catch(failed(b)))));
    }
    if (can.rerun) {
      const m = menu('Re-run', { 'data-run-act': 'rerun' }, [['same', 'on the same data'], ['latest', 'on the newest data']]);
      m.addEventListener('change', () => {
        const data = m.value;
        if (!data) { return; }
        m.disabled = true;
        B.ask('/runs/' + enc + '/rerun', { data }, 0).then((g) => {
          const nr = g.run || (g.confirm && g.confirm.run) || null;
          said(m, g.answer !== undefined ? 'Re-ran it: done at once (the result was there).' : g.confirm ? 'The re-run needs your confirmation: it is in the list, waiting for you.' : 'Re-run started' + (data === 'same' ? ' on the same data.' : ' on the newest data.'));
          load().then(() => { if (nr && nr.id) { openDrawer(nr.id, null); } });
        }).catch(failed(m)).then(() => { m.disabled = false; m.value = ''; });
      });
      acts.appendChild(m);
    }
    if (can.export) {
      const formats = [['csv', 'CSV'], ['xlsx', 'Excel'], ['parquet', 'Parquet']].concat(s.type === 'report' || s.type === 'board' ? [['pdf', 'PDF']] : []);
      const m = menu('Export', { 'data-run-act': 'export' }, formats);
      m.addEventListener('change', () => { const f = m.value; m.value = ''; if (f) { exportRun(r, f, m); } });
      acts.appendChild(m);
    }
    if (can.promote) {
      const m = menu('Promote', { 'data-run-act': 'promote' }, [['snapshot', 'to a dataset snapshot'], ['report', 'to a report']]);
      m.addEventListener('change', () => { const as = m.value; m.value = ''; if (as) { promote(r, as, m); } });
      acts.appendChild(m);
    }
    dBody.appendChild(acts);

    // what it is
    const filters = [].concat(p.filters || []).map((f) => f.field + ' ' + (f.op === '!=' ? '≠' : f.op || 'in') + ' ' + [].concat(f.values || []).join(', '));
    Object.keys(p.slicers || {}).forEach((k) => { filters.unshift(k + ' in (' + [].concat(p.slicers[k]).join(', ') + ')'); });
    const parms = Object.keys(p.params || {}).map((k) => k + ' = ' + p.params[k]);
    const src = s.type === 'report' || s.type === 'board' ? 'Report "' + (s.title || s.id) + '"' + (s.page ? ', page ' + s.page : '') + (s.visual ? ', visual "' + (r.title || s.visual) + '"' : '') : (s.type || '') + (s.id ? ' ' + s.id : '');
    const facts = h('dl', { class: 'bi-run-facts' },
      fact('Source', src, s.href ? h('a', { href: s.href, class: 'bi-run-link', text: ' (open the report)' }) : null),
      fact('Run by', r.runner + (r.owner && r.owner !== r.runner ? ' for ' + r.owner : '') + ', computed as ' + r.runner + ' · ' + when(r.submittedAt) + (r.node ? ' on node ' + r.node : '') + (r.attempt > 1 ? ' · attempt ' + r.attempt : '')),
      fact('Filters', filters.join(' · ')),
      fact('Parameters', parms.join(' · ')),
      fact('As of', R.asOf(r)),
      fact('Data', (r.data && r.data.words) || '', r.data && r.data.tables && r.data.tables.length ? h('details', { class: 'bi-run-versions' }, h('summary', { text: 'all versions' }),
        h('ul', {}, r.data.tables.map((t) => h('li', { text: t.table + (t.connector ? ' (' + t.connector + ')' : '') + ': ' + t.versions })))) : null),
      fact('Estimate', (R.range(est) || '') + (est.readRows != null ? ', ' + R.count(est.readRows) + ' rows read' : '') + (est.basis ? ' (' + R.basis(est.basis) + ')' : '')
        + (R.finished(r.state) && act2.seconds != null ? ' · actual ' + R.duration(act2.seconds) + (act2.rowsRead != null ? ', ' + R.count(act2.rowsRead) + ' rows' : '') : '')),
      est.why ? fact('Why', est.why) : null,
      fact('Tier', [R.TIERS[r.tier] || r.tier, r.engine ? r.engine + ' engine' : '', r['class'] || ''].filter(Boolean).join(' · ')),
      r.reuse ? fact('Reused', 'the result of ' + r.reuse.of + (r.reuse.mode === 're-masked' ? ', with your masks applied' : '')) : null,
      r.rerunOf ? fact('Re-run of', r.rerunOf) : null,
      r.promotedTo ? fact('Promoted to', String(r.promotedTo.id || r.promotedTo)) : null);
    dBody.appendChild(facts);

    // kept until · keep longer · delete the result
    const keepRow = h('div', { class: 'bi-run-drow' }, h('strong', { text: 'Kept until ' }), h('span', { 'data-run-expires': '', text: r.expiresAt ? when(r.expiresAt).slice(0, 10) : R.active(r.state) ? 'it finishes, then ' + q('[data-runs-retention]').textContent + ' days' : 'kept (promoted or pinned)' }));
    if (can.keep) {
      const m = menu('Keep longer', { 'data-run-act': 'keep' }, [['90', '90 days'], ['180', '180 days'], ['365', 'a year']]);
      m.addEventListener('change', () => { const days = Number(m.value); m.value = ''; if (days) { B.api('POST', '/runs/' + enc + '/keep', { days }).then((x) => { refresh(x); said(m, 'Kept for ' + days + ' days.'); }).catch(failed(m)); } });
      keepRow.appendChild(m);
    }
    if (can['delete']) {
      keepRow.appendChild(act('Delete result', { 'data-run-act': 'delete', class: 'btn-pill btn-ghost bi-danger' }, (b) => B.confirm(b, 'Delete this result now? The run stays listed, as expired.', 'Delete result').then((yes) => {
        if (yes) { B.api('DELETE', '/runs/' + enc + '/result').then(() => { said(b, 'Result deleted.'); return B.api('GET', '/runs/' + enc).then(refresh); }).catch(failed(b)); }
      })));
    }
    dBody.appendChild(keepRow);

    // shared with
    const sh = (r.shared && r.shared.with) || [];
    const MODE = { same: 'sees your result (same rights)', 're-masked': 're-masked: their masks applied', recomputed: 'their own recomputation' };
    const shareRow = h('div', { class: 'bi-run-drow' }, h('strong', { text: 'Shared with ' }), sh.length ? null : h('span', { text: '—' }));
    const list = h('ul', { class: 'bi-run-shares' }, sh.map((x) => h('li', { 'data-run-share': x.recipient }, x.recipient + ' (' + (MODE[x.mode] || x.mode) + ') ',
      can.share ? act('Remove', { 'aria-label': 'Stop sharing with ' + x.recipient }, (b) => B.api('DELETE', '/runs/' + enc + '/share/' + encodeURIComponent(x.recipient)).then(() => { said(b, 'No longer shared with ' + x.recipient + '.'); return B.api('GET', '/runs/' + enc).then(refresh); }).catch(failed(b))) : null)));
    if (sh.length) { shareRow.appendChild(list); }
    if (can.share) { shareRow.appendChild(act('Share…', { 'data-run-act': 'share' }, (b) => shareForm(r, b))); }
    dBody.appendChild(shareRow);

    // schedule
    const schedRow = h('div', { class: 'bi-run-drow' }, h('strong', { text: 'Schedule ' }), h('span', { text: r.schedule ? r.schedule : '—' }));
    if (can.schedule) { schedRow.appendChild(act('Schedule…', { 'data-run-act': 'schedule' }, (b) => scheduleForm(r, b))); }
    dBody.appendChild(schedRow);
    if (r.shared && r.shared.from) { dBody.appendChild(h('p', { class: 'bi-run-note', text: 'Shared with you by ' + r.shared.from + '. Masks hold: you see what your own rights allow.' })); }
  }

  function exportRun(r, fmt, anchor) {
    said(anchor, 'Preparing the ' + fmt.toUpperCase() + ' file…', false);
    fetch('/bi/api/runs/' + encodeURIComponent(r.id) + '/export', { method: 'POST', credentials: 'same-origin', headers: { 'Content-Type': 'application/json', Accept: '*/*' }, body: JSON.stringify({ format: fmt }) })
      .then((res) => {
        if (!res.ok) { return res.json().catch(() => ({})).then((d) => { throw Object.assign(new Error(d.detail || res.statusText), { code: d.code, detail: d.detail || res.statusText }); }); }
        const cd = res.headers.get('Content-Disposition') || '', m = /filename="?([^";]+)"?/.exec(cd);
        return res.blob().then((blob) => {
          const a = h('a', { href: URL.createObjectURL(blob), download: m ? m[1] : (B.slug(r.title || r.id) + '.' + fmt) });
          document.body.appendChild(a); a.click(); a.remove();
          window.setTimeout(() => URL.revokeObjectURL(a.href), 4000);
          said(anchor, 'Downloaded ' + (m ? m[1] : fmt.toUpperCase()) + '. Masked cells stay masked.', false);
        });
      }).catch(failed(anchor));
  }

  function inlineForm(anchor, label, fields, okText, onOk) {
    const old = drawer.querySelector('[data-run-form]');
    if (old) { old.remove(); }
    const f = h('form', { class: 'bi-ask bi-run-form', 'data-run-form': '', 'aria-label': label }, fields, h('button', { type: 'submit', class: 'btn-pill btn-accent', text: okText }),
      h('button', { type: 'button', class: 'btn-pill btn-ghost', 'data-run-form-cancel': '', text: 'Cancel' }));
    f.addEventListener('submit', (e) => { e.preventDefault(); onOk(f, f.querySelector('[type=submit]')); });
    f.querySelector('[data-run-form-cancel]').addEventListener('click', () => { f.remove(); anchor.focus(); });
    f.addEventListener('keydown', (e) => { if (e.key === 'Escape') { e.preventDefault(); e.stopPropagation(); f.remove(); anchor.focus(); } });
    anchor.after(f);
    const first = f.querySelector('input,select');
    if (first) { first.focus(); }
    return f;
  }
  function shareForm(r, anchor) {
    inlineForm(anchor, 'Share this run', [h('label', {}, 'With (names or role:<name>, comma separated) ', h('input', { name: 'to', class: 'studio-in', required: true, autocomplete: 'off', placeholder: 'bob, role:risk-team' }))], 'Share', (f, b) => {
      const to = f.elements.to.value.split(',').map((x) => x.trim()).filter(Boolean);
      B.busy(b, () => B.api('POST', '/runs/' + encodeURIComponent(r.id) + '/share', { to }).then((d) => {
        const words = (d.shares || []).map((x) => x.recipient + ': ' + ({ same: 'sees your result', 're-masked': 'sees it with their masks', recomputed: 'gets their own run' }[x.mode] || x.mode)).join('; ');
        f.remove(); said(anchor, 'Shared. ' + words + '.');
        return B.api('GET', '/runs/' + encodeURIComponent(r.id)).then(refresh);
      }).catch(failed(b)));
    });
  }
  function scheduleForm(r, anchor) {
    inlineForm(anchor, 'Schedule this run', [h('label', {}, 'When ', h('input', { name: 'schedule', class: 'studio-in', required: true, value: 'business-days 07:30', autocomplete: 'off', 'aria-describedby': 'runSchedHint' })),
      h('label', {}, 'On ', h('select', { name: 'data', class: 'studio-select' }, h('option', { value: 'latest', text: 'the newest data' }), h('option', { value: 'same', text: 'the same dates' }))),
      h('small', { id: 'runSchedHint', class: 'text-muted-d', text: 'daily 07:00 · business-days 07:30 · monthly last 18:00 business · after-load TRD. Each firing is a background run and a notice.' })], 'Schedule', (f, b) => {
      B.busy(b, () => B.api('POST', '/runs/' + encodeURIComponent(r.id) + '/schedule', { schedule: f.elements.schedule.value.trim(), data: f.elements.data.value }).then((s) => {
        f.remove(); said(anchor, 'Scheduled: ' + (s.schedule || f.elements.schedule.value) + (s.nextAt ? ', next ' + when(s.nextAt) : '') + '.');
      }).catch(failed(b)));
    });
  }
  function promote(r, as, anchor) {
    const name = B.slug(r.title || 'run') || 'run';
    inlineForm(anchor, as === 'snapshot' ? 'Promote to a dataset snapshot' : 'Promote to a report', [h('label', {}, 'Name ', h('input', { name: 'name', class: 'studio-in', required: true, value: name, autocomplete: 'off', pattern: '[A-Za-z0-9_.-]+' }))], 'Promote', (f, b) => {
      B.busy(b, () => B.api('POST', '/runs/' + encodeURIComponent(r.id) + '/promote', { as, name: f.elements.name.value.trim() }).then((d) => {
        f.remove();
        const it = d.item || {};
        said(anchor, (as === 'snapshot' ? 'Snapshot ' + ((d.snapshot && d.snapshot.name) || '') + (d.snapshot && d.snapshot.version ? ' v' + d.snapshot.version : '') + ' made as a draft dataset' : 'Draft report made') + '. Review it before you publish.');
        if (it.id) { anchor.parentNode.appendChild(h('a', { href: '/bi/i/' + B.id2path(it.id), class: 'btn-pill btn-ghost', text: 'Open the draft' })); }
      }).catch(failed(b)));
    });
  }

  show(params.get('tab') || 'items');                                   // last: the Runs tab's list and drawer above are ready
})();
