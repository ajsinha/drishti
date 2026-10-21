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
   the runs do (the live channel's biruns subscription, no polling), and the drawer of one run with every action its `can` allows (open, cancel, re-run on the same or the newest
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
  qa('[data-ws-goto]').forEach((a) => a.addEventListener('click', (e) => { e.preventDefault(); show(a.getAttribute('data-ws-goto'), true); const t = q('[data-ws-tab="' + a.getAttribute('data-ws-goto') + '"]'); if (t) { t.focus(); } }));
  // Members is a tab only where the workspace has members (bi.js shows its section for a shared workspace)
  const members = q('[data-members-section]'), membersTab = q('[data-ws-tab="members"]');
  const syncMembers = () => { membersTab.hidden = members.hidden; if (params.get('tab') === 'members' && !members.hidden && membersTab.getAttribute('aria-selected') !== 'true') { show('members'); } };
  new MutationObserver(syncMembers).observe(members, { attributes: true, attributeFilter: ['hidden'] });
  syncMembers();

  // ---- the list ---------------------------------------------------------------------------------------------------------------------------------------
  const tbody = q('[data-runs-table] tbody'), form = q('[data-runs-filters]'), sayBox = q('[data-runs-say]');
  const byId = {};
  const fresh = new Set();                      // the runs a "Run again" made on this page: highlighted at the top of the list
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
      h('span', { class: 'bi-run-q', text: R.titleOf(r) || '(untitled)' }),
      shared ? h('span', { class: 'bi-run-shared', title: 'Shared with you by ' + shared, text: ' ⇄ shared by ' + shared }) : null,
      supersededBy(r),
      h('small', { class: 'bi-run-src', text: R.sourceLine(r) }));
    const status = h('td', { class: 'bi-run-status bi-run-' + r.state },
      h('span', { text: st.label + (r.reuse ? ' ⇄' : '') }),
      R.active(r.state) && r.progress != null ? h('small', { text: Math.round(r.progress * 100) + ' %' }) : null);
    const tables = r.data && Array.isArray(r.data.tables) ? r.data.tables : [];
    const data = td(R.dataWords(r) || '–', { title: tables.length ? tables.map((t) => t.table + ' ' + t.versions).join('\n') : 'The server did not say which data versions this run read' });
    const took = R.took(r);
    const estTip = est.seconds ? 'estimated ' + R.range(est).replace(/^about /, '') + (R.finished(r.state) && took != null ? ', took ' + R.duration(took) : '') : '';
    const open = h('button', { type: 'button', class: 'btn-pill btn-ghost bi-run-more', 'aria-label': 'Open the run ' + R.titleOf(r), 'data-run-open': r.id, text: '⋯' });
    const tr = h('tr', { 'data-run-row': r.id, 'data-run-state': r.state, tabindex: '0', class: 'bi-run-row' + (fresh.has(r.id) ? ' bi-run-new' : '') },
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
  /** The run that a "Run again" of this one made (the newest), as a link that opens it: "superseded by <run>". */
  function supersededBy(r) {
    const by = Object.keys(byId).map((k) => byId[k]).filter((x) => x.rerunOf === r.id).sort((a, b) => String(b.submittedAt || '').localeCompare(String(a.submittedAt || '')))[0];
    if (!by) { return null; }
    const a = h('a', { href: '?tab=runs&run=' + encodeURIComponent(by.id), class: 'bi-run-link', 'data-run-goto': by.id, text: R.titleOf(by) + ' (' + R.stateOf(by.state).label.toLowerCase() + ', ' + when(by.submittedAt).slice(11) + ')' });
    a.addEventListener('click', (e) => { e.preventDefault(); e.stopPropagation(); openDrawer(by.id, a); });
    return h('small', { class: 'bi-run-superseded', 'data-run-superseded': by.id }, 'superseded by ', a);
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
      say(R.titleOf(r) + ': ' + R.line(r));
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
    if (note && note.id !== id) { note = null; }
    returnTo = from || document.activeElement;
    openId = id;
    drawer.hidden = false;
    drawer.classList.add('bi-run-drawer-open');
    const known = byId[id];
    if (known) { drawDrawer(known); }
    window.setTimeout(() => { const f = drawer.querySelector('[data-run-drawer-close]'); if (f) { f.focus(); } }, 0);
    if (known && known.atOnce) { return; }                            // answered at once: the server has no row to ask for
    B.api('GET', '/runs/' + encodeURIComponent(id)).then((r) => { if (openId === id) { byId[id] = r; drawDrawer(r); } })
      .catch((e) => { if (openId === id && !known) { q('[data-run-drawer-title]').textContent = 'Run'; B.clear(dBody).appendChild(h('p', { class: 'bi-run-bad', role: 'alert', text: R.words(e.code, e.detail) })); } });
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
  // "Run again" (BI_RUNS.md section 7.3): a labelled choice of how, and the button that does it. Changing the choice does nothing on its own. While the new
  // run goes, the drawer shows that run with the button busy (a spinner and "Running…"); when it ends the button is itself again.
  let rerunning = null;
  function runAgain(r, busy) {
    const enc = encodeURIComponent(r.id), id = 'runAgainAs-' + String(r.id).replace(/[^A-Za-z0-9_-]/g, '');
    const asof = R.asOfShort(r);
    const sel = h('select', { class: 'studio-select', id, 'data-run-again-as': '' },
      h('option', { value: 'same', text: 'Same as before' + (asof ? ' (as of ' + asof + ')' : '') }), h('option', { value: 'latest', text: 'With the latest data' }));
    const go = h('button', { type: 'button', class: 'btn-pill btn-accent bi-run-again-go', 'data-run-act': 'rerun' });
    const label = (on) => {
      B.clear(go);
      go.disabled = on; sel.disabled = on;
      go.setAttribute('aria-busy', on ? 'true' : 'false');
      if (on) { go.appendChild(h('span', { class: 'bi-spin', 'data-run-spinner': '', 'aria-hidden': 'true' })); }
      go.appendChild(document.createTextNode(on ? 'Running…' : 'Run again'));
    };
    label(busy);
    go.addEventListener('click', () => {
      const data = sel.value;
      label(true);
      B.ask('/runs/' + enc + '/rerun', { data }, 0).then((g) => {
        const nr = g.run || (g.confirm && g.confirm.run) || g.ranAs || null;
        if (!nr || !nr.id) { label(false); said(go, g.answer !== undefined ? 'Done at once.' : 'Run again: asked.'); return; }
        const got = byId[nr.id] = Object.assign({}, byId[nr.id] || {}, nr);
        const from = nr.reuse && nr.reuse.of ? byId[nr.reuse.of] : null;
        const reused = nr.state === 'reused' || !!nr.reuse;
        // answered inside the inline wait and computed anew (the real server's 200 {run, answer}): the server keeps no row of it (BI_RUNS.md 6)
        const atOnce = !reused && g.answer !== undefined;
        const text = atOnce ? 'Done at once: asked again ' + (data === 'same' ? 'as before' : 'with the latest data') + (nr.actual && nr.actual.rowsOut != null ? ', ' + R.count(nr.actual.rowsOut) + (nr.actual.rowsOut === 1 ? ' row' : ' rows') : '') + '.'
          : reused ?'Done at once: reused the answer from ' + (from && from.finishedAt ? when(from.finishedAt) : nr.reuse && nr.reuse.of ? nr.reuse.of : 'an earlier run') + '.'
          : g.confirm ? 'Run again needs your confirmation: it is in the list, waiting for you.' : 'Running again' + (data === 'same' ? ' as before.' : ' with the latest data.');
        if (atOnce) { got.atOnce = true; }                                   // no row on the server: its drawer is drawn from the reply only
        if (R.active(nr.state) && !g.confirm) { rerunning = nr.id; }
        note = { id: nr.id, text, bad: false, href: reused ? '/bi/run/' + encodeURIComponent(nr.id) : null };
        say(text);
        fresh.add(nr.id);
        return load().then(() => {
          // the list reloads from the server, and a run answered at once (200 {run, answer}) is not in it: its row and drawer come from the reply
          if (!byId[nr.id]) { byId[nr.id] = got; }
          const row = tbody.querySelector('[data-run-row="' + CSS.escape(nr.id) + '"]');
          if (!row) { replaceRow(byId[nr.id]); }
          if (byId[r.id]) { replaceRow(byId[r.id]); }                 // the old row says "superseded by"
          openDrawer(nr.id, tbody.querySelector('[data-run-row="' + CSS.escape(nr.id) + '"]'));
        });
      }).catch((e) => { label(false); failed(go, 'Run again')(e); });
    });
    return h('div', { class: 'bi-run-again', role: 'group', 'aria-label': 'Run again', 'data-run-again': '' },
      h('label', { for: id, text: 'Run again as' }), h('div', { class: 'bi-run-again-row' }, sel, go));
  }
  function menu(label, attrs, items) {
    const sel = h('select', Object.assign({ class: 'studio-select', 'aria-label': label }, attrs || {}), h('option', { value: '', text: label + ' ▾' }), items.map((i) => h('option', { value: i[0], text: i[1] })));
    return sel;
  }
  // what the last action said stays in the drawer (a redraw replaces the button it was said beside) until another run is opened
  let note = null;
  const noteBox = () => dBody.querySelector('[data-run-drawer-msg]');
  const said = (anchor, text, bad) => {
    if (anchor && anchor.isConnected && (anchor.closest('[data-run-form]') || !dBody.contains(anchor))) { B.near(anchor, text, bad); }
    say(text);
    note = openId ? { id: openId, text, bad: !!bad } : null;
    const box = noteBox();
    if (box && note) { box.textContent = text; box.classList.toggle('bi-run-bad', note.bad); box.hidden = false; }
  };
  // a server's problem in words; a fault of this page (an exception, no code and no detail) says its own message and goes to the console, never a bare
  // "Something went wrong."
  const failed = (anchor, what) => (e) => {
    if (e && !e.code && !e.detail) {
      if (window.console) { window.console.error((what || 'A run action') + ' failed in the page', e); }
      said(anchor, (what || 'The action') + ' failed in the page: ' + ((e && e.message) || String(e)), true);
      return;
    }
    said(anchor, R.words(e.code, e.detail), true);
  };
  const refresh = (r) => { if (r && r.id) { byId[r.id] = r; replaceRow(r); if (openId === r.id) { drawDrawer(r); } } };

  function drawDrawer(r) {
    const can = r.can || {}, est = r.estimate || {}, act2 = r.actual || {}, s = r.source || {}, p = r.params || {};
    const enc = encodeURIComponent(r.id);
    q('[data-run-drawer-title]').textContent = R.titleOf(r);
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
    if (can.rerun || (rerunning === r.id && R.active(r.state))) { acts.appendChild(runAgain(r, rerunning === r.id && R.active(r.state))); }
    if (rerunning === r.id && !R.active(r.state)) { rerunning = null; }
    const ex = R.exportButtons(r, (t, bad, b) => said(b, t, bad));          // CSV and Excel, from the stored result as the caller's own version
    if (ex) { ex.setAttribute('data-run-act', 'export'); acts.appendChild(ex); }
    if (can.promote) { acts.appendChild(act('Promote…', { 'data-run-act': 'promote' }, (b) => promote(r, b))); }
    dBody.appendChild(acts);
    const msg = h('p', { class: 'bi-run-dmsg', 'data-run-drawer-msg': '', hidden: !(note && note.id === r.id) });
    if (note && note.id === r.id) {
      msg.textContent = note.text; msg.classList.toggle('bi-run-bad', note.bad);
      if (note.href) { msg.appendChild(document.createTextNode(' ')); msg.appendChild(h('a', { class: 'btn-pill btn-ghost', href: note.href, 'data-run-open-result': '', text: 'Open result' })); }
    }
    dBody.appendChild(msg);

    // what it is
    const filters = [].concat(p.filters || []).map((f) => f.field + ' ' + (f.op === '!=' ? '≠' : f.op || 'in') + ' ' + [].concat(f.values || []).join(', '));
    Object.keys(p.slicers || {}).forEach((k) => { filters.unshift(k + ' in (' + [].concat(p.slicers[k]).join(', ') + ')'); });
    const parms = Object.keys(p.params || {}).map((k) => k + ' = ' + p.params[k]);
    const src = s.type === 'report' || s.type === 'board' ? 'Report "' + R.reportName(s) + '"' + (s.page ? ', page ' + s.page : '') + (s.visual ? ', visual "' + (r.title && r.title !== s.id ? r.title : R.nameOfId(s.visual)) + '"' : '') : (s.type || '') + (s.id ? ' ' + s.id : '');
    const facts = h('dl', { class: 'bi-run-facts' },
      fact('Source', src, s.href ? h('a', { href: s.href, class: 'bi-run-link', text: ' (open the report)' }) : null),
      fact('Run by', r.runner + (r.owner && r.owner !== r.runner ? ' for ' + r.owner : '') + ', computed as ' + r.runner + ' · ' + when(r.submittedAt) + (r.node ? ' on node ' + r.node : '') + (r.attempt > 1 ? ' · attempt ' + r.attempt : '')),
      fact('Filters', filters.join(' · ')),
      fact('Parameters', parms.join(' · ')),
      fact('As of', R.asOf(r)),
      fact('Data', R.dataWords(r), r.data && Array.isArray(r.data.tables) && r.data.tables.length ? h('details', { class: 'bi-run-versions' }, h('summary', { text: 'all versions' }),
        h('ul', {}, r.data.tables.map((t) => h('li', { text: t.table + (t.connector ? ' (' + t.connector + ')' : '') + ': ' + t.versions })))) : null),
      fact('Estimate', (R.range(est) || '') + (est.readRows != null ? ', ' + R.count(est.readRows) + ' rows read' : '') + (est.basis ? ' (' + R.basis(est.basis) + ')' : '')
        + (R.finished(r.state) && act2.seconds != null ? ' · actual ' + R.duration(act2.seconds) + (act2.rowsRead != null ? ', ' + R.count(act2.rowsRead) + ' rows' : '') : '')),
      est.why ? fact('Why', est.why) : null,
      fact('Tier', [R.TIERS[r.tier] || r.tier, r.engine ? r.engine + ' engine' : '', r['class'] || ''].filter(Boolean).join(' · ')),
      r.reuse ? fact('Reused', 'the result of ' + r.reuse.of + (r.reuse.mode === 're-masked' ? ', with your masks applied' : '')) : null,
      r.rerunOf ? fact('Re-run of', r.rerunOf) : null,
      r.promotedTo ? fact('Promoted to', String(r.promotedTo.id || r.promotedTo), promotedLinks(r)) : null);
    dBody.appendChild(facts);

    // kept until · keep longer · delete the result
    const keepRow = h('div', { class: 'bi-run-drow' }, h('strong', { text: 'Kept until ' }), h('span', { 'data-run-expires': '', text: R.keptUntil(r, Number(q('[data-runs-retention]').textContent) || 30, (t) => when(t).slice(0, 10)) || '–' }));
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

    // shared with: each recipient's mode as the server decided it (sees the same, re-masked, recomputed as them), and Unshare
    const sh = (r.shared && r.shared.with) || [];
    const shareRow = h('div', { class: 'bi-run-drow' }, h('strong', { text: 'Shared with ' }), sh.length ? null : h('span', { text: '—' }));
    const list = h('ul', { class: 'bi-run-shares' }, sh.map((x) => h('li', { 'data-run-share': x.recipient, 'data-run-share-mode': x.mode || '' },
      h('span', { class: 'bi-run-who', text: x.recipient }), ' ', h('span', { class: 'bi-run-mode bi-run-mode-' + (x.mode || 'none'), title: R.modeWhy(x.mode), text: R.mode(x.mode) }),
      x.recipientRun ? h('small', { class: 'text-muted-d', text: ' (their run ' + x.recipientRun.slice(-6) + ')' }) : null, ' ',
      can.share ? act('Unshare', { 'data-run-unshare': x.recipient, 'aria-label': 'Stop sharing with ' + x.recipient }, (b) => B.api('DELETE', '/runs/' + enc + '/share/' + encodeURIComponent(x.recipient)).then(() => { said(b, 'No longer shared with ' + x.recipient + '. A run of their own stays theirs.'); return B.api('GET', '/runs/' + enc).then(refresh); }).catch(failed(b))) : null)));
    if (sh.length) { shareRow.appendChild(list); }
    if (can.share) { shareRow.appendChild(act('Share…', { 'data-run-act': 'share' }, (b) => shareForm(r, b))); }
    dBody.appendChild(shareRow);

    // schedule: the run's own (the owner's), with its next firing; edit, pause or resume, remove
    const sc = r.schedule && typeof r.schedule === 'object' ? r.schedule : null;
    const schedRow = h('div', { class: 'bi-run-drow' }, h('strong', { text: 'Schedule ' }), h('span', { 'data-run-schedule': sc ? sc.id : '', text: sc ? R.scheduleWords(sc) : r.schedule || '—' }));
    if (can.schedule && !sc) { schedRow.appendChild(act('Schedule…', { 'data-run-act': 'schedule' }, (b) => scheduleForm(r, b, null))); }
    if (can.schedule && sc) {
      const sid = encodeURIComponent(sc.id);
      const after = (b, text) => (s2) => { said(b, text); refresh(Object.assign({}, r, { schedule: s2 || null })); };   // the answer is the schedule: no second read that could redraw over a form opened meanwhile
      schedRow.appendChild(act('Edit', { 'data-run-sched-edit': sc.id }, (b) => scheduleForm(r, b, sc)));
      schedRow.appendChild(act(sc.enabled === false ? 'Resume' : 'Pause', { 'data-run-sched-toggle': sc.id }, (b) => B.busy(b, () => B.api('PUT', '/runs/schedules/' + sid, { enabled: sc.enabled === false })
        .then((s2) => after(b, s2.enabled === false ? 'Paused: it does not fire until you resume it.' : 'Resumed' + (s2.nextAt ? ': next ' + when(s2.nextAt) + ' UTC.' : '.'))(s2)).catch(failed(b)))));
      schedRow.appendChild(act('Remove', { 'data-run-sched-remove': sc.id, class: 'btn-pill btn-ghost bi-danger' }, (b) => B.confirm(b, 'Remove this schedule? Runs it made stay listed.', 'Remove').then((yes) => {
        if (yes) { B.api('DELETE', '/runs/schedules/' + sid).then(() => after(b, 'Schedule removed.')(null)).catch(failed(b)); }
      })));
    }
    dBody.appendChild(schedRow);
    if (r.shared && r.shared.from) { dBody.appendChild(h('p', { class: 'bi-run-note', text: 'Shared with you by ' + r.shared.from + '. Masks hold: you see what your own rights allow.' })); }
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
    // the people picker (people.js, the directory the share dialog uses) adds a user or role:<name> as a chip; typing names, comma separated, works too
    const chips = h('ul', { class: 'bi-run-chips', 'data-run-share-chips': '', 'aria-label': 'Share with' });
    const picked = [];
    const addChip = (who) => {
      if (!who || picked.indexOf(who) >= 0) { return; }
      picked.push(who);
      const x = h('button', { type: 'button', class: 'bi-run-chip-x', 'aria-label': 'Remove ' + who, text: '×' });
      const li = h('li', { class: 'bi-run-chip', 'data-run-share-chip': who }, who, ' ', x);
      x.addEventListener('click', () => { picked.splice(picked.indexOf(who), 1); li.remove(); });
      chips.appendChild(li);
    };
    const input = h('input', { name: 'to', class: 'studio-in', autocomplete: 'off', placeholder: 'a name, or role:risk-team', role: 'combobox', 'aria-autocomplete': 'list', 'aria-expanded': 'false', 'aria-controls': 'runSharePick', 'aria-describedby': 'runShareHint' });
    const pick = h('ul', { id: 'runSharePick', class: 'shr-list bi-run-pick', role: 'listbox', hidden: true, 'aria-label': 'People and roles' });
    const f = inlineForm(anchor, 'Share this run', [h('span', { class: 'bi-run-picker' }, h('label', {}, 'With ', input), pick), chips,
      h('small', { id: 'runShareHint', class: 'text-muted-d', text: 'Each person gets what their own rights allow: the same result, your result re-masked for them, or the question recomputed as them. The server decides and says which.' })], 'Share', (form, b) => {
      input.value.split(',').map((x) => x.trim()).filter(Boolean).forEach(addChip);
      input.value = '';
      if (!picked.length) { said(input, 'Choose at least one person or role.', true); input.focus(); return; }
      B.busy(b, () => B.api('POST', '/runs/' + encodeURIComponent(r.id) + '/share', { to: picked.slice() }).then((d) => {
        const shares = d.shares || [];
        const ok = shares.filter((x) => x.mode), refused = shares.filter((x) => !x.mode);
        form.remove();
        said(anchor, (ok.length ? 'Shared. ' + ok.map((x) => x.recipient + ' ' + R.mode(x.mode)).join('; ') + '.' : 'Not shared.')
          + (refused.length ? ' Not shared with ' + refused.map((x) => x.recipient + ' (' + R.words(x.code, x.detail) + ')').join('; ') + '.' : ''), !ok.length);
        return B.api('GET', '/runs/' + encodeURIComponent(r.id)).then(refresh);
      }).catch(failed(b)));
    });
    if (window.DrishtiPeople) { window.DrishtiPeople.attach(input, pick, { minQuery: 1, onPick: (e) => addChip(e.type === 'role' ? 'role:' + e.name : e.name), taken: () => { const t = {}; picked.forEach((p) => { t[p.indexOf('role:') === 0 ? 'role:' + p.slice(5) : 'user:' + p] = true; }); return t; } }); }
    input.addEventListener('keydown', (e) => { if (e.key === ',' || (e.key === 'Enter' && input.value.trim() && pick.hidden)) { e.preventDefault(); addChip(input.value.replace(/,$/, '').trim()); input.value = ''; } });
    f.setAttribute('data-run-share-form', '');
  }
  function scheduleForm(r, anchor, sc) {
    // friendly presets that write the SubscriptionSchedule grammar (daily, weekdays, business-days, weekly <day>, monthly last [business]); "Written out" takes it as text
    const p = R.parseSchedule(sc ? sc.schedule : 'business-days 07:30');
    const kind = h('select', { name: 'kind', class: 'studio-select', 'aria-label': 'How often' }, R.PRESETS.map((x) => h('option', { value: x[0], text: x[1], selected: x[0] === p.kind })));
    const day = h('select', { name: 'day', class: 'studio-select', 'aria-label': 'Day of the week' }, R.DAYS.map((x) => h('option', { value: x[0], text: x[1], selected: x[0] === (p.day || 'mon') })));
    const time = h('input', { name: 'time', type: 'time', class: 'studio-in', value: p.time || '07:30', 'aria-label': 'Time (the server\'s zone)', required: true });
    const text = h('input', { name: 'text', class: 'studio-in', value: p.kind === 'custom' ? p.text : '', autocomplete: 'off', placeholder: 'monthly last 18:00 business', 'aria-label': 'The schedule, written out' });
    const data = h('select', { name: 'data', class: 'studio-select' }, h('option', { value: 'latest', text: 'the newest data', selected: !sc || sc.data !== 'same' }), h('option', { value: 'same', text: 'the same dates', selected: !!sc && sc.data === 'same' }));
    const preview = h('code', { 'data-run-sched-preview': '' });
    const sync = () => {
      const k = kind.value;
      day.hidden = k !== 'weekly';
      time.hidden = k === 'custom';
      text.hidden = k !== 'custom';
      preview.textContent = R.scheduleText({ kind: k, day: day.value, time: time.value, text: text.value }) || '…';
    };
    const f = inlineForm(anchor, sc ? 'Change the schedule' : 'Schedule this run', [h('span', { class: 'bi-run-when' }, kind, ' ', day, ' ', time, text),
      h('label', {}, 'On ', data), h('small', { class: 'text-muted-d' }, 'Writes ', preview, '. Each firing asks again as you, now: a background run and a notice.')], sc ? 'Save' : 'Schedule', (form, b) => {
      const schedule = R.scheduleText({ kind: kind.value, day: day.value, time: time.value, text: text.value });
      if (!schedule) { said(text, 'Write the schedule, for example monthly last 18:00 business.', true); text.focus(); return; }
      const call = sc ? B.api('PUT', '/runs/schedules/' + encodeURIComponent(sc.id), { schedule, data: data.value }) : B.api('POST', '/runs/' + encodeURIComponent(r.id) + '/schedule', { schedule, data: data.value });
      B.busy(b, () => call.then((s2) => {
        form.remove();
        said(anchor, (sc ? 'Schedule changed: ' : 'Scheduled: ') + R.scheduleWords(s2) + '.');
        refresh(Object.assign({}, r, { schedule: s2 }));
      }).catch(failed(b)));
    });
    [kind, day, time, text].forEach((x) => x.addEventListener('input', sync));
    kind.addEventListener('change', sync);
    sync();
    f.setAttribute('data-run-sched-form', '');
  }
  // Promote (BI_RUNS 9.4 as built): POST /runs/{id}/promote {name, title?, to: dataset|report} makes a draft dataset over a snapshot of the result (and a draft
  // report on it with `to: report`) in the runner's personal workspace. The items it made are remembered here so "Promoted to" links them after a redraw.
  const promoted = {};
  function promotedLinks(r) {
    const made = promoted[r.id];
    const link = (href, text, attr) => h('a', Object.assign({ href, class: 'bi-run-link', text }, attr));
    if (!made) { return h('span', {}, ' · a draft in ', link('/bi/w/me', 'My workspace', { 'data-run-promoted-where': '' })); }
    return h('span', {}, made.dataset ? [' · ', link('/bi/i/' + B.id2path(made.dataset), 'the draft dataset', { 'data-run-promoted-dataset': made.dataset })] : null,
      made.report ? [' · ', link('/bi/i/' + B.id2path(made.report), 'the draft report', { 'data-run-promoted-report': made.report })] : null);
  }
  function promote(r, anchor) {
    const name = B.slug(R.titleOf(r) || 'run') || 'run';
    const to = h('select', { name: 'to', class: 'studio-select', 'aria-label': 'Promote to' }, h('option', { value: 'dataset', text: 'a dataset snapshot' }), h('option', { value: 'report', text: 'a report on it (and its dataset)' }));
    inlineForm(anchor, 'Promote this result', [
      h('label', {}, 'Name ', h('input', { name: 'name', class: 'studio-in', required: true, value: name, autocomplete: 'off', pattern: '[A-Za-z0-9_.-]+', title: 'letters, digits, dot, dash or underscore' })),
      h('label', {}, 'Title ', h('input', { name: 'title', class: 'studio-in', value: R.titleOf(r), autocomplete: 'off', maxlength: '200', placeholder: 'Left empty: the run\'s title and dates' })),
      h('label', {}, 'To ', to),
      h('small', { class: 'text-muted-d', text: 'The rows are kept as you see them (masked columns stay masked for every reader). It is a draft in My workspace: review it before you publish.' })
    ], 'Promote', (f, b) => {
      const body = { name: f.elements.name.value.trim(), to: f.elements.to.value };
      if (f.elements.title.value.trim()) { body.title = f.elements.title.value.trim(); }
      B.busy(b, () => B.api('POST', '/runs/' + encodeURIComponent(r.id) + '/promote', body).then((d) => {
        f.remove();
        const ds = (d && d.dataset) || {}, rep = (d && d.report) || null;
        promoted[r.id] = { dataset: ds.item || null, report: rep && rep.item ? rep.item : null };
        said(anchor, 'Promoted: snapshot ' + (d.snapshot || body.name) + ' version ' + (d.version || 1) + ', a draft dataset' + (rep ? ' and a draft report on it' : '') + ' in My workspace. Review it before you publish.');
        return B.api('GET', '/runs/' + encodeURIComponent(r.id)).then(refresh, () => refresh(Object.assign({}, r, { promotedTo: (d.snapshot || body.name) + '@' + (d.version || 1) })));
      }).catch(failed(b)));
    });
  }

  show(params.get('tab') || 'items');                                   // last: the Runs tab's list and drawer above are ready
})();
