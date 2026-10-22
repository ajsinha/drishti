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
/* Concept A, the small pickers under a block's pills and the "+ Add a block here" zones (UI_BOARD_GUIDE.md "The designer"): Swap chip (a
   searchable list of chips, the ones that fit the block's data first; a pick swaps it at once, so the live page previews it), Add a block (the
   same list, chips for this page's data first, the new block fed and its fields matched), Its data (the sources on this page in words, or
   another), Follows… (the blocks it can follow, in words, or nothing) and Change fields (a checklist of the supplier's fields, written into
   the pin mapping). One popover, drishtiUiBoard.pick.open(kind, instance, anchor, options). */
(function () {
  'use strict';
  const U = window.drishtiUiBoard, h = U.h, clear = U.clear, W = U.words;
  const D = U.designer;
  if (!D) { return; }
  const root = D.root, pop = root.querySelector('[data-ub-pop]');
  const PIN_TYPES = { id: ['id'], dimension: ['dimension', 'id', 'text', 'date'], measure: ['measure'], date: ['date'], text: ['text', 'dimension', 'id', 'date', 'measure'], number: ['measure'] };
  let store = null, opener = null;

  const chips = () => store || (store = D.api('GET', '/ui/api/chips').then((l) => (Array.isArray(l) ? l : [])).catch(() => []));
  const refOf = (c) => c.chip + '@' + (c.latest || c.version || 1);
  const fits = (attr, colSpec) => (PIN_TYPES[W.typeOf(colSpec) || 'text'] || [W.typeOf(colSpec)]).indexOf(attr.type) >= 0;

  /** Matches the supplier's fields to a block's empty columns by type: required and many-valued columns first, one field each. */
  function autoMap(inst) {
    const i = D.doc.chips[inst];
    return D.attributes(i.supply).then((attrs) => {
      if (!attrs) { return; }
      const used = new Set(W.shown(D, inst));
      W.dataPins(D, inst).forEach((d) => d.cols.forEach(([k, cs]) => {
        if ((i.map || {})[k] != null) { return; }
        const ok = attrs.filter((a) => fits(a, cs) && !used.has(a.name));
        if (!ok.length) { return; }
        i.map = i.map || {};
        if (cs.many) { i.map[k] = ok.slice(0, Math.min(4, cs.max || 4)).map((a) => a.name); i.map[k].forEach((a) => used.add(a)); } else if (cs.required || W.typeOf(cs) === 'id') { i.map[k] = ok[0].name; used.add(ok[0].name); }
      }));
    });
  }

  function close() {
    pop.hidden = true; clear(pop); pop.removeAttribute('data-kind');
    if (opener && document.contains(opener)) { opener.focus(); }
    opener = null;
  }
  function place(anchor) {
    const r = anchor.getBoundingClientRect(), base = root.getBoundingClientRect();
    pop.style.left = Math.max(0, Math.min(r.left - base.left, base.width - 340)) + 'px';
    pop.style.top = (r.bottom - base.top + 6) + 'px';
  }
  function shell(kind, title, anchor) {
    opener = anchor;
    clear(pop);
    pop.setAttribute('data-kind', kind);
    pop.setAttribute('aria-label', title);
    const x = h('button', { type: 'button', class: 'ub-icon ub-pop-x', 'aria-label': 'Close', 'data-ub-pop-close': true }, h('i', { class: 'bi bi-x-lg', 'aria-hidden': 'true' }));
    x.addEventListener('click', close);
    pop.appendChild(h('header', { class: 'ub-pop-head' }, h('strong', { text: title }), x));
    const body = h('div', { class: 'ub-pop-body' });
    pop.appendChild(body);
    pop.hidden = false;
    place(anchor);
    return body;
  }
  function choice(text, sub, on, attrs) {
    const b = h('button', Object.assign({ type: 'button', class: 'ub-choice' }, attrs || {}), h('span', { class: 'ub-choice-t', text }), sub ? h('small', { class: 'ub-choice-s', text: sub }) : null);
    b.addEventListener('click', on);
    return b;
  }

  // ---- Swap chip and Add a block: one searchable list -------------------------------------------------------------------------------------
  function chipList(kind, inst, anchor, index) {
    const body = shell(kind, kind === 'swap' ? 'Swap ' + W.titleOf(D, inst) + ' for' : 'Add a block here', anchor);
    const q = h('input', { type: 'search', class: 'studio-in', 'data-ub-pick-q': true, placeholder: 'Search chips', 'aria-label': 'Search chips' });
    const list = h('div', { class: 'ub-pick-list', 'data-ub-pick-list': true, role: 'list' });
    body.appendChild(q); body.appendChild(list);
    const src = W.sources(D)[0];
    const want = kind === 'swap' ? W.mainType(W.chipOf(D, inst)) : (src ? (src.supply.connector && W.paramOf(src.supply.query) && !/search/.test(src.supply.connector) ? 'document' : 'rowset') : '');
    const current = kind === 'swap' ? D.doc.chips[inst].use.split('@')[0] : '';
    chips().then((all) => {
      const paint = () => {
        clear(list);
        const words = q.value.trim().toLowerCase().split(/\s+/).filter(Boolean);
        const hit = all.filter((c) => c.chip !== current && words.every((w) => (c.chip + ' ' + (c.title || '') + ' ' + (c.purpose || '') + ' ' + (c.tags || []).join(' ')).toLowerCase().indexOf(w) >= 0));
        const good = want ? hit.filter((c) => W.mainType(c) === want) : [], rest = hit.filter((c) => good.indexOf(c) < 0);
        const group = (label, cs) => {
          if (!cs.length) { return; }
          list.appendChild(h('p', { class: 'ub-pick-group', text: label }));
          cs.slice(0, 40).forEach((c) => list.appendChild(choice(c.title || c.chip, c.purpose || '', () => pick(c, good.indexOf(c) >= 0), { 'data-ub-pick': refOf(c), role: 'listitem' })));
        };
        group(kind === 'swap' ? 'Fits its data' : (src ? 'For this page\'s data: ' + src.text : 'Suggested'), good);
        group(good.length ? 'Other chips' : 'Chips', rest);
        if (!hit.length) { list.appendChild(h('p', { class: 'text-muted-d', text: 'No chip matches “' + q.value + '”.' })); }
      };
      q.addEventListener('input', paint);
      paint();
    });
    const pick = (c, suggested) => {
      const ref = refOf(c);
      D.fetchChip(ref).then((full) => {
        const doc = (full && full.document) || {};
        if (kind === 'swap') {
          W.swap(D, inst, ref, doc);
          D.changed('chips');
          D.say('Swapped ' + W.titleOf(D, inst) + ' for ' + (c.title || c.chip) + ': the page shows it live. Ctrl+Z puts it back.');
          list.querySelectorAll('[data-ub-pick]').forEach((b) => b.setAttribute('aria-current', String(b.getAttribute('data-ub-pick') === ref)));
          return;
        }
        add(ref, doc, suggested && src ? src.supply : null, index);
        close();
      });
    };
    q.focus();
  }
  /** A new block at this place in the page's order, fed by the page's data when it was a suggestion. */
  function add(ref, doc, supply, index) {
    const base = ref.split('@')[0].replace(/^panel-|^bi-/, '');
    let inst = base, n = 2;
    while (D.doc.chips[inst]) { inst = base + '-' + n++; }
    D.doc.chips[inst] = { use: ref };
    if (supply) { D.doc.chips[inst].supply = JSON.parse(JSON.stringify(supply)); }
    const list = W.order(D).filter((x) => x !== inst);
    list.splice(index == null ? list.length : index, 0, inst);
    D.placeNew(inst, null);
    W.reflow(D, list);
    (supply ? autoMap(inst) : Promise.resolve()).then(() => {
      D.changed('chips');
      D.select(inst);
      D.say('Added ' + (doc.title || ref) + (supply ? ', fed by ' + W.source(supply) : ': choose its data') + '.');
    });
  }

  // ---- Its data ---------------------------------------------------------------------------------------------------------------------------
  function data(inst, anchor) {
    const i = D.doc.chips[inst], body = shell('data', 'Where ' + W.titleOf(D, inst) + ' gets its data', anchor), now = JSON.stringify(i.supply || null);
    const use = (supply) => {
      if (supply) { i.supply = supply; } else { delete i.supply; }
      autoMap(inst).then(() => { D.changed('chips'); D.say(W.titleOf(D, inst) + ' now shows ' + (supply ? W.source(supply) : 'what it follows') + '.'); close(); });
    };
    W.sources(D).forEach((s) => body.appendChild(choice(s.text.charAt(0).toUpperCase() + s.text.slice(1), 'as in ' + s.used, () => use(s.supply),
      { 'data-ub-source': JSON.stringify(s.supply), 'aria-current': String(JSON.stringify(s.supply) === now) })));
    body.appendChild(choice('Only what it follows', 'no data of its own', () => use(null), { 'data-ub-source': 'none' }));
    const kind = h('select', { class: 'studio-select', 'aria-label': 'Kind of source' }, [['connector', 'A connector or search'], ['model', 'A BI model'], ['live', 'A live topic']].map(([v, t]) => h('option', { value: v, text: t })));
    const name = h('input', { class: 'studio-in mono', placeholder: 'trading/trade-search', 'aria-label': 'Its name', 'data-ub-source-name': true });
    const go = h('button', { type: 'button', class: 'ub-btn ub-btn-accent', text: 'Use it' });
    go.addEventListener('click', () => { if (!name.value.trim()) { name.focus(); return; } const s = {}; s[kind.value] = name.value.trim(); use(s); });
    body.appendChild(h('details', { class: 'ub-pop-more' }, h('summary', { text: 'Another source…' }), h('div', { class: 'ub-row' }, kind, name, go)));
  }

  // ---- Follows… ---------------------------------------------------------------------------------------------------------------------------
  function follows(inst, anchor) {
    const body = shell('follows', W.titleOf(D, inst) + ' follows', anchor);
    const mine = (D.doc.traces || []).filter((t) => t.to.split('.')[0] === inst && !/^board\./.test(t.from));
    const set = (opt) => {
      D.doc.traces = (D.doc.traces || []).filter((t) => !(t.to.split('.')[0] === inst && !/^board\./.test(t.from)));
      if (opt) { D.addTrace(opt.from, opt.to, opt.fromType, opt.toType); } else { D.changed('traces'); }
      D.say(W.titleOf(D, inst) + ' follows ' + (opt ? opt.text.charAt(0).toLowerCase() + opt.text.slice(1) : 'nothing') + '.');
      close();
    };
    const opts = W.followable(D, inst);
    opts.forEach((o) => body.appendChild(choice(o.text, null, () => set(o), { 'data-ub-follow': o.from, 'aria-current': String(mine.some((t) => t.from === o.from)) })));
    body.appendChild(choice('Nothing', 'it shows its own data', () => set(null), { 'data-ub-follow': '', 'aria-current': String(!mine.length) }));
    if (!opts.length) { body.insertBefore(h('p', { class: 'text-muted-d', text: 'No block on this page has a selection it can follow.' }), body.firstChild); }
  }

  // ---- Change fields ----------------------------------------------------------------------------------------------------------------------
  function fields(inst, anchor) {
    const i = D.doc.chips[inst], body = shell('fields', 'Fields of ' + W.titleOf(D, inst), anchor);
    if (!i.supply) { body.appendChild(h('p', { class: 'text-muted-d', text: 'It shows the fields of the block it follows. Give it its own data to choose fields.' })); return; }
    body.appendChild(h('p', { class: 'text-muted-d', text: 'From ' + W.source(i.supply) + '.' }));
    D.attributes(i.supply).then((attrs) => {
      const write = (k, many, vals) => {
        i.map = i.map || {};
        if (!vals.length) { delete i.map[k]; } else { i.map[k] = many ? vals : vals[0]; }
        if (!Object.keys(i.map).length) { delete i.map; }
        D.changed('map');
      };
      W.dataPins(D, inst).forEach((d) => d.cols.forEach(([k, cs]) => {
        const now = d.map[k], list = now == null ? [] : (Array.isArray(now) ? now : [now]), col = k.split('.').pop();
        const label = col.charAt(0).toUpperCase() + col.slice(1).replace(/-/g, ' ');
        if (!attrs) {
          const inp = h('input', { class: 'studio-in mono', value: list.join(', '), 'data-ub-field-col': k, 'aria-label': label });
          inp.addEventListener('change', () => write(k, !!cs.many, inp.value.split(',').map((s) => s.trim()).filter(Boolean)));
          body.appendChild(h('label', { class: 'ub-field' }, h('span', { class: 'ub-field-l', text: label }), inp));
          return;
        }
        const ok = attrs.filter((a) => fits(a, cs));
        if (cs.many) {
          const box = h('fieldset', { class: 'ub-checks', 'data-ub-field-col': k }, h('legend', { text: label + (cs.max ? ' (up to ' + cs.max + ')' : '') }));
          ok.forEach((a) => {
            const c = h('input', { type: 'checkbox', value: a.name, checked: list.indexOf(a.name) >= 0, 'data-ub-field': a.name });
            c.addEventListener('change', () => write(k, true, Array.from(box.querySelectorAll('input:checked')).map((x) => x.value)));
            box.appendChild(h('label', { class: 'ub-check' }, c, ' ' + W.field(a.name, attrs)));
          });
          body.appendChild(box);
        } else {
          const sel = h('select', { class: 'studio-select', 'data-ub-field-col': k, 'aria-label': label }, h('option', { value: '', text: cs.required ? 'Choose (needed)' : '(none)' }),
            ok.map((a) => h('option', { value: a.name, selected: list.indexOf(a.name) >= 0, text: W.field(a.name, attrs) })));
          sel.addEventListener('change', () => write(k, false, sel.value ? [sel.value] : []));
          body.appendChild(h('label', { class: 'ub-field' }, h('span', { class: 'ub-field-l', text: label }), sel));
        }
      }));
      const done = h('button', { type: 'button', class: 'ub-btn ub-btn-accent', text: 'Done', 'data-ub-pop-done': true });
      done.addEventListener('click', close);
      body.appendChild(done);
    });
  }

  function open(kind, inst, anchor, o) {
    if (!pop) { return; }
    if (!pop.hidden && pop.getAttribute('data-kind') === kind && opener === anchor) { close(); return; }
    ({ swap: () => chipList('swap', inst, anchor), add: () => chipList('add', null, anchor, (o || {}).index), data: () => data(inst, anchor), follows: () => follows(inst, anchor), fields: () => fields(inst, anchor) }[kind])();
  }
  document.addEventListener('keydown', (e) => { if (e.key === 'Escape' && !pop.hidden) { e.preventDefault(); close(); } });
  document.addEventListener('pointerdown', (e) => { if (!pop.hidden && !pop.contains(e.target) && !(opener && opener.contains(e.target))) { close(); } });
  U.pick = { open, close, add, autoMap };
})();
