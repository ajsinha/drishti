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
/* New board, guided (concept C): four steps in plain words, a live preview beside them, and the result opened in the designer.
   1 What data: a source in words ("Trades of one book", "One trade"), from the packs' kinds (GET /build/board-api/sources), with a search.
   2 Pick what to show: the chips that fit that data (ui-board-suggest.js rank: the palette's "fits this data"), as checkbox cards with a line of
     purpose and what each follows; wired for you (a detail follows the table's selection, another table follows it by filter). "Browse all
     chips…" opens the Chip Store.
   3 Arrange: the order, and each chip full width or half.
   4 Save: a name, and just for you or in a workspace (PUT /ui/api/boards/<name>, If-None-Match: *); then the designer opens on it.
   The preview is the render API as the person (POST /ui/api/render through drishtiUiBoard.render), redrawn on every change. */
(function () {
  'use strict';
  const U = window.drishtiUiBoard, root = document.querySelector('[data-ubg]');
  if (!U || !U.suggest || !root) { return; }
  const S = U.suggest, h = U.h, clear = U.clear, $ = (s) => root.querySelector(s);
  const STEPS = ['data', 'show', 'arrange', 'save'];
  const st = { step: 'data', sources: [], source: null, attrs: null, chips: null, ranked: [], picked: [], spans: {}, seen: new Set(['data']) };
  const status = $('[data-ubg-status]'), preview = $('[data-ubg-preview]'), pvStatus = $('[data-ubg-preview-status]');
  const say = (t, bad) => { status.textContent = t || ''; status.classList.toggle('bi-bad', !!bad); };
  const api = (method, url, body, headers) => fetch(url, { method, credentials: 'same-origin', headers: Object.assign(body ? { 'Content-Type': 'application/json' } : {}, headers || {}), body: body ? JSON.stringify(body) : undefined })
    .then((r) => r.text().then((t) => { let b = null; try { b = t ? JSON.parse(t) : null; } catch (e) { b = { detail: t }; } if (!r.ok) { b = b || {}; b.status = r.status; b.code = b.code || 'HTTP-' + r.status; throw b; } return b; }));

  // ---- step 1: what data ------------------------------------------------------------------------------------------------------------------
  const srcList = $('[data-ubg-sources]'), srcQ = $('[data-ubg-source-q]');
  function paintSources() {
    clear(srcList);
    const words = srcQ.value.trim().toLowerCase().split(/\s+/).filter(Boolean);
    const shown = st.sources.filter((s) => words.every((w) => (s.title + ' ' + s.hint + ' ' + s.pack + ' ' + s.kind).toLowerCase().indexOf(w) >= 0));
    shown.forEach((s) => {
      const on = st.source === s;
      const card = h('button', { type: 'button', class: 'ubg-card', 'data-ubg-source': s.id, 'aria-pressed': String(on) },
        h('strong', { text: s.title }), h('span', { class: 'ubg-line', text: s.hint }));
      card.addEventListener('click', () => choose(s));
      const li = h('li', {}, card);
      if (on) {
        Object.keys(s.params || {}).filter((p) => s.params[p].ask).forEach((p) => {
          const inp = h('input', { class: 'studio-in', 'data-ubg-param': p, value: s.params[p].default || '', placeholder: s.params[p].example || '' });
          inp.addEventListener('change', () => { s.params[p].default = inp.value.trim() || undefined; refresh(); });
          li.appendChild(h('label', { class: 'ubg-ask' }, h('span', { text: s.params[p].ask }), inp));
        });
      }
      srcList.appendChild(li);
    });
    if (!shown.length) { srcList.appendChild(h('li', { class: 'text-muted-d', text: st.sources.length ? 'Nothing matches.' : 'No data offered: switch a pack on, or ask an administrator.' })); }
  }
  function choose(s) {
    st.source = s; st.attrs = null; st.ranked = []; st.picked = [];
    paintSources();
    const sup = s.supply || {}, q = new URLSearchParams();
    ['connector', 'model', 'live'].forEach((k) => { if (sup[k]) { q.set(k, sup[k]); } });
    say('Looking for chips that suit ' + s.title.toLowerCase() + '…');
    Promise.all([api('GET', '/ui/api-suppliers?' + q).then((r) => r.attributes || null).catch(() => null), st.chips ? st.chips : api('GET', '/ui/api/chips').catch(() => [])])
      .then(([attrs, chips]) => {
        if (st.source !== s) { return; }
        st.attrs = attrs; st.chips = Array.isArray(chips) ? chips : [];
        st.ranked = S.shortlist(S.rank(st.chips, attrs, s.shape), 5, 2);
        const main = st.ranked.find((c) => c.role === 'main' && (s.shape === 'one' || c.gives));
        const detail = s.shape === 'many' ? st.ranked.find((c) => c.role === 'detail') : st.ranked.find((c) => c !== main);
        st.picked = [main, detail].filter(Boolean).map((c) => c.ref);
        say(st.ranked.length + ' chips suit this data.');
        next.disabled = false;
        paintShow(); refresh();
      });
  }

  // ---- step 2: pick what to show --------------------------------------------------------------------------------------------------------
  const showList = $('[data-ubg-suggest]');
  const byRef = (ref) => st.ranked.find((c) => c.ref === ref) || extra[ref];
  const extra = {};
  function plan() {
    const s = st.source, out = { table: null, insts: [] };
    if (!s) { return out; }
    const names = new Set();
    st.picked.forEach((ref) => {
      const c = byRef(ref);
      if (!c) { return; }
      const base = c.chip.replace(/^panel-|^bi-/, '');
      let n = base, k = 2;
      while (names.has(n)) { n = base + '-' + k++; }
      names.add(n);
      out.insts.push({ name: n, c });
    });
    if (s.shape === 'many') { out.table = out.insts.find((x) => x.c.role === 'main' && x.c.gives) || null; }
    out.insts.forEach((x) => {
      const ins = S.pinList(x.c.pins, 'in');
      x.detail = !!out.table && x.c.role === 'detail' && ins.some((p) => p.type === 'id' && p.role !== 'data');
      x.filtered = !!out.table && x !== out.table && !x.detail && ins.some((p) => p.type === 'filter');
      x.follows = x.detail ? 'Follows the ' + S.noun(s.supply).one + ' you click in ' + out.table.c.title
        : x.filtered ? 'Follows the ' + S.noun(s.supply).one + ' you click in ' + out.table.c.title + ', by filter' : x === out.table ? 'Gives the ' + S.noun(s.supply).one + ' you click to the others' : '';
    });
    return out;
  }
  function paintShow() {
    clear(showList);
    $('[data-ubg-data-note]').textContent = st.source ? 'Data: ' + st.source.title + ' · suggested for this data' : '';
    const p = plan(), refs = st.ranked.map((c) => c.ref).concat(Object.keys(extra).filter((r) => !st.ranked.some((c) => c.ref === r)));
    refs.forEach((ref) => {
      const c = byRef(ref), on = st.picked.indexOf(ref) >= 0, x = p.insts.find((i) => i.c.ref === ref);
      const box = h('input', { type: 'checkbox', 'data-ubg-pick': ref, checked: on });
      box.addEventListener('change', () => { st.picked = box.checked ? st.picked.concat([ref]) : st.picked.filter((r) => r !== ref); paintShow(); refresh(); });
      const purpose = String(c.purpose || '').split(/(?<=\.)\s/)[0];
      showList.appendChild(h('li', {}, h('label', { class: 'ubg-card ubg-check' + (on ? ' ubg-on' : '') }, box,
        h('span', {}, h('strong', { text: c.title }), h('span', { class: 'ubg-line', text: purpose.length > 110 ? purpose.slice(0, 108) + '…' : purpose }),
          x && x.follows ? h('span', { class: 'ubg-follows', text: x.follows }) : null))));
    });
    if (!refs.length) { showList.appendChild(h('li', { class: 'text-muted-d', text: 'No chip in the store fits this data yet: browse all chips.' })); }
  }
  // the Chip Store, all of it
  const dlg = $('[data-ubg-browse]'), bq = $('[data-ubg-browse-q]'), blist = $('[data-ubg-browse-list]');
  function paintBrowse() {
    clear(blist);
    const words = bq.value.trim().toLowerCase().split(/\s+/).filter(Boolean);
    (st.chips || []).filter((c) => c.latest != null && words.every((w) => (c.chip + ' ' + (c.title || '') + ' ' + (c.purpose || '')).toLowerCase().indexOf(w) >= 0)).slice(0, 60).forEach((c) => {
      const ref = c.chip + '@' + c.latest, fits = S.fits(c, st.attrs);
      const b = h('button', { type: 'button', class: 'ubg-card', 'data-ubg-add': ref, disabled: st.picked.indexOf(ref) >= 0 },
        h('strong', { text: c.title || c.chip }), h('span', { class: 'ubg-line', text: String(c.purpose || '').split(/(?<=\.)\s/)[0] }), fits ? null : h('span', { class: 'ubg-follows', text: 'Its data needs columns this data does not have' }));
      b.addEventListener('click', () => {
        if (!st.ranked.some((x) => x.ref === ref)) { const r = S.rank([c], null, st.source.shape)[0] || { chip: c.chip, ref, title: c.title || c.chip, purpose: c.purpose || '', role: 'main', pins: c.pins }; extra[ref] = r; }
        st.picked = st.picked.concat([ref]); dlg.close(); paintShow(); refresh();
      });
      blist.appendChild(h('li', {}, b));
    });
  }
  $('[data-ubg-browse-open]').addEventListener('click', () => { bq.value = ''; paintBrowse(); dlg.showModal(); bq.focus(); });
  bq.addEventListener('input', paintBrowse);

  // ---- step 3: arrange ------------------------------------------------------------------------------------------------------------------
  const arrList = $('[data-ubg-arrange]');
  function paintArrange() {
    clear(arrList);
    plan().insts.forEach((x, i, all) => {
      const up = h('button', { type: 'button', class: 'btn-pill btn-ghost', 'data-ubg-up': x.c.ref, 'aria-label': 'Move ' + x.c.title + ' up', disabled: i === 0 }, h('i', { class: 'bi bi-arrow-up', 'aria-hidden': 'true' }));
      const down = h('button', { type: 'button', class: 'btn-pill btn-ghost', 'data-ubg-down': x.c.ref, 'aria-label': 'Move ' + x.c.title + ' down', disabled: i === all.length - 1 }, h('i', { class: 'bi bi-arrow-down', 'aria-hidden': 'true' }));
      const half = (st.spans[x.c.ref] || 'full') === 'half';
      const span = h('span', { class: 'ubg-seg', role: 'group', 'aria-label': 'Width of ' + x.c.title },
        h('button', { type: 'button', 'data-ubg-span': 'full', 'aria-pressed': String(!half), text: 'Full width' }), h('button', { type: 'button', 'data-ubg-span': 'half', 'aria-pressed': String(half), text: 'Half' }));
      const move = (d) => { const a = st.picked.slice(), k = a.indexOf(x.c.ref); a.splice(k, 1); a.splice(k + d, 0, x.c.ref); st.picked = a; paintArrange(); refresh(); };
      up.addEventListener('click', () => move(-1)); down.addEventListener('click', () => move(1));
      span.querySelectorAll('button').forEach((b) => b.addEventListener('click', () => { st.spans[x.c.ref] = b.getAttribute('data-ubg-span'); paintArrange(); refresh(); }));
      arrList.appendChild(h('li', { class: 'ubg-card ubg-arr', 'data-ubg-item': x.c.ref }, h('span', { class: 'ubg-num', text: String(i + 1) }), h('strong', { text: x.c.title }), h('span', { class: 'ubg-grow' }), span, up, down));
    });
  }

  // ---- the board ---------------------------------------------------------------------------------------------------------------------------
  const nameIn = $('[data-ubg-name]'), titleIn = $('[data-ubg-title]'), wsIn = $('[data-ubg-ws]');
  /** The id column a selection filters on (`filter-on: rows.trade`): a mapped id column of the data pin, not its own row id. */
  function idColumn(map, pins) {
    const d = S.dataPins(pins)[0], c = d && Object.keys(d.columns).find((k) => k !== 'id' && d.columns[k].type === 'id' && map[d.name + '.' + k] != null);
    return c ? d.name + '.' + c : null;
  }
  function board() {
    const s = st.source, p = plan(), params = {};
    Object.keys(s.params || {}).forEach((k) => { const v = Object.assign({}, s.params[k]); delete v.ask; delete v.example; params[k] = v; });
    const doc = { rachana: 1, board: nameIn.value.trim() || 'new-board', title: titleIn.value.trim() || s.title, chips: {}, traces: [],
      layout: { desktop: { columns: 12, areas: [] }, phone: { order: [], collapsed: [] } } };
    if (Object.keys(params).length) { doc.params = params; }
    let x = 1, y = 1, rowH = 0;
    p.insts.forEach((it) => {
      const sup = JSON.parse(JSON.stringify(s.supply));
      if (it.detail) { sup.connector = sup.connector.replace(/-search$/, ''); delete sup.query; }
      const map = S.autoMap(it.c.pins, st.attrs);
      doc.chips[it.name] = { use: it.c.ref, title: it.c.title, supply: sup, map };
      if (it.detail) {
        const pin = S.pinList(it.c.pins, 'in').find((q) => q.type === 'id' && q.role !== 'data');
        doc.traces.push({ from: p.table.name + '.' + S.pinList(p.table.c.pins, 'out').find((q) => q.type === 'id').name, to: it.name + '.' + pin.name });
      } else if (it.filtered) {
        const t = { from: p.table.name + '.' + S.pinList(p.table.c.pins, 'out').find((q) => q.type === 'id').name, to: it.name + '.filter' }, col = idColumn(map, it.c.pins);
        if (col) { t.transform = { 'filter-on': col }; }
        doc.traces.push(t);
      }
      const w = (st.spans[it.c.ref] || 'full') === 'half' ? 6 : 12, hgt = S.dataPins(it.c.pins).some((q) => q.type === 'rowset') ? 6 : 4;
      if (x + w - 1 > 12) { x = 1; y += rowH; rowH = 0; }
      doc.layout.desktop.areas.push({ chip: it.name, at: [x, y], span: [w, hgt] });
      x += w; rowH = Math.max(rowH, hgt);
      if (x > 12) { x = 1; y += rowH; rowH = 0; }
      doc.layout.phone.order.push(it.name);
    });
    return doc;
  }

  // ---- the live preview ---------------------------------------------------------------------------------------------------------------------
  let current = null, timer = 0;
  function refresh() {
    clearTimeout(timer);
    timer = setTimeout(() => {
      if (current) { current.close(); current = null; }
      if (!st.source || !st.picked.length) { clear(preview).appendChild(h('p', { class: 'text-muted-d ubg-empty', text: st.source ? 'Pick something to show: it paints here, with your data.' : 'Choose the data: the board paints here, as you.' })); return; }
      const doc = board(), params = {};
      Object.keys(doc.params || {}).forEach((k) => { if (doc.params[k].default != null) { params[k] = doc.params[k].default; } });
      current = U.render(preview, { board: doc }, { device: preview.offsetWidth < 560 ? 'phone' : 'desktop', status: pvStatus, params });
      U.guided.doc = doc;
    }, 250);
  }

  // ---- steps -----------------------------------------------------------------------------------------------------------------------------
  const back = $('[data-ubg-back]'), next = $('[data-ubg-next]');
  function go(step) {
    st.step = step; st.seen.add(step);
    root.setAttribute('data-step', step);
    STEPS.forEach((k) => { $('[data-ubg-pane="' + k + '"]').hidden = k !== step; });
    root.querySelectorAll('[data-ubg-go]').forEach((b) => { const k = b.getAttribute('data-ubg-go'); b.setAttribute('aria-current', String(k === step)); b.disabled = !st.seen.has(k) && k !== step; });
    const i = STEPS.indexOf(step);
    back.hidden = i === 0;
    next.textContent = ['Next: pick what to show', 'Next: arrange', 'Next: save', 'Create the board'][i];
    next.disabled = (i === 0 && !st.source) || (i === 1 && !st.picked.length);
    if (step === 'show') { paintShow(); } else if (step === 'arrange') { paintArrange(); } else if (step === 'save' && !nameIn.value) {
      nameIn.value = (st.source.title + ' board').toLowerCase().replace(/[^a-z0-9]+/g, '-').replace(/^-|-$/g, '');
      titleIn.value = st.source.title;
    }
    const h1 = $('[data-ubg-pane="' + step + '"] h2'); if (h1) { h1.focus({ preventScroll: true }); }
  }
  root.querySelectorAll('[data-ubg-go]').forEach((b) => b.addEventListener('click', () => go(b.getAttribute('data-ubg-go'))));
  back.addEventListener('click', () => go(STEPS[Math.max(0, STEPS.indexOf(st.step) - 1)]));
  next.addEventListener('click', () => { const i = STEPS.indexOf(st.step); if (i < 3) { go(STEPS[i + 1]); } else { save(); } });
  root.addEventListener('change', (e) => { if (e.target.matches('[data-ubg-pick]')) { next.disabled = st.step === 'show' && !st.picked.length; } });
  root.querySelectorAll('[name="ubg-where"]').forEach((r) => r.addEventListener('change', () => { wsIn.disabled = !$('[name="ubg-where"][value="workspace"]').checked; if (!wsIn.disabled) { wsIn.focus(); } }));
  titleIn.addEventListener('change', refresh);

  function save() {
    const name = nameIn.value.trim(), ws = $('[name="ubg-where"][value="workspace"]').checked ? wsIn.value.trim() : '';
    if (!/^[a-z0-9][a-z0-9-]*$/.test(name)) { say('A board name is lower-case letters, digits and dashes.', true); nameIn.focus(); return; }
    if ($('[name="ubg-where"][value="workspace"]').checked && !ws) { say('Name the workspace, or keep the board just for you.', true); wsIn.focus(); return; }
    const q = ws ? '?workspace=' + encodeURIComponent(ws) : '';
    say('Saving…');
    next.disabled = true;
    api('PUT', '/ui/api/boards/' + encodeURIComponent(name) + q, board(), { 'If-None-Match': '*' }).then(() => {
      say('Saved. Opening it in the designer…');
      location.href = '/build/board/' + encodeURIComponent(name) + q;
    }).catch((e) => {
      next.disabled = false;
      say(e.status === 412 || e.status === 409 ? 'A board called ' + name + ' exists already: choose another name.' : (e.code + ': ' + (e.detail || 'not saved')), true);
    });
  }

  srcQ.addEventListener('input', paintSources);
  api('GET', '/build/board-api/sources').then((r) => { st.sources = (r && r.sources) || []; paintSources(); }).catch((e) => say(e.code + ': ' + e.detail, true));
  window.addEventListener('pagehide', () => { if (current) { current.close(); } });
  go('data');
  refresh();
  U.guided = { state: st, board, plan };
})();
