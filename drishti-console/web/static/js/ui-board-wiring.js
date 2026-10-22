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
/* The designer's Wiring view (concept B): what feeds what on a board, in plain words. No pins, no type names, no YAML.
   - Boxes: DATA (one per supplier: "Trades (trading)", "connector · trade-search") and CHIP (its title and one line: "follows: chosen book ·
     gives: chosen trade"). Data sits on the left, chips flow right by dependency; a box dragged keeps its place in the board's
     layout.wiring ({box: [x, y]}).
   - Wires: the circuit traces of ui-board-route.js (90° bends, hops, junction dots). Colour 1 (the accent) is data from a connector; colour 2
     (ok, green) a selection passed on. Each wire is labelled in words ("chosen trade"). Click one: a popover says it as a sentence, with
     Change and Remove.
   - Connect: drag from a box's right edge (or click the edge, then a box); only the boxes that can take something light up; with more than one
     thing to pass, a short question in words.
   Registers itself as the view "wiring" (drishtiUiBoard.view.set('wiring')); without the designer shell's view switch, it adds a minimal
   Page | Wiring toggle. Edits go through the designer (D.addTrace, D.removeTrace, D.changed), so undo, the check and the YAML follow. */
(function () {
  'use strict';
  const U = window.drishtiUiBoard, root = document.querySelector('[data-ub-design]');
  if (!U || !U.designer || !U.suggest || !root) { return; }
  const D = U.designer, S = U.suggest, h = U.h, clear = U.clear, SVG = 'http://www.w3.org/2000/svg';
  const BW = 240, BH = 110, COLW = 380, ROWH = 160, PAD = 40;
  const pane = h('div', { class: 'ubw', 'data-ubw': true, hidden: true, 'aria-label': 'Wiring' });
  const stage = h('div', { class: 'ubw-stage', 'data-ubw-stage': true });
  const svg = document.createElementNS(SVG, 'svg');
  svg.setAttribute('class', 'ubw-svg'); svg.setAttribute('data-ubw-wires', '');
  const key = h('p', { class: 'ubw-key', 'data-ubw-key': true },
    h('span', {}, h('i', { class: 'ubw-swatch ubw-data', 'aria-hidden': 'true' }), ' data from a connector'),
    h('span', {}, h('i', { class: 'ubw-swatch ubw-sel', 'aria-hidden': 'true' }), ' a selection passed on'),
    h('span', { text: 'Click a wire to change it · drag from a box\'s right edge to connect' }));
  const say = h('p', { class: 'ubw-say', role: 'status', 'aria-live': 'polite', 'data-ubw-say': true });
  stage.appendChild(svg);
  pane.append(say, h('div', { class: 'ubw-scroll' }, stage), key);
  const host = root.querySelector('[data-ub-pane="canvas"]'), wrap = root.querySelector('.ub-canvas-wrap');
  host.insertBefore(pane, wrap.nextSibling);
  let model = null, pop = null, link = null, active = false;

  // ---- the model: boxes and wires, from the board document ---------------------------------------------------------------------------------
  const instTitle = (n) => { const i = D.doc.chips[n] || {}, c = D.chipDoc(i.use) || {}; return i.title || c.title || S.cap(S.words(n)); };
  const supKey = (s) => s && (s.connector ? 'data:' + s.connector : s.model ? 'data:model:' + s.model : s.live ? 'data:live:' + s.live : s.dataset ? 'data:ds:' + s.dataset : null);
  const outPins = (n) => S.pinList((D.chipDoc((D.doc.chips[n] || {}).use) || {}).pins, 'out');
  const inPins = (n) => S.pinList((D.chipDoc((D.doc.chips[n] || {}).use) || {}).pins, 'in');
  /** The words for what an out pin passes on: "chosen trade", "chosen date", "trades filter". */
  function passes(n, pin) {
    const p = outPins(n).find((x) => x.name === pin) || { type: '' }, nn = S.noun((D.doc.chips[n] || {}).supply) || { one: 'row', many: 'rows' };
    if (p.type === 'id') { const of = String(p.of || ''); return 'chosen ' + (of.indexOf('/') >= 0 ? S.words(of.split('/')[1]) : nn.one); }
    if (p.type === 'filter') { return nn.many + ' filter'; }
    if (p.type === 'rowset') { return 'visible ' + nn.many; }
    return 'chosen ' + (p.type === 'date' ? 'date' : S.words(pin));
  }
  function build() {
    const boxes = {}, wires = [], chips = D.doc.chips || {};
    Object.keys(chips).forEach((n) => {
      const sup = chips[n].supply, k = supKey(sup);
      if (k && !boxes[k]) { const l = S.sourceLabel(sup) || { title: k, sub: '' }; boxes[k] = { key: k, kind: 'data', title: l.title, sub: l.sub, supply: sup }; }
      boxes[n] = { key: n, kind: 'chip', title: instTitle(n), follows: [], gives: [] };
      if (k) { const nn = S.noun(sup) || { search: true, many: 'data' }; wires.push({ kind: 'data', from: k, fromPin: 'data', to: n, label: nn.search ? nn.many : 'the ' + nn.one }); }
    });
    (D.doc.traces || []).forEach((t, i) => {
      const [a, ...ap] = t.from.split('.'), b = t.to.split('.')[0];
      if (!boxes[b]) { return; }
      let label, from;
      if (a === 'board') {
        from = 'data:board';
        if (!boxes[from]) { boxes[from] = { key: from, kind: 'data', title: 'Board settings', sub: 'set when the board opens' }; }
        label = S.words(ap.slice(1).join('.'));
      } else if (boxes[a]) { from = a; label = passes(a, ap.join('.')); } else { return; }
      wires.push({ kind: a === 'board' ? 'data' : 'sel', trace: i, from, fromPin: ap.join('.'), to: b, label });
      boxes[b].follows.push(label);
      if (boxes[a] && boxes[a].gives.indexOf(label) < 0) { boxes[a].gives.push(label); }
    });
    Object.keys(boxes).forEach((k) => {
      const b = boxes[k];
      if (b.kind !== 'chip') { return; }
      if (!b.gives.length) { outPins(k).filter((p) => p.type === 'id').forEach((p) => b.gives.push(passes(k, p.name))); }
      b.sub = [b.follows.length ? 'follows: ' + b.follows.join(', ') : '', b.gives.length ? 'gives: ' + b.gives.join(', ') : ''].filter(Boolean).join(' · ') || 'stands alone';
    });
    place(boxes, wires);
    return { boxes, wires };
  }
  /** Data on the left, each chip one column right of the furthest chip it follows; a saved place (layout.wiring) wins. */
  function place(boxes, wires) {
    const depth = {}, saved = ((D.doc.layout || {}).wiring) || {};
    const d = (k, seen) => {
      if (depth[k] != null) { return depth[k]; }
      if (boxes[k].kind === 'data' || seen.has(k)) { return 0; }
      seen.add(k);
      const ins = wires.filter((w) => w.to === k && w.kind === 'sel').map((w) => d(w.from, seen));
      depth[k] = 1 + (ins.length ? Math.max(...ins) : 0);
      return depth[k];
    };
    const rows = {}, col = {};
    Object.keys(boxes).forEach((k) => { if (boxes[k].kind === 'chip') { col[k] = d(k, new Set()); } });
    Object.keys(boxes).forEach((k) => {                     // a data box sits one column left of the first chip it feeds, so its wires stay short
      if (boxes[k].kind !== 'data') { return; }
      const to = wires.filter((w) => w.from === k).map((w) => col[w.to] || 1);
      col[k] = Math.max(0, (to.length ? Math.min(...to) : 1) - 1);
    });
    Object.keys(boxes).sort((a, b) => col[a] - col[b] || (boxes[a].kind === 'chip' ? 0 : 1) - (boxes[b].kind === 'chip' ? 0 : 1)).forEach((k) => {
      const b = boxes[k];
      if (Array.isArray(saved[k])) { b.x = +saved[k][0]; b.y = +saved[k][1]; return; }
      rows[col[k]] = (rows[col[k]] || 0) + 1;
      b.x = PAD + col[k] * COLW;
      b.y = PAD + (rows[col[k]] - 1) * ROWH;
    });
  }

  // ---- drawing ---------------------------------------------------------------------------------------------------------------------------
  function boxEl(b) {
    const grip = h('button', { type: 'button', class: 'ubw-grip', 'data-ubw-grip': b.key, 'aria-label': 'Connect ' + b.title + ' to another box', title: 'Drag to a box to connect' });
    const el = h('div', { class: 'ubw-box ubw-' + b.kind, 'data-box': b.key, 'data-kind': b.kind, tabindex: '0', role: 'group', 'aria-label': (b.kind === 'data' ? 'Data: ' : 'Chip: ') + b.title },
      h('div', { class: 'ubw-tag', text: b.kind === 'data' ? 'DATA' : 'CHIP' }), h('div', { class: 'ubw-title', text: b.title }), h('div', { class: 'ubw-sub', text: b.sub }), grip);
    el.style.left = b.x + 'px'; el.style.top = b.y + 'px';
    el.addEventListener('pointerdown', (e) => { if (!e.target.closest('.ubw-grip')) { drag(e, b, el); } });
    el.addEventListener('click', () => { if (link) { finish(b.key); } });
    el.addEventListener('keydown', (e) => { if (e.key === 'Enter' && link) { e.preventDefault(); finish(b.key); } });
    grip.addEventListener('pointerdown', (e) => start(e, b));
    grip.addEventListener('click', (e) => { e.stopPropagation(); if (!link) { arm(b); } else if (link.from === b.key) { cancel(); } });
    return el;
  }
  function anchors(m) {
    const outs = {}, ins = {};
    m.wires.forEach((w, k) => { const s = w.from + '|' + w.fromPin; (outs[w.from] = outs[w.from] || []).indexOf(s) < 0 && outs[w.from].push(s); (ins[w.to] = ins[w.to] || []).push(k); });
    m.wires.forEach((w, k) => {
      const a = m.boxes[w.from], b = m.boxes[w.to], os = outs[w.from], is = ins[w.to];
      w.a = [a.x + BW, Math.round(a.y + BH * (os.indexOf(w.from + '|' + w.fromPin) + 1) / (os.length + 1))];
      w.b = [b.x, Math.round(b.y + BH * (is.indexOf(k) + 1) / (is.length + 1))];
    });
  }
  function draw() {
    if (!active) { return; }
    closePop();
    model = build();
    Array.from(stage.querySelectorAll('.ubw-box')).forEach((e) => e.remove());
    let w = 0, hh = 0;
    Object.values(model.boxes).forEach((b) => { stage.appendChild(boxEl(b)); w = Math.max(w, b.x + BW + PAD * 2); hh = Math.max(hh, b.y + BH + PAD * 2); });
    stage.style.width = w + 'px'; stage.style.height = Math.max(hh, 320) + 'px';
    svg.setAttribute('width', String(w)); svg.setAttribute('height', String(Math.max(hh, 320)));
    wires();
    say.textContent = Object.keys(model.boxes).length ? '' : 'No data or chips yet: add a chip from the Chip Store, then wire it here.';
  }
  function wires() {
    while (svg.firstChild) { svg.removeChild(svg.firstChild); }
    anchors(model);
    const rects = {};
    Object.values(model.boxes).forEach((b) => { rects[b.key] = { left: b.x, top: b.y, right: b.x + BW, bottom: b.y + BH }; });
    const plan = U.route.plan(model.wires.map((x, i) => ({ i, a: x.a, b: x.b, from: x.from + '|' + x.fromPin, fromBox: x.from, toBox: x.to })), rects);
    plan.wires.forEach((pw) => {
      const x = model.wires[pw.i], g = document.createElementNS(SVG, 'g');
      g.setAttribute('class', 'ubw-wire ubw-' + x.kind);
      g.setAttribute('data-wire', String(pw.i)); g.setAttribute('data-label', x.label); g.setAttribute('data-points', pw.points.map((p) => p.join(',')).join(' '));
      g.setAttribute('tabindex', '0'); g.setAttribute('role', 'button'); g.setAttribute('aria-label', sentence(x));
      const hit = document.createElementNS(SVG, 'path'), line = document.createElementNS(SVG, 'path'), text = document.createElementNS(SVG, 'text');
      hit.setAttribute('d', pw.d); hit.setAttribute('class', 'ubw-hit');
      line.setAttribute('d', pw.d); line.setAttribute('class', 'ubw-line');
      const segs = pw.points.slice(1).map((p, k) => [pw.points[k], p]).filter((s) => s[0][1] === s[1][1]);
      const last = segs[segs.length - 1], long = segs.slice().sort((p, q) => Math.abs(q[1][0] - q[0][0]) - Math.abs(p[1][0] - p[0][0]))[0];
      const s = last && Math.abs(last[1][0] - last[0][0]) >= 70 ? last : long || [pw.points[0], pw.points[pw.points.length - 1]];
      text.setAttribute('x', String(Math.round(Math.min(s[0][0], s[1][0]) + 8))); text.setAttribute('y', String(s[0][1] - 7));
      text.setAttribute('class', 'ubw-label'); text.textContent = x.label;
      g.append(hit, line, text);
      g.addEventListener('click', (e) => openPop(x, e));
      g.addEventListener('keydown', (e) => { if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); openPop(x, null, g); } });
      svg.appendChild(g);
    });
    plan.junctions.forEach((j) => {
      const c = document.createElementNS(SVG, 'circle');
      c.setAttribute('cx', j[0]); c.setAttribute('cy', j[1]); c.setAttribute('r', '3.5'); c.setAttribute('class', 'ubw-junction');
      svg.appendChild(c);
    });
  }

  // ---- a wire, in words ------------------------------------------------------------------------------------------------------------------
  const titleOf = (k) => (model.boxes[k] || {}).title || k;
  function sentence(x) {
    if (x.kind === 'data' && x.from === 'data:board') { return titleOf(x.to) + ' takes the board\'s ' + x.label + '.'; }
    if (x.kind === 'data') { return titleOf(x.to) + ' reads ' + x.label + ' from ' + titleOf(x.from) + '.'; }
    const what = x.label.replace(/^chosen /, '');
    return x.label.indexOf('chosen ') === 0 ? titleOf(x.to) + ' follows the ' + what + ' chosen in ' + titleOf(x.from) + '.' : titleOf(x.to) + ' follows the ' + x.label + ' of ' + titleOf(x.from) + '.';
  }
  function closePop() { if (pop) { pop.remove(); pop = null; } }
  function popAt(el, x, y) {
    closePop();
    pop = el;
    pop.style.left = Math.max(8, Math.min(x, stage.offsetWidth - 300)) + 'px'; pop.style.top = (y + 10) + 'px';
    stage.appendChild(pop);
    const f = pop.querySelector('button'); if (f) { f.focus(); }
  }
  function openPop(x, e, g) {
    const r = stage.getBoundingClientRect(), at = e ? [e.clientX - r.left, e.clientY - r.top] : [x.b[0] - 200, x.b[1]];
    const rm = h('button', { type: 'button', class: 'btn-pill btn-ghost', 'data-ubw-remove': true, text: 'Remove' });
    const ch = h('button', { type: 'button', class: 'btn-pill btn-ghost', 'data-ubw-change': true, text: 'Change' });
    const close = h('button', { type: 'button', class: 'btn-pill btn-ghost ubw-x', 'aria-label': 'Close', text: '×' });
    const el = h('div', { class: 'ubw-pop', role: 'dialog', 'aria-label': 'Wire', 'data-ubw-pop': true }, h('p', { text: sentence(x) }), h('div', { class: 'ubw-row' }, ch, rm, close));
    rm.addEventListener('click', () => { closePop(); remove(x); });
    close.addEventListener('click', () => { closePop(); if (g) { g.focus(); } });
    ch.addEventListener('click', () => {
      const alts = alternatives(x);
      clear(el).appendChild(h('p', { text: alts.length ? 'Instead:' : 'Nothing else on this board can feed it.' }));
      alts.forEach((a) => { const b = h('button', { type: 'button', class: 'ubw-choice', text: a.text }); b.addEventListener('click', () => { closePop(); a.apply(); }); el.appendChild(b); });
      el.appendChild(close);
    });
    popAt(el, at[0], at[1]);
  }
  function remove(x) {
    if (x.trace != null) { D.removeTrace(x.trace); return; }
    const i = D.doc.chips[x.to];
    if (i) { delete i.supply; delete i.map; D.changed('chips'); D.say(titleOf(x.to) + ' no longer reads ' + x.label + '.'); }
  }
  /** What else could feed the same chip: another data box, or another chip's selection of a kind the pin takes. */
  function alternatives(x) {
    if (x.kind === 'data' && x.trace == null) {
      return Object.values(model.boxes).filter((b) => b.kind === 'data' && b.supply && b.key !== x.from)
        .map((b) => ({ text: 'Read ' + (S.noun(b.supply) || {}).many + ' from ' + b.title, apply: () => feed(b, x.to) }));
    }
    if (x.trace == null) { return []; }
    const t = D.doc.traces[x.trace], inPin = t.to.split('.').slice(1).join('.'), want = (inPins(x.to).find((p) => p.name === inPin) || {}).type;
    const out = [];
    Object.keys(D.doc.chips).filter((n) => n !== x.to).forEach((n) => outPins(n).forEach((p) => {
      if (n + '.' + p.name === t.from || !S.compatible(p.type, want)) { return; }
      out.push({ text: 'Follow the ' + passes(n, p.name).replace(/^chosen (.*)$/, '$1 chosen') + ' in ' + titleOf(n), apply: () => { t.from = n + '.' + p.name; D.changed('traces'); D.say(sentence(Object.assign({}, x, { from: n, label: passes(n, p.name) }))); } });
    }));
    return out;
  }

  // ---- connect -----------------------------------------------------------------------------------------------------------------------------
  /** What box `from` can give box `to`, in words: [{text, apply}]. */
  function options(from, to) {
    const a = model.boxes[from], b = model.boxes[to];
    if (!a || !b || from === to || b.kind !== 'chip') { return []; }
    if (a.kind === 'data') {
      if (!a.supply || supKey((D.doc.chips[to] || {}).supply) === from || !S.dataPins((D.chipDoc(D.doc.chips[to].use) || {}).pins).length) { return []; }
      return [{ text: 'its ' + (S.noun(a.supply) || {}).many, apply: () => feed(a, to) }];
    }
    const out = [], have = new Set((D.doc.traces || []).map((t) => t.from + '>' + t.to));
    outPins(from).forEach((o) => inPins(to).filter((p) => p.role !== 'data' && S.compatible(o.type, p.type)).forEach((p) => {
      if (have.has(from + '.' + o.name + '>' + to + '.' + p.name)) { return; }
      out.push({ text: 'the ' + passes(from, o.name) + (p.type === 'filter' ? ', to filter by it' : p.type === 'id' ? ', to show it' : ', as its ' + S.words(p.name)),
        apply: () => { D.addTrace(from + '.' + o.name, to + '.' + p.name, o.type, p.type); D.say(titleOf(to) + ' now follows the ' + passes(from, o.name) + ' of ' + titleOf(from) + '.'); } });
    }));
    return out;
  }
  /** A chip reads a data box: its supply, and its columns matched to the supplier's attributes by type and name. */
  function feed(box, to) {
    const i = D.doc.chips[to];
    i.supply = JSON.parse(JSON.stringify(box.supply));
    D.attributes(i.supply).then((attrs) => {
      i.map = S.autoMap((D.chipDoc(i.use) || {}).pins, attrs);
      D.changed('chips');
      D.say(titleOf(to) + ' now reads ' + (S.noun(box.supply) || {}).many + ' from ' + box.title + '.');
    });
  }
  function light(from) {
    stage.querySelectorAll('.ubw-box').forEach((el) => {
      const k = el.getAttribute('data-box'), ok = k !== from && options(from, k).length > 0;
      el.classList.toggle('ubw-ok', ok); el.classList.toggle('ubw-dim', !ok && k !== from);
    });
    stage.classList.add('ubw-linking');
  }
  function arm(b) { link = { from: b.key }; light(b.key); say.textContent = 'Connect ' + b.title + ' to: click a lit box (Esc cancels).'; }
  function cancel() {
    link = null;
    stage.classList.remove('ubw-linking');
    stage.querySelectorAll('.ubw-box').forEach((el) => el.classList.remove('ubw-ok', 'ubw-dim'));
    const t = svg.querySelector('.ubw-tmp'); if (t) { t.remove(); }
    say.textContent = '';
  }
  function finish(to) {
    const from = link && link.from;
    cancel();
    if (!from || to === from) { return; }
    const opts = options(from, to);
    if (!opts.length) { say.textContent = titleOf(to) + ' cannot take anything from ' + titleOf(from) + '.'; return; }
    if (opts.length === 1) { opts[0].apply(); return; }
    const b = model.boxes[to], el = h('div', { class: 'ubw-pop', role: 'dialog', 'aria-label': 'What to pass', 'data-ubw-ask': true }, h('p', { text: 'What should ' + titleOf(from) + ' pass to ' + titleOf(to) + '?' }));
    opts.forEach((o) => { const c = h('button', { type: 'button', class: 'ubw-choice', text: o.text }); c.addEventListener('click', () => { closePop(); o.apply(); }); el.appendChild(c); });
    const x = h('button', { type: 'button', class: 'btn-pill btn-ghost', text: 'Cancel' }); x.addEventListener('click', closePop); el.appendChild(x);
    popAt(el, b.x, b.y + BH);
  }
  function start(e, b) {
    e.preventDefault();
    const r = stage.getBoundingClientRect(), x0 = e.clientX, y0 = e.clientY;
    let moved = false;
    const tmp = document.createElementNS(SVG, 'path');
    tmp.setAttribute('class', 'ubw-tmp');
    const move = (ev) => {
      if (!moved && Math.hypot(ev.clientX - x0, ev.clientY - y0) < 6) { return; }
      if (!moved) { moved = true; link = { from: b.key }; light(b.key); svg.appendChild(tmp); }
      const ax = b.x + BW, ay = b.y + BH / 2, px = ev.clientX - r.left, py = ev.clientY - r.top, mx = Math.round((ax + px) / 2);
      tmp.setAttribute('d', 'M' + ax + ',' + ay + ' L' + mx + ',' + ay + ' L' + mx + ',' + py + ' L' + px + ',' + py);
    };
    const up = (ev) => {
      window.removeEventListener('pointermove', move); window.removeEventListener('pointerup', up);
      if (!moved) { return; }                                                       // a click: the grip's click arms the link
      const over = document.elementFromPoint(ev.clientX, ev.clientY), box = over && over.closest('.ubw-box');
      if (box) { finish(box.getAttribute('data-box')); } else { cancel(); }
      gripDragged = true; setTimeout(() => { gripDragged = false; }, 0);
    };
    window.addEventListener('pointermove', move); window.addEventListener('pointerup', up);
  }
  let gripDragged = false;
  stage.addEventListener('click', (e) => { if (gripDragged) { e.stopPropagation(); } }, true);
  function drag(e, b, el) {
    if (e.button !== 0 || link) { return; }
    const x0 = e.clientX, y0 = e.clientY, bx = b.x, by = b.y;
    let moved = false;
    const move = (ev) => {
      const dx = ev.clientX - x0, dy = ev.clientY - y0;
      if (!moved && Math.hypot(dx, dy) < 5) { return; }
      moved = true;
      b.x = Math.max(0, Math.round((bx + dx) / 10) * 10); b.y = Math.max(0, Math.round((by + dy) / 10) * 10);
      el.style.left = b.x + 'px'; el.style.top = b.y + 'px';
      wires();
    };
    const up = () => {
      window.removeEventListener('pointermove', move); window.removeEventListener('pointerup', up);
      if (!moved) { return; }
      const l = D.doc.layout = D.doc.layout || {};
      l.wiring = l.wiring || {};
      l.wiring[b.key] = [b.x, b.y];
      D.changed('layout');
      draw();
    };
    window.addEventListener('pointermove', move); window.addEventListener('pointerup', up);
  }
  window.addEventListener('keydown', (e) => { if (e.key === 'Escape' && active) { if (link) { cancel(); } closePop(); } });

  // ---- the view switch ---------------------------------------------------------------------------------------------------------------------
  const view = {
    show() { active = true; pane.hidden = false; wrap.hidden = true; root.classList.add('ubw-on'); draw(); },
    hide() { active = false; cancel(); closePop(); pane.hidden = true; wrap.hidden = false; root.classList.remove('ubw-on'); }
  };
  const changed = D.changed;
  D.changed = function (what) { const r = changed.apply(this, arguments); if (active && what !== 'map') { draw(); } return r; };
  if (U.view && U.view.register) { U.view.register('wiring', view); } else { minimalSwitch(); }
  /** Until the designer shell's own switch (concept A) is on this branch: a Page | Wiring toggle by the heading. */
  function minimalSwitch() {
    const views = { page: { show() { wrap.hidden = false; requestAnimationFrame(() => U.canvas && U.canvas.wires && U.canvas.wires()); }, hide() {} }, wiring: view };
    let cur = 'page';
    const btn = (k, text) => h('button', { type: 'button', role: 'tab', class: 'btn-pill btn-ghost', 'data-ub-view-to': k, 'aria-selected': String(k === 'page'), text });
    const sw = h('span', { class: 'ub-view-switch', role: 'tablist', 'aria-label': 'View' }, btn('page', 'Page'), btn('wiring', 'Wiring'));
    const head = root.querySelector('[data-ub-heading]');
    head.parentNode.insertBefore(sw, head.nextSibling);
    U.view = {
      register(k, v) { views[k] = v; },
      get: () => cur,
      set(k) {
        if (!views[k] || k === cur) { return; }
        views[cur].hide(); cur = k; views[k].show();
        sw.querySelectorAll('[data-ub-view-to]').forEach((b) => b.setAttribute('aria-selected', String(b.getAttribute('data-ub-view-to') === k)));
        root.setAttribute('data-ub-current-view', k);
      }
    };
    sw.querySelectorAll('[data-ub-view-to]').forEach((b) => b.addEventListener('click', () => U.view.set(b.getAttribute('data-ub-view-to'))));
    root.addEventListener('ub:mode', (e) => { sw.hidden = e.detail !== 'board'; if (e.detail !== 'board') { U.view.set('page'); } });
  }
  U.wiring = { draw, options, model: () => model, sentence };
})();
